/****************************************************************************
 * sf32lb52_audio_tx.c - SF32LB52 板载扬声器播放 (AUDPRC -> AUDCODEC DAC -> PA)
 *
 * 架构 (与官方 52x 唯一支持的播放链路一致, 见 docs_ble/30 §10/§11):
 *
 *   apb(PCM) --worker--> 32bit 打包 --DMA(DMA1_Channel1, REQ 51)-->
 *   AUDPRC TX_CH0 --> AUDPRC DAC_PATH(DST_SEL=0=CODEC) --> AUDCODEC DAC CH0
 *   --> 模拟 DAC1 --> 板载 Class-D PA(PA10) --> 喇叭
 *
 * 关键点 (都有官方源码依据):
 *   - 52x 没有 AUDCODEC DACx 的 DMA 实例, 官方播放走 AUDPRC 的 DMA;
 *   - audio codec 侧 dac_cfg.opmode 必须为 0 (= AUDPRC -> codec),
 *     且必须在 HAL_AUDCODEC_Config_TChanel() 之前设定 (opmode 落在 DAC_CFG);
 *   - AUDPRC dest_sel 必须为 0 (= CODEC): 只有 MEM 才关循环 DMA/半传输回调;
 *   - 先静音 -> 配模拟通路 -> 使能 -> 最后解静音, 防 POP; PA 拉高后等 30ms;
 *   - 16kHz 走 XTAL 48M (AUDPRC STB div=3000), 不需要 audio PLL, 也就不与
 *     采集路径的 PLL 配置打架。
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <debug.h>
#include <errno.h>
#include <string.h>
#include <time.h>

#include <nuttx/audio/audio.h>
#include <nuttx/fs/fs.h>
#include <nuttx/arch.h>
#include <nuttx/irq.h>
#include <nuttx/spinlock.h>
#include <nuttx/cache.h>
#include <nuttx/kmalloc.h>
#include <nuttx/mutex.h>
#include <nuttx/semaphore.h>
#include <nuttx/kthread.h>
#include <nuttx/queue.h>

/* SiFli HAL */
#define SOC_BF0_HCPU
#define SF32LB52X
#include "bf0_hal.h"
#include "dma_config.h"

#include "sf32lb52_audcodec.h"

/****************************************************************************
 * Pre-processor Definitions
 ****************************************************************************/

#define TX_HALF_BYTES   2048          /* 半个 DMA 缓冲 = 1024 样点 = 64ms @16k */
#define TX_BUF_BYTES    (TX_HALF_BYTES * 2)

#define TX_DEFAULT_RATE 16000
#define TX_DEFAULT_CHS  1
#define TX_DEFAULT_BITS 16

#define TX_APB_BYTES    4096          /* 每只 apb 的 PCM 字节数 (与采集一致) */
#define TX_APB_NUM      8

#define TX_WORKER_PRIO  120
#define TX_WORKER_STACK 2048

#define TX_PA_PIN       10            /* AUDIO_PA_CTRL = PA10 (hwp_gpio1 pin10) */

#ifndef CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME
#  define CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME "pcm0p"
#endif

/* DMA 缓冲必须 cache 行对齐 (D-cache 打开时 DMA 读内存) */
#define TX_BUF_ALIGN    32

/****************************************************************************
 * Private Types
 ****************************************************************************/

struct sf32lb52_tx_s
{
  struct audio_lowerhalf_s dev;       /* NuttX audio lower-half (必须放最前) */

  mutex_t        lock;                /* 保护 pendq/cur                                                   */
  sem_t          wake;                /* ISR -> worker                                                    */
  sem_t          done;                /* worker 退出确认                                                  */
  struct dq_queue_s pendq;            /* 等待播放的 apb                                                   */

  FAR struct ap_buffer_s *cur;        /* 正在播放的 apb                                                   */
  uint32_t       cur_off;             /* 已消费字节                                                       */

