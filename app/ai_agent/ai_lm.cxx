/****************************************************************************
 * ai_lm.cxx - velaAI V3 端侧语言模型推理 (TFLite Micro, 16x8 量化)
 *
 * 模型: model/velaai_lm_v3_16x8.tflite
 *   - 训练: /home/aila/projects/velaAI (train_gpu_v3.py, val acc 0.8705)
 *   - 导出: export_tflite_v3.py (LSTM 单步 unroll, 多IO显式状态)
 *
 * Tensor 布局 (host 侧与 Keras 参考逐项校验):
 *   输入: [0]=token int32(1,1)  [1]=h_in f32(1,256)  [2]=c_in f32(1,256)
 *   输出: [0]=c_out f32(1,256)  [1]=logits f32(1,5000)  [2]=h_out f32(1,256)
 *
 * 生成: 逐 token 自回归 argmax, 遇 <eos>(2) 早停; 回复取最后一个 <bot> 之后文本。
 *
 * Agent 模式 (ai_lm_agent_reply): 按 velaAI 训练格式走两段式
 *   "<usr> q" -> "<call> tool <arg>.. </tool>" -> 端侧执行器 -> "<obs> v"
 *   -> "<bot> 回复"; 量化模型槽位复制易错, 用执行器真值做校验+模板兜底。
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>
#include <pthread.h>

#include "ai_lm.h"
#include "tokenizer.h"
#include "model/model_data.h"
#ifdef CONFIG_AI_AGENT_LVGL_UI
#include "pet_care.h"
#endif

#include "tensorflow/lite/micro/micro_interpreter.h"
#include "tensorflow/lite/micro/micro_mutable_op_resolver.h"
#include "tensorflow/lite/schema/schema_generated.h"

/****************************************************************************
 * Pre-processor Definitions
 ****************************************************************************/

/* Tensor Arena: 主机侧 RecordingMicroInterpreter 实测 37840 字节, 留余量取 64KB。
 * 放在 SRAM 的静态 BSS 里: 解释器每个 token 都要反复读写这块内存, 放 PSRAM
 * (QSPI) 实测把推理从 ~9 s 拖到 16~49 s。SRAM 能装下是因为把 g_allsyms 那
 * 107 KB 从 .data 挪回了 flash (见板级 ld.script)。 */
#ifndef AI_LM_ARENA_SIZE
#define AI_LM_ARENA_SIZE (64 * 1024)
#endif

#define AI_LM_MAX_PROMPT_CHARS  96    /* 用户输入上限 (UTF-8 字节) */
#define AI_LM_PROMPT_MAX_TOKENS 96    /* prompt token 上限 */

/* 输入张量索引 (与导出模型一致, host 校验过) */
#define IN_TOKEN 0
#define IN_H     1
#define IN_C     2

/* 输出张量索引: [0]=c_out [1]=logits [2]=h_out */
#define OUT_C     0
#define OUT_LOGIT 1
#define OUT_H     2

/****************************************************************************
 * Private Data
 ****************************************************************************/

/* 模型字节 (RODATA, XIP 直接从 Flash 执行, 16 字节对齐; 定义见 model_data.h) */

/* Tensor Arena (SRAM BSS, 见上方说明) */
static uint8_t s_arena[AI_LM_ARENA_SIZE] __attribute__((aligned(16)));

static tflite::MicroInterpreter *s_interp = NULL;
static tflite::MicroMutableOpResolver<12> s_resolver;
static pthread_mutex_t s_lm_mutex = PTHREAD_MUTEX_INITIALIZER;

/* 自回归状态 (跨 step 保持, 由锁保护) */
static float s_c[AI_LM_LSTM_UNITS];
static float s_h[AI_LM_LSTM_UNITS];

/* 回复文本暂存 (避免大数组在栈上) */
static char s_reply_scratch[AI_LM_REPLY_MAX + 1];

/* 标记 token id (ai_lm_init 时查表; 词表中为完整单 piece) */
static int s_id_toolend = -1;    /* </tool> */
static int s_id_obs     = -1;    /* <obs>   */
static int s_id_bot     = -1;    /* <bot>   */
static int s_id_call    = -1;    /* <call>  */

/* 推理取消标志: UI 可中途打断长推理, 释放 CPU (volatile, 无需加锁) */
static volatile int s_cancel;

/****************************************************************************
 * Private Functions
 ****************************************************************************/

/* 在输出 logits 张量上做 argmax (数据留在 arena 内, 不拷贝) */
static int32_t lm_argmax(const TfLiteTensor *logits)
{
  int32_t best = 0;
  float bestv = logits->data.f[0];

  for (int i = 1; i < logits->dims->data[1]; i++)
    {
      if (logits->data.f[i] > bestv)
        {
          bestv = logits->data.f[i];
          best = i;
        }
    }

  return best;
}

/* 单步推理: 喂 token + 当前状态, 更新 s_c/s_h, 返回 logits 张量 */
static const TfLiteTensor *lm_step(int32_t token)
{
  TfLiteTensor *in_tok = s_interp->input(IN_TOKEN);
  TfLiteTensor *in_h = s_interp->input(IN_H);
  TfLiteTensor *in_c = s_interp->input(IN_C);

  in_tok->data.i32[0] = token;
  memcpy(in_h->data.f, s_h, sizeof(s_h));
  memcpy(in_c->data.f, s_c, sizeof(s_c));

  if (s_interp->Invoke() != kTfLiteOk)
    {
      return NULL;
    }

  memcpy(s_c, s_interp->output(OUT_C)->data.f, sizeof(s_c));
  memcpy(s_h, s_interp->output(OUT_H)->data.f, sizeof(s_h));

  return s_interp->output(OUT_LOGIT);
}

/* 去掉字符串首尾空白 */
static void reply_trim(char *s)
{
  char *start = s;
  char *end;

  while (*start == ' ' || *start == '\t' || *start == '\n' || *start == '\r')
    {
      start++;
    }

  end = start + strlen(start);
  while (end > start &&
         (end[-1] == ' ' || end[-1] == '\t' || end[-1] == '\n' ||
          end[-1] == '\r'))
    {
      *--end = '\0';
    }

  if (start != s)
    {
      memmove(s, start, strlen(start) + 1);
    }
}

/****************************************************************************
 * Public Functions
 ****************************************************************************/

