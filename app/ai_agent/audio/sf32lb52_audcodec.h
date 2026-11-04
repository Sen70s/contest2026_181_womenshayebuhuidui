/****************************************************************************
 * sf32lb52_audcodec.h - SF32LB52 内置音频编解码器驱动接口
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#ifndef SF32LB52_AUDCODEC_H
#define SF32LB52_AUDCODEC_H

/****************************************************************************
 * Public Function Prototypes
 ****************************************************************************/

/**
 * 注册 audcodec 为 NuttX 音频设备 /dev/audio0 (麦克风采集)。
 *
 * @return 0 成功, 负值失败
 */
int sf32lb52_audcodec_register(void);

/**
 * 注册板载扬声器播放设备 (AUDPRC -> AUDCODEC DAC -> 板载 PA)。
 *
 * 设备名由 CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME 决定, 默认 /dev/audio/pcm0p
 * (与 ai_agent 的 AGENT_AUDIO_PLAYBACK_DEV 一致)。
 *
 * @return 0 成功, 负值失败
 */
int sf32lb52_audio_tx_register(void);

#endif /* SF32LB52_AUDCODEC_H */
