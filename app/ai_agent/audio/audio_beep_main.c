/****************************************************************************
 * audio_beep_main.c - SF32LB52 扬声器最小验证 (蜂鸣)
 *
 * 目的: 回答"喇叭/功放/走线到底有没有声音"。不走 NuttX audio 框架
 * (驱动目前只有采集 RX 路径), 直接调 SiFli HAL 驱动 audcodec DAC:
 *
 *   HAL_PMU_EnableAudio(1) / HAL_RCC_EnableModule(RCC_MOD_AUDCODEC)
 *   -> HAL_AUDCODEC_Init(&codec)            (DMA 句柄要先挂好)
 *   -> bf0_enable_pll(rate, 1) / HAL_TURN_ON_PLL()
 *   -> HAL_AUDCODEC_Config_DACPath(&codec, 0)
 *   -> HAL_AUDCODEC_Config_DACPath_Volume(&codec, 0, vol)
 *   -> HAL_AUDCODEC_Config_Analog_DACPath(&codec.Init.dac_cfg.dac_clk)
 *   -> 拉高 PA10 (AUDIO_PA_CTRL, 板载 Class-D PA 使能)
 *   -> HAL_AUDCODEC_Transmit_DMA(&codec, buf, size, HAL_AUDCODEC_DAC_CH0) 循环
 *   -> 播放 N 秒 -> HAL_AUDCODEC_DMAStop() -> PA 拉低 -> 关 DAC/PLL
 *
 * DMA: DMA2_Channel1 + AUDCODEC_DAC0_DMA_REQUEST(41)。DAC 没有预置的
 *      instance/IRQ 宏 (dma_config.h 只给了 request), 本测试不注册 IRQ,
 *      用定时 + DMAStop 收尾。
 *
 * 用法: audio_beep [秒数=3] [频率Hz=1000] [音量dB=-10]
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <math.h>

#include <nuttx/cache.h>        /* up_clean_dcache: D-cache 打开时 DMA 前必须写回 */

/* SiFli HAL (与 arch / audio_setup 构建同一套宏与头文件, 见 CMakeLists) */
#define SOC_BF0_HCPU
#define SF32LB52X
#include "bf0_hal.h"
#include "dma_config.h"

/****************************************************************************
 * Pre-processor Definitions
 ****************************************************************************/

#define BEEP_RATE        16000   /* 采样率 Hz (与采集路径一致) */
#define BEEP_BITS        16
#define BEEP_BUF_BYTES   4096    /* 循环 DMA 缓冲 (2048 样点 = 128ms @16k) */
#define BEEP_PA_PIN      10         /* AUDIO_PA_CTRL = PA10 = hwp_gpio1 的 pin index 10
                                     * (HAL_GPIO_WritePin 收的是端口内 pin index,
                                     *  见 bf0_hal_gpio.c:379 与 sifli_gpio.c:471) */

/****************************************************************************
 * Private Data
 ****************************************************************************/

/* 循环 DMA 缓冲必须按 cache 行对齐 (D-cache 打开时 DMA 读的是内存) */
static int16_t g_buf[BEEP_BUF_BYTES / 2] __attribute__((aligned(32)));

static AUDCODEC_HandleTypeDef g_codec;
static DMA_HandleTypeDef      g_dma_tx;