int ai_lm_init(void)
{
  const tflite::Model *model;

  pthread_mutex_lock(&s_lm_mutex);

  if (s_interp != NULL)
    {
      pthread_mutex_unlock(&s_lm_mutex);
      return 0;
    }

  model = tflite::GetModel(g_velaai_model);
  if (model == NULL || model->version() != TFLITE_SCHEMA_VERSION)
    {
      pthread_mutex_unlock(&s_lm_mutex);
      return -1;
    }

  /* 注册模型所需的全部算子 (与 host 侧 op 列表一致) */
  s_resolver.AddGather();
  s_resolver.AddFullyConnected();
  s_resolver.AddSoftmax();
  s_resolver.AddSplit();
  s_resolver.AddUnpack();
  s_resolver.AddTanh();
  s_resolver.AddLogistic();
  s_resolver.AddMul();
  s_resolver.AddAdd();
  s_resolver.AddQuantize();
  s_resolver.AddDequantize();

  static tflite::MicroInterpreter interpreter(model, s_resolver, s_arena,
                                              AI_LM_ARENA_SIZE);
  if (interpreter.AllocateTensors() != kTfLiteOk)
    {
      pthread_mutex_unlock(&s_lm_mutex);
      return -1;
    }

  s_interp = &interpreter;
  memset(s_c, 0, sizeof(s_c));
  memset(s_h, 0, sizeof(s_h));

  /* 标记 token id (agent 模式解析用) */
  s_id_toolend = sp_piece_id("</tool>");
  s_id_obs     = sp_piece_id("<obs>");
  s_id_bot     = sp_piece_id("<bot>");
  s_id_call    = sp_piece_id("<call>");

  pthread_mutex_unlock(&s_lm_mutex);
  return 0;
}

/* 内部生成: 支持额外停止标记 (命中时该标记 token 一并写入 out, 供上层解析) */
static int lm_generate_internal(const int32_t *prompt, int n_prompt,
                                const int32_t *stop_ids, int n_stops,
                                int32_t *out_tokens, int max_new, int *out_len)
{
  const TfLiteTensor *logits;
  int32_t next;
  int produced = 0;

  if (out_len != NULL)
    {
      *out_len = 0;
    }

  if (s_interp == NULL || prompt == NULL || out_tokens == NULL ||
      n_prompt <= 0 || max_new <= 0)
    {
      return -1;
    }

  pthread_mutex_lock(&s_lm_mutex);

  /* 每次生成从零状态开始 (状态只在单次生成内部传递) */
  memset(s_c, 0, sizeof(s_c));
  memset(s_h, 0, sizeof(s_h));

  /* 1) 编码阶段: 逐 token 走 prompt, 只累积状态 */
  for (int i = 0; i < n_prompt; i++)
    {
      if (s_cancel)
        {
          pthread_mutex_unlock(&s_lm_mutex);
          return -1;
        }

      logits = lm_step(prompt[i]);
      if (logits == NULL)
        {
          pthread_mutex_unlock(&s_lm_mutex);
          return -1;
        }
    }

  /* 2) 解码阶段: argmax 自回归, EOS 早停; 每步检查取消 (UI 打断) */
  for (int step = 0; step < max_new; step++)
    {
      if (s_cancel)
        {
          pthread_mutex_unlock(&s_lm_mutex);
          return -1;
        }

      next = lm_argmax(logits);

      if (next == AI_LM_EOS_ID)
        {
          break;
        }

      out_tokens[produced++] = next;

      if (stop_ids != NULL)
        {
          int hit = 0;

          for (int k = 0; k < n_stops; k++)
            {
              if (next == stop_ids[k])
                {
                  hit = 1;
                  break;
                }
            }

          if (hit)
            {
              break;
            }
        }

      logits = lm_step(next);
      if (logits == NULL)
        {
          break;
        }
    }

  pthread_mutex_unlock(&s_lm_mutex);

  if (out_len != NULL)
    {
      *out_len = produced;
    }

  return 0;
}

int ai_lm_generate(const int32_t *prompt, int n_prompt,
                   int32_t *out_tokens, int max_new, int *out_len)
{
  return lm_generate_internal(prompt, n_prompt, NULL, 0,
                              out_tokens, max_new, out_len);
}

/****************************************************************************
 * Public Functions (取消机制)
 ****************************************************************************/

void ai_lm_cancel(void)
{
  s_cancel = 1;
}

void ai_lm_cancel_clear(void)
{
  s_cancel = 0;
}

int ai_lm_cancel_check(void)
{
  return s_cancel;
}

int ai_lm_reply(const char *user_text, char *reply, int reply_size)
{
  char prompt[AI_LM_MAX_PROMPT_CHARS + 32];
  int32_t prompt_ids[AI_LM_PROMPT_MAX_TOKENS];
  int32_t gen_ids[AI_LM_MAX_NEW];
  int n_prompt;
  int n_gen;
  int n_dec;
  char *bot;

  s_cancel = 0;   /* 新请求开始, 清取消标志 */

  if (reply == NULL || reply_size <= 0)
    {
      return -1;
    }

  reply[0] = '\0';

  if (user_text == NULL || user_text[0] == '\0')
    {
      return -1;
    }

  /* 1) 构造 prompt: "<usr> {text} <bot>" (与训练数据格式一致) */
  snprintf(prompt, sizeof(prompt), "<usr> %.*s <bot>",
           AI_LM_MAX_PROMPT_CHARS, user_text);

  /* 2) 分词 */
  n_prompt = sp_tokenize(prompt, prompt_ids, AI_LM_PROMPT_MAX_TOKENS);
  if (n_prompt <= 0)
    {
      return -1;
    }

  /* 3) 自回归生成 */
  if (ai_lm_generate(prompt_ids, n_prompt, gen_ids, AI_LM_MAX_NEW, &n_gen) != 0)
    {
      return -1;
    }

  if (n_gen <= 0)
    {
      return -1;
    }

  /* 4) 解码 */
  n_dec = sp_detokenize(gen_ids, n_gen, s_reply_scratch,
                        sizeof(s_reply_scratch));
  if (n_dec <= 0)
    {
      return -1;
    }

  /* 5) 提取最后一个 <bot> 之后的文本 */
  bot = strstr(s_reply_scratch, "<bot>");
  if (bot != NULL)
    {
      bot += 5;
      /* 跳过 bot 前缀空白 */
      while (*bot == ' ')
        {
          bot++;
        }

      if (*bot == '\0')
        {
          return -1;
        }

      strncpy(reply, bot, reply_size - 1);
      reply[reply_size - 1] = '\0';
    }
  else
    {
      /* 无 <bot>: 若是工具调用残渣则视为失败, 否则直接展示 */
      if (strstr(s_reply_scratch, "<call>") != NULL ||
          strstr(s_reply_scratch, "<obs>") != NULL)
        {
          return -1;
        }

      strncpy(reply, s_reply_scratch, reply_size - 1);
      reply[reply_size - 1] = '\0';
    }

  reply_trim(reply);

  return (reply[0] != '\0') ? 0 : -1;
}

/****************************************************************************
 * Agent 模式: 两段式生成 + 端侧执行器 + 槽位校验/模板兜底
 *
 * 量化 (16x8) 后模型的槽位复制 (房间/数值 -> 回复) 明显退化 (主机侧 100 条
 * 评估: 整句 18% vs 全精度 82%), 但工具调用生成仍 100% 准确。故:
 *   - <call> 由模型生成, 参数与用户原话对齐 (数字/房间修复)
 *   - <obs> 由端侧执行器产出 (设备状态表 / RTC / 模拟传感器)
 *   - <bot> 回复由模型生成, 但须通过槽位校验 (回复含调用的房间与数值);
 *     不通过则回退到与 velaAI 训练模板一致的确定性回复
 ****************************************************************************/

