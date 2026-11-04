/****************************************************************************
 * audio_play_main.c - 播放 16bit 单声道 PCM 文件到 /dev/audio/pcm0p
 *
 * 用途: media 框架在本工作区不可用 (frameworks/multimedia/media 需要
 * cutils/properties.h), 所以产品侧直接写音频设备。上层 (TTS/提示音) 把
 * PCM 落到文件后调本命令即可出声; 也可作为 audio_playback 的替代后端。
 *
 * 用法: audio_play <file.pcm> [采样率=16000]
 *      audio_play tone [秒=3]      (内置 1kHz 自测音, 不读文件)
 *
 * 依赖: audio_setup 已注册 /dev/audio/pcm0p (CONFIG_EXAMPLES_AUDIO_SETUP_TX)
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <errno.h>
#include <fcntl.h>
#include <mqueue.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include <nuttx/audio/audio.h>

#define PLAY_DEV_PATH   "/dev/audio/" CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME
#define PLAY_MQ_NAME    "/audio_play_q"
#define NBUFS           8

int main(int argc, FAR char *argv[])
{
  struct audio_caps_s caps;
  struct audio_buf_desc_s desc;
  struct ap_buffer_info_s binfo;
  struct mq_attr attr;
  struct ap_buffer_s *bufs[NBUFS];
  const char *path = (argc > 1) ? argv[1] : "tone";
  int rate = (argc > 2) ? atoi(argv[2]) : 16000;
  int tone = (strcmp(path, "tone") == 0);
  int tone_secs = (argc > 2) ? atoi(argv[2]) : 3;
  int fd = -1;
  int afd = -1;
  mqd_t mq = (mqd_t)-1;
  int nbufs = 0;
  int i;
  int eof = 0;
  uint32_t phase = 0;
  unsigned long total = 0;
  struct ap_buffer_s *last = NULL;   /* 最后一个入队的 apb: 它回来 = 播完 */
  int idle = 0;                      /* 连续超时次数 */

  static const int16_t sine16[16] =
    { 0, 4909, 9239, 12000, 12000, 9239, 4909, 0,
      -4909, -9239, -12000, -12000, -9239, -4909, 0, 0 };

  memset(bufs, 0, sizeof(bufs));

  if (!tone)
    {
      fd = open(path, O_RDONLY);
      if (fd < 0)
        {
          printf("audio_play: open %s failed: %d\n", path, errno);
          return 1;
        }
    }
  else
    {
      rate = 16000;
      if (tone_secs <= 0 || tone_secs > 60) tone_secs = 3;
    }

  afd = open(PLAY_DEV_PATH, O_WRONLY);
  if (afd < 0)
    {
      printf("audio_play: open %s failed: %d (先跑 audio_setup)\n",
             PLAY_DEV_PATH, errno);
      goto err;
    }

  memset(&caps, 0, sizeof(caps));
  caps.ac_len            = sizeof(caps);
  caps.ac_type           = AUDIO_TYPE_OUTPUT;
  caps.ac_subtype        = AUDIO_FMT_PCM;
  caps.ac_channels       = 1;
  caps.ac_controls.hw[0] = rate;
  caps.ac_controls.b[2]  = 16;
  if (ioctl(afd, AUDIOIOC_CONFIGURE, (uintptr_t)&caps) < 0)
    {
      printf("audio_play: CONFIGURE failed: %d\n", errno);
      goto err;
    }

  memset(&binfo, 0, sizeof(binfo));
  if (ioctl(afd, AUDIOIOC_GETBUFFERINFO, (uintptr_t)&binfo) != OK ||
      binfo.buffer_size <= 0)
    {
      binfo.buffer_size = 4096;
      binfo.nbuffers    = NBUFS;
    }

  if (binfo.nbuffers > NBUFS) binfo.nbuffers = NBUFS;

  memset(&attr, 0, sizeof(attr));
  attr.mq_maxmsg  = 16;
  attr.mq_msgsize = sizeof(struct audio_msg_s);
  mq = mq_open(PLAY_MQ_NAME, O_RDWR | O_CREAT, 0666, &attr);
  if (mq == (mqd_t)-1)
    {
      printf("audio_play: mq_open failed: %d\n", errno);
      goto err;
    }

  if (ioctl(afd, AUDIOIOC_REGISTERMQ, (uintptr_t)mq) < 0)
    {
      printf("audio_play: REGISTERMQ failed: %d\n", errno);
      goto err;
    }

  /* 预填: 从文件/自测音取数据, 填满 nbufs 后 START */
  for (i = 0; i < binfo.nbuffers; i++)
    {
      struct audio_buf_desc_s d;

      memset(&d, 0, sizeof(d));
      d.numbytes  = binfo.buffer_size;
      d.u.pbuffer = &bufs[i];
      if (ioctl(afd, AUDIOIOC_ALLOCBUFFER, (uintptr_t)&d) != sizeof(d))
        {
          printf("audio_play: ALLOCBUFFER %d failed\n", i);
          goto err;
        }

      nbufs++;
    }

  for (i = 0; i < nbufs; i++)
    {
      struct ap_buffer_s *apb = bufs[i];
      int want = apb->nmaxbytes;
      int got;

      if (eof)
        {
          got = 0;
        }
      else if (tone)
        {
          int16_t *s = (int16_t *)apb->samp;
          int n = want / 2;
          int k;

          for (k = 0; k < n; k++)
            {
              s[k] = sine16[phase & 15];
              phase++;
            }

          got = want;
          if ((unsigned long)(phase * 2) >= (unsigned long)tone_secs * rate * 2)
            {
              eof = 1;
            }
        }
      else
        {
          got = read(fd, apb->samp, want);
          if (got < 0)
            {
              printf("audio_play: read failed: %d\n", errno);
              goto err;
            }

          if (got < want)
            {
              eof = 1;
            }
        }

      if (got == 0)
        {
          break;
        }

      apb->nbytes  = got;
      apb->curbyte = 0;
      last = apb;

      {
        struct audio_buf_desc_s d;
        d.u.buffer = apb;
        d.numbytes = got;
        if (ioctl(afd, AUDIOIOC_ENQUEUEBUFFER, (uintptr_t)&d) < 0)
          {
            printf("audio_play: ENQUEUE %d failed: %d\n", i, errno);
            goto err;
          }
      }
    }

  if (ioctl(afd, AUDIOIOC_START) < 0)
    {
      printf("audio_play: START failed: %d\n", errno);
      goto err;
    }

  printf("audio_play: %s @ %d Hz -> %s\n",
         tone ? "tone" : path, rate, PLAY_DEV_PATH);

  /* 播放循环: DEQUEUE 回来后继续喂, 直到文件/自测音结束且队列排空 */
  for (; ; )
    {
      struct audio_msg_s msg;
      struct ap_buffer_s *apb;
      unsigned int prio;
      struct timespec ts;
      int want, got;

      /* 带超时: 万一框架不再回 DEQUEUE (或驱动异常), 也绝不卡死控制台 */
      clock_gettime(CLOCK_REALTIME, &ts);
      ts.tv_sec += 3;
      if (mq_timedreceive(mq, (char *)&msg, sizeof(msg), &prio, &ts) <= 0)
        {
          if (++idle >= 2)
            {
              printf("audio_play: 空闲超时, 收尾退出\n");
              break;
            }
          continue;
        }

      idle = 0;

      if (msg.msg_id != AUDIO_MSG_DEQUEUE || msg.u.ptr == NULL)
        {
          continue;
        }

      apb = msg.u.ptr;

      if (eof)
        {
          /* 已到结尾: 只等最后那只缓冲回来就收工 (不依赖 flags, 避免死等) */
          if (apb == last)
            {
              break;
            }

          continue;
        }

      want = apb->nmaxbytes;

      if (tone)
        {
          int16_t *s = (int16_t *)apb->samp;
          int n = want / 2;
          int k;

          for (k = 0; k < n; k++)
            {
              s[k] = sine16[phase & 15];
              phase++;
            }

          got = want;
          if ((unsigned long)(phase * 2) >= (unsigned long)tone_secs * rate * 2)
            {
              eof = 1;
            }
        }
      else
        {
          got = read(fd, apb->samp, want);
          if (got < 0)
            {
              printf("audio_play: read failed: %d\n", errno);
              break;
            }

          if (got < want)
            {
              eof = 1;
            }
        }

      total += got;

      if (got > 0)
        {
          struct audio_buf_desc_s d;
          apb->nbytes  = got;
          apb->curbyte = 0;
          d.u.buffer = apb;
          d.numbytes = got;
          last = apb;
          if (ioctl(afd, AUDIOIOC_ENQUEUEBUFFER, (uintptr_t)&d) < 0)
            {
              printf("audio_play: re-enqueue failed: %d\n", errno);
              break;
            }
        }
    }

  ioctl(afd, AUDIOIOC_STOP, 0);
  printf("audio_play: done, %lu bytes\n", total);

err:
  for (i = 0; i < nbufs; i++)
    {
      if (bufs[i] != NULL && afd >= 0)
        {
          struct audio_buf_desc_s d;
          d.u.buffer = bufs[i];
          ioctl(afd, AUDIOIOC_FREEBUFFER, (uintptr_t)&d);
        }
    }

  if (afd >= 0)
    {
      ioctl(afd, AUDIOIOC_UNREGISTERMQ);
      close(afd);
    }

  if (mq != (mqd_t)-1)
    {
      mq_close(mq);
      mq_unlink(PLAY_MQ_NAME);
    }

  if (fd >= 0) close(fd);
  return 0;
}