  bool           running;
  bool           worker_run;
  volatile bool  worker_stop;
  volatile uint32_t half_pending;     /* bit0: 前半空, bit1: 后半空 (ISR 置)                              */
  volatile uint32_t underruns;
  volatile uint32_t deq_count;
  volatile uint32_t halves;          /* 半/全传输回调总次数 (DMA 速率探针) */
  /* 2026-11-04 v142k AUDIO-FIX（TTS 欠载/APB 不回收）诊断+安全：
   *   enq_calls/cancel_calls -> 上层到底投/撤了多少 APB；
   *   pend_depth/pend_max    -> pendq 当前/历史最大深度（诊断 deq=0）；
   *   drain                  -> STOP 排空期间禁止 worker 再取 cur（防与
   *                             tx_stop 的 free 竞态，leaked worker 时关键）。 */
  volatile uint32_t enq_calls;
  volatile uint32_t cancel_calls;
  volatile uint32_t pend_max;
  uint32_t       pend_depth;         /* 受 lock 保护 */
  volatile uint32_t drain;
  /* 2026-11-04 v142k AUDIO-ROOT 对齐（perf-fw）：worker 卡死定位用。 */
  volatile uint32_t phase;           /* TX_PHASE_*（worker 当前阶段） */
  volatile uint32_t fill_start;      /* 进入 tx_fill 的 tick（stall 计算） */
  volatile uint32_t fill_stall_max;  /* 历史最长 tx_fill 持续 tick */
  volatile uint32_t lock_fail;       /* trylock 失败计数（g_tx.lock 竞争） */
  uint32_t       t_start;
  /* 2026-11-01 v102a 诊断：区分"start 没被调用"与"DMA 已死" */
  uint32_t       start_calls;        /* tx_start 真正进入次数 */
  volatile uint32_t irq_count;       /* DMA ISR 次数 */
  volatile uint32_t fills;           /* tx_fill 进入次数 */
  volatile uint32_t dma_err;         /* HAL DMA 错误次数 */
  /* v110 P0-1 心跳取证：ISR->worker 信号链是否断 */
  volatile uint32_t wake_posts;      /* ISR 投递 wake 次数 */
  volatile uint32_t wake_got;        /* worker 被唤醒次数 */
  volatile uint32_t loop_idle;       /* worker 空转/超时次数 */
  volatile uint32_t run_false;       /* worker 因 !running 丢弃次数 */
  volatile uint32_t in_fill;         /* 1=正在 tx_fill 里（用于判定阻塞） */
  volatile uint32_t stall_detect;    /* 心跳判定停摆次数 */

  int            rate;
  int            channels;
  int            bpsamp;
  int            volume;              /* 0.5dB 单位, -36..54                                              */

  /* 硬件 */
  AUDCODEC_HandleTypeDef codec;
  AUDPRC_HandleTypeDef   prc;
  DMA_HandleTypeDef      dma_tx;

  uint8_t        dma_buf[TX_BUF_BYTES] __attribute__((aligned(TX_BUF_ALIGN)));

  struct audio_caps_s caps;           /* 当前配置                                                         */
};

/****************************************************************************
 * Private Data
 ****************************************************************************/

static struct sf32lb52_tx_s g_tx;

/* 16kHz DAC 时钟表: 官方 codec_dac_clk_config_xtal[3] (clk_src_sel=0 -> XTAL,
 * 不需要 audio PLL); SINC_GAIN=0x14D (AVDD 3.3V)                            */
static AUDCODE_DAC_CLK_CONFIG_TYPE g_dac_clk_16k_xtal =
  { 16000, 0, 1, 4, 0x14D, 0, 5, 4, 2, 20, 20, 0 };

/* ADC 侧本次不用, 但 HAL 的句柄要求指针合法: 用官方 codec_adc_clk_config_pll[3] */
static AUDCODE_ADC_CLK_CONFIG_TYPE g_adc_clk_16k =
  { 16000, 1, 10, 1, 1, 0, 5, 2 };

/****************************************************************************
 * Private Functions
 ****************************************************************************/

static void tx_pa(int on)
{
  HAL_PIN_Set(PAD_PA10, GPIO_A10, PIN_NOPULL, 1);
  HAL_GPIO_WritePin((GPIO_TypeDef *)hwp_gpio1, TX_PA_PIN,
                    on ? GPIO_PIN_SET : GPIO_PIN_RESET);
}

/* 把 apb 里的 16bit 单声道样点打包成 32bit 字: 每个字两个连续样点。
 * (DMA 以 32bit 搬进 TX_CH0_ENTRY; 若真机音高不对, 改 TX_PACK_DUP=1 试
 *  "每个样点重复两次" 的打包。) */

#define TX_PACK_DUP 0

/* worker 当前阶段（心跳/ps 取证用；0=空闲） */
#define TX_PHASE_IDLE      0
#define TX_PHASE_WAIT_SEM  1
#define TX_PHASE_FILL      2
#define TX_PHASE_LOCK      3
#define TX_PHASE_UPPER     4
#define TX_PHASE_EXIT      5