/* ── 常量表 (与 velaAI build_dataset_v3.py 一致) ── */

static const char *const AG_ROOMS[] = {
  "客厅", "卧室", "主卧", "次卧", "儿童房", "婴儿房", "厨房", "卫生间",
  "阳台", "玄关", "书房", "母婴室", "老人房", "客房", "杂物间", "衣帽间",
  "走廊", "楼梯", "酒窖", "影音室", "电竞房", "健身房", "瑜伽室", "茶室",
  "琴房", "画室", "储藏室", "阁楼", "地下室", "洗衣房",
};
#define AG_N_ROOMS (int)(sizeof(AG_ROOMS) / sizeof(AG_ROOMS[0]))

static const char *const AG_DEVICES[] = {
  "加湿器", "空气净化器", "风扇", "热水器", "新风机",
};
#define AG_N_DEVICES (int)(sizeof(AG_DEVICES) / sizeof(AG_DEVICES[0]))

static const char *const AG_DOORS_SPECIAL[] = {
  "车库门", "邻居门", "小区大门", "公司门", "保险柜", "机房", "配电室",
  "物业办公室",
};
#define AG_N_DOORS_SPECIAL (int)(sizeof(AG_DOORS_SPECIAL) / sizeof(AG_DOORS_SPECIAL[0]))

static const char *const AG_DOORS_NORMAL[] = {
  "大门", "后门", "侧门", "前门", "正门", "室内门", "阳台门", "储物间门",
};
#define AG_N_DOORS_NORMAL (int)(sizeof(AG_DOORS_NORMAL) / sizeof(AG_DOORS_NORMAL[0]))

static const char *const AG_CITIES[] = {
  "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "西安", "天津",
  "武汉", "南京", "苏州", "青岛", "大连", "昆明", "福州", "哈尔滨", "长春",
  "沈阳", "石家庄", "太原", "济南", "郑州", "长沙", "南昌", "合肥", "厦门",
  "南宁", "海口", "贵阳", "兰州", "西宁", "银川", "乌鲁木齐", "拉萨",
  "呼和浩特", "宁波", "无锡", "佛山", "东莞",
};
#define AG_N_CITIES (int)(sizeof(AG_CITIES) / sizeof(AG_CITIES[0]))

static const char *const AG_AC_MODES[] = {
  "制热", "制冷", "除湿", "智能", "送风", "自动",
};
#define AG_N_AC_MODES (int)(sizeof(AG_AC_MODES) / sizeof(AG_AC_MODES[0]))

/* ── 执行器设备状态表 ── */

#define AG_MAX_DEVS   16
#define AG_DEV_NAME   40

typedef struct
{
  char name[AG_DEV_NAME];
  int  state;      /* 1=on 0=off 2=standby 3=error */
  int  value;      /* 空调温度等 */
} ag_dev_t;

static ag_dev_t s_ag_devs[AG_MAX_DEVS];
static int s_ag_ndevs;

/* 查找或创建设备表项 */
static ag_dev_t *ag_dev_find(const char *name)
{
  for (int i = 0; i < s_ag_ndevs; i++)
    {
      if (strcmp(s_ag_devs[i].name, name) == 0)
        {
          return &s_ag_devs[i];
        }
    }

  if (s_ag_ndevs >= AG_MAX_DEVS || strlen(name) >= AG_DEV_NAME)
    {
      return NULL;
    }

  strncpy(s_ag_devs[s_ag_ndevs].name, name, AG_DEV_NAME - 1);
  s_ag_devs[s_ag_ndevs].name[AG_DEV_NAME - 1] = '\0';
  s_ag_devs[s_ag_ndevs].state = 1;   /* 新设备默认开 (演示友好) */
  s_ag_devs[s_ag_ndevs].value = 0;
  return &s_ag_devs[s_ag_ndevs++];
}

/* ── 参数容器与解析 ── */

#define AG_MAX_ARGS 6
#define AG_ARG_LEN  40

typedef struct
{
  char a[AG_MAX_ARGS][AG_ARG_LEN];
  int  n;
} ag_args_t;

/* 从 "<call> tool <arg> a <arg> b ..." 文本解析工具与参数 */
static int ag_parse_call(const char *text, char *tool, int tool_size,
                         ag_args_t *args)
{
  const char *call = strstr(text, "<call>");
  char work[192];
  char *tok;
  char *save;

  if (call == NULL)
    {
      return -1;
    }

  call += 6;
  while (*call == ' ')
    {
      call++;
    }

  /* 截到 </tool> / <obs> / 串尾 */
  snprintf(work, sizeof(work), "%s", call);

  for (char *p = work; *p; p++)
    {
      if (strncmp(p, "</tool>", 7) == 0 || strncmp(p, "<obs>", 5) == 0 ||
          strncmp(p, "<bot>", 5) == 0)
        {
          *p = '\0';
          break;
        }
    }

  args->n = 0;
  tool[0] = '\0';

  for (tok = strtok_r(work, " ", &save); tok != NULL;
       tok = strtok_r(NULL, " ", &save))
    {
      if (strcmp(tok, "<arg>") == 0)
        {
          continue;
        }

      if (tool[0] == '\0')
        {
          strncpy(tool, tok, tool_size - 1);
          tool[tool_size - 1] = '\0';
        }
      else if (args->n < AG_MAX_ARGS)
        {
          strncpy(args->a[args->n], tok, AG_ARG_LEN - 1);
          args->a[args->n][AG_ARG_LEN - 1] = '\0';
          args->n++;
        }
    }

  return (tool[0] != '\0') ? 0 : -1;
}

/* ── 参数修复: 以用户原话为准 ── */

/* 字符串是否全为 ASCII 数字 */
static int ag_all_digits(const char *s)
{
  if (s == NULL || *s == '\0')
    {
      return 0;
    }

  for (const char *p = s; *p; p++)
    {
      if (*p < '0' || *p > '9')
        {
          return 0;
        }
    }

  return 1;
}

/* arg 的每个 CJK 字符是否都出现在 text 中 (连写参数的宽松匹配) */
static int ag_chars_in(const char *arg, const char *text)
{
  if (strstr(text, arg) != NULL)
    {
      return 1;
    }

  for (const char *p = arg; *p; )
    {
      if ((*p & 0xE0) == 0xC0 || (*p & 0xF0) == 0xE0 || (*p & 0xF8) == 0xF0)
        {
          /* 多字节字符首字节: 求完整长度 */
          int len = (((*p & 0xF0) == 0xE0) ? 3 :
                     ((*p & 0xE0) == 0xC0) ? 2 : 4);
          char ch[5];

          if (len > (int)(strlen(p)))
            {
              len = 1;
            }

          memcpy(ch, p, len);
          ch[len] = '\0';

          if (strstr(text, ch) == NULL)
            {
              return 0;
            }

          p += len;
        }
      else
        {
          p++;
        }
    }

  return 1;
}

