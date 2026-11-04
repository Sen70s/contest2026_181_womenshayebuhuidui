/****************************************************************************
 * sf32lb52_audcodec.c - SF32LB52 内置音频编解码器 NuttX Audio Lower-Half
 *
 * 麦克风采集 (MEMS MIC -> audcodec ADC CH0, DMA 循环采集):
 *   - 默认 16kHz / 16bit / 单声道 (PCM S16_LE)
 *   - DMA: DMAC1_CH4, 请求 39 (AUDCODEC_ADC0_DMA_REQUEST)
 *   - ISR: HAL_DMA_IRQHandler -> HAL_AUDCODEC_Rx( Half )CpltCallback -> 信号量
 *   - 工作线程: 半缓冲就绪 -> 填充 pending apb -> upper 回调 DEQUEUE
 *
 * 参考: SiFli SDK rtos/rtthread/bsp/sifli/drivers/drv_audcodec_m.c
 *       (openvela Audio Driver Guide: audio_lowerhalf_s + audio_register)
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <debug.h>
#include <errno.h>
#include <string.h>
#include <time.h>
#include <fcntl.h>
#include <syslog.h>

#include <nuttx/audio/audio.h>
#include <nuttx/fs/fs.h>
#include <nuttx/arch.h>           /* up_enable_irq / up_disable_irq */
#include <nuttx/irq.h>
#include <nuttx/spinlock.h>       /* enter_critical_section */
#include <nuttx/cache.h>          /* up_invalidate_dcache */
#include <nuttx/kmalloc.h>
#include <nuttx/mutex.h>
#include <nuttx/semaphore.h>
#include <nuttx/kthread.h>
#include <nuttx/queue.h>

/* SiFli HAL (需与 arch 构建一致的宏与头文件路径, 见 CMakeLists) */
#define SOC_BF0_HCPU
#define SF32LB52X
#include "bf0_hal.h"
#include "dma_config.h"

#include "sf32lb52_audcodec.h"

/* HAL_AUDCODEC_Config_ADCPath_Volume() 在 HAL 里确实存在
 * (vendor/sifli/chips/drivers/hal/bf0_hal_audcodec_m.c:1023),
 * 但 drivers/Include/ 下没有任何头文件声明它 —— 不补原型就是隐式声明
 * (按 int f() + 默认实参提升处理, 属 UB, 见 docs_ble/25.3)。
 * 放在 .c 而不是 .h: 这个头文件也被 audio_setup_main.c 在不引入 HAL
 * 头文件的情况下 include, 把 HAL 类型写进 .h 会让它编不过。 */
extern HAL_StatusTypeDef HAL_AUDCODEC_Config_ADCPath_Volume(
    AUDCODEC_HandleTypeDef *hacodec, int channel, int volume);

/****************************************************************************
 * Pre-processor Definitions
 ****************************************************************************/

/* 采集设备名: 出货镜像用 pcm0c (与 ai_agent 的 AGENT_AUDIO_CAPTURE_DEV 对齐),
 * 音频验证镜像用 audio0 (本仓 mic 工具与 audio_rec_test 的历史默认值)。 */
#ifndef CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME
#  define SF32LB52_AUDIO_DEVNAME    "audio0"
#else
#  define SF32LB52_AUDIO_DEVNAME    CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME
#endif

/* DMA 循环缓冲: 半缓冲 = 2048 B = 64ms @ 16kHz/16bit/mono */
#define SF32LB52_RX_BUF_SIZE        4096
#define SF32LB52_RX_HALF            (SF32LB52_RX_BUF_SIZE / 2)
#define SF32LB52_AUDIO_BUF_SIZE     4096   /* apb 大小 (与 mic_capture 一致) */
#define SF32LB52_AUDIO_NUM_BUFS     8      /* apb 数量 (与 mic_capture 一致) */

#define SF32LB52_AUDIO_DEFAULT_RATE 16000
#define SF32LB52_AUDIO_DEFAULT_CHS  1
#define SF32LB52_AUDIO_DEFAULT_BITS 16

/* 采集工作线程 */
#define SF32LB52_AUDIO_WORKER_PRIO  120
#define SF32LB52_AUDIO_WORKER_STACK 2048

/* MICBIAS + MEMS 麦克风稳定等待 (us)。真机实测约需 0.5s 才出真实采样,
 * 期间 ADC 输出为常数 (0x0000 / 0xFFFF)。取 600ms 留余量。 */
#define SF32LB52_AUDIO_MIC_SETTLE_US  (600 * 1000)

/* 半缓冲就绪位 (ISR 置位, worker 取走并清位) */
#define SF32LB52_RX_FIRST_HALF      0
#define SF32LB52_RX_SECOND_HALF     1

/****************************************************************************
 * Private Types
 ****************************************************************************/

struct sf32lb52_audio_s
{
  struct audio_lowerhalf_s dev;         /* 必须是第一个成员 */

  AUDCODEC_HandleTypeDef codec;         /* audcodec HAL 句柄 */
  DMA_HandleTypeDef dma_rx;             /* ADC CH0 DMA (DMAC1_CH4, REQ 39) */

  /* DMA 直写本缓冲, CPU 读取前必须 invalidate。起点须按 cache line 对齐:
   * armv8-m 的 invalidate 对非对齐起点向下取整到行首、对非对齐终点向上取整
   * 到行尾 (nuttx/arch/arm/src/armv8-m/arm_cache.c); 边界那半个行走 DCCIMVAC
   * (clean+invalidate), 会把 CPU 侧遗留的陈旧副本 (register 时 memset 的 0)
   * 写回 DMA 刚覆盖的采样, 也会越界碰到 rx_ready / dbg_* / recording 等字段。
   * 这里取 64: 同时覆盖 32/64 两种行大小 (Cortex-M33 的行大小由 CCSIDR 决定,
   * 不能沿用 Cortex-M7 的 32 常量), register() 里再用
   * up_get_dcache_linesize() 断言。 */
  uint8_t rx_buf[SF32LB52_RX_BUF_SIZE] __attribute__((aligned(64)));
  volatile uint32_t rx_ready;           /* 就绪半缓冲位掩码: bit0=前半, bit1=后半 */

  uint16_t samprate;
  uint8_t  nchannels;
  uint8_t  bpsamp;
  volatile bool recording;              /* 交付闸门: worker 只在它为真时填/交缓冲 */
  volatile bool hw_on;                  /* HAL/模拟前端已开 —— hw_stop() 的幂等
                                           判据。不能用 recording 代替: start
                                           中途失败时硬件已开而 recording 还没
                                           置上 (审查 DR-005/DR-B006) */

  /* shutdown 是在 core 关中断的自旋锁区内被调用的 (audio.c:241-277): 既不能
   * 等 worker, 也不能再回调上层 —— 回调要写 upper->status, 而 core 在
   * shutdown 返回后立刻把它释放 (audio.c:273)。所以 shutdown 只做三件立即
   * 能做完的事: 停硬件 / 收回交付权 / 作废 drain 请求; 队列里属于上一会话的
   * apb 留给下一次 START (线程上下文, 允许持锁/阻塞) 清理 —— 关中断下既不能
   * apb_free (要取 apb->lock) 也不能回调 (审查 DR-001/DR-003)。这个标志就是
   * 那把交接棒。 */
  volatile bool orphan;

  sem_t rx_sem;                         /* ISR -> 工作线程 */
  pid_t worker;                         /* 采集工作线程 */
  volatile bool worker_stop;            /* register 失败时请 worker 自行退出 */
  sem_t worker_done;                    /* worker 确认退出 (有序停机) */

  /* 诊断计数器 (采集无数据排查, 见开发记录 13) */
  volatile uint32_t dbg_irq;            /* DMA ISR 进入次数 */
  volatile uint32_t dbg_cplt;           /* 半/全传输回调次数 */
  volatile uint32_t dbg_wake;           /* 工作线程唤醒次数 */
  volatile uint32_t dbg_nobuf;          /* 无 pending apb 可填而丢弃的半缓冲数 */

  volatile int skip_events;             /* 启动后丢弃的前 N 个半缓冲事件
                                            (ADC 使能瞬态满幅毛刺) */

  mutex_t pendlock;
  dq_queue_t pendq;                     /* 等待填充的 apb */
  struct ap_buffer_s *aux;              /* 当前填充目标 (受 pendlock 保护) */

  /* worker 此刻正拿着裸指针的那只 apb (受 pendlock 保护)。只在一轮交付的
   * "取用 -> 归还" 之间非空, 是"驱动那份引用由谁归还"的唯一判据:
   *   inflight == apb              -> worker 拿着它, 引用只能由 worker 收尾时归还;
   *   aux == apb 且 inflight != apb -> 只是下一个填充目标, worker 还没碰过,
   *                                    引用由摘除者归还。
   * 旧版把 aux 当成"worker 正在填充"的同义词: enqueuebuffer 直接放进 aux 的
   * 那只 (worker 还没碰) 一旦被 freebuffer 摘走就再没人归还引用 (审查
   * DR-B002), 而 shutdown 里的 discard 又会与 worker 的归还叠加成提前释放
   * (审查 DR-001/DR-B003)。 */
  struct ap_buffer_s *inflight;

