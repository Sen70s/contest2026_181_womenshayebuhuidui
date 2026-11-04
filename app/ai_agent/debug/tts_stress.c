/****************************************************************************
 * tts_stress.c - TTS 播报压测（NSH 取证工具）
 *
 * 目的: 复现 2026-10-24 的 TTS 播报崩溃 —— audcodec_tx 任务断言在
 *   sem_post.c:63（tx_fill -> apb_free 路径, 疑为 TX 播放驱动 apb
 *   缓冲所有权竞争）。v49 已对齐归还顺序 + 补 .freebuffer, 但该机制
 *   未复现验证（当时约 20% 概率, 无法按需触发, 见
 *   docs_ble/43_changelog.md 第八节）。只有密集的"合成->播放->归还
 *   apb"循环才有机会撞出这个竞争窗口。
 *
 * ★ 这是取证工具, 不是功能演示: 跑它可能触发上述已知断言,
 *   板子会重启 —— v48 之后固件能自恢复(看门狗复位后正常启动),
 *   那正是我们要抓的现场。
 *
 * 压测路径(为什么绕得开 libuv / 线程亲和卡死):
 *   voice_channel_speak() 不能从 NSH 命令线程直接调 —— 它在非
 *   libuv 上下文里会永久挂死(docs_ble/43_changelog.md "仍未解决",
 *   独立 bug)。本命令只调用 message_bus_push_outbound()(纯
 *   pthread mutex/condvar 实现, 与调用线程身份无关), 往出站队列投
 *   channel="voice" 的消息; 真正的 TTS->播放由 ai_agent 守护进程的
 *   outbound_dispatch_task 线程同步执行 —— 与真机上"语音回复被念
 *   出来"是同一条生产路径(packages/ai_agent/src/agent_main.c:488
 *   的 voice 分支), 2026-10-24 的崩溃就发生在这条路径上。
 *
 * 用法: tts_stress [次数] [间隔秒]     默认 10 次, 间隔 5 秒
 *   tts_stress          -> 10 轮 x 5s
 *   tts_stress 30 1     -> 30 轮 x 1s（密集播放, 才能复现 apb 竞争）
 *
 * 判读: 本命令只统计"入队成功/失败" —— 入队成功不等于播报完成。
 *   播报结果与崩溃现场看串口日志:
 *     [voice] speak: ... / [voice] speak done: total ...ms
 *     Assertion failed at /sched/semaphore/sem_post.c:63 task: audcodec_tx
 *   另: 守护进程对连续播报失败有 5s voice cooldown, 冷却期内的消息
 *   会被丢弃(串口可见 "voice cooldown active"), 属预期行为。
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ai_agent 守护进程的消息总线(同一 flat 镜像内直接链接):
 *   packages/ai_agent/src/core/message_bus.h  —— 出站队列接口
 *   其中的 agent_compat.h 提供 OK/ERROR 约定 */
#include "core/message_bus.h"

#define TTS_STRESS_TAG              "[tts_stress]"

#define TTS_STRESS_DEFAULT_ROUNDS   10    /* 默认轮数 */
#define TTS_STRESS_DEFAULT_INTERVAL 5     /* 默认间隔(秒) */
#define TTS_STRESS_MAX_ROUNDS       1000  /* 轮数上限, 防误输入拖垮板子 */
#define TTS_STRESS_MAX_INTERVAL     3600  /* 间隔上限(秒) */