/* 在用户原话中找房间名 (最长优先) */
static const char *ag_room_in(const char *text)
{
  for (int i = 0; i < AG_N_ROOMS; i++)
    {
      if (strstr(text, AG_ROOMS[i]) != NULL)
        {
          return AG_ROOMS[i];
        }
    }

  return NULL;
}

/* 修复生成的调用参数: 数字参数以用户原话最后一个数字串为准;
 * 房间/设备参数若与原话不符, 用原话中扫到的房间替换 */
static void ag_repair_args(const char *user, const char *tool, ag_args_t *args)
{
  const char *room = ag_room_in(user);
  char numbuf[16];
  const char *num = NULL;

  /* 用户原话最后一个连续数字串 */
  {
    const char *p = user + strlen(user);

    while (p > user && !(*(p - 1) >= '0' && *(p - 1) <= '9'))
      {
        p--;
      }

    if (p > user)
      {
        const char *end = p;

        while (p > user && *(p - 1) >= '0' && *(p - 1) <= '9')
          {
            p--;
          }

        int len = (int)(end - p);

        if (len > 0 && len < (int)sizeof(numbuf))
          {
            memcpy(numbuf, p, len);
            numbuf[len] = '\0';
            num = numbuf;
          }
      }
  }

  for (int i = 0; i < args->n; i++)
    {
      if (ag_all_digits(args->a[i]))
        {
          /* 数值参数: 用户原话有数字且不同 -> 以原话为准 */
          if (num != NULL && strcmp(args->a[i], num) != 0)
            {
              strncpy(args->a[i], num, AG_ARG_LEN - 1);
              args->a[i][AG_ARG_LEN - 1] = '\0';
            }
        }
      else if (!ag_chars_in(args->a[i], user))
        {
          /* 房间/设备参数与原话不符: 用原话扫到的房间重建 */
          if (room != NULL)
            {
              const char *dev = NULL;

              if (strcmp(tool, "set_device") == 0 ||
                  strcmp(tool, "get_device_status") == 0)
                {
                  for (int d = 0; d < AG_N_DEVICES; d++)
                    {
                      if (strstr(user, AG_DEVICES[d]) != NULL)
                        {
                          dev = AG_DEVICES[d];
                          break;
                        }
                    }
                }

              snprintf(args->a[i], AG_ARG_LEN, "%s%s", room,
                       dev != NULL ? dev : "");
            }
        }
    }
}

/* ── 端侧执行器: 产出 obs 值 ── */

static int ag_execute(const char *tool, const ag_args_t *args,
                      char *obs, int obs_size)
{
  ag_dev_t *dev;

  if (strcmp(tool, "get_time") == 0)
    {
      time_t now = time(NULL);
      struct tm tmv;

      localtime_r(&now, &tmv);
      snprintf(obs, obs_size, "%d:%02d", tmv.tm_hour, tmv.tm_min);
      return 0;
    }

  if (strcmp(tool, "get_temp") == 0)
    {
      snprintf(obs, obs_size, "25.8");    /* 板上无温度传感器, 演示值 */
      return 0;
    }

  if (strcmp(tool, "get_weather") == 0)
    {
      snprintf(obs, obs_size, "晴");      /* 离线设备, 演示值 */
      return 0;
    }

  if (strcmp(tool, "get_device_status") == 0)
    {
      dev = ag_dev_find(args->n > 0 ? args->a[0] : "设备");
      snprintf(obs, obs_size, "%s",
               dev == NULL ? "off" :
               dev->state == 1 ? "on" :
               dev->state == 2 ? "standby" :
               dev->state == 3 ? "error" : "off");
      return 0;
    }

  if (strcmp(tool, "set_ac") == 0)
    {
      dev = ag_dev_find(args->n > 0 ? args->a[0] : "空调");
      if (dev != NULL && args->n > 1)
        {
          dev->state = 1;
          dev->value = atoi(args->a[1]);
        }

      snprintf(obs, obs_size, "success");
      return 0;
    }

  if (strcmp(tool, "set_ac_mode") == 0 || strcmp(tool, "lock_door") == 0 ||
      strcmp(tool, "unlock_door") == 0)
    {
      snprintf(obs, obs_size, "success");
      return 0;
    }

  /* set_timer / cancel_timer: 接真实闹钟状态机（pet_care, 场景①）。
   * 参数 N 为分钟数（ag_parse_call 已解析），返回值进 observation
   * 参与第二段生成；闹钟预告/到点/升级由 pet_care 自己渲染。 */
  if (strcmp(tool, "set_timer") == 0)
    {
#ifdef CONFIG_AI_AGENT_LVGL_UI
      int minutes = atoi(args->n > 0 ? args->a[0] : "0");
      if (minutes > 0 && pet_care_alarm_set(minutes) == 0)
        {
          snprintf(obs, obs_size, "success");
        }
      else
        {
          snprintf(obs, obs_size, "invalid_minutes");
        }
#else
      snprintf(obs, obs_size, "unsupported");
#endif
      return 0;
    }

  if (strcmp(tool, "cancel_timer") == 0)
    {
#ifdef CONFIG_AI_AGENT_LVGL_UI
      pet_care_alarm_cancel();
      snprintf(obs, obs_size, "success");
#else
      snprintf(obs, obs_size, "unsupported");
#endif
      return 0;
    }

  if (strcmp(tool, "set_device") == 0)
    {
      dev = ag_dev_find(args->n > 0 ? args->a[0] : "设备");
      if (dev != NULL && args->n > 1)
        {
          dev->state = (strcmp(args->a[1], "on") == 0) ? 1 : 0;
        }

      snprintf(obs, obs_size, "success");
      return 0;
    }

  if (strcmp(tool, "open_door") == 0)
    {
      snprintf(obs, obs_size, "permission_denied");

      /* 权限门仅限特殊门列表 */
      if (args->n > 0)
        {
          for (int i = 0; i < AG_N_DOORS_SPECIAL; i++)
            {
              if (strstr(args->a[0], AG_DOORS_SPECIAL[i]) != NULL)
                {
                  return 0;
                }
            }
        }

      snprintf(obs, obs_size, "success");
      return 0;
    }

  return -1;   /* 未知工具 */
}

/* ── 模板回复 (与 velaAI build_dataset_v3.py 训练模板一致) ── */

