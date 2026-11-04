/****************************************************************************
 * audio_play_test_main.c - 扬声器播放验收 (NuttX audio 框架 -> AUDPRC 播放)
 *
 * 流程与 audio_rec_test 对偶 (见 docs_ble/30 §10/§11):
 *   open(/dev/audio/pcm0p) -> CONFIGURE(16k/16bit/mono) -> REGISTERMQ
 *   -> ALLOCBUFFER x8 -> 填 1kHz 正弦 -> ENQUEUE x8 -> START
 *   -> mq_receive(DEQUEUE) -> 重新填下一段 -> 再 ENQUEUE
 *   -> 播够 N 秒 -> STOP -> 释放
 *
 * 用法: audio_play_test [秒数=3] [频率Hz=1000]
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <mqueue.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include <nuttx/audio/audio.h>

#define PLAY_DEV_PATH   "/dev/audio/pcm0p"
#define PLAY_MQ_NAME    "/play_test_q"
#define NBUFS           8
#define RATE            16000
#define BUFBYTES        4096

static struct ap_buffer_s *g_bufs[NBUFS];
static int g_nbufs;
static uint32_t g_phase;

/* 1kHz @16kHz = 正好 16 样点一个周期; 用查表代替逐样点 sin() ——
 * 逐样点 sin() 太慢会把 re-enqueue 拖成瓶颈 (实测只有 ~2 apb/s)。 */
static void fill_tone(struct ap_buffer_s *apb, int freq)
{
  static const int16_t sine16[16] =
    { 0, 4909, 9239, 12000, 12000, 9239, 4909, 0,
      -4909, -9239, -12000, -12000, -9239, -4909, 0, 0 };
  int16_t *s = (int16_t *)apb->samp;
  int n = apb->nmaxbytes / 2;
  int i;

  (void)freq;                       /* 本版固定 1kHz 表 */

  for (i = 0; i < n; i++)
    {
      s[i] = sine16[g_phase & 15];
      g_phase++;
    }

  apb->nbytes  = apb->nmaxbytes;
  apb->curbyte = 0;
}