static size_t tx_fill(uint8_t *dst, size_t bytes, bool *starved)
{
  size_t done = 0;

  /* AUDIO-FIX：STOP 正在排空 pendq/cur（并 free APB）时，worker 绝不能再
   * 进 tx_fill 去取同一只 APB —— 否则 stop 的 free 与 worker 的 memcpy
   * 竞态 = UAF/串音。drain 由 tx_stop 置位、tx_start 清。 */
  if (g_tx.drain)
    {
      memset(dst, 0, bytes);
      *starved = true;
      return 0;
    }

  *starved = false;
  g_tx.fills++;
  g_tx.fill_start = clock_systime_ticks();
  g_tx.phase = TX_PHASE_FILL;
  g_tx.in_fill = 1;

  while (done < bytes)
    {
      size_t want = bytes - done;
      size_t avail;

      if (g_tx.cur == NULL)
        {
          FAR struct ap_buffer_s *apb = NULL;

          /* AUDIO-ROOT 仪器化：先 trylock，失败计入 lock_fail（区分
           * "等 g_tx.lock" 与 "卡在别处"）；随后阻塞等待。 */
          g_tx.phase = TX_PHASE_LOCK;
          if (nxmutex_trylock(&g_tx.lock) != OK)
            {
              g_tx.lock_fail++;
              nxmutex_lock(&g_tx.lock);
            }
          if (!dq_empty(&g_tx.pendq))
            {
              apb = (FAR struct ap_buffer_s *)dq_remfirst(&g_tx.pendq);
              if (g_tx.pend_depth > 0)
                {
                  g_tx.pend_depth--;
                }
            }
          nxmutex_unlock(&g_tx.lock);
          g_tx.phase = TX_PHASE_FILL;

          if (apb == NULL)
            {
              break;                       /* 没数据了: 余下填静音 */
            }

          g_tx.cur     = apb;
          g_tx.cur_off = 0;
        }

      avail = g_tx.cur->nbytes - g_tx.cur_off;

      if (avail == 0)
        {
          /* 这一只播完了: 交还上层, 释放驱动引用 */
          FAR struct ap_buffer_s *apb = g_tx.cur;

          g_tx.cur = NULL;
          g_tx.cur_off = 0;
          g_tx.deq_count++;
          g_tx.phase = TX_PHASE_UPPER;
          g_tx.dev.upper(g_tx.dev.priv, AUDIO_CALLBACK_DEQUEUE, apb, OK);
          apb_free(apb);
          g_tx.phase = TX_PHASE_FILL;
          continue;
        }

      if (want > avail)
        {
          want = avail;
        }

      if (g_tx.cur_off == 0 && g_tx.cur->samp != NULL)
        {
          /* DMA 读内存, 先写回 cache */
          up_clean_dcache((uintptr_t)g_tx.cur->samp,
                          (uintptr_t)g_tx.cur->samp + g_tx.cur->nbytes);
        }

      memcpy(dst + done, (uint8_t *)g_tx.cur->samp + g_tx.cur_off, want);
      g_tx.cur_off += want;
      done         += want;
    }

  if (done < bytes)
    {
      memset(dst + done, 0, bytes - done);   /* 欠载: 补静音 */
      *starved = true;
    }

  up_clean_dcache((uintptr_t)dst, (uintptr_t)dst + bytes);

  {
    uint32_t dur = clock_systime_ticks() - g_tx.fill_start;

    if (dur > g_tx.fill_stall_max)
      {
        g_tx.fill_stall_max = dur;
      }
  }

  g_tx.phase = TX_PHASE_IDLE;
  g_tx.in_fill = 0;
  return done;
}

static int tx_worker(int argc, FAR char *argv[])
{
  /* ★ 常驻 worker（2026-11-01）：线程只在注册时创建一次、终身复用。
   * 历史 bug：worker 原来在 tx_stop 时 return 退出、下次 tx_start 再
   * kthread_create —— 内核线程 TCB 还没释放完就重开时 PID 复用，
   * 真机出现 sched_releasetcb.c:77 refs>0 断言（task audcodec_tx），
   * 复位/黑屏（开机提示音后紧接 TTS 时最容易触发）。 */
  for (;;)
    {
      uint32_t pending;
      bool starved;

      /* v110 P0-1：1s 超时等待，既能被中断唤醒，也能周期打印心跳 */
      {
        int wret;

        g_tx.phase = TX_PHASE_WAIT_SEM;
        wret = nxsem_tickwait_uninterruptible(&g_tx.wake, 100);
        g_tx.phase = TX_PHASE_IDLE;

        if (wret == OK)
          {
            g_tx.wake_got++;
          }
        else
          {
            g_tx.loop_idle++;
          }
      }

      /* 5s 心跳：halves 涨但 fills 不涨 / in_fill=1 不落，就是停摆证据 */
      {
        static uint32_t hb_last;
        static uint32_t hb_halves;
        static uint32_t hb_fills;
        uint32_t now = clock_systime_ticks();

        if (hb_last == 0)
          {
            hb_last = now;
            hb_halves = g_tx.halves;
            hb_fills = g_tx.fills;
          }
        else if ((uint32_t)(now - hb_last) >= (uint32_t)(5 * CLK_TCK))
          {
            uint32_t dh = g_tx.halves - hb_halves;
            uint32_t df = g_tx.fills - hb_fills;
            uint32_t fstall = g_tx.in_fill
                              ? (uint32_t)(now - g_tx.fill_start) : 0;

            if (g_tx.running && dh > 0 && df == 0)
              {
                g_tx.stall_detect++;
              }
            if (g_tx.running || g_tx.wake_posts != 0)
              {
                syslog(LOG_INFO,
                  "[audio_tx] hb run=%d dhalves=%u dfills=%u halves=%u irq=%u "
                  "fills=%u deq=%u q=%u cur=%u enq=%u canc=%u posts=%u got=%u "
                  "idle=%u runF=%u inFill=%u drain=%u pend=%u stall=%u "
                  "phase=%u fstall=%u lockfail=%u fmax=%u\n",
                  (int)g_tx.running, (unsigned)dh, (unsigned)df,
                  (unsigned)g_tx.halves, (unsigned)g_tx.irq_count,
                  (unsigned)g_tx.fills, (unsigned)g_tx.deq_count,
                  (unsigned)g_tx.pend_depth,
                  (unsigned)(g_tx.cur != NULL ? g_tx.cur->nbytes : 0),
                  (unsigned)g_tx.enq_calls, (unsigned)g_tx.cancel_calls,
                  (unsigned)g_tx.wake_posts, (unsigned)g_tx.wake_got,
                  (unsigned)g_tx.loop_idle, (unsigned)g_tx.run_false,
                  (unsigned)g_tx.in_fill, (unsigned)g_tx.drain,
                  (unsigned)(g_tx.half_pending & 3),
                  (unsigned)g_tx.stall_detect,
                  (unsigned)g_tx.phase, (unsigned)fstall,
                  (unsigned)g_tx.lock_fail,
                  (unsigned)g_tx.fill_stall_max);
              }
            hb_last = now;
            hb_halves = g_tx.halves;
            hb_fills = g_tx.fills;
          }
      }

      if (g_tx.worker_stop)
        {
          g_tx.phase = TX_PHASE_EXIT;
          break;
        }

      /* 没在放音：清掉可能残留的半缓冲标志，继续睡，不碰硬件。 */
      if (!g_tx.running)
        {
          g_tx.run_false++;
          g_tx.half_pending = 0;
          continue;
        }

      pending = g_tx.half_pending & 3;
      if (pending == 0)
        {
          continue;
        }

      if (pending & 1)
        {
          tx_fill(g_tx.dma_buf, TX_HALF_BYTES, &starved);
          if (starved) g_tx.underruns++;
        }

      if (pending & 2)
        {
          tx_fill(g_tx.dma_buf + TX_HALF_BYTES, TX_HALF_BYTES, &starved);
          if (starved) g_tx.underruns++;
        }

      g_tx.half_pending &= ~pending;   /* 只清已处理的位, 不吞掉新到的半传输 */
    }

  nxsem_post(&g_tx.done);
  return OK;
}