static int ag_template(const char *tool, const ag_args_t *args,
                       const char *obs, char *reply, int size)
{
  const char *a0 = args->n > 0 ? args->a[0] : "";
  const char *a1 = args->n > 1 ? args->a[1] : "";

  if (strcmp(tool, "set_ac") == 0)
    {
      snprintf(reply, size, "好的，%s空调已设为%s度", a0, a1);
    }
  else if (strcmp(tool, "set_ac_mode") == 0)
    {
      snprintf(reply, size, "好的，%s空调已切换到%s", a0, a1);
    }
  else if (strcmp(tool, "set_device") == 0)
    {
      if (strcmp(a1, "off") == 0)
        {
          snprintf(reply, size, "好的，已为您关闭%s", a0);
        }
      else
        {
          snprintf(reply, size, "好的，已为您打开%s", a0);
        }
    }
  else if (strcmp(tool, "get_device_status") == 0)
    {
      const char *st = (strcmp(obs, "on") == 0) ? "正在运行" :
                       (strcmp(obs, "standby") == 0) ? "待机中" :
                       (strcmp(obs, "error") == 0) ? "出现故障" : "已关闭";
      snprintf(reply, size, "%s%s", a0, st);
    }
  else if (strcmp(tool, "get_temp") == 0)
    {
      snprintf(reply, size, "当前室内温度 %s 度", obs);
    }
  else if (strcmp(tool, "get_time") == 0)
    {
      int hh = 0;
      int mm = 0;

      sscanf(obs, "%d:%d", &hh, &mm);
      if (mm == 0)
        {
          snprintf(reply, size, "现在是 %d 点 整", hh);
        }
      else
        {
          snprintf(reply, size, "现在是 %d 点 %d 分", hh, mm);
        }
    }
  else if (strcmp(tool, "get_weather") == 0)
    {
      if (a0[0] != '\0')
        {
          snprintf(reply, size, "%s现在%s", a0, obs);
        }
      else
        {
          snprintf(reply, size, "今天天气%s", obs);
        }
    }
  else if (strcmp(tool, "set_timer") == 0)
    {
      snprintf(reply, size, "好的，%s后提醒您", a0);
    }
  else if (strcmp(tool, "cancel_timer") == 0)
    {
      snprintf(reply, size, "好的，已取消定时");
    }
  else if (strcmp(tool, "lock_door") == 0)
    {
      snprintf(reply, size, "好的，%s已上锁", a0);
    }
  else if (strcmp(tool, "unlock_door") == 0)
    {
      snprintf(reply, size, "好的，已打开%s", a0);
    }
  else if (strcmp(tool, "open_door") == 0)
    {
      if (strcmp(obs, "permission_denied") == 0)
        {
          snprintf(reply, size, "抱歉，我没有控制%s的权限", a0);
        }
      else
        {
          snprintf(reply, size, "好的，已打开%s", a0);
        }
    }
  else
    {
      return -1;
    }

  return 0;
}

/* ── 槽位校验: 模型回复须包含调用的房间/数值参数与 obs 数值 ── */

/* obs 数值 (时间/温度) 的各数字组是否都在 reply 中 */
static int ag_obs_digits_in(const char *obs, const char *reply)
{
  char groups[8][12];
  int ng = 0;
  const char *p = obs;

  while (*p && ng < 8)
    {
      if (*p >= '0' && *p <= '9')
        {
          int len = 0;

          while (p[len] >= '0' && p[len] <= '9' && len < 11)
            {
              len++;
            }

          memcpy(groups[ng], p, len);
          groups[ng][len] = '\0';
          ng++;
          p += len;
        }
      else
        {
          p++;
        }
    }

  for (int i = 0; i < ng; i++)
    {
      if (strstr(reply, groups[i]) == NULL)
        {
          return 0;
        }
    }

  return 1;
}

static int ag_verify(const char *tool, const ag_args_t *args,
                     const char *obs, const char *reply)
{
  if (strchr(reply, '<') != NULL)
    {
      return 0;    /* 泄漏了标记残片 */
    }

  for (int i = 0; i < args->n; i++)
    {
      if (ag_all_digits(args->a[i]))
        {
          if (strstr(reply, args->a[i]) == NULL)
            {
              return 0;
            }
        }
      else if (!ag_chars_in(args->a[i], reply))
        {
          return 0;
        }
    }

  if (strcmp(tool, "get_time") == 0 || strcmp(tool, "get_temp") == 0)
    {
      return ag_obs_digits_in(obs, reply);
    }

  if (strcmp(tool, "get_weather") == 0)
    {
      return strstr(reply, obs) != NULL;
    }

  if (strcmp(tool, "get_device_status") == 0)
    {
      /* 状态类回复须包含与 obs 对应的状态措辞 */
      const char *need = (strcmp(obs, "on") == 0) ? "正在运行" :
                         (strcmp(obs, "standby") == 0) ? "待机中" :
                         (strcmp(obs, "error") == 0) ? "出现故障" : "已关闭";
      return strstr(reply, need) != NULL;
    }

  return 1;
}

/* ── 意图识别: 规则先行, 修正量化模型的偶发工具错选 ── */

enum
{
  AG_INT_NONE = 0,
  AG_INT_LIGHT,       /* 直接: 灯 */
  AG_INT_CURTAIN,     /* 直接: 窗帘 */
  AG_INT_AC_ONOFF,    /* 直接: 空调开关 */
  AG_INT_MUSIC,       /* 直接: 音乐 */
  AG_INT_TV,          /* 直接: 电视 */
  AG_INT_WEATHER,     /* 工具: get_weather */
  AG_INT_TIME,        /* 工具: get_time */
  AG_INT_TEMP,        /* 工具: get_temp */
  AG_INT_AC_SET,      /* 工具: set_ac */
  AG_INT_AC_MODE,     /* 工具: set_ac_mode */
  AG_INT_TIMER_SET,   /* 工具: set_timer */
  AG_INT_TIMER_CANCEL,/* 工具: cancel_timer */
  AG_INT_DOOR_LOCK,   /* 工具: lock_door */
  AG_INT_DOOR_UNLOCK, /* 工具: unlock_door */
  AG_INT_DEV_ONOFF,   /* 工具: set_device */
  AG_INT_DEV_STATUS,  /* 工具: get_device_status */
};

static int ag_has(const char *text, const char *sub)
{
  return strstr(text, sub) != NULL;
}

/* 用户文本中是否有 ASCII 数字 */
static int ag_has_digits(const char *text)
{
  for (const char *p = text; *p; p++)
    {
      if (*p >= '0' && *p <= '9')
        {
          return 1;
        }
    }

  return 0;
}

