/****************************************************************************
 * audio_setup_main.c - 注册 SF32LB52 板载音频设备 (/dev/audio0)
 *
 * NSH 应用: audio_setup
 * 在 ai_agent 或 nxrecorder 使用麦克风前运行一次即可 (设备注册后常驻)。
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/ioctl.h>

#include <nuttx/audio/audio.h>

#include "sf32lb52_audcodec.h"

/****************************************************************************
 * Public Functions
 ****************************************************************************/

int main(int argc, FAR char *argv[])
{
  int ret;
  int fd;
  struct audio_caps_s caps;

  printf("audio_setup: registering SF32LB52 audcodec...\n");

  ret = sf32lb52_audcodec_register();
  if (ret < 0)
    {
      printf("audio_setup: register failed: %d\n", ret);
      return ret;
    }

  /* 验证设备可打开 */
  fd = open("/dev/audio/" CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME, O_RDONLY);
  if (fd < 0)
    {
      printf("audio_setup: open /dev/audio/%s failed: %d\n",
             CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME, errno);
      return -errno;
    }

  memset(&caps, 0, sizeof(caps));
  caps.ac_type = AUDIO_TYPE_QUERY;
  ret = ioctl(fd, AUDIOIOC_GETCAPS, &caps);
  if (ret < 0)
    {
      printf("audio_setup: GETCAPS failed: %d\n", errno);
    }
  else
    {
      printf("audio_setup: device caps OK (input=%d output=%d)\n",
             caps.ac_controls.b[0], caps.ac_controls.b[1]);
    }

  close(fd);
  printf("audio_setup: /dev/audio/%s ready (mic capture 16kHz/16bit/mono)\n",
         CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME);

#ifdef CONFIG_EXAMPLES_AUDIO_SETUP_TX
  /* 播放设备: AUDPRC -> AUDCODEC DAC -> 板载 PA (见 docs_ble/30 §10/§11) */
  ret = sf32lb52_audio_tx_register();
  if (ret < 0)
    {
      printf("audio_setup: playback register failed: %d\n", ret);
      return ret;
    }

  fd = open("/dev/audio/" CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME, O_WRONLY);
  if (fd < 0)
    {
      printf("audio_setup: open /dev/audio/%s failed: %d\n",
             CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME, errno);
      return -errno;
    }

  memset(&caps, 0, sizeof(caps));
  caps.ac_type = AUDIO_TYPE_QUERY;
  ret = ioctl(fd, AUDIOIOC_GETCAPS, &caps);
  if (ret >= 0)
    {
      printf("audio_setup: playback caps OK (output=%d)\n",
             caps.ac_controls.b[1]);
    }
  close(fd);
  printf("audio_setup: /dev/audio/%s ready (speaker 16kHz/16bit/mono)\n",
         CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME);
#endif

  return 0;
}