static void tx_isr(void)
{
  g_tx.irq_count++;
  HAL_DMA_IRQHandler(&g_tx.dma_tx);
}

/* 2026-11-01: HAL DMA 错误回调（weak 默认空）——DMA 出错时 HAL 会静默关通道，
 * 必须留下计数/日志，否则现场只能看到"半中断不再来"。 */
void HAL_AUDPRC_ErrorCallback(AUDPRC_HandleTypeDef *haprc, int cid)
{
  (void)haprc;
  g_tx.dma_err++;
  if (g_tx.dma_err <= 8)
    {
      syslog(LOG_ERR, "[audio_tx] HAL DMA error cid=%d (dma_err=%u)\n",
             cid, (unsigned)g_tx.dma_err);
    }
}

static void tx_half_cb(DMA_HandleTypeDef *hdma)
{
  g_tx.halves++;
  g_tx.wake_posts++;
  g_tx.half_pending |= 1;
  nxsem_post(&g_tx.wake);
}

static void tx_full_cb(DMA_HandleTypeDef *hdma)
{
  g_tx.halves++;
  g_tx.wake_posts++;
  g_tx.half_pending |= 2;
  nxsem_post(&g_tx.wake);
}

/* 由 HAL 的 weak 回调转进来 */
void HAL_AUDPRC_TxHalfCpltCallback(AUDPRC_HandleTypeDef *haprc, int cid)
{
  tx_half_cb(NULL);
}

void HAL_AUDPRC_TxCpltCallback(AUDPRC_HandleTypeDef *haprc, int cid)
{
  tx_full_cb(NULL);
}

/****************************************************************************
 * audio_ops_s
 ****************************************************************************/

static int tx_getcaps(FAR struct audio_lowerhalf_s *dev, int type,
                      FAR struct audio_caps_s *caps)
{
  caps->ac_format.hw  = 0;
  caps->ac_controls.w = 0;

  switch (caps->ac_type)
    {
      case AUDIO_TYPE_QUERY:
        caps->ac_channels = 1;
        switch (caps->ac_subtype)
          {
            case AUDIO_TYPE_QUERY:
              caps->ac_controls.b[0] = AUDIO_TYPE_OUTPUT | AUDIO_TYPE_FEATURE;
              break;
            default:
              caps->ac_controls.b[0] = AUDIO_SUBFMT_END;
              break;
          }
        break;

      case AUDIO_TYPE_OUTPUT:
        caps->ac_channels = 1;
        switch (caps->ac_subtype)
          {
            case AUDIO_TYPE_QUERY:
              caps->ac_controls.hw[0] = AUDIO_SAMP_RATE_16K;
              caps->ac_controls.b[2]  = 16;
              break;
            default:
              caps->ac_controls.b[0] = AUDIO_SUBFMT_PCM_S16_LE;
              break;
          }
        break;

      case AUDIO_TYPE_FEATURE:
        switch (caps->ac_subtype)
          {
            case AUDIO_FU_VOLUME:
              caps->ac_controls.hw[0] = (uint16_t)g_tx.volume;
              break;
            default:
              break;
          }
        break;

      default:
        break;
    }

  /* 必须返回 ac_len (不是 OK): audio core 把该返回值直接透传给
   * AUDIOIOC_GETCAPS 的调用者 (nuttx/audio/audio.c:960), nxplayer 判的是
   * "ioctl(...) == cap.ac_len"; 返回 0 会让所有客户端报
   * "No suitable Audio Device found" (采集侧踩过同样的坑)。 */
  return caps->ac_len;
}