static int ag_detect_intent(const char *user)
{
  if (ag_has(user, "天气"))
    {
      return AG_INT_WEATHER;
    }

  if (ag_has(user, "灯"))
    {
      return AG_INT_LIGHT;
    }

  if (ag_has(user, "窗帘"))
    {
      return AG_INT_CURTAIN;
    }

  /* 新设备 (加湿器等): 先于空调/灯等通用词 */
  for (int d = 0; d < AG_N_DEVICES; d++)
    {
      if (ag_has(user, AG_DEVICES[d]))
        {
          if (ag_has(user, "状态") || ag_has(user, "开着") ||
              ag_has(user, "怎么样") || ag_has(user, "是否") ||
              ag_has(user, "开启吗") || ag_has(user, "查一下") ||
              ag_has(user, "查询") || ag_has(user, "查个"))
            {
              return AG_INT_DEV_STATUS;
            }

          return AG_INT_DEV_ONOFF;
        }
    }

  if (ag_has(user, "空调"))
    {
      if (ag_has(user, "切换") || ag_has(user, "模式") ||
          ag_has(user, "除湿") || ag_has(user, "制热"))
        {
          return AG_INT_AC_MODE;
        }

      if (ag_has_digits(user) && (ag_has(user, "调") || ag_has(user, "设") ||
                                  ag_has(user, "开到") || ag_has(user, "度")))
        {
          return AG_INT_AC_SET;
        }

      return AG_INT_AC_ONOFF;
    }

  if (ag_has(user, "提醒") || ag_has(user, "定时") || ag_has(user, "叫我"))
    {
      return ag_has(user, "取消") ? AG_INT_TIMER_CANCEL : AG_INT_TIMER_SET;
    }

  if (ag_has(user, "解锁") || ag_has(user, "开锁"))
    {
      return AG_INT_DOOR_UNLOCK;
    }

  if (ag_has(user, "锁"))
    {
      return AG_INT_DOOR_LOCK;
    }

  if (ag_has(user, "门"))
    {
      return AG_INT_DOOR_LOCK;   /* 无更具体意图时按上锁处理 */
    }

  if (ag_has(user, "几点") || ag_has(user, "时间") || ag_has(user, "什么时候"))
    {
      return AG_INT_TIME;
    }

  if (ag_has(user, "多少度") || (ag_has(user, "温度") && !ag_has(user, "调")))
    {
      return AG_INT_TEMP;
    }

  if (ag_has(user, "音乐") || ag_has(user, "歌"))
    {
      return AG_INT_MUSIC;
    }

  if (ag_has(user, "电视"))
    {
      return AG_INT_TV;
    }

  return AG_INT_NONE;
}

/* 意图对应的工具名 (直接回复类返回 NULL) */
static const char *ag_intent_tool(int intent)
{
  switch (intent)
    {
      case AG_INT_WEATHER:      return "get_weather";
      case AG_INT_TIME:         return "get_time";
      case AG_INT_TEMP:         return "get_temp";
      case AG_INT_AC_SET:       return "set_ac";
      case AG_INT_AC_MODE:      return "set_ac_mode";
      case AG_INT_TIMER_SET:    return "set_timer";
      case AG_INT_TIMER_CANCEL: return "cancel_timer";
      case AG_INT_DOOR_LOCK:    return "lock_door";
      case AG_INT_DOOR_UNLOCK:  return "unlock_door";
      case AG_INT_DEV_ONOFF:    return "set_device";
      case AG_INT_DEV_STATUS:   return "get_device_status";
      default:                  return NULL;
    }
}

/* 用户文本中最后一个 "数字+时长单位" (如 "10分钟"), 无则 NULL */
static const char *ag_duration_scan(const char *user, char *buf, int size)
{
  const char *p = user + strlen(user);

  while (p > user && !(*(p - 1) >= '0' && *(p - 1) <= '9'))
    {
      p--;
    }

  if (p == user)
    {
      return NULL;
    }

  const char *end = p;

  while (p > user && *(p - 1) >= '0' && *(p - 1) <= '9')
    {
      p--;
    }

  const char *units[] = { "个半小时", "半小时", "一刻钟", "分钟", "小时", "秒钟" };

  for (int u = 0; u < (int)(sizeof(units) / sizeof(units[0])); u++)
    {
      int ulen = (int)strlen(units[u]);

      if ((int)(strlen(user) - (end - user)) >= ulen &&
          strncmp(end, units[u], ulen) == 0)
        {
          int dlen = (int)(end - p);

          if (dlen + ulen < size)
            {
              memcpy(buf, p, dlen);
              memcpy(buf + dlen, units[u], ulen);
              buf[dlen + ulen] = '\0';
              return buf;
            }
        }
    }

  return NULL;
}

/* 按意图从用户原话重建调用参数 */
static void ag_intent_args(int intent, const char *user, char *tool,
                           int tool_size, ag_args_t *args)
{
  const char *room = ag_room_in(user);
  char rname[24];
  char buf[24];
  const char *v;

  snprintf(rname, sizeof(rname), "%s", room != NULL ? room : "客厅");
  args->n = 0;

  switch (intent)
    {
      case AG_INT_WEATHER:
        strncpy(tool, "get_weather", tool_size - 1);

        for (int i = 0; i < AG_N_CITIES; i++)
          {
            if (ag_has(user, AG_CITIES[i]))
              {
                strncpy(args->a[0], AG_CITIES[i], AG_ARG_LEN - 1);
                args->a[0][AG_ARG_LEN - 1] = '\0';
                args->n = 1;
                break;
              }
          }

        break;

      case AG_INT_TIME:
        strncpy(tool, "get_time", tool_size - 1);
        break;

      case AG_INT_TEMP:
        strncpy(tool, "get_temp", tool_size - 1);
        break;

      case AG_INT_AC_SET:
        {
          strncpy(tool, "set_ac", tool_size - 1);
          strncpy(args->a[0], rname, AG_ARG_LEN - 1);
          args->a[0][AG_ARG_LEN - 1] = '\0';
          args->n = 1;

          /* 温度: 用户原话最后一个数字串 */
          v = ag_duration_scan(user, buf, sizeof(buf));   /* 复用数字扫描 */

          if (v == NULL)
            {
              const char *q = user + strlen(user);

              while (q > user && !(*(q - 1) >= '0' && *(q - 1) <= '9'))
                {
                  q--;
                }

              if (q > user)
                {
                  const char *e = q;

                  while (q > user && *(q - 1) >= '0' && *(q - 1) <= '9')
                    {
                      q--;
                    }

                  int len = (int)(e - q);

                  if (len > 0 && len < (int)sizeof(buf))
                    {
                      memcpy(buf, q, len);
                      buf[len] = '\0';
                      v = buf;
                    }
                }
            }
          else
            {
              /* 时长扫描命中时截掉单位部分, 取纯数字 */
              char *d = buf;

              while (*d >= '0' && *d <= '9')
                {
                  d++;
                }

              *d = '\0';
              v = buf;
            }

          if (v != NULL && v[0] != '\0')
            {
              strncpy(args->a[1], v, AG_ARG_LEN - 1);
              args->a[1][AG_ARG_LEN - 1] = '\0';
              args->n = 2;
            }
        }
        break;

      case AG_INT_AC_MODE:
        strncpy(tool, "set_ac_mode", tool_size - 1);
        strncpy(args->a[0], rname, AG_ARG_LEN - 1);
        args->a[0][AG_ARG_LEN - 1] = '\0';
        args->n = 1;

        for (int m = 0; m < AG_N_AC_MODES; m++)
          {
            if (ag_has(user, AG_AC_MODES[m]))
              {
                strncpy(args->a[1], AG_AC_MODES[m], AG_ARG_LEN - 1);
                args->a[1][AG_ARG_LEN - 1] = '\0';
                args->n = 2;
                break;
              }
          }

        break;

      case AG_INT_TIMER_SET:
        strncpy(tool, "set_timer", tool_size - 1);
        v = ag_duration_scan(user, buf, sizeof(buf));

        if (v != NULL)
          {
            strncpy(args->a[0], v, AG_ARG_LEN - 1);
            args->a[0][AG_ARG_LEN - 1] = '\0';
            args->n = 1;
          }

        break;

      case AG_INT_TIMER_CANCEL:
        strncpy(tool, "cancel_timer", tool_size - 1);
        break;

      case AG_INT_DOOR_LOCK:
      case AG_INT_DOOR_UNLOCK:
        strncpy(tool, intent == AG_INT_DOOR_LOCK ? "lock_door" : "unlock_door",
                tool_size - 1);
        strncpy(args->a[0], "大门", AG_ARG_LEN - 1);

        for (int i = 0; i < AG_N_DOORS_SPECIAL; i++)
          {
            if (ag_has(user, AG_DOORS_SPECIAL[i]))
              {
                strncpy(args->a[0], AG_DOORS_SPECIAL[i], AG_ARG_LEN - 1);
                break;
              }
          }

        for (int i = 0; i < AG_N_DOORS_NORMAL; i++)
          {
            if (ag_has(user, AG_DOORS_NORMAL[i]))
              {
                strncpy(args->a[0], AG_DOORS_NORMAL[i], AG_ARG_LEN - 1);
                break;
              }
          }

        args->a[0][AG_ARG_LEN - 1] = '\0';
        args->n = 1;
        break;

      case AG_INT_DEV_ONOFF:
      case AG_INT_DEV_STATUS:
        {
          const char *dev = AG_DEVICES[0];

          for (int d = 0; d < AG_N_DEVICES; d++)
            {
              if (ag_has(user, AG_DEVICES[d]))
                {
                  dev = AG_DEVICES[d];
                  break;
                }
            }

          strncpy(tool, intent == AG_INT_DEV_ONOFF ? "set_device"
                                                   : "get_device_status",
                  tool_size - 1);
          snprintf(args->a[0], AG_ARG_LEN, "%s%s", rname, dev);
          args->n = 1;

          if (intent == AG_INT_DEV_ONOFF)
            {
              strncpy(args->a[1],
                      (ag_has(user, "关") && !ag_has(user, "打开")) ? "off" : "on",
                      AG_ARG_LEN - 1);
              args->a[1][AG_ARG_LEN - 1] = '\0';
              args->n = 2;
            }
        }
        break;

      default:
        break;
    }

  tool[tool_size - 1] = '\0';
}