int main(int argc, FAR char *argv[])
{
  int seconds = (argc > 1) ? atoi(argv[1]) : 3;
  int freq    = (argc > 2) ? atoi(argv[2]) : 1000;
  struct audio_caps_s caps;
  struct audio_buf_desc_s desc;
  mqd_t mq;
  int afd;
  int i;
  int total = 0;
  int deqs = 0;
  long target;

  if (seconds <= 0 || seconds > 60) seconds = 3;
  if (freq <= 0 || freq > 8000) freq = 1000;
  target = (long)seconds * RATE * 2;

  /* 静态变量在 NuttX 平铺构建下不会随任务重建清零 (见 audio_rec_test 的教训) */
  g_nbufs = 0;
  g_phase = 0;
  memset(g_bufs, 0, sizeof(g_bufs));

  printf("play_test: %d s @ %d Hz -> %s\n", seconds, freq, PLAY_DEV_PATH);

  afd = open(PLAY_DEV_PATH, O_WRONLY);
  if (afd < 0)
    {
      printf("play_test: open failed: %d\n", errno);
      return 1;
    }

  memset(&caps, 0, sizeof(caps));
  caps.ac_len     = sizeof(caps);
  caps.ac_type    = AUDIO_TYPE_OUTPUT;
  caps.ac_subtype = AUDIO_FMT_PCM;
  caps.ac_channels = 1;
  caps.ac_controls.hw[0] = RATE;
  caps.ac_controls.b[2]  = 16;
  if (ioctl(afd, AUDIOIOC_CONFIGURE, (uintptr_t)&caps) < 0)
    {
      printf("play_test: CONFIGURE failed: %d\n", errno);
      goto err_close;
    }

  /* 必须先查 GETBUFFERINFO: audio core 用它设定 upper->nbuffers,
   * 否则 audio_allocbuffer() 会因 periods >= nbuffers(0) 直接返回 0,
   * 客户端看到的就是只有 sizeof(desc) 才算成功的 ALLOCBUFFER 失败。 */
  {
    struct ap_buffer_info_s binfo;

    memset(&binfo, 0, sizeof(binfo));
    if (ioctl(afd, AUDIOIOC_GETBUFFERINFO, (uintptr_t)&binfo) != OK)
      {
        binfo.buffer_size = BUFBYTES;
        binfo.nbuffers    = NBUFS;
      }

    printf("play_test: buffer info %d x %d\n", binfo.nbuffers,
           binfo.buffer_size);
  }

  /* 音量 (NuttX 约定: AUDIO_TYPE_FEATURE / AUDIO_FU_VOLUME) */
  {
    struct audio_caps_s vcaps;

    memset(&vcaps, 0, sizeof(vcaps));
    vcaps.ac_len = sizeof(vcaps);
    vcaps.ac_type = AUDIO_TYPE_FEATURE;
    vcaps.ac_format.hw = AUDIO_FU_VOLUME;
    vcaps.ac_controls.hw[0] = 0;      /* 0 dB */
    if (ioctl(afd, AUDIOIOC_CONFIGURE, (uintptr_t)&vcaps) < 0)
      {
        printf("play_test: volume set failed: %d (继续)\n", errno);
      }
  }

  /* mq 属性必须两头对齐 (采集侧踩过同样的坑, 见 audio_rec_test_main.c):
   *  - mq_msgsize 不能超过本树的 MQ_MAX_BYTES = CONFIG_MQ_MAXMSGSIZE = 32,
   *    否则 nxmq_alloc_msgq() 直接 DEBUGASSERT (mq_msgqalloc.c:76);
   *  - 也不能大于接收缓冲: mq_receive 校验 "缓冲 < maxmsgsize -> EMSGSIZE(90)"
   *    (mq_receive.c:98);
   *  - 传 NULL attr 时队列取 CONFIG_MQ_MAXMSGSIZE=32, 收 8 字节消息同样 EMSGSIZE。
   * 照采集侧做法: mq_msgsize = sizeof(struct audio_msg_s), 缓冲同尺寸。 */
  {
    struct mq_attr attr;

    memset(&attr, 0, sizeof(attr));
    attr.mq_maxmsg  = 16;
    attr.mq_msgsize = sizeof(struct audio_msg_s);
    mq = mq_open(PLAY_MQ_NAME, O_RDWR | O_CREAT, 0666, &attr);
  }
  if (mq == (mqd_t)-1)
    {
      printf("play_test: mq_open failed: %d\n", errno);
      goto err_close;
    }

  if (ioctl(afd, AUDIOIOC_REGISTERMQ, (uintptr_t)mq) < 0)
    {
      printf("play_test: REGISTERMQ failed: %d\n", errno);
      goto err_mq;
    }

  for (i = 0; i < NBUFS; i++)
    {
      memset(&desc, 0, sizeof(desc));
      desc.numbytes  = BUFBYTES;
      desc.u.pbuffer = &g_bufs[i];
      if (ioctl(afd, AUDIOIOC_ALLOCBUFFER, (uintptr_t)&desc) != sizeof(desc))
        {
          printf("play_test: ALLOCBUFFER %d failed\n", i);
          goto err_bufs;
        }
      g_nbufs++;
      fill_tone(g_bufs[i], freq);
      desc.u.buffer = g_bufs[i];
      desc.numbytes = g_bufs[i]->nbytes;
      if (ioctl(afd, AUDIOIOC_ENQUEUEBUFFER, (uintptr_t)&desc) < 0)
        {
          printf("play_test: ENQUEUE %d failed: %d\n", i, errno);
          goto err_bufs;
        }
    }

  if (ioctl(afd, AUDIOIOC_START) < 0)
    {
      printf("play_test: START failed: %d\n", errno);
      goto err_bufs;
    }

  printf("play_test: playing ... 请听喇叭\n");

  /* 一直播到累计时长够; DEQUEUE 回来后填下一段再入队 */
  while (total < target)
    {
      struct audio_msg_s msg;
      struct ap_buffer_s *apb;
      unsigned int prio;

      if (mq_receive(mq, (char *)&msg, sizeof(msg), &prio) <= 0)
        {
          printf("play_test: mq_receive failed: %d\n", errno);
          break;
        }

      if (msg.msg_id != AUDIO_MSG_DEQUEUE || msg.u.ptr == NULL)
        {
          continue;
        }

      apb = msg.u.ptr;

      /* 注意: 播放方向下 audio core 在 DEQUEUE 时会把 apb->nbytes 清 0 并把
       * 数据 memset 成静音 (nuttx/audio/audio.c:1490-1494, 为的是零拷贝复用),
       * 所以进度只能用我们自己的计数 —— 不能读 apb->nbytes (那正是上一版
       * "sec 0" 刷屏的原因)。 */
      total += BUFBYTES;
      deqs++;
      if (deqs <= 3)
        {
          printf("play_test: deq#%d apb=%p nbytes=%d nmaxbytes=%d flags=%04x\n",
                 deqs, apb, apb->nbytes, apb->nmaxbytes, apb->flags);
        }

      fill_tone(apb, freq);

      desc.u.buffer = apb;
      desc.numbytes = apb->nbytes;
      if (ioctl(afd, AUDIOIOC_ENQUEUEBUFFER, (uintptr_t)&desc) < 0)
        {
          printf("play_test: re-enqueue failed: %d\n", errno);
          break;
        }

      if ((total % (RATE * 2)) == 0)
        {
          printf("play_test: sec %d\n", total / (RATE * 2));
        }

      if (deqs > (seconds * RATE * 2 / BUFBYTES) + NBUFS)
        {
          break;      /* 自己数够时长就收尾, 不依赖 nbytes */
        }
    }

  ioctl(afd, AUDIOIOC_STOP, 0);
  printf("play_test: done, %d bytes queued\n", total);

err_bufs:
  for (i = 0; i < g_nbufs && i < NBUFS; i++)
    {
      if (g_bufs[i] != NULL)
        {
          struct audio_buf_desc_s d;
          d.u.buffer = g_bufs[i];
          ioctl(afd, AUDIOIOC_FREEBUFFER, (uintptr_t)&d);
          g_bufs[i] = NULL;
        }
    }

  ioctl(afd, AUDIOIOC_UNREGISTERMQ);
err_mq:
  mq_close(mq);
  mq_unlink(PLAY_MQ_NAME);
err_close:
  close(afd);
  return total > 0 ? 0 : 1;
}