  /* drain 请求/应答按"代次"配对: STOP 每次递增 drain_seq 并唤醒 worker,
   * worker 清空 pending 后回报 drain_done_seq = 它处理的那个代次。
   * 只有 drain_done_seq == 本次 seq 才表示"确实清空了" —— 信号量计数
   * 不可作为判据 (陈旧 post 会让等待立即满足, 见 DR-F001)。 */
  volatile uint32_t drain_seq;          /* STOP 递增: drain 请求代次 */
  volatile uint32_t drain_done_seq;     /* worker 已完成的 drain 代次 */
  sem_t drain_done;                     /* worker 完成 drain 后 post */
};

/* sf32lb52_audio_unlink_locked() 的结果: 被摘除的 apb 当时在哪里 */
enum sf32lb52_apb_loc_e
{
  SF32LB52_APB_NOT_MEMBER = 0,          /* 不属于本驱动的任何队列 */
  SF32LB52_APB_IN_AUX,                  /* 躺在 aux 里, worker 没拿着 */
  SF32LB52_APB_IN_WORK,                 /* worker 正拿着裸指针 (inflight) */
  SF32LB52_APB_IN_PENDQ                 /* 在等待队列里, 已被摘出 */
};

/****************************************************************************
 * Private Function Prototypes
 ****************************************************************************/

static int sf32lb52_audio_getcaps(FAR struct audio_lowerhalf_s *dev,
                                  int type, FAR struct audio_caps_s *caps);
static int sf32lb52_audio_configure(FAR struct audio_lowerhalf_s *dev,
                                    FAR const struct audio_caps_s *caps);
static int sf32lb52_audio_start(FAR struct audio_lowerhalf_s *dev);
static int sf32lb52_audio_stop(FAR struct audio_lowerhalf_s *dev);
static int sf32lb52_audio_enqueuebuffer(FAR struct audio_lowerhalf_s *dev,
                                        FAR struct ap_buffer_s *apb);
static int sf32lb52_audio_cancelbuffer(FAR struct audio_lowerhalf_s *dev,
                                       FAR struct ap_buffer_s *apb);
static int sf32lb52_audio_freebuffer(FAR struct audio_lowerhalf_s *dev,
                                     FAR struct audio_buf_desc_s *bufdesc);
static int sf32lb52_audio_reserve(FAR struct audio_lowerhalf_s *dev);
static int sf32lb52_audio_release(FAR struct audio_lowerhalf_s *dev);
static int sf32lb52_audio_shutdown(FAR struct audio_lowerhalf_s *dev);
static int sf32lb52_audio_ioctl(FAR struct audio_lowerhalf_s *dev,
                                int cmd, unsigned long arg);

/* start_capture() 的失败路径要用它做逆序回滚, 而它定义在 start_capture 之后 */
static void sf32lb52_audio_hw_stop(FAR struct sf32lb52_audio_s *priv);

/****************************************************************************
 * Private Data
 ****************************************************************************/

static const struct audio_ops_s g_sf32lb52_audio_ops =
{
  .getcaps       = sf32lb52_audio_getcaps,
  .configure     = sf32lb52_audio_configure,
  .shutdown      = sf32lb52_audio_shutdown,
  .start         = sf32lb52_audio_start,
  .stop          = sf32lb52_audio_stop,
  .enqueuebuffer = sf32lb52_audio_enqueuebuffer,
  .cancelbuffer  = sf32lb52_audio_cancelbuffer,
  .freebuffer    = sf32lb52_audio_freebuffer,
  .reserve       = sf32lb52_audio_reserve,
  .release       = sf32lb52_audio_release,
  .ioctl         = sf32lb52_audio_ioctl,
};

static struct sf32lb52_audio_s g_audio;

/****************************************************************************
 * Private Functions
 ****************************************************************************/

/* ── HAL 回调 (由 HAL DMA ISR 调用, 弱符号由驱动接管) ── */

void HAL_AUDCODEC_RxCpltCallback(AUDCODEC_HandleTypeDef *hacodec, int cid)
{
  struct sf32lb52_audio_s *priv = &g_audio;

  (void)hacodec;
  (void)cid;

  /* 置"后半就绪"位 (只置位不清位: 位掩码由 worker 在临界区内取走),
   * 与 worker 的取位构成一次 ISR/线程共享的读改写 */
  priv->rx_ready |= (1u << SF32LB52_RX_SECOND_HALF);
  priv->dbg_cplt++;
  nxsem_post(&priv->rx_sem);
}

void HAL_AUDCODEC_RxHalfCpltCallback(AUDCODEC_HandleTypeDef *hacodec, int cid)
{
  struct sf32lb52_audio_s *priv = &g_audio;

  (void)hacodec;
  (void)cid;
  priv->rx_ready |= (1u << SF32LB52_RX_FIRST_HALF);
  priv->dbg_cplt++;
  nxsem_post(&priv->rx_sem);
}

/* ── DMA 中断 ── */

static int sf32lb52_audio_isr(int irq, FAR void *context, FAR void *arg)
{
  struct sf32lb52_audio_s *priv = (struct sf32lb52_audio_s *)arg;

  priv->dbg_irq++;
  HAL_DMA_IRQHandler(&priv->dma_rx);
  return OK;
}

/* ── 队列摘除 / 会话残留清理 ── */

/* 把 apb 从 aux/pendq 里摘出来 (调用者必须已持有 pendlock)。
 *
 * 返回"位置"而不是"是否摘到", 因为调用方要据此决定"驱动那份引用"由谁归还:
 *   IN_WORK   —— worker 正拿着裸指针 (inflight): 队列一个都不动。摘队列并不能
 *                阻止它继续 memcpy (它拿的是裸指针), 反而让这份引用失去唯一
 *                的归还责任人 -> 永久泄漏; 引用由 worker 收尾时归还。
 *   IN_AUX    —— 只是"下一个填充目标", worker 没碰过: 摘除者归还驱动引用。
 *   IN_PENDQ  —— 摘除与 worker 的 dq_remfirst 在 pendlock 内互斥, 从此 worker
 *                再拿不到它: 摘除者归还驱动引用。
 *   NOT_MEMBER—— 不属于本驱动; 也可能已经交付 (引用已还), 或正处在 deliver
 *                的"已声明交付权、还没回调 upper()"窗口里 (引用由 worker 还)。 */

static int sf32lb52_audio_unlink_locked(FAR struct sf32lb52_audio_s *priv,
                                        FAR struct ap_buffer_s *apb)
{
  FAR struct dq_entry_s *entry;

  if (priv->inflight == apb)
    {
      return SF32LB52_APB_IN_WORK;
    }

  if (priv->aux == apb)
    {
      priv->aux = NULL;
      return SF32LB52_APB_IN_AUX;
    }

  /* dq_rem 对未入队的节点会静默清空整条队列 (include/nuttx/queue.h:229-254),
   * 故先遍历确认成员关系再摘 (DR-B004) */

  for (entry = priv->pendq.head; entry != NULL; entry = entry->flink)
    {
      if (entry == &apb->dq_entry)
        {
          dq_rem(entry, &priv->pendq);
          return SF32LB52_APB_IN_PENDQ;
        }
    }

  return SF32LB52_APB_NOT_MEMBER;
}

/* 清理上一会话残留的 apb —— 只在 START (线程上下文) 调用, 只归还驱动引用,
 * 绝不回调上层: shutdown 返回后 core 已经释放 upper->status (audio.c:273),
 * 再发 AUDIO_CALLBACK_DEQUEUE 就是空指针解引用。
 * 返回 true 表示队列确实清空了 (可以清 orphan 标志)。
 * 若此刻 worker 正拿着某只 apb (inflight, 只有几微秒的窗口), 本次不动队列:
 * 硬抢会丢掉引用的归还责任人, 留到下一次 START 再清 (审查 DR-B001)。 */

static bool sf32lb52_audio_orphan_cleanup(FAR struct sf32lb52_audio_s *priv)
{
  dq_queue_t tmp;
  FAR struct ap_buffer_s *apb;
  int ret;

  dq_init(&tmp);

  ret = nxmutex_lock(&priv->pendlock);
  if (ret < 0)
    {
      return false;
    }

  if (priv->inflight != NULL)
    {
      nxmutex_unlock(&priv->pendlock);
      return false;                     /* worker 正在用, 下一轮再清 */
    }

  apb = priv->aux;
  priv->aux = NULL;
  if (apb != NULL)
    {
      dq_addlast(&apb->dq_entry, &tmp);
    }

  while (!dq_empty(&priv->pendq))
    {
      apb = (FAR struct ap_buffer_s *)dq_remfirst(&priv->pendq);
      dq_addlast(&apb->dq_entry, &tmp);
    }

  nxmutex_unlock(&priv->pendlock);

  /* 这些 apb 的 crefs 必为 2 (上层持有 + 驱动持有): 上层若已 FREEBUFFER,
   * 它早就被摘出队列了, 不会出现在这里。故这次 apb_free 只把驱动那份还掉,
   * 对象仍归上层, 不会提前释放。 */

  while (!dq_empty(&tmp))
    {
      apb = (FAR struct ap_buffer_s *)dq_remfirst(&tmp);
      apb_free(apb);
    }

  return true;
}