/* 用户要"开"回复却"关" (或反之) 判不一致 */
/* ── 直接回复 (无工具调用) 的动作一致性检查与模板兜底 ── */

static int ag_direct_consistent(const char *user, const char *reply)
{
  int user_open  = (strstr(user, "打开") != NULL || strstr(user, "开启") != NULL ||
                    strstr(user, "开一下") != NULL || strstr(user, "开个") != NULL);
  int user_close = (strstr(user, "关闭") != NULL || strstr(user, "关掉") != NULL ||
                    strstr(user, "关上") != NULL || strstr(user, "关一下") != NULL);
  int rep_open   = (strstr(reply, "打开") != NULL || strstr(reply, "开启") != NULL);
  int rep_close  = (strstr(reply, "关闭") != NULL || strstr(reply, "关了") != NULL ||
                    strstr(reply, "停止") != NULL);

  if ((user_open && !user_close && rep_close && !rep_open) ||
      (user_close && !user_open && rep_open && !rep_close))
    {
      return 0;
    }

  /* 原话提到房间而回复没有 -> 槽位错 */
  const char *room = ag_room_in(user);

  if (room != NULL && strstr(reply, room) == NULL)
    {
      return 0;
    }

  return 1;
}

/* 直接指令模板兜底 (与训练数据话术一致) */
static int ag_direct_template(const char *user, char *reply, int size)
{
  const char *room = ag_room_in(user);
  char rname[24];

  snprintf(rname, sizeof(rname), "%s", room != NULL ? room : "客厅");

  int want_open  = (strstr(user, "打开") != NULL || strstr(user, "开启") != NULL ||
                    strstr(user, "开一下") != NULL || strstr(user, "播放") != NULL);
  int want_close = (strstr(user, "关闭") != NULL || strstr(user, "关掉") != NULL ||
                    strstr(user, "关上") != NULL || strstr(user, "停止") != NULL);

  if (strstr(user, "灯") != NULL && (strstr(user, "空调") != NULL ||
                                     strstr(user, "都") != NULL))
    {
      snprintf(reply, size, "好的，%s的灯和空调已全部打开", rname);
      return 0;
    }

  if (strstr(user, "灯") != NULL)
    {
      if (want_close)
        {
          snprintf(reply, size, "好的，%s灯已关闭", rname);
        }
      else
        {
          snprintf(reply, size, "好的，已为您打开%s灯", rname);
        }

      ag_dev_t *d = ag_dev_find(rname);

      if (d != NULL)
        {
          char n[AG_DEV_NAME];

          snprintf(n, sizeof(n), "%s灯", rname);
          strncpy(d->name, n, AG_DEV_NAME - 1);
          d->name[AG_DEV_NAME - 1] = '\0';
          d->state = want_close ? 0 : 1;
        }

      return 0;
    }

  if (strstr(user, "空调") != NULL)
    {
      if (want_close)
        {
          snprintf(reply, size, "好的，已为您关闭%s空调", rname);
        }
      else
        {
          snprintf(reply, size, "好的，已为您开启%s空调", rname);
        }

      return 0;
    }

  if (strstr(user, "窗帘") != NULL)
    {
      if (want_close)
        {
          snprintf(reply, size, "好的，%s窗帘已关闭", rname);
        }
      else
        {
          snprintf(reply, size, "好的，已为您打开%s窗帘", rname);
        }

      return 0;
    }

  if (strstr(user, "音乐") != NULL || strstr(user, "歌") != NULL)
    {
      if (want_close || strstr(user, "停") != NULL)
        {
          snprintf(reply, size, "好的，音乐已停止");
        }
      else
        {
          snprintf(reply, size, "好的，已为您播放音乐");
        }

      return 0;
    }

  if (strstr(user, "电视") != NULL)
    {
      if (want_close)
        {
          snprintf(reply, size, "好的，电视已关闭");
        }
      else
        {
          snprintf(reply, size, "好的，电视已打开");
        }

      return 0;
    }

  return -1;
}

/****************************************************************************
 * Public Functions (agent 模式)
 ****************************************************************************/