static int tx_configure(FAR struct audio_lowerhalf_s *dev,
                        FAR struct audio_caps_s *caps)
{
  FAR struct sf32lb52_tx_s *priv = (FAR struct sf32lb52_tx_s *)dev;

  /* 音量走 AUDIO_TYPE_FEATURE/AUDIO_FU_VOLUME (nxplayer/nxaudio 的约定) */
  if (caps->ac_type == AUDIO_TYPE_FEATURE)
    {
      int vol;

      if (caps->ac_format.hw != AUDIO_FU_VOLUME)
        {
          return -ENOTTY;
        }

      vol = (int)(int16_t)caps->ac_controls.hw[0];
      if (vol < -36) vol = -36;
      if (vol > 54)  vol = 54;
      priv->volume = vol;

      if (priv->running)
        {
          HAL_AUDCODEC_Config_DACPath_Volume(&priv->codec, 0, vol);
        }

      return OK;
    }

  if (caps->ac_type != AUDIO_TYPE_OUTPUT)
    {
      return -ENOTTY;
    }
  priv->channels = (caps->ac_channels > 0) ? caps->ac_channels : TX_DEFAULT_CHS;
  if (caps->ac_controls.hw[0])
    {
      priv->rate = (int)caps->ac_controls.hw[0];
    }
  if (caps->ac_controls.b[2])
    {
      priv->bpsamp = (int)caps->ac_controls.b[2];
    }

  /* 本版只支持 16kHz/16bit/单声道 */
  priv->rate    = TX_DEFAULT_RATE;
  priv->channels = TX_DEFAULT_CHS;
  priv->bpsamp  = TX_DEFAULT_BITS;

  return OK;
}