/* 交还 inflight 标记 (worker 侧收尾): 拿不到锁就不动 —— 标记仍指向本线程
 * 刚取的那只 apb, 下一轮的取用路径会重新认领它, 不会丢。 */

static void sf32lb52_audio_clear_inflight(FAR struct sf32lb52_audio_s *priv,
                                          FAR struct ap_buffer_s *apb)
{
  if (nxmutex_lock(&priv->pendlock) == OK)
    {
      if (priv->inflight == apb)
        {
          priv->inflight = NULL;
        }

      nxmutex_unlock(&priv->pendlock);
    }
}

/* ── 采集工作线程: 半缓冲就绪 -> 填充 apb ── */

static void sf32lb52_audio_deliver(FAR struct sf32lb52_audio_s *priv,
                                   FAR const uint8_t *data, size_t len)
{
  while (len > 0)
    {
      FAR struct ap_buffer_s *apb;
      size_t space;
      size_t chunk;
      bool deliver;
      int ret;

      /* 持锁取"当前填充目标", 并立刻用 inflight 声明"worker 手里有裸指针"。
       * priv->aux / priv->inflight 是 worker 与 enqueue/cancel/free/shutdown
       * 共享的字段, 每一次读写都必须在 pendlock 内 (DR-F003: 早前版本在锁外
       * 取用, 会与 enqueuebuffer 抢同一只 apb, 缓冲被互相覆盖而丢失) */

      ret = nxmutex_lock(&priv->pendlock);
      if (ret < 0)
        {
          priv->dbg_nobuf++;            /* 未持锁就绝不碰 aux/pendq (DR-J04) */
          return;
        }

      apb = priv->inflight;
      if (apb == NULL)
        {
          apb = priv->aux;
          if (apb == NULL)
            {
              apb = (FAR struct ap_buffer_s *)dq_remfirst(&priv->pendq);
              priv->aux = apb;
            }

          if (apb != NULL)
            {
              priv->inflight = apb;
            }
        }

      nxmutex_unlock(&priv->pendlock);

      if (apb == NULL)
        {
          priv->dbg_nobuf++;
          return;                       /* 无缓冲可填: 丢弃本半缓冲 */
        }

      /* 锁外拷贝: apb 由 worker 独占 (inflight 已声明), 而 upper 会阻塞在
       * mq_send 上 (真机 mq_maxmsg=16), 持 pendlock 回调会让 enqueue 等线程
       * 永久阻塞。space 用 curbyte >= nmaxbytes 判定, 避免无符号下溢 (DR-B010) */

      space = (apb->curbyte < apb->nmaxbytes)
                ? (size_t)(apb->nmaxbytes - apb->curbyte) : 0;
      if (space == 0)
        {
          /* aux 里是一只已满的 apb (只可能来自下面那次 pendlock 加锁失败,
           * 来不及清 aux 的极端路径, 或客户端给了 nmaxbytes==0 的缓冲)。
           * 就此收工, 否则 chunk=0 会让本循环空转 (data/len 都不前进);
           * 同时交还 inflight, 否则这一只会把 inflight 粘住, 之后每个半缓冲
           * 都在这里静默丢弃 (审查 N3)。apb 留在 aux 里, freebuffer/drain/
           * 残留清理都能正常回收它。 */

          priv->dbg_nobuf++;
          sf32lb52_audio_clear_inflight(priv, apb);
          return;
        }

      chunk = (len < space) ? len : space;

      memcpy(apb->samp + apb->curbyte, data, chunk);
      apb->curbyte += chunk;
      data += chunk;
      len -= chunk;

      if (apb->curbyte >= apb->nmaxbytes)
        {
          /* 一个 apb 填满: 持锁"声明交付权" —— 只有仍握着 inflight 的人可以
           * 回调上层, 声明与清除在同一个临界区内完成, 所以每只 apb 的
           * upper(DEQUEUE) 恰好一次; shutdown 收走交付权之后就不会再冒出
           * 回调 (审查 DR-002/DR-B001 的修法)。 */

          apb->nbytes = apb->curbyte;

          ret = nxmutex_lock(&priv->pendlock);
          if (ret < 0)
            {
              /* 未持锁就声明不了: 不回调也不归还引用 (对象仍由驱动引用
               * 保活), 留给 STOP 的 drain 收尾 (DR-J04) */

              priv->dbg_nobuf++;
              sf32lb52_audio_clear_inflight(priv, apb);
              return;
            }

          deliver = (priv->inflight == apb);
          if (deliver)
            {
              priv->inflight = NULL;

              if (priv->aux == apb)
                {
                  priv->aux = NULL;
                }
            }

          /* deliver == false: 交付权已被 shutdown 收走 (它同时把 inflight 清了)。
           * 这时绝不能顺手清 aux —— 那会让这只 apb 从 inflight/aux/pendq 三处
           * 一起消失, 驱动引用既没人还也没人认领 (审查 N2: 永久泄漏 + 客户端
           * 少收一只缓冲)。留在 aux 里, 由 reserve/configure 的残留清理归还。 */

          nxmutex_unlock(&priv->pendlock);

          if (deliver)
            {
              priv->dev.upper(priv->dev.priv, AUDIO_CALLBACK_DEQUEUE, apb, OK);

              /* 归还驱动引用 (与 enqueuebuffer 的 apb_reference 配对)。
               * 必须在 upper() 之后: 该回调要写 apb->flags (audio.c:1529),
               * 而上层可能已并发 AUDIOIOC_FREEBUFFER —— 正是这份引用让对象
               * 活到此刻 (v3 的核心修复)。
               * 交付权被 shutdown 收走时这里不再归还: 引用归还责任随交付权
               * 一起转移, 由下一次 START 的 orphan 清理收尾 —— 两边都还就是
               * 同一份引用还两次 (审查 DR-001/DR-B003)。 */

              apb_free(apb);
            }
        }
      else
        {
          /* 没填满: 交还 inflight (下一轮重新取, aux 仍是同一只)。让
           * "in-flight" 只覆盖真正拿着裸指针的那几微秒, aux 与它分离之后
           * freebuffer/shutdown 才能安全判断引用归谁 (审查 DR-B002)。 */

          sf32lb52_audio_clear_inflight(priv, apb);
        }
    }
}

static void sf32lb52_audio_worker_impl(FAR struct sf32lb52_audio_s *priv,
                                       int half)
{
  FAR const uint8_t *data;

  data = (half == SF32LB52_RX_FIRST_HALF)
           ? priv->rx_buf : priv->rx_buf + SF32LB52_RX_HALF;

  /* DMA 绕过 D-cache 直写 RAM: 读之前必须 invalidate, 否则可能读到 memset
   * 遗留的陈旧全零行而非本次采样 (CONFIG_ARMV8M_DCACHE=y 时)。 */
  up_invalidate_dcache((uintptr_t)data,
                       (uintptr_t)data + SF32LB52_RX_HALF);

  sf32lb52_audio_deliver(priv, data, SF32LB52_RX_HALF);
}

/* 清空 pending 队列并归还缓冲 — 只在 audcodec 工作线程上下文执行
 * (与 deliver 同线程, 不会并发进入 audio 核心)。
 * 返回 <0 表示没能清空 (pendlock 加锁失败): 调用方不得把它当成 drain 完成。 */
static int sf32lb52_audio_drain_pending(FAR struct sf32lb52_audio_s *priv)
{
  dq_queue_t tmp;
  FAR struct ap_buffer_s *apb;
  int ret;

  dq_init(&tmp);

  ret = nxmutex_lock(&priv->pendlock);
  if (ret < 0)
    {
      return ret;                       /* 未持锁: 队列一个都没动 (DR-J04) */
    }

  /* 正在填充的那只按已填长度归还 (它不在 pendq 里, 早前版本直接丢弃)。
   * drain 跑在 worker 自己线程里, inflight 若非空就是本线程刚留下的标记,
   * 一并交回 (清除它 = 放弃稍后回调上层的权利, 这里本来就要归还缓冲)。 */

  apb = priv->aux;
  priv->aux = NULL;
  priv->inflight = NULL;
  if (apb != NULL)
    {
      apb->nbytes = apb->curbyte;
      dq_addlast(&apb->dq_entry, &tmp);
    }

  while (!dq_empty(&priv->pendq))
    {
      apb = (FAR struct ap_buffer_s *)dq_remfirst(&priv->pendq);
      apb->nbytes = apb->curbyte;
      dq_addlast(&apb->dq_entry, &tmp);
    }

  nxmutex_unlock(&priv->pendlock);

  /* 锁外回调: upper 可能阻塞在 mq_send, 持 pendlock 会让其它线程永久阻塞 */

  while (!dq_empty(&tmp))
    {
      apb = (FAR struct ap_buffer_s *)dq_remfirst(&tmp);
      priv->dev.upper(priv->dev.priv, AUDIO_CALLBACK_DEQUEUE, apb, OK);

      /* 归还驱动引用 (与 enqueuebuffer 的 apb_reference 配对): 此刻起 worker
       * 不再碰这只 apb, 且 upper() 已经写完 apb->flags。若上层在这期间
       * FREEBUFFER, 这份引用同样保证对象活到 upper() 返回之后。 */

      apb_free(apb);
    }

  return OK;
}