int ai_lm_agent_reply(const char *user_text, char *reply, int reply_size)
{
  char prompt[AI_LM_MAX_PROMPT_CHARS + 32];
  char gen1_text[AI_LM_REPLY_MAX + 64];
  char gen2_text[AI_LM_REPLY_MAX + 64];
  char tool[24];
  char intent_tool[24];
  char obs[24];
  char call_part[160];
  ag_args_t args;
  int32_t gen_ids[64];
  int n_gen;
  int n_prompt;
  int intent;
  const char *itool;
  char *p;

  s_cancel = 0;   /* 新请求开始, 清取消标志 */

  if (reply == NULL || reply_size <= 0)
    {
      return -1;
    }

  reply[0] = '\0';

  if (user_text == NULL || user_text[0] == '\0' || s_interp == NULL)
    {
      return -1;
    }

  if (s_id_toolend < 0 || s_id_obs < 0 || s_id_bot < 0 || s_id_call < 0)
    {
      return -1;
    }

  /* ── 意图识别 (规则): 修正量化模型的偶发工具错选 ── */
  intent = ag_detect_intent(user_text);
  itool = ag_intent_tool(intent);

  /* ── 阶段 1: "<usr> {q}" -> 生成 <call>...</tool> 或直接闲聊回复 ── */
  snprintf(prompt, sizeof(prompt), "<usr> %.*s", AI_LM_MAX_PROMPT_CHARS,
           user_text);

  {
    int32_t prompt_ids[AI_LM_PROMPT_MAX_TOKENS];
    int32_t stops[2];

    n_prompt = sp_tokenize(prompt, prompt_ids, AI_LM_PROMPT_MAX_TOKENS);
    if (n_prompt <= 0)
      {
        return -1;
      }

    stops[0] = s_id_toolend;
    stops[1] = s_id_obs;

    if (lm_generate_internal(prompt_ids, n_prompt, stops, 2,
                             gen_ids, 48, &n_gen) != 0)
      {
        return -1;
      }
  }

  if (sp_detokenize(gen_ids, n_gen, gen1_text, sizeof(gen1_text)) <= 0)
    {
      return -1;
    }

  /* ── 分支 A: 工具调用路径 ── */
  if (intent >= AG_INT_WEATHER && intent <= AG_INT_DEV_STATUS)
    {
      /* 意图为工具类: 以意图为准重建调用与参数 (量化模型偶发错选工具) */
      ag_args_t iargs;

      ag_intent_args(intent, user_text, intent_tool, sizeof(intent_tool),
                     &iargs);
      strncpy(tool, intent_tool, sizeof(tool) - 1);
      tool[sizeof(tool) - 1] = '\0';
      args = iargs;

      if (ag_execute(tool, &args, obs, sizeof(obs)) != 0)
        {
          return -1;
        }

      /* 重建 "<call> tool <arg>.. </tool>" 上下文 */
      {
        int off = snprintf(call_part, sizeof(call_part), "<call> %s", tool);

        for (int i = 0; i < args.n && off < (int)sizeof(call_part) - 2; i++)
          {
            off += snprintf(call_part + off, sizeof(call_part) - off,
                            " <arg> %s", args.a[i]);
          }

        snprintf(call_part + off, sizeof(call_part) - off, " </tool>");
      }
    }
  else if (strstr(gen1_text, "<call>") != NULL &&
           ag_parse_call(gen1_text, tool, sizeof(tool), &args) == 0 &&
           itool == NULL &&
           ag_execute(tool, &args, obs, sizeof(obs)) == 0)
    {
      /* 意图未命中 (AG_INT_NONE) 且模型自发产生了合法调用: 沿用之 */
      ag_repair_args(user_text, tool, &args);
      ag_execute(tool, &args, obs, sizeof(obs));

      /* 提取 "<call> ... </tool>" 上下文 (截到 </tool>, 缺失则补) */
      p = strstr(gen1_text, "<call>");
      snprintf(call_part, sizeof(call_part), "%s", p);

      char *e = strstr(call_part, "</tool>");

      if (e != NULL)
        {
          e[7] = '\0';
        }
      else
        {
          char *o = strstr(call_part, "<obs>");

          if (o != NULL)
            {
              *o = '\0';
            }

          strncat(call_part, " </tool>", sizeof(call_part) -
                  strlen(call_part) - 1);
        }
    }
  else
    {
      /* 意图为直接回复类 (灯/窗帘/空调开关/音乐/电视) 或模型未产生调用 */
      goto direct_path;
    }

  /* ── 阶段 2: 注入 obs -> 生成 "<bot> 回复" (到 <eos> 早停) ── */
  {
    char prompt2[AI_LM_MAX_PROMPT_CHARS + 224];
    int32_t prompt2_ids[AI_LM_PROMPT_MAX_TOKENS + 64];
    int n_prompt2;

    snprintf(prompt2, sizeof(prompt2), "<usr> %.*s %s <obs> %s",
             AI_LM_MAX_PROMPT_CHARS, user_text, call_part, obs);

    n_prompt2 = sp_tokenize(prompt2, prompt2_ids,
                            AI_LM_PROMPT_MAX_TOKENS + 64);
    if (n_prompt2 <= 0)
      {
        return -1;
      }

    if (lm_generate_internal(prompt2_ids, n_prompt2, NULL, 0,
                             gen_ids, 48, &n_gen) != 0)
      {
        return -1;
      }
  }

  if (sp_detokenize(gen_ids, n_gen, gen2_text, sizeof(gen2_text)) <= 0)
    {
      return -1;
    }

  /* 提取 <bot> 后文本; 通过槽位校验则采用模型回复 */
  p = strstr(gen2_text, "<bot>");

  if (p != NULL)
    {
      p += 5;
      while (*p == ' ')
        {
          p++;
        }

      if (ag_verify(tool, &args, obs, p))
        {
          strncpy(reply, p, reply_size - 1);
          reply[reply_size - 1] = '\0';
          reply_trim(reply);
          return (reply[0] != '\0') ? 0 : -1;
        }
    }

  /* 校验失败/无 <bot>: 模板兜底 */
  if (ag_template(tool, &args, obs, reply, reply_size) == 0 &&
      reply[0] != '\0')
    {
      return 0;
    }

  return -1;

direct_path:
  /* ── 分支 B: 直接回复 (闲聊/直接设备指令) ── */
  p = strstr(gen1_text, "<bot>");
  if (p != NULL && intent == AG_INT_NONE)
    {
      p += 5;
      while (*p == ' ')
        {
          p++;
        }

      if (strchr(p, '<') == NULL && *p != '\0')
        {
          if (ag_direct_consistent(user_text, p))
            {
              strncpy(reply, p, reply_size - 1);
              reply[reply_size - 1] = '\0';
              reply_trim(reply);
              return (reply[0] != '\0') ? 0 : -1;
            }
        }
    }

  /* 直接回复不一致/意图为直接指令: 意图+房间模板兜底 */
  if (ag_direct_template(user_text, reply, reply_size) == 0 &&
      reply[0] != '\0')
    {
      return 0;
    }

  /* 最后退回简单模式 (关键词由调用方兜底) */
  return ai_lm_reply(user_text, reply, reply_size);
}