static int tx_start(FAR struct audio_lowerhalf_s *dev)
{
  FAR struct sf32lb52_tx_s *priv = (FAR struct sf32lb52_tx_s *)dev;
  int ret;

  priv->start_calls++;
  syslog(LOG_INFO, "[audio_tx] tx_start#%u running=%d halves=%u irq=%u\n",
         (unsigned)priv->start_calls, (int)priv->running,
         (unsigned)priv->halves, (unsigned)priv->irq_count);

  if (priv->running)
    {
      return OK;
    }

  /* ── 时钟/电源 ── */
  HAL_PMU_EnableAudio(1);
  HAL_RCC_EnableModule(RCC_MOD_AUDCODEC);
  HAL_RCC_EnableModule(RCC_MOD_AUDPRC);

  /* ── AUDCODEC: mem->codec 之外的 AUDPRC 通路 (opmode=0) ── */
  memset(&priv->codec, 0, sizeof(priv->codec));
  priv->codec.Instance = hwp_audcodec;
  priv->codec.Init.en_dly_sel = 0;
  priv->codec.Init.dac_cfg.opmode  = 0;            /* 0 = AUDPRC -> codec */
  priv->codec.Init.dac_cfg.dac_clk = &g_dac_clk_16k_xtal;
  priv->codec.Init.adc_cfg.opmode  = 1;
  priv->codec.Init.adc_cfg.adc_clk = &g_adc_clk_16k;

  if (HAL_AUDCODEC_Init(&priv->codec) != HAL_OK)
    {
      auderr("ERROR: HAL_AUDCODEC_Init failed\n");
      return -EIO;
    }

  HAL_TURN_ON_PLL();                       /* 开模拟参考 (Refgen), 不用 PLL 也要 */
  HAL_AUDCODEC_Config_TChanel(&priv->codec, 0, &priv->codec.Init.dac_cfg);
  HAL_AUDCODEC_Config_DACPath_Volume(&priv->codec, 0, priv->volume);

  /* ── AUDPRC ── */
  memset(&priv->prc, 0, sizeof(priv->prc));
  memset(&priv->dma_tx, 0, sizeof(priv->dma_tx));
  priv->dma_tx.Instance     = AUDPRC_TX0_DMA_INSTANCE;
  priv->dma_tx.Init.Request = AUDPRC_TX0_DMA_REQUEST;

  priv->prc.Instance = hwp_audprc;
  priv->prc.hdma[HAL_AUDPRC_TX_CH0] = &priv->dma_tx;
  priv->prc.Init.clk_div = 1;
  priv->prc.Init.adc_div = 3000;           /* 48MHz/3000 = 16kHz (官方表 16k 项) */
  priv->prc.Init.dac_div = 3000;
  priv->prc.Init.clk_sel = 0;

  if (HAL_AUDPRC_Init(&priv->prc) != HAL_OK)
    {
      auderr("ERROR: HAL_AUDPRC_Init failed\n");
      return -EIO;
    }

  __HAL_AUDPRC_CLK_XTAL(&priv->prc);
  __HAL_AUDPRC_STB_DIV_CLK(&priv->prc, 3000, 3000);
  __HAL_AUDPRC_DAC_DST_CODEC(&priv->prc);          /* DST_SEL=0, dest_sel=0 */
  priv->prc.dest_sel = 0;

  /* 直通 (源=目标采样率): 关 SRC/HBF, 混音/MUX 取官方例程的 0x5050 造型 */
  priv->prc.Init.dac_cfg.src_ch_en     = 0;
  priv->prc.Init.dac_cfg.src_sinc_en   = 0;
  priv->prc.Init.dac_cfg.sinc_ratio    = 0;
  priv->prc.Init.dac_cfg.src_hbf1_en   = 0;
  priv->prc.Init.dac_cfg.src_hbf2_en   = 0;
  priv->prc.Init.dac_cfg.src_hbf3_en   = 0;
  priv->prc.Init.dac_cfg.vol_l         = 0;
  priv->prc.Init.dac_cfg.vol_r         = 0;
  priv->prc.Init.dac_cfg.mixlsrc0 = 0;  priv->prc.Init.dac_cfg.mixlsrc1 = 5;
  priv->prc.Init.dac_cfg.mixrsrc0 = 0;  priv->prc.Init.dac_cfg.mixrsrc1 = 5;
  priv->prc.Init.dac_cfg.muxlsrc0 = 0;  priv->prc.Init.dac_cfg.muxlsrc1 = 5;
  priv->prc.Init.dac_cfg.muxrsrc0 = 0;  priv->prc.Init.dac_cfg.muxrsrc1 = 5;
  HAL_AUDPRC_Config_DACPath(&priv->prc, &priv->prc.Init.dac_cfg);

  {
    AUDPRC_ChnlCfgTypeDef chnl;
    memset(&chnl, 0, sizeof(chnl));
    chnl.dma_mask = 0;
    chnl.mode     = 0;      /* mono */
    chnl.format   = 0;      /* 16bit */
    chnl.en       = 1;
    HAL_AUDPRC_Config_TChanel(&priv->prc, HAL_AUDPRC_TX_CH0, &chnl);
  }

  /* ── worker 运行期状态复位（线程在 register 时已创建一次） ── */
  priv->halves = 0;
  priv->half_pending = 0;
  priv->drain = 0;              /* AUDIO-FIX：解除 STOP 排空保护 */
  priv->deq_count = 0;
  priv->underruns = 0;

  /* 预填两个半缓冲 (此时 DMA 还没跑) */
  {
    bool starved;
    tx_fill(priv->dma_buf, TX_HALF_BYTES, &starved);
    tx_fill(priv->dma_buf + TX_HALF_BYTES, TX_HALF_BYTES, &starved);
  }

  /* ── 中断 ── */
  ret = irq_attach(AUDPRC_TX0_DMA_IRQ + NVIC_IRQ_FIRST, (xcpt_t)tx_isr, priv);
  if (ret < 0)
    {
      auderr("ERROR: irq_attach failed: %d\n", ret);
      return ret;
    }

  /* ── 启动: 先静音 -> 模拟通路 -> 使能 -> DMA -> 解静音 ── */
  HAL_AUDCODEC_Config_DACPath(&priv->codec, 1);     /* 静音 */
  HAL_AUDCODEC_Config_Analog_DACPath(priv->codec.Init.dac_cfg.dac_clk);
  __HAL_AUDCODEC_DAC_ENABLE(&priv->codec);

  /* v110 P0-3：running 必须在 DMA/中断使能之前置位，否则首个 HT 到达时
   * worker 可能走 !running 分支把 half_pending 清掉（丢半缓冲）。 */
  priv->running = true;

  if (HAL_AUDPRC_Transmit_DMA(&priv->prc, priv->dma_buf, TX_BUF_BYTES,
                              HAL_AUDPRC_TX_CH0) != HAL_OK)
    {
      auderr("ERROR: HAL_AUDPRC_Transmit_DMA failed\n");
      priv->running = false;
      return -EIO;
    }

  priv->half_pending = 0;        /* 使能中断前清一次残留位 */
  up_enable_irq(AUDPRC_TX0_DMA_IRQ + NVIC_IRQ_FIRST);
  __HAL_AUDPRC_ENABLE(&priv->prc);

  tx_pa(1);
  usleep(30 * 1000);
  HAL_AUDCODEC_Config_DACPath(&priv->codec, 0);     /* 解静音 */

  priv->t_start = clock_systime_ticks();
  audinfo("audio tx started (%d Hz, %d ch, %d bit)\n",
          priv->rate, priv->channels, priv->bpsamp);
  return OK;
}