/* 单调时钟毫秒(只做耗时统计, 不依赖墙钟) */
static long tts_stress_now_ms(void)
{
  struct timespec ts;

  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (long)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

/* 严格的十进制整数解析: 拒绝空串/垃圾尾巴/溢出, 避免把误输入当 0 */
static int tts_stress_parse_long(const char *s, long *out)
{
  char *end = NULL;
  long v;

  if (s == NULL || *s == '\0')
    {
      return -1;
    }

  errno = 0;
  v = strtol(s, &end, 10);
  if (errno != 0 || end == s || *end != '\0')
    {
      return -1;
    }

  *out = v;
  return 0;
}

int main(int argc, FAR char *argv[])
{
  long rounds = TTS_STRESS_DEFAULT_ROUNDS;
  long interval = TTS_STRESS_DEFAULT_INTERVAL;
  long queued = 0;
  long failed = 0;
  long total_ms;
  long max_round_ms = 0;
  long sum_round_ms = 0;
  long t0;
  int i;

  /* ── 参数: tts_stress [次数] [间隔秒], 非法输入直接报用法 ── */
  if (argc > 3)
    {
      printf("用法: tts_stress [次数] [间隔秒]  (默认 %d %d)\n",
             TTS_STRESS_DEFAULT_ROUNDS, TTS_STRESS_DEFAULT_INTERVAL);
      return 1;
    }

  if (argc >= 2 &&
      (tts_stress_parse_long(argv[1], &rounds) != 0 ||
       rounds < 1 || rounds > TTS_STRESS_MAX_ROUNDS))
    {
      printf("%s 非法次数 '%s' (允许 1..%d)\n", TTS_STRESS_TAG,
             argv[1], TTS_STRESS_MAX_ROUNDS);
      return 1;
    }

  if (argc == 3 &&
      (tts_stress_parse_long(argv[2], &interval) != 0 ||
       interval < 0 || interval > TTS_STRESS_MAX_INTERVAL))
    {
      printf("%s 非法间隔 '%s' (允许 0..%d 秒)\n", TTS_STRESS_TAG,
             argv[2], TTS_STRESS_MAX_INTERVAL);
      return 1;
    }

  printf("%s 开始: %ld 轮, 间隔 %lds —— 每轮向出站队列投一条 "
         "channel=voice 消息, 由 ai_agent 守护进程线程真实播报\n",
         TTS_STRESS_TAG, rounds, interval);
  printf("%s 取证目标: audcodec_tx sem_post.c:63 断言(apb 所有权, "
         "v49 后未复现); 播报结果看串口 [voice] speak 日志\n",
         TTS_STRESS_TAG);
  fflush(stdout);

  t0 = tts_stress_now_ms();

  for (i = 1; i <= (int)rounds; i++)
    {
      long rt0 = tts_stress_now_ms();
      long r_ms;
      char text[96];
      agent_msg_t msg;
      int push_failed = 0;

      printf("%s round %d/%ld begin\n", TTS_STRESS_TAG, i, rounds);
      fflush(stdout);   /* 万一触发断言重启, 保证串口留有本轮现场 */

      /* 每轮一句带轮号的短中文, 方便与串口 [voice] speak 日志对齐 */
      snprintf(text, sizeof(text), "这是第%d轮压力测试播报。", i);

      memset(&msg, 0, sizeof(msg));
      strncpy(msg.channel, "voice", sizeof(msg.channel) - 1);
      strncpy(msg.chat_id, "tts_stress", sizeof(msg.chat_id) - 1);
      msg.content = strdup(text);      /* 入队成功后总线接管所有权 */
      msg.kind = AGENT_MSG_KIND_FINAL; /* 与真实语音回复同类的最终消息 */

      if (msg.content == NULL)
        {
          printf("%s round %d/%ld end (strdup 失败, 内存不足)\n",
                 TTS_STRESS_TAG, i, rounds);
          failed++;
        }
      else if (message_bus_push_outbound(&msg) != OK)
        {
          /* 失败继续下一轮, 绝不 abort: 压测工具要的是统计不是退出。
           * 入队失败时 content 所有权仍在调用方, 必须自己释放。 */
          free(msg.content);
          push_failed = 1;
          failed++;
        }
      else
        {
          queued++;
        }

      /* 间隔放在入队之后: 模拟"每隔 interval 秒来一条新回复"的到达率。
       * 最后一轮不再等, 尽快出统计。 */
      if (i < (int)rounds && interval > 0)
        {
          sleep((unsigned int)interval);
        }

      r_ms = tts_stress_now_ms() - rt0;
      sum_round_ms += r_ms;
      if (r_ms > max_round_ms)
        {
          max_round_ms = r_ms;
        }

      printf("%s round %d/%ld end (%s, %ldms)\n", TTS_STRESS_TAG, i,
             rounds, push_failed ? "入队失败" : "已入队", r_ms);
      fflush(stdout);
    }

  total_ms = tts_stress_now_ms() - t0;

  printf("%s 完成: %ld 轮 | 入队成功 %ld | 失败 %ld | 总耗时 %ldms "
         "(平均 %ldms/轮, 最长 %ldms)\n",
         TTS_STRESS_TAG, rounds, queued, failed, total_ms,
         rounds > 0 ? sum_round_ms / rounds : 0, max_round_ms);
  printf("%s 注意: 入队成功不等于播报完成; 播报/崩溃证据在串口日志 "
         "([voice] speak / Assertion failed ... audcodec_tx)\n",
         TTS_STRESS_TAG);
  printf("%s 若触发已知断言板子会重启, v48 起可自恢复 —— 那就是取证成功\n",
         TTS_STRESS_TAG);

  return failed > 0 ? 1 : 0;
}