static int sf32lb52_audio_worker(int argc, FAR char *argv[])
{
  FAR struct sf32lb52_audio_s *priv = &g_audio;

  (void)argc;
  (void)argv;

  while (1)
    {
      irqstate_t flags;
      int half;

      /* uninterruptible: CONFIG_CANCELLATION_POINTS=y 时 nxsem_wait 可因
       * 信号返回负值, 会被当成"有效唤醒" (DR-F004) */
      nxsem_wait_uninterruptible(&priv->rx_sem);
      priv->dbg_wake++;

      /* register 失败时的有序停机: 主动退出, 让 errout 能安全销毁信号量
       * (只置标志 + post, 见 register 的 errout_worker) */

      if (priv->worker_stop)
        {
          nxsem_post(&priv->worker_done);
          return 0;
        }

      /* 停止采集的 drain 请求 (代次绑定): 处理完把"已完成的代次"回报给
       * STOP, 由 STOP 端比对 drain_done_seq == 本次 seq 才算数 (DR-F001) */

      if (priv->drain_seq != priv->drain_done_seq)
        {
          uint32_t seq = priv->drain_seq;

          /* 只有真的清空了才回报完成代次; 否则宁可让 STOP 走超时分支,
           * 也不能谎报"已清空" (DR-J04) */

          if (sf32lb52_audio_drain_pending(priv) == OK)
            {
              priv->drain_done_seq = seq;
              nxsem_post(&priv->drain_done);
            }

          continue;
        }

      /* 取一个就绪的半缓冲位并清位。ISR 会并发置位, 取位/清位必须在
       * 临界区内完成, 否则读改写会吞掉刚置上的那一位 (DR-F011) */

      flags = enter_critical_section();
      if ((priv->rx_ready & (1u << SF32LB52_RX_FIRST_HALF)) != 0)
        {
          priv->rx_ready &= ~(1u << SF32LB52_RX_FIRST_HALF);
          half = SF32LB52_RX_FIRST_HALF;
        }
      else if ((priv->rx_ready & (1u << SF32LB52_RX_SECOND_HALF)) != 0)
        {
          priv->rx_ready &= ~(1u << SF32LB52_RX_SECOND_HALF);
          half = SF32LB52_RX_SECOND_HALF;
        }
      else
        {
          half = -1;                    /* 陈旧唤醒: 没有新的半缓冲 */
        }

      leave_critical_section(flags);

      if (half < 0)
        {
          continue;
        }

      /* ADC 使能瞬态: 通路建立期间输出满幅毛刺 (真机实测首个半缓冲
       * max=32750, 假能量触发 VAD), 丢弃前几个事件 */
      if (priv->skip_events > 0)
        {
          priv->skip_events--;
          continue;
        }

      if (priv->recording)
        {
          sf32lb52_audio_worker_impl(priv, half);
        }
    }

  return 0;
}

/* ── 采集启动/停止 (HAL 序列参考 SiFli SDK drv_audcodec_m.c) ── */

static int sf32lb52_audio_start_capture(FAR struct sf32lb52_audio_s *priv)
{
  HAL_StatusTypeDef res;

  if (priv->recording)
    {
      return OK;
    }

  /* v109：v108 的"无条件强制断电重上电"在真机上导致启动期采集挂死
   * （每次开机 P1 麦克风探针 → start_capture → 约 20s 后看门狗复位，进入
   * 复位循环）。这里先撤回该段，保留下方 ADC_CH0_CFG 读回日志；
   * P0-3 需要基于复位原因/幂等状态重新设计，不能在冷态序列前盲调 HAL。 */

  /* 上一次 STOP 若没等到 worker 确认 (drain_seq 未回配对), 那个请求对本次
   * 会话毫无意义; 直接作废, 否则 worker 迟到醒来时会去 drain 本次刚入队的
   * 缓冲 (drain 请求与会话绑定, DR-J05)。
   * 注意: 上一会话的残留 apb 不在这里清 —— START 之前客户端已经按契约把
   * 自己的缓冲 ENQUEUE 进来了 (audio_rec_test/mic_capture 都是先 enqueue 后
   * START), 在这里清会把新会话刚挂上的缓冲一起吃掉 (审查 N1)。清理挪到
   * reserve()/configure() —— 那两个回调一定排在新会话第一次 enqueue 之前。 */

  priv->drain_done_seq = priv->drain_seq;

  /* PLL: 16kHz 1000 系列 (type=1 -> freq_type=2) */
  bf0_enable_pll(priv->samprate, 1);

  /* ★ 关键: HAL_TURN_ON_PLL() 补上 bf0_enable_pll() 不做的那部分模拟前端:
   *   - BG_CFG0 整体配置: VREF_SEL=0xc(3.3V) / MIC_VREF_SEL=4 / EN_RCFLT / EN_AMP
   *     (bf0_enable_pll 只 RMW 了 EN 与 EN_SMPL 两位, 不设参考电压)
   *   - HAL_AUCODEC_Refgen_Init(): REFGEN_CFG |= EN —— 参考电压发生器
   * 缺这两样的后果: ADC 没有参考电压 → 转换不出有效值, 输出成片常数
   * (真机实测 PCM 里是 0x0000 与 0xFFFF 两种精确常量交替)。
   * 依据: SiFli 官方文档 AUDCODEC HAL 的 RX 序列明确包含 Refgen_Init;
   *       HAL_TURN_ON_PLL 的实现在 bf0_hal_audcodec_m.c:429-485。
   * 注意: 本树内没有任何地方调用过 HAL_TURN_ON_PLL (厂商 RT-Thread 驱动
   *       不在本树), 这是之前漏掉它的原因。 */
  HAL_TURN_ON_PLL();

  /* 从这里起模拟前端已带电: 置 hw_on, 让失败路径与 hw_stop 都能把它关回去
   * (审查 DR-005/DR-B006: 旧版拿 recording 当"硬件是否开"的判据, start 中途
   * 失败时 recording 还不是真 -> 以后任何 stop/release/shutdown 都空转,
   * PLL/ADC/MICBIAS 常供电且 State 卡 BUSY, 同一处失败可无限重复) */
  priv->hw_on = true;

  /* ADC 通道配置 (16bit, MIC 音量) */
  res = HAL_AUDCODEC_Config_RChanel(&priv->codec, 0,
                                    &priv->codec.Init.adc_cfg);
  if (res != HAL_OK)
    {
      sf32lb52_audio_hw_stop(priv);
      return -EIO;
    }

  /* F1 判别实验(2026-10-13): +12dB -> +30dB(HAL 允许的最大档, 校验范围 -60~30, 见
   * bf0_hal_audcodec_m.c:1038)。目的: 把"麦克风对声音无响应"一刀切成两类 ——
   *   底噪 RMS 明显变大(约 x8) => ADC 前端是活的, 问题在增益链, 软件可解;
   *   RMS 几乎不变            => ADC 输入端悬空/麦未接通, 必须量 pin36 MIC_BIAS。
   * 回退: 把 30 改回 12 即可, 无副作用。 */
  res = HAL_AUDCODEC_Config_ADCPath_Volume(&priv->codec, 0, 30);
  if (res != HAL_OK)
    {
      auderr("ERROR: Config_ADCPath_Volume failed: %d\n", (int)res);
      sf32lb52_audio_hw_stop(priv);
      return -EIO;
    }

  /* ── 顺序修正 (2026-10-10 真机取证) ──
   * 原顺序是 Receive_DMA → Config_Analog_ADCPath → ADC_ENABLE, 即让 DMA 在
   * ADC 使能之前就跑起来。但 HAL 自己在 Receive_DMA 里把全局使能注掉了
   * ("//__HAL_AUDCODEC_ENABLE(hacodec);"), 所以那段时间 DMA 搬的是未转换的
   * ADC 寄存器空闲值 —— 真机 PCM 里成片的 0x0000 / 0xFFFF 常数即由此而来。
   * 改为"先配置并使能 ADC → 等 MICBIAS/MEMS 麦稳定 → 再启动 DMA"。 */

  /* 模拟 MIC 通路 (MICBIAS 等) */
  HAL_AUDCODEC_Config_Analog_ADCPath(priv->codec.Init.adc_cfg.adc_clk);

  /* 使能 ADC */
  __HAL_AUDCODEC_ADC_ENABLE(&priv->codec);

  /* MICBIAS + MEMS 麦稳定等待 (SDK 的 Config_Analog_ADCPath 只等 3ms,
   * 真机实测需要 ~0.5s 才出真实采样: 3 秒采集里前 0.47s 全是常数) */
  usleep(SF32LB52_AUDIO_MIC_SETTLE_US);

  /* ★ v108 P0-3：读回校验 —— 600ms settle 是开环的，必须确认增益寄存器
   * 真的写进去了。ADC_CH0_CFG 地址与反解方式与 mic_selfcheck.c:78-91 一致：
   * +30dB ⇒ rough=(30+60)/6=15 → ROUGH_VOL[11:8]=0x0F00（low16 期望 0x0F00）。 */
  {
    volatile uint32_t cfg = *(volatile uint32_t *)0x50088020UL;
    uint32_t low = cfg & 0xFFFFu;

    syslog(LOG_INFO, "[pcm_rec] ADC_CH0_CFG=0x%08x gain=0x%04x\n",
           (unsigned)cfg, (unsigned)low);

    /* v114：比较 ROUGH_VOL[11:8] 字段；旧代码拿整个低16位比 0x0F00 会每次假告警 */
    if ((low & 0x00000F00u) != 0x00000F00u)
      {
        HAL_StatusTypeDef r2 =
            HAL_AUDCODEC_Config_ADCPath_Volume(&priv->codec, 0, 30);

        cfg = *(volatile uint32_t *)0x50088020UL;
        syslog(LOG_WARNING,
               "[pcm_rec] gain readback 0x%04x != 0x0F00, retry=%d now=0x%08x\n",
               (unsigned)low, (int)r2, (unsigned)cfg);
      }
  }

  /* 丢掉上一次会话遗留的就绪位 (此时 DMA/中断都还没开) */
  priv->rx_ready = 0;

  /* DMA 启动前 invalidate rx_buf: 丢弃 register() 里 memset 在 D-cache
   * 中留下的脏零行。必须用 invalidate 而不是 clean —— clean 会把零写回,
   * 覆盖 DMA 刚写入的采样 (DR-F010) */
  up_invalidate_dcache((uintptr_t)priv->rx_buf,
                       (uintptr_t)priv->rx_buf + SF32LB52_RX_BUF_SIZE);

  /* 最后启动 DMA 循环采集: 此时 ADC 已在出真实数据 */
  res = HAL_AUDCODEC_Receive_DMA(&priv->codec, priv->rx_buf,
                                 SF32LB52_RX_BUF_SIZE,
                                 HAL_AUDCODEC_ADC_CH0);
  if (res != HAL_OK)
    {
      sf32lb52_audio_hw_stop(priv);     /* 逆序回滚: 关 ADC/模拟通路/PLL,
                                         * 并清 HAL 的 State 位 */
      return -EIO;
    }

  up_enable_irq(AUDCODEC_ADC0_DMA_IRQ + NVIC_IRQ_FIRST);

  /* DMA 刚启动时仍有少量瞬态; 稳定等待已在上游完成, 故只需丢 2 个事件 */
  priv->skip_events = 2;

  /* 只有启动序列确实成功才置 recording (worker 在 register() 里创建,
   * 永不重建: 早前版本的"存活探测 + nxtask_delete + 重建"在删不掉内核
   * 线程时会静默造出第二个 worker 抢同一 pendq/aux, 已整段删除, DR-F008) */
  priv->recording = true;
  return OK;
}