/* ★ 采样率时钟表: 取自官方 SDK `drv_audcodec_m.c` 的
 *   `codec_dac_clk_config_pll[3]` = 16 kHz 项 (字段含义见
 *   vendor/sifli/chips/drivers/Include/bf0_hal_audcodec.h:71-87):
 *
 *     {48000, 1, 1, 0, SINC_GAIN, 1,  5, 4, 2, 20, 20, 0},
 *     {32000, 1, 1, 1, SINC_GAIN, 1,  5, 4, 2, 20, 20, 0},
 *     {24000, 1, 1, 5, SINC_GAIN, 1, 10, 2, 2, 10, 10, 1},
 *     {16000, 1, 1, 4, SINC_GAIN, 1,  5, 4, 2, 20, 20, 0},   <-- 本项
 *     ...
 *   SINC_GAIN = 0x14D (AVDD 3.3V) / 0xa0 (1.8V)
 *
 *   顺带交叉验证: 同一处的 `codec_adc_clk_config_pll[3]` =
 *   {16000, 1, 10, 1, 1, 0, 5, 2} 与采集驱动用的表逐字段一致 ——
 *   即 docs_ble/28 §7.4 里"无法交叉验证的时钟表"现在得到官方源码确认。 */
static AUDCODE_DAC_CLK_CONFIG_TYPE g_dac_clk_16k =
  {
    16000,   /* samplerate */
    1,       /* clk_src_sel   (1 = PLL) */
    1,       /* clk_div */
    4,       /* osr_sel */
    0x14D,   /* sinc_gain     (AVDD 3.3V) */
    1,       /* sel_clk_dac_source */
    5,       /* diva_clk_dac */
    4,       /* diva_clk_chop_dac */
    2,       /* divb_clk_chop_dac */
    20,      /* diva_clk_chop_bg */
    20,      /* diva_clk_chop_refgen */
    0        /* sel_clk_dac (SF32LB52X) */
  };

static AUDCODE_ADC_CLK_CONFIG_TYPE g_adc_clk_unused =
  { 16000, 1, 10, 1, 1, 0, 5, 2 };

/****************************************************************************
 * Private Functions
 ****************************************************************************/

static void beep_fill_sine(int freq)
{
  int n = BEEP_BUF_BYTES / 2;
  int i;

  for (i = 0; i < n; i++)
    {
      double ph = 2.0 * 3.14159265358979 * (double)freq * (double)i / (double)BEEP_RATE;
      g_buf[i] = (int16_t)(sin(ph) * 12000.0);   /* ~ -8.7 dBFS, 防削波 */
    }

  /* DMA 读之前把 cache 里的数据写回内存 */
  up_clean_dcache((uintptr_t)g_buf, (uintptr_t)g_buf + BEEP_BUF_BYTES);
}

static void beep_pa(int on)
{
  HAL_PIN_Set(PAD_PA10, GPIO_A10, PIN_NOPULL, 1);
  HAL_GPIO_WritePin((GPIO_TypeDef *)hwp_gpio1, BEEP_PA_PIN,
                    on ? GPIO_PIN_SET : GPIO_PIN_RESET);
}

/****************************************************************************
 * Public Functions
 ****************************************************************************/