static int tx_stop(FAR struct audio_lowerhalf_s *dev)
{
  FAR struct sf32lb52_tx_s *priv = (FAR struct sf32lb52_tx_s *)dev;

  if (!priv->running)
    {
      return OK;
    }

  priv->running = false;

  HAL_AUDCODEC_Config_DACPath(&priv->codec, 1);     /* 先静音, 防 POP */
  __HAL_AUDPRC_DISABLE(&priv->prc);
  up_disable_irq(AUDPRC_TX0_DMA_IRQ + NVIC_IRQ_FIRST);
  HAL_AUDPRC_DMAStop(&priv->prc, HAL_AUDPRC_TX_CH0);
  HAL_AUDCODEC_Close_Analog_DACPath();
  __HAL_AUDCODEC_DAC_DISABLE(&priv->codec);
  tx_pa(0);

  /* 停 worker：只清运行标志 + 唤醒一次，不销毁线程（见 tx_worker 注释）。 */
  priv->half_pending = 0;
  nxsem_post(&priv->wake);

  /* AUDIO-FIX（2026-11-04 v142k）：置 drain 并等 worker 退出 tx_fill
   * （in_fill==0）再排空，避免本函数 DEQUEUE/apb_free 与 worker 的
   * tx_fill/memcpy 竞态（worker 泄漏仍活着时尤其危险）。上限 100ms，正常 <1ms。 */
  priv->drain = 1;
  for (int i = 0; i < 100 && priv->in_fill; i++)
    {
      usleep(1000);
    }

  /* 队列里没播完的: 交还上层并释放驱动引用 */
  for (; ; )
    {
      FAR struct ap_buffer_s *apb = NULL;

      nxmutex_lock(&priv->lock);
      if (!dq_empty(&priv->pendq))
        {
          apb = (FAR struct ap_buffer_s *)dq_remfirst(&priv->pendq);
          if (priv->pend_depth > 0)
            {
              priv->pend_depth--;
            }
        }
      nxmutex_unlock(&priv->lock);

      if (apb == NULL)
        {
          break;
        }

      priv->dev.upper(priv->dev.priv, AUDIO_CALLBACK_DEQUEUE, apb, OK);
      apb_free(apb);
    }

  /* AUDIO-FIX：cur 也要在锁内摘除再 free（worker 可能刚读到指针） */
  {
    FAR struct ap_buffer_s *apb = NULL;

    nxmutex_lock(&priv->lock);
    apb = priv->cur;
    priv->cur = NULL;
    priv->cur_off = 0;
    nxmutex_unlock(&priv->lock);

    if (apb != NULL)
      {
        priv->dev.upper(priv->dev.priv, AUDIO_CALLBACK_DEQUEUE, apb, OK);
        apb_free(apb);
      }
  }

  {

    uint32_t ticks = clock_systime_ticks() - priv->t_start;

    printf("audio_tx: halves=%u (=%u 半缓冲), underruns=%u, deq=%u, %u ticks\n",

           (unsigned)priv->halves, (unsigned)priv->halves,

           (unsigned)priv->underruns, (unsigned)priv->deq_count,

           (unsigned)ticks);

    printf("audio_tx: 平均 %.0f 半缓冲/秒 (16kHz 播放应为 15.6)\n",
           ticks ? (double)priv->halves * (double)CLK_TCK / (double)ticks : 0.0);

    printf("audio_tx: start=%u irq=%u fills=%u dma_err=%u enq=%u canc=%u q=%u qmax=%u "
           "lockfail=%u fstall_max=%u\n",
           (unsigned)priv->start_calls, (unsigned)priv->irq_count,
           (unsigned)priv->fills, (unsigned)priv->dma_err,
           (unsigned)priv->enq_calls, (unsigned)priv->cancel_calls,
           (unsigned)priv->pend_depth, (unsigned)priv->pend_max,
           (unsigned)priv->lock_fail, (unsigned)priv->fill_stall_max);
  }

  /* ★ v102a 根因修复（S1/S2 确定）：stop 成功后必须回调 COMPLETE，
   * 否则 upper 永远停在 AUDIO_STATE_DRAINING（audio.c:1557-1561 只有
   * COMPLETE 能收敛回 OPEN）→ 下一次 START 返回 OK 却不调 lower->start
   * （audio.c:633）→ DMA 不启动、APB 永不归还、DMA 重播旧数据（"卡住的响"）。
   * 采集侧同坑已修（sf32lb52_audcodec.c:936-944），TX 侧此前漏了。
   * 上下文是 ioctl 调用者（非中断），可安全回调。 */
  priv->dev.upper(priv->dev.priv, AUDIO_CALLBACK_COMPLETE, NULL, OK);
  syslog(LOG_INFO, "[audio_tx] tx_stop done: COMPLETE sent (start=%u irq=%u fills=%u dma_err=%u)\n",
         (unsigned)priv->start_calls, (unsigned)priv->irq_count,
         (unsigned)priv->fills, (unsigned)priv->dma_err);
  return OK;
}