/* 停止采集的硬件部分 —— 非阻塞, 可在关中断上下文 (shutdown) 调用。
 * 只做"立即能做完"的事: 关交付闸门 + 停 DMA/中断/PLL/ADC; 队列一律不动,
 * 因为清队列必须回调上层, 而 shutdown 返回后 upper->status 已被释放。 */

static void sf32lb52_audio_hw_stop(FAR struct sf32lb52_audio_s *priv)
{
  if (!priv->hw_on)
    {
      return;                           /* 幂等: 没开过就什么都不做 */
    }

  priv->hw_on = false;
  priv->recording = false;              /* 关交付闸门 */
  up_disable_irq(AUDCODEC_ADC0_DMA_IRQ + NVIC_IRQ_FIRST);
  HAL_AUDCODEC_DMAStop(&priv->codec, HAL_AUDCODEC_ADC_CH0);

  /* 与 HAL_TURN_ON_PLL() 对称: 关掉 REFGEN/PLL (bf0_hal_audcodec_m.c:406)
   * 放在 DMA/中断都停之后, 避免关参考电压时 ADC 还在转换 */
  HAL_AUDCODEC_Close_Analog_ADCPath();
  HAL_TURN_OFF_PLL();

  /* SDK 的 DMAStop 不清 State (源码中被注释), Receive_DMA 会一直
   * HAL_BUSY -> 二次启动 EIO (真机 2026-08-16 实测), 此处补清 */
  priv->codec.State[HAL_AUDCODEC_ADC_CH0] = HAL_AUDCODEC_STATE_READY;

  HAL_AUDCODEC_Close_Analog_ADCPath();
  __HAL_AUDCODEC_ADC_DISABLE(&priv->codec);

  priv->skip_events = 0;
}

/* 停止采集 + 等 worker 确认 pending 队列已清空。线程上下文用;
 * shutdown 不能走这里: core 在关中断的自旋锁区内调用它, 阻塞等待既等不到
 * worker (关中断下 worker 无法被调度), 也让依赖定时器中断的超时永不生效
 * (DR-J13)。 */

static int sf32lb52_audio_stop_capture(FAR struct sf32lb52_audio_s *priv)
{
  uint32_t seq;
  bool drained;

  if (!priv->recording)
    {
      /* 采集没在跑: 没有要 drain 的东西; 但 start 可能失败在半路, 硬件还
       * 开着 —— hw_stop 幂等, 顺手关回去 (审查 DR-005/DR-B006) */

      sf32lb52_audio_hw_stop(priv);
      return OK;
    }

  sf32lb52_audio_hw_stop(priv);

  /* 诊断: 采集计数 + 原始字流 dump。DMA 已停, 但 CPU 侧 D-cache 里可能
   * 仍是陈旧副本, 读之前同样要 invalidate (DR-F010) */
  up_invalidate_dcache((uintptr_t)priv->rx_buf,
                       (uintptr_t)priv->rx_buf + SF32LB52_RX_BUF_SIZE);
  {
    const int16_t *s16 = (const int16_t *)priv->rx_buf;
    int big = 0;
    int mx = 0;

    for (int i = 0; i < 512; i++)
      {
        int a = s16[i] < 0 ? -s16[i] : s16[i];

        if (a > 20000)
          {
            big++;
          }

        if (a > mx)
          {
            mx = a;
          }
      }

    printf("[mic-dbg] irq=%u cplt=%u wake=%u nobuf=%u max16=%d big=%d\n",
           (unsigned)priv->dbg_irq, (unsigned)priv->dbg_cplt,
           (unsigned)priv->dbg_wake, (unsigned)priv->dbg_nobuf, mx, big);
    printf("[mic-raw]");

    for (int i = 0; i < 8; i++)
      {
        printf(" %04x", (uint16_t)s16[i]);
      }

    printf("\n");
  }

  priv->dbg_irq = priv->dbg_cplt = priv->dbg_wake = priv->dbg_nobuf = 0;

  /* 清空 pending apb: 交给 audcodec 工作线程执行 (代次请求 + 唤醒),
   * 与交付路径同上下文, 避免并发进入 audio 核心。
   * 完成判据是"worker 回报的完成代次 == 本次请求代次", 不是信号量计数:
   * 陈旧的 post 会让等待立刻返回, 所以必须在截止时间内循环等待 (DR-F001)。
   * CLOCK_MONOTONIC: 本产品有 BLE/RTC 对时, 墙钟跳变会造成假超时 (DR-F005) */

  seq = ++priv->drain_seq;
  nxsem_post(&priv->rx_sem);

  {
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    ts.tv_nsec += 200 * 1000 * 1000;
    if (ts.tv_nsec >= 1000000000L)
      {
        ts.tv_sec++;
        ts.tv_nsec -= 1000000000L;
      }

    while (priv->drain_done_seq != seq)
      {
        /* 任何非 OK 返回 (超时/错误) 都意味着"未确认", 交给下面的判据 */

        if (nxsem_clockwait_uninterruptible(&priv->drain_done,
                                            CLOCK_MONOTONIC, &ts) < 0)
          {
            break;
          }
      }
  }

  drained = (priv->drain_done_seq == seq);

  if (!drained)
    {
      /* 未确认 drain 完成: 绝不能再去动 worker 拥有的状态 —— 早前版本在
       * 这里锁外 dq_init(&pendq) + aux=NULL, 与仍存活的 worker 并发,
       * 正是真机 semaphore.h:523 断言/UAF 的根因; 也不能把"没清空"
       * 当成"已停止"返回 OK, 否则上层会在回调未完成时 FREEBUFFER。 */
      printf("[mic] drain timeout: worker did not confirm (seq=%u done=%u)\n",
             (unsigned)seq, (unsigned)priv->drain_done_seq);
      return -ETIMEDOUT;
    }

  /* 清信号量残留计数: 缓慢的 worker 未消费的 post 若不清, 下一会话
   * 会被陈旧唤醒轰炸 (wake 远大于 cplt); 就绪位已在临界区内取走,
   * 陈旧唤醒只会空转一次 (见 worker 的 half < 0 分支) */
  while (nxsem_trywait(&priv->rx_sem) >= 0)
    {
    }

  /* 通知 core "本会话确实停了": core 在调用 lower->ops->stop 前把状态置成
   * AUDIO_STATE_DRAINING, 而只有 AUDIO_CALLBACK_COMPLETE 能把它收敛回
   * AUDIO_STATE_OPEN (nuttx/audio/audio.c:1557-1561); 不发的话同一个 fd 里
   * 再 START 会返回 OK 却根本不调用驱动的 start (audio.c:633-657) —— 表现为
   * "静默不采集"; nxrecorder 的停止流程更是死等这条 AUDIO_MSG_COMPLETE
   * (apps/system/nxrecorder/nxrecorder.c:786-807, 审查 DR-B004)。
   * 此刻采集已确实停止、upper->status 仍有效、上下文是 ioctl 线程 (不再有
   * shutdown 那种关中断约束), 可以安全回调。 */

  priv->dev.upper(priv->dev.priv, AUDIO_CALLBACK_COMPLETE, NULL, OK);

  return OK;
}