int main(int argc, FAR char *argv[])
{
  int seconds = (argc > 1) ? atoi(argv[1]) : 3;
  int freq    = (argc > 2) ? atoi(argv[2]) : 1000;
  int vol     = (argc > 3) ? atoi(argv[3]) : -2;   /* 0.5dB 单位 → -1 dB */
  const char *mode = (argc > 4) ? argv[4] : "cpu";
  int i;

  if (seconds <= 0 || seconds > 60) seconds = 3;
  if (freq <= 0 || freq > 8000) freq = 1000;
  if (vol < -36 || vol > 54) vol = -2;    /* HAL 单位: 0.5 dB, 范围 -36..54 */

  printf("beep: %d s @ %d Hz, vol=%d dB, buf=%d B, PA=PA10\n",
         seconds, freq, vol, BEEP_BUF_BYTES);

  memset(&g_codec, 0, sizeof(g_codec));
  memset(&g_dma_tx, 0, sizeof(g_dma_tx));
  beep_fill_sine(freq);

  /* ── 时钟/电源 + DAC 通路 (参考 SiFli SDK bf0_audio / drv_audcodec_m) ── */
  HAL_PMU_EnableAudio(1);
  HAL_RCC_EnableModule(RCC_MOD_AUDCODEC);

  g_dma_tx.Instance = DMA2_Channel1;
  g_dma_tx.Init.Request = AUDCODEC_DAC0_DMA_REQUEST;

  g_codec.Instance = hwp_audcodec;
  g_codec.hdma[HAL_AUDCODEC_DAC_CH0] = &g_dma_tx;
  g_codec.Init.en_dly_sel = 0;
  g_codec.Init.dac_cfg.opmode = 1;      /* 1 = mem -> codec (DMA 从内存喂) */
  g_codec.Init.dac_cfg.dac_clk = &g_dac_clk_16k;
  g_codec.Init.adc_cfg.opmode = 1;
  g_codec.Init.adc_cfg.adc_clk = &g_adc_clk_unused;

  if (HAL_AUDCODEC_Init(&g_codec) != HAL_OK)
    {
      printf("beep: HAL_AUDCODEC_Init failed\n");
      return 1;
    }

  bf0_enable_pll(BEEP_RATE, 1);
  HAL_TURN_ON_PLL();

  /* ★ 关键: 数字 DAC 通道必须由 Config_TChanel() 配置 —— 它才写
   *   DAC_CFG(osr/clk) 与 DAC_CH0_CFG(ENABLE=1 / DEM_MODE / DATA_FORMAT
   *   =16bit / SINC_GAIN=dac_clk->sinc_gain)。少了这一步, 通道 ENABLE=0,
   *   DMA 搬数据也没有输出 (第一版就踩了这个坑: 真机读回 DAC_CH0_CFG
   *   =0x0c002584 → ENABLE=0/DMA_EN=0/DATA_FORMAT=0)。
   *   顺序照官方 SDK drv_audcodec_m.c: Config_TChanel → Volume → DMA
   *   → DAC_ENABLE → Config_DACPath(解除 mute) → Analog_DACPath。 */
  if (HAL_AUDCODEC_Config_TChanel(&g_codec, 0, &g_codec.Init.dac_cfg) != HAL_OK)
    {
      printf("beep: Config_TChanel failed\n");
      return 1;
    }

  /* 音量单位是 0.5 dB, 合法范围 -36..54 (HAL 内部 <<1 处理) */
  if (HAL_AUDCODEC_Config_DACPath_Volume(&g_codec, 0, vol) != HAL_OK)
    {
      printf("beep: Config_DACPath_Volume(%d) failed\n", vol);
      return 1;
    }

  /* 官方 HAL 文档的播放顺序: 先静音 -> 配模拟通路 -> 使能 -> 最后解静音
   * (docs/source/zh_CN/hal/audcodec.md), 避免 POP。 */
  HAL_AUDCODEC_Config_DACPath(&g_codec, 1);          /* 1 = DOUT_MUTE */
  HAL_AUDCODEC_Config_Analog_DACPath(g_codec.Init.dac_cfg.dac_clk);
  __HAL_AUDCODEC_DAC_ENABLE(&g_codec);               /* enable at last */
  HAL_AUDCODEC_Config_DACPath(&g_codec, 0);          /* 0 = 解静音 */

  if (strcmp(mode, "cpu") == 0 || strcmp(mode, "cpu1") == 0)
    {
      /* ── CPU 直喂 DAC FIFO (不使用 DMA) ──
       * 52x 官方没有 AUDCODEC DAC0 的 DMA 实例/IRQ (dma_config.h 只有
       * REQUEST_41), 官方链路是 AUDPRC->AUDCODEC->喇叭。本模式绕开 DMA,
       * 直接写 DAC_CH0_ENTRY, 并把 FIFO 计数当作"DAC 是否在消费"的探针:
       * FIFO 深度 8, 写满后循环会被 DAC 的 16kHz 消费速率自然限速。 */
      uint32_t total = (uint32_t)BEEP_RATE * (uint32_t)seconds;
      uint32_t written = 0;
      int      ch = (strcmp(mode, "cpu1") == 0) ? 1 : 0;
      uint32_t t0, overrun = 0;

      /* 目标通道 (板载 PA 的输入是模组 pin33/34 = AU_DAC1P/N, 名字里的 "DAC1"
       * 对应哪个数字通道需实测: cpu 用 CH0, cpu1 用 CH1) */
      if (ch == 1)
        {
          if (HAL_AUDCODEC_Config_TChanel(&g_codec, 1, &g_codec.Init.dac_cfg) != HAL_OK)
            {
              printf("beep: Config_TChanel(1) failed\n");
              return 1;
            }
          HAL_AUDCODEC_Config_DACPath_Volume(&g_codec, 1, vol);
        }

      beep_pa(1);                       /* 开功放, 等它稳定 (官方 ~100ms) */
      usleep(100 * 1000);

      printf("beep[cpu]: CH%d, 写 %u 个样点, 8 样点/批 + 480us 节拍\n",
             ch, (unsigned)total);
      t0 = clock_systime_ticks();
      while (written < total)
        {
          int k;

          /* 一批 8 个 (FIFO 深度 8) */
          for (k = 0; k < 8 && written < total; k++, written++)
            {
              int16_t s = g_buf[written % (BEEP_BUF_BYTES / 2)];
              if (ch == 1)
                {
                  hwp_audcodec->DAC_CH1_ENTRY = (uint32_t)(uint16_t)s;
                }
              else
                {
                  hwp_audcodec->DAC_CH0_ENTRY = (uint32_t)(uint16_t)s;
                }
            }

          if ((hwp_audcodec->APB_STAT & 0xF) >= 8)
            {
              overrun++;   /* 还满着 = 我们喂快了 (DAC 比 16kHz 慢) */
            }

          HAL_Delay_us(480);            /* 8 样点 @16kHz = 500us */
        }
      {
        uint32_t dt = clock_systime_ticks() - t0;
        printf("beep[cpu]: done, %u 样点, %u ticks, FIFO 满批次数=%u\n",
               (unsigned)written, (unsigned)dt, (unsigned)overrun);
        printf("beep[cpu]: 实际速率约 %.0f Hz (目标 16000)\n",
               dt ? (double)written * (double)CLK_TCK / (double)dt : 0.0);
      }

      HAL_AUDCODEC_Config_DACPath(&g_codec, 1);       /* 静音收尾 */
      __HAL_AUDCODEC_DAC_DISABLE(&g_codec);
      HAL_AUDCODEC_Close_Analog_DACPath();
      HAL_TURN_OFF_PLL();
      HAL_PMU_EnableAudio(0);
      beep_pa(0);
      return 0;
    }

  beep_pa(1);                       /* 开功放 (DMA 模式) */
  usleep(100 * 1000);

  if (HAL_AUDCODEC_Transmit_DMA(&g_codec, (uint8_t *)g_buf, BEEP_BUF_BYTES,
                                HAL_AUDCODEC_DAC_CH0) != HAL_OK)
    {
      printf("beep: Transmit_DMA failed\n");
      return 1;
    }

  printf("beep: playing %d s ... 请听喇叭\n", seconds);

  /* 分 3 段响/停, 便于人耳判别 (总时长 ≈ seconds) */
  for (i = 0; i < 3; i++)
    {
      beep_pa(1);
      usleep((seconds * 1000000) / 6);
      beep_pa(0);
      usleep((seconds * 1000000) / 6);
    }

  HAL_AUDCODEC_DMAStop(&g_codec, HAL_AUDCODEC_DAC_CH0);
  __HAL_AUDCODEC_DAC_DISABLE(&g_codec);
  HAL_AUDCODEC_Close_Analog_DACPath();
  HAL_TURN_OFF_PLL();
  HAL_PMU_EnableAudio(0);
  beep_pa(0);

  printf("beep: done\n");
  return 0;
}