static int tx_enqueuebuffer(FAR struct audio_lowerhalf_s *dev,
                            FAR struct ap_buffer_s *apb)
{
  FAR struct sf32lb52_tx_s *priv = (FAR struct sf32lb52_tx_s *)dev;
  int ret;

  /* AUDIO-FIX：STOP 排空/停止期间拒绝新入队 —— 否则泄漏 worker 在 stop
   * 之后投进来的 APB 会残留到下一句 TTS 的 pendq 里（串音/旧音频）且
   * 可能在下一次 START 被误播。返回 -EAGAIN 让上层 worker 自然退出。 */
  if (priv->drain)
    {
      return -EAGAIN;
    }

  apb_reference(apb);                     /* 驱动自持一份引用 */

  ret = nxmutex_lock(&priv->lock);
  if (ret < 0)
    {
      apb_free(apb);
      return ret;
    }

  apb->flags |= AUDIO_APB_OUTPUT_ENQUEUED;
  dq_addlast(&apb->dq_entry, &priv->pendq);
  priv->enq_calls++;
  priv->pend_depth++;
  if (priv->pend_depth > priv->pend_max)
    {
      priv->pend_max = priv->pend_depth;
    }
  nxmutex_unlock(&priv->lock);

  /* 若 DMA 正在跑且有空半缓冲, 唤醒 worker 立即补数据 */
  if (priv->running && priv->half_pending != 0)
    {
      nxsem_post(&priv->wake);
    }

  return OK;
}

static int tx_cancelbuffer(FAR struct audio_lowerhalf_s *dev,
                           FAR struct ap_buffer_s *apb)
{
  FAR struct sf32lb52_tx_s *priv = (FAR struct sf32lb52_tx_s *)dev;
  FAR struct dq_entry_s *entry;
  int ret = -EINVAL;

  ret = nxmutex_lock(&priv->lock);
  if (ret < 0)
    {
      return ret;
    }

  for (entry = priv->pendq.head; entry != NULL; entry = entry->flink)
    {
      if (entry == &apb->dq_entry)
        {
          dq_rem(&apb->dq_entry, &priv->pendq);
          if (priv->pend_depth > 0)
            {
              priv->pend_depth--;
            }
          priv->cancel_calls++;
          apb_free(apb);                 /* 归还驱动引用 */
          ret = OK;
          break;
        }
    }

  nxmutex_unlock(&priv->lock);
  return ret;
}

static int tx_ioctl(FAR struct audio_lowerhalf_s *dev, int cmd,
                    unsigned long arg)
{
  FAR struct sf32lb52_tx_s *priv = (FAR struct sf32lb52_tx_s *)dev;

  /* 音量不走这里 (NuttX 约定: AUDIO_TYPE_FEATURE/AUDIO_FU_VOLUME -> configure)。
   * 这里只补 core 的默认缓冲几何, 其余交回 core。 */
  if (cmd == AUDIOIOC_GETBUFFERINFO)
    {
      FAR struct ap_buffer_info_s *info =
        (FAR struct ap_buffer_info_s *)((uintptr_t)arg);

      if (info != NULL)
        {
          info->buffer_size = TX_APB_BYTES;
          info->nbuffers    = TX_APB_NUM;
        }

      return OK;
    }

  return -ENOTTY;
}

static void tx_shutdown(FAR struct audio_lowerhalf_s *dev)
{
  FAR struct sf32lb52_tx_s *priv = (FAR struct sf32lb52_tx_s *)dev;

  /* 非阻塞: 只静音 + 关 PA, 不等待、不回调 (与采集侧同样的策略) */
  HAL_AUDCODEC_Config_DACPath(&priv->codec, 1);
  tx_pa(0);
}

static const struct audio_ops_s g_tx_ops =
{
  .getcaps       = tx_getcaps,
  .configure     = tx_configure,
  .shutdown      = tx_shutdown,
  .start         = tx_start,
  .stop          = tx_stop,
  .enqueuebuffer = tx_enqueuebuffer,
  .cancelbuffer  = tx_cancelbuffer,
  .ioctl         = tx_ioctl,
};

/****************************************************************************
 * Public Functions
 ****************************************************************************/

int sf32lb52_audio_tx_register(void)
{
  FAR struct sf32lb52_tx_s *priv = &g_tx;
  int ret;

  memset(priv, 0, sizeof(*priv));
  priv->dev.ops = &g_tx_ops;
  priv->rate    = TX_DEFAULT_RATE;
  priv->channels = TX_DEFAULT_CHS;
  priv->bpsamp  = TX_DEFAULT_BITS;
  priv->volume  = 0;                     /* 0 dB */

  nxmutex_init(&priv->lock);
  dq_init(&priv->pendq);

  /* 常驻 TX worker：注册时建一次，stop/start 只改运行标志。 */
  nxsem_init(&priv->wake, 0, 0);
  nxsem_init(&priv->done, 0, 0);
  priv->worker_run = true;

  ret = kthread_create("audcodec_tx", TX_WORKER_PRIO, TX_WORKER_STACK,
                       tx_worker, NULL);
  if (ret < 0)
    {
      auderr("ERROR: kthread_create failed: %d\n", ret);
      return ret;
    }

  ret = audio_register(CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME, &priv->dev);
  if (ret < 0)
    {
      auderr("ERROR: audio_register(%s) failed: %d\n",
             CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME, ret);
      return ret;
    }

  audinfo("registered /dev/audio/%s (AUDPRC -> AUDCODEC -> PA)\n",
          CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME);
  return OK;
}