/* ── audio_ops_s 实现 ── */

static int sf32lb52_audio_getcaps(FAR struct audio_lowerhalf_s *dev,
                                  int type, FAR struct audio_caps_s *caps)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;

  (void)priv;
  caps->ac_format.hw = 0;
  caps->ac_controls.w = 0;

  switch (caps->ac_type)
    {
      /* 总体能力查询 (subtype=QUERY 或具体格式) */

      case AUDIO_TYPE_QUERY:
        caps->ac_channels = 1;

        switch (caps->ac_subtype)
          {
            case AUDIO_TYPE_QUERY:
              /* 支持 PCM + 输入/输出单元 */
              caps->ac_controls.b[0] = AUDIO_TYPE_INPUT | AUDIO_TYPE_OUTPUT;
              caps->ac_format.hw = 1 << (AUDIO_FMT_PCM - 1);
              break;

            case AUDIO_FMT_PCM:
              /* PCM 子格式列表 (按 ac_format.b[0] 分页) */
              switch (caps->ac_format.b[0])
                {
                  case 0:
                    caps->ac_controls.b[0] = AUDIO_SUBFMT_PCM_S16_LE;
                    caps->ac_controls.b[1] = AUDIO_SUBFMT_END;
                    break;

                  default:
                    caps->ac_controls.b[0] = AUDIO_SUBFMT_END;
                    break;
                }

              break;

            default:
              caps->ac_controls.b[0] = AUDIO_SUBFMT_END;
              break;
          }

        break;

      /* 采集能力 */

      case AUDIO_TYPE_INPUT:
        caps->ac_channels = 1;

        switch (caps->ac_subtype)
          {
            case AUDIO_TYPE_QUERY:
              /* 支持的格式 */
              caps->ac_controls.b[0] = AUDIO_SUBFMT_PCM_S16_LE;
              caps->ac_controls.b[1] = AUDIO_SUBFMT_END;
              break;

            case AUDIO_FMT_PCM:
              /* 采样率范围: NuttX 约定采样率放 ac_controls.hw[0] (16 bit),
               * 高位补 b[3], 位深放 b[2] (对照 nxrecorder 的 CONFIGURE 填法,
               * apps/system/nxrecorder/nxrecorder.c:1189)。
               * 早前版本误写 b[0]/b[1], 既与 b[2] 的位深语义冲突, 又把
               * 16000/48000 截断成 128 (构建告警 -Woverflow), 见 docs_ble/25。 */
              caps->ac_controls.hw[0] = SF32LB52_AUDIO_DEFAULT_RATE;
              caps->ac_controls.hw[1] = 48000;
              break;

            default:
              caps->ac_controls.b[0] = AUDIO_SUBFMT_END;
              break;
          }

        break;

      /* 播放能力: P1 未实现 */

      case AUDIO_TYPE_OUTPUT:
        caps->ac_channels = 1;

        if (caps->ac_subtype == AUDIO_TYPE_QUERY)
          {
            caps->ac_controls.b[0] = AUDIO_SUBFMT_END;
          }
        else
          {
            caps->ac_controls.b[0] = AUDIO_SUBFMT_END;
          }

        break;

      default:
        return -ENOTTY;
    }

  /* 必须返回 ac_len 而不是 OK: audio core 把本返回值直接透传给
   * AUDIOIOC_GETCAPS 的调用者 (nuttx/audio/audio.c:960),
   * 而 nxrecorder 判的是 "ioctl(...) == cap.ac_len"
   * (apps/system/nxrecorder/nxrecorder.c:202)。
   * 返回 OK(=0) 会让所有走该约定的客户端报 "No suitable Audio Device found"
   * —— 实测 nxrecorder 的 recordraw 因此完全不可用 (2026-10-10)。
   * 树内 8 个参考驱动 (cs4344/cs43l22/audio_i2s/audio_dma/...) 均返回 ac_len。 */
  return caps->ac_len;
}

static int sf32lb52_audio_configure(FAR struct audio_lowerhalf_s *dev,
                                    FAR const struct audio_caps_s *caps)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;

  if (caps->ac_type != AUDIO_TYPE_INPUT)
    {
      return -ENOTTY;
    }

  /* 会话边界兜底: 客户端没走 RESERVE 时在这里清上一会话的残留
   * (configure 同样排在第一次 ENQUEUEBUFFER 之前, 见 reserve 的说明) */

  if (priv->orphan)
    {
      priv->orphan = !sf32lb52_audio_orphan_cleanup(priv);
    }

  /* nxrecorder 约定: 采样率在 hw[0] (b[3] 为高位), 位深在 b[2] */
  priv->samprate = caps->ac_controls.hw[0];
  if (priv->samprate == 0)
    {
      priv->samprate = SF32LB52_AUDIO_DEFAULT_RATE;
    }

  priv->nchannels = caps->ac_channels;
  if (priv->nchannels == 0)
    {
      priv->nchannels = SF32LB52_AUDIO_DEFAULT_CHS;
    }

  priv->bpsamp = caps->ac_controls.b[2];
  if (priv->bpsamp == 0)
    {
      priv->bpsamp = SF32LB52_AUDIO_DEFAULT_BITS;
    }

  /* 当前仅支持 16kHz/16bit/单声道, 其它请求收敛到该配置 */
  priv->samprate = SF32LB52_AUDIO_DEFAULT_RATE;
  priv->nchannels = SF32LB52_AUDIO_DEFAULT_CHS;
  priv->bpsamp = SF32LB52_AUDIO_DEFAULT_BITS;

  /* 16kHz 采样率时钟表 (PLL): {rate, clk_src_sel, clk_div, osr_sel,
   *  sel_clk_adc_source, sel_clk_adc, diva_clk_adc, fsp}
   * 参考 SiFli SDK codec_adc_clk_config_pll[3] (16k 项)
   * 注意: 结构体首字段是 samplerate, 早前版本漏写 16000 导致全体左移
   * (clk_src_sel=10 非法, clk_div=1), ADC 时钟快约一个量级 (真机实测
   * 半传输中断 ~1kHz, 应为 ~16Hz), 2026-08-16 修复 */
  static AUDCODE_ADC_CLK_CONFIG_TYPE adc_clk_16k =
    { 16000, 1, 10, 1, 1, 0, 5, 2 };

  priv->codec.Init.adc_cfg.opmode = 1;
  priv->codec.Init.adc_cfg.adc_clk = &adc_clk_16k;

  return OK;
}

static int sf32lb52_audio_start(FAR struct audio_lowerhalf_s *dev)
{
  return sf32lb52_audio_start_capture((FAR struct sf32lb52_audio_s *)dev);
}

static int sf32lb52_audio_stop(FAR struct audio_lowerhalf_s *dev)
{
  /* drain 未确认时必须把错误传上去 (不能恒返回 OK): audio core 判
   * ret != OK 并回退状态 (nuttx/audio/audio.c:698-702) */
  return sf32lb52_audio_stop_capture((FAR struct sf32lb52_audio_s *)dev);
}

static int sf32lb52_audio_enqueuebuffer(FAR struct audio_lowerhalf_s *dev,
                                        FAR struct ap_buffer_s *apb)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;
  FAR struct dq_entry_s *entry;
  int ret;

  apb->curbyte = 0;

  /* ★ 驱动自己持一份引用 (crefs 1->2), 必须在把 apb 挂进 aux/pendq 之前取:
   * 一旦挂进去, worker 随时可能碰它, 上层也随时可能 AUDIOIOC_FREEBUFFER。
   * 有了这份引用, 上层释放只会把 crefs 减到 1, 不会把内存从 worker 脚下
   * 抽走 —— 这正是 v3 要修的所有权 UAF (docs_ble/25.1、29)。
   * 配对归还点 (有且只有一处会执行):
   *   deliver 的 upper(DEQUEUE) 之后 / drain 归还之后 / cancelbuffer 之后 /
   *   freebuffer 从 pendq 摘除之后 (那时 worker 已不可能再拿到它)。 */

  apb_reference(apb);

  ret = nxmutex_lock(&priv->pendlock);
  if (ret < 0)
    {
      /* 没能挂进队列: 立刻把刚取的引用还回去, 否则永久泄漏 (DR-J04) */

      apb_free(apb);
      return ret;
    }

  /* 重复入队预检 (审查 DR-B005): 同一只 apb 二次入队会同时挂在 aux 与
   * pendq 上, 之后 drain/orphan 清理对同一 dq_entry 连续 dq_addlast 会让
   * 链表自环 -> dq_remfirst 死循环 + 反复 apb_free。宁可拒绝, 也不能让
   * 客户端的误用破坏内核链表。 */

  if (priv->aux == apb || priv->inflight == apb)
    {
      nxmutex_unlock(&priv->pendlock);
      apb_free(apb);                    /* 撤销刚取的那份引用 */
      return -EINVAL;
    }

  for (entry = priv->pendq.head; entry != NULL; entry = entry->flink)
    {
      if (entry == &apb->dq_entry)
        {
          nxmutex_unlock(&priv->pendlock);
          apb_free(apb);
          return -EINVAL;
        }
    }

  if (priv->aux == NULL)
    {
      priv->aux = apb;
    }
  else
    {
      dq_addlast(&apb->dq_entry, &priv->pendq);
    }

  nxmutex_unlock(&priv->pendlock);
  return OK;
}

static int sf32lb52_audio_cancelbuffer(FAR struct audio_lowerhalf_s *dev,
                                       FAR struct ap_buffer_s *apb)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;
  int where;
  int ret;

  /* 先判成员关系再摘: 旧实现对非队列成员直接 dq_rem, 而 dq_rem 会把
   * pendq.head/tail 双双置 NULL —— 静默清空整条等待队列
   * (include/nuttx/queue.h:229-254, docs_ble/29 §3.3)。 */

  ret = nxmutex_lock(&priv->pendlock);
  if (ret < 0)
    {
      return ret;                       /* 未持锁: 队列未动 (DR-J04) */
    }

  where = sf32lb52_audio_unlink_locked(priv, apb);
  nxmutex_unlock(&priv->pendlock);

  if (where != SF32LB52_APB_IN_AUX && where != SF32LB52_APB_IN_PENDQ)
    {
      /* IN_WORK: worker 正拿着它, 交付与引用归还都归它 (这里再交付一次就是
       * 重复回调); NOT_MEMBER: 不属于本驱动。两种情况都只返回。 */

      return OK;
    }

  /* 已从 aux/pendq 摘除: worker 不可能再拿到它, 交付上层后归还驱动引用
   * (上层自己那份引用不受影响: cancel 不释放缓冲, 上层仍持有它)。 */

  priv->dev.upper(priv->dev.priv, AUDIO_CALLBACK_DEQUEUE, apb, OK);
  apb_free(apb);
  return OK;
}

/* 释放一个音频缓冲 (AUDIOIOC_FREEBUFFER)。
 *
 * 这个回调必须实现: 核心的缺省路径直接 apb_free (nuttx/audio/audio.c:741-751),
 * 驱动没有机会在释放前把 pendq/aux 里的引用摘掉 —— 真机复现回溯就是这样:
 * apb_free ← audio_freebuffer ← audio_ioctl ← FREEBUFFER (docs_ble/25.1)。
 *
 * v3 的引用计数分工 (docs_ble/29 §3.2):
 *   - 上层那份引用由本回调归还 (核心缺省实现也是这么做的);
 *   - 驱动那份引用 (enqueuebuffer 里的 apb_reference) 只有在该 apb 确实
 *     从 pendq 里被摘出来时才在这里归还 —— worker 从此再拿不到它, 不还
 *     就永远还不掉 (crefs 停在 1 = 内存泄漏);
 *   - aux 里那只 / 不在队列里的那只, 驱动引用一律由 worker 在
 *     upper(DEQUEUE) 之后归还 —— 它可能正拿着裸指针干活, 在这里提前归还
 *     等于把内存从 worker 脚下抽走 (UAF)。
 *
 * 两个易错点:
 * 1) ops 表的第二参数是 audio_buf_desc_s, 不是 ap_buffer_s
 *    (include/nuttx/audio/audio.h:959), apb 在 bufdesc->u.buffer 里;
 * 2) 返回正值表示成功: core 用它决定是否回收自己 apbs[] 里的记录
 *    (audio.c:753-766), 故与树内参考实现 (audio_dma.c:419) 一致返回
 *    sizeof(struct audio_buf_desc_s)。 */

static int sf32lb52_audio_freebuffer(FAR struct audio_lowerhalf_s *dev,
                                     FAR struct audio_buf_desc_s *bufdesc)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;
  FAR struct ap_buffer_s *apb = bufdesc->u.buffer;
  int where;
  int ret;

  if (apb == NULL)
    {
      return -EINVAL;
    }

  /* 1) 先把驱动内部的引用摘干净: 摘完 worker 才不会继续往一只"上层认为
   *    已回收"的缓冲里写 (摘除必须与 worker 的取用同在 pendlock 内互斥)。
   *    成员关系预检由 unlink_locked 负责 —— 对非成员调 dq_rem 会静默清空
   *    整条 pendq (DR-B004)。 */

  ret = nxmutex_lock(&priv->pendlock);
  if (ret < 0)
    {
      return ret;                       /* 未持锁: 一个字段都没动 (DR-J04) */
    }

  where = sf32lb52_audio_unlink_locked(priv, apb);
  nxmutex_unlock(&priv->pendlock);

  /* 2) 归还上层那一份引用: AUDIOIOC_FREEBUFFER 的语义就是上层放弃自己那份,
   *    核心只在 ops->freebuffer 缺失时才代做 (audio.c:741-751)。 */

  apb_free(apb);

  /* 3) 归还驱动那一份引用 —— 当且仅当 apb 已完全落在驱动手里、worker 再也
   *    不会碰它 (IN_AUX: 只是填充目标; IN_PENDQ: 已摘出等待队列)。此时 crefs
   *    必为 1 (刚还掉上层那份), 第二次 apb_free 才真正释放对象。
   *
   *    IN_WORK: worker 正拿着裸指针。摘队列既不能阻止它继续写 (它拿的是裸
   *    指针), 又会让这份引用失去唯一的归还责任人 -> 永久泄漏。引用由 worker
   *    收尾时归还 (它无论如何都会 apb_free)。
   *    NOT_MEMBER: 已交付 (引用已还) 或正处在 worker 的交付窗口里 (它马上会
   *    还) —— 都不能在这里再还一次, 否则 crefs 提前归零 = UAF。
   *    (审查 DR-B002: 旧版只认 IN_PENDQ, 于是 aux 里那只的驱动引用永久泄漏) */

  if (where == SF32LB52_APB_IN_AUX || where == SF32LB52_APB_IN_PENDQ)
    {
      apb_free(apb);
    }

  return sizeof(struct audio_buf_desc_s);
}

static int sf32lb52_audio_reserve(FAR struct audio_lowerhalf_s *dev)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;

  /* 新会话的第一个回调 (AUDIOIOC_RESERVE 一定排在客户端 ENQUEUEBUFFER 之前):
   * 把上一会话"采集未停就 close"留下的残留 apb 收干净 (只归还驱动引用, 不
   * 回调上层 —— 那时 upper->status 早已被 core 释放)。
   * 放在 START 里做是错的: 客户端按契约先 enqueue 后 START, 会把新会话刚挂上
   * 的缓冲一起吃掉, 表现为"采集静默停摆" (审查 N1)。 */

  if (priv->orphan)
    {
      priv->orphan = !sf32lb52_audio_orphan_cleanup(priv);
    }

  return OK;
}

/* NuttX audio 核心 AUDIOIOC_GETBUFFERINFO / SETBUFFERINFO / 未知命令
 * 直接转发到 lower->ops->ioctl (audio.c:1257/1271/1294, 无 NULL 保护),
 * 缺失该成员时 mic_capture 的 GETBUFFERINFO 会经 NULL 函数指针调用
 * 触发 hard fault (真机 2026-08-16 实测崩溃点, 见开发记录 13 第九节)。 */
static int sf32lb52_audio_ioctl(FAR struct audio_lowerhalf_s *dev,
                                int cmd, unsigned long arg)
{
  FAR struct ap_buffer_info_s *binfo;

  (void)dev;

  switch (cmd)
    {
      case AUDIOIOC_GETBUFFERINFO:
        if (arg == 0)
          {
            return -EINVAL;
          }

        binfo = (FAR struct ap_buffer_info_s *)arg;
        binfo->buffer_size = SF32LB52_AUDIO_BUF_SIZE;
        binfo->nbuffers    = SF32LB52_AUDIO_NUM_BUFS;
        return OK;

      case AUDIOIOC_SETBUFFERINFO:
        return OK;   /* 接受, 但维持驱动默认缓冲配置 */

      default:
        return -ENOTTY;
    }
}

static int sf32lb52_audio_release(FAR struct audio_lowerhalf_s *dev)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;

  /* 与 stop 一致: drain 失败要传上去, 不得恒返回 OK */

  return sf32lb52_audio_stop_capture(priv);
}

static int sf32lb52_audio_shutdown(FAR struct audio_lowerhalf_s *dev)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;

  /* core 在 audio_close() 里持 upper->spinlock 且关中断调用本回调, 并在它
   * 返回后立刻 kumm_free(upper->status) (nuttx/audio/audio.c:241-277)。
   * 三条推论 (DR-J13 + 两轮审查的 DR-001/002/003):
   *   1) 不能有任何可阻塞等待: 关中断后 worker 无法被调度, 依赖定时器中断
   *      的超时也永远不会生效 —— 等了就是整机死锁 (真机上"录音未停就 close"
   *      正是这样挂死的, 旧实现走 stop_capture 的 drain 等待)。
   *   2) 不能再回调上层: 返回之后 upper->status 已释放, audio_dequeuebuffer
   *      里的 upper->status->tail++ (audio.c:1504) 就是空指针解引用。
   *   3) 不能在这里 apb_free: apb_free 内部要 nxmutex_lock(&apb->lock)
   *      (lib_buffer.c:120), 关中断下阻塞在一把可能被别人持有的 mutex 上
   *      同样是永久挂死。
   * 所以这里只做三件立即能做完、且都是纯寄存器/内存写的事。 */

  sf32lb52_audio_hw_stop(priv);

  /* 作废尚未确认的 drain 请求: 会话已死, 不能让 worker 醒来后拿它去 drain
   * 下一会话刚入队的缓冲, 更不能让它在 shutdown 之后回调上层 (DR-B001)。 */

  priv->drain_done_seq = priv->drain_seq;

  /* 收回"交付权": worker 若正拿着某只 apb, 恢复后会发现自己不再是交付权
   * 持有者, 于是既不回调也不归还引用 —— 那只 apb 留在 aux 里, 由下一次
   * START 的 orphan 清理归还驱动引用 (关中断下不能 apb_free)。
   * 拿不到锁 (worker 正持锁的几微秒窗口) 时无法收回, 一并记为 orphan。 */

  if (nxmutex_trylock(&priv->pendlock) == OK)
    {
      bool stolen = (priv->inflight != NULL);

      priv->inflight = NULL;
      priv->orphan = stolen || priv->aux != NULL || !dq_empty(&priv->pendq);
      nxmutex_unlock(&priv->pendlock);
    }
  else
    {
      priv->orphan = true;
    }

  return OK;
}

/****************************************************************************
 * Public Functions
 ****************************************************************************/

int sf32lb52_audcodec_register(void)
{
  FAR struct sf32lb52_audio_s *priv = &g_audio;
  AUDCODEC_HandleTypeDef *codec = &priv->codec;
  int ret;

  memset(priv, 0, sizeof(*priv));

  /* rx_buf 必须按 D-cache 行对齐: up_invalidate_dcache 对非对齐起点会向下
   * 取整到行首、终点向上取整到行尾 (边界行 clean+invalidate), 会把相邻
   * 字段的陈旧数据写回、也越界碰到 rx_buf 之后的字段 (DR-F010)。
   * 行大小取决于芯片 CCSIDR, 不能沿用 Cortex-M7 的 32 常量, 故运行时判断;
   * 非 DCACHE 构建下 up_get_dcache_linesize() 为 0 (invalidate 是空操作),
   * 此时跳过检查以免除零。
   * 必须是"返回错误的运行时检查"而不是 DEBUGASSERT: 发布构建会把断言整个
   * 编译掉, 将来若有人调小 rx_buf 的 aligned() 就没有任何兜底 (DR-J14)。 */

  if (up_get_dcache_linesize() != 0 &&
      (((uintptr_t)priv->rx_buf) % up_get_dcache_linesize()) != 0)
    {
      auderr("ERROR: rx_buf %p is not %zu-byte aligned\n",
             priv->rx_buf, up_get_dcache_linesize());
      return -EINVAL;
    }

  /* ── 时钟/电源 (参考 SiFli SDK bf0_audio_init) ── */
  HAL_PMU_EnableAudio(1);
  HAL_RCC_EnableModule(RCC_MOD_AUDCODEC);

  /* ── DMA 句柄: ADC CH0 = DMAC1_CH4, 请求 39 ── */
  priv->dma_rx.Instance = AUDCODEC_ADC0_DMA_INSTANCE;
  priv->dma_rx.Init.Request = AUDCODEC_ADC0_DMA_REQUEST;

  /* ── audcodec 句柄 ── */
  codec->Instance = hwp_audcodec;
  codec->hdma[HAL_AUDCODEC_ADC_CH0] = &priv->dma_rx;
  codec->Init.en_dly_sel = 0;
  codec->Init.adc_cfg.opmode = 1;
  codec->Init.adc_cfg.adc_clk = NULL;   /* configure() 时按采样率设置 */

  /* 默认 16kHz 时钟表 (同样补上 samplerate 首字段) */
  static AUDCODE_ADC_CLK_CONFIG_TYPE adc_clk_default =
    { 16000, 1, 10, 1, 1, 0, 5, 2 };
  codec->Init.adc_cfg.adc_clk = &adc_clk_default;
  priv->samprate = SF32LB52_AUDIO_DEFAULT_RATE;
  priv->nchannels = SF32LB52_AUDIO_DEFAULT_CHS;
  priv->bpsamp = SF32LB52_AUDIO_DEFAULT_BITS;

  if (HAL_AUDCODEC_Init(codec) != HAL_OK)
    {
      auderr("ERROR: HAL_AUDCODEC_Init failed\n");
      ret = -EIO;
      goto errout_hal;
    }

  /* ── 同步原语 ── */
  nxsem_init(&priv->rx_sem, 0, 0);
  nxsem_init(&priv->drain_done, 0, 0);
  nxsem_init(&priv->worker_done, 0, 0);
  nxmutex_init(&priv->pendlock);
  dq_init(&priv->pendq);

  /* ── DMA 中断 ── */
  ret = irq_attach(AUDCODEC_ADC0_DMA_IRQ + NVIC_IRQ_FIRST,
                   sf32lb52_audio_isr, priv);
  if (ret != OK)
    {
      auderr("ERROR: irq_attach failed: %d\n", ret);
      goto errout_sync;
    }

  /* ── 采集工作线程 (唯一一次创建, 之后永不重建) ── */
  priv->worker = kthread_create("audcodec_rx", SF32LB52_AUDIO_WORKER_PRIO,
                                SF32LB52_AUDIO_WORKER_STACK,
                                sf32lb52_audio_worker, NULL);
  if (priv->worker < 0)
    {
      ret = (int)priv->worker;
      auderr("ERROR: kthread_create failed: %d\n", ret);
      goto errout_irq;
    }

  /* ── 注册到 NuttX 音频框架 ── */
  priv->dev.ops = &g_sf32lb52_audio_ops;
  ret = audio_register(SF32LB52_AUDIO_DEVNAME, &priv->dev);
  if (ret < 0)
    {
      auderr("ERROR: audio_register failed: %d\n", ret);
      goto errout_worker;
    }

  ainfo("SF32LB52 audcodec registered: %s (16kHz/16bit/mono)\n",
        SF32LB52_AUDIO_DEVNAME);
  return OK;

/* 失败路径按创建顺序的逆序回滚 (DR-J07 + 审查 DR-004): 不回滚的话, register
 * 失败会留下一个永远阻塞在 rx_sem 上的 worker 线程、一条已挂上 ISR 的向量,
 * 以及仍然带电的 audcodec (PMU/RCC/HAL 都开着) —— 下次再 register 会创建出
 * 第二个 worker 抢同一条 pendq。 */

errout_worker:
  /* 有序停机, 不能只靠 kthread_delete: CONFIG_CANCELLATION_POINTS=y 时它只给
   * 停在 uninterruptible 信号量上的线程置一个延迟取消位, 线程并不会真的死
   * (sched/task/task_cancelpt.c), 随后 destroy 它正等待的信号量、或再 register
   * 造出第二个 worker 抢同一条 pendq 都会踩到它 (审查 DR-B008/N4)。
   * 这里: 置标志 + post 唤醒, 等它自己回报退出; 等不到再退回强删。 */

  priv->worker_stop = true;
  nxsem_post(&priv->rx_sem);
  {
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    ts.tv_nsec += 100 * 1000 * 1000;
    if (ts.tv_nsec >= 1000000000L)
      {
        ts.tv_sec++;
        ts.tv_nsec -= 1000000000L;
      }

    if (nxsem_clockwait_uninterruptible(&priv->worker_done, CLOCK_MONOTONIC,
                                        &ts) < 0)
      {
        kthread_delete(priv->worker);
      }
  }

  priv->worker = 0;
errout_irq:
  irq_detach(AUDCODEC_ADC0_DMA_IRQ + NVIC_IRQ_FIRST);
errout_sync:
  nxmutex_destroy(&priv->pendlock);
  nxsem_destroy(&priv->worker_done);
  nxsem_destroy(&priv->drain_done);
  nxsem_destroy(&priv->rx_sem);
errout_hal:
  (void)HAL_AUDCODEC_DeInit(codec);
  HAL_RCC_DisableModule(RCC_MOD_AUDCODEC);
  HAL_PMU_EnableAudio(0);
  return ret;
}
