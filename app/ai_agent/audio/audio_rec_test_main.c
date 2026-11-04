/****************************************************************************
 * audio_rec_test_main.c - Standalone mic capture test
 *
 * Mirrors the proven sequence in speech/mic_capture.c exactly:
 *   open(O_RDWR) -> RESERVE -> CONFIGURE(16k/16bit/1ch) -> mq_open(O_RDWR)
 *   -> REGISTERMQ -> GETBUFFERINFO -> ALLOCBUFFER xN -> ENQUEUEBUFFER xN
 *   -> START -> mq_receive(AUDIO_MSG_DEQUEUE) -> copy apb->samp -> re-enqueue
 *
 * Records N seconds of 16kHz/16bit/mono PCM to /data/mic_test.pcm and
 * prints per-second peak amplitude.
 *
 * Team 181 - mic verification (2026-10-09)
 ****************************************************************************/

#include <nuttx/config.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <errno.h>
#include <unistd.h>
#include <mqueue.h>
#include <sys/ioctl.h>
#include <nuttx/audio/audio.h>

#ifndef CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME
#  define DEVPATH     "/dev/audio/audio0"
#else
#  define DEVPATH     "/dev/audio/" CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME
#endif
#define OUTPATH     "/data/mic_test.pcm"
#define MQNAME      "/rec_test_mq"
#define RATE        16000
#define CHANNELS    1
#define BPSAMP      16
#define BUFBYTES    4096
#define NBUFS       8
#define MQMSG_SIZE  sizeof(struct audio_msg_s)

static struct ap_buffer_s *g_bufs[NBUFS];
static int g_nbufs = 0;

static int enqueue_buf(int afd, struct ap_buffer_s *apb)
{
  struct audio_buf_desc_s desc;

  apb->nbytes  = apb->nmaxbytes;
  desc.numbytes = apb->nbytes;
  desc.u.buffer = apb;

  return ioctl(afd, AUDIOIOC_ENQUEUEBUFFER, (uintptr_t)&desc);
}

int main(int argc, char *argv[])
{
  int afd = -1;
  int ofd = -1;
  mqd_t mq = (mqd_t)-1;
  int i;
  struct audio_caps_desc_s cap_desc;
  struct ap_buffer_info_s binfo;
  struct mq_attr attr;
  int seconds = (argc > 1) ? atoi(argv[1]) : 3;
  long target;
  long total = 0;
  long idle_loops = 0;

  if (seconds <= 0 || seconds > 60) seconds = 3;
  target = (long)seconds * RATE * 2;

  /* ★ NuttX 平铺构建下, 内置应用每次启动都【不会】重新初始化静态/全局
   * 变量 (nxtask_startup() 只是调用 entrypoint, 见
   * nuttx/libs/libc/sched/task_startup.c:58-67)。上一次运行的 g_nbufs
   * 会残留下来, 释放循环按残留值多走几轮, 越界读到 g_bufs[8] ——
   * 而 g_bufs[8] 正好是紧邻其后的 g_nbufs 本身 (都在这条 BSS 里),
   * 于是把 8/16/24… 这样的整数当成 ap_buffer_s* 交给
   * AUDIOIOC_FREEBUFFER -> apb_free 对无效 mutex 加锁 -> 断言
   * (真机 2026-10-11 用 crefs 轨迹定位, 与驱动引用计数无关)。
   * 这是测试程序自己的坑, 必须自己清干净。 */
  g_nbufs = 0;
  memset(g_bufs, 0, sizeof(g_bufs));

  /* 1) open O_RDWR: kernel mq_send needs write access to our mq */
  afd = open(DEVPATH, O_RDWR);
  if (afd < 0)
    {
      printf("rec_test: open %s failed: %d\n", DEVPATH, errno);
      return 1;
    }

  /* 2) RESERVE */
  if (ioctl(afd, AUDIOIOC_RESERVE) < 0)
    {
      printf("rec_test: RESERVE failed: %d\n", errno);
      goto err_close;
    }

  /* 3) CONFIGURE 16kHz/16bit/mono PCM */
  memset(&cap_desc, 0, sizeof(cap_desc));
  cap_desc.caps.ac_len            = sizeof(cap_desc.caps);
  cap_desc.caps.ac_type           = AUDIO_TYPE_INPUT;
  cap_desc.caps.ac_channels       = CHANNELS;
  cap_desc.caps.ac_controls.hw[0] = RATE;
  cap_desc.caps.ac_controls.b[2]  = BPSAMP;
  cap_desc.caps.ac_subtype        = AUDIO_FMT_PCM;
  if (ioctl(afd, AUDIOIOC_CONFIGURE, (uintptr_t)&cap_desc) < 0)
    {
      printf("rec_test: CONFIGURE failed: %d\n", errno);
      goto err_release;
    }

  /* 4) mq (O_RDWR: kernel file_mq_send writes into it) */
  attr.mq_maxmsg  = 16;
  attr.mq_msgsize = MQMSG_SIZE;
  attr.mq_flags   = 0;
  mq = mq_open(MQNAME, O_CREAT | O_RDWR, 0644, &attr);
  if (mq == (mqd_t)-1)
    {
      printf("rec_test: mq_open failed: %d\n", errno);
      goto err_release;
    }
  if (ioctl(afd, AUDIOIOC_REGISTERMQ, (uintptr_t)mq) < 0)
    {
      printf("rec_test: REGISTERMQ failed: %d\n", errno);
      goto err_mq;
    }

  /* 5) buffer geometry (defaults if driver declines) */
  memset(&binfo, 0, sizeof(binfo));
  if (ioctl(afd, AUDIOIOC_GETBUFFERINFO, (uintptr_t)&binfo) != OK)
    {
      binfo.buffer_size = BUFBYTES;
      binfo.nbuffers    = NBUFS;
    }
  if (binfo.buffer_size <= 0) binfo.buffer_size = BUFBYTES;
  if (binfo.nbuffers <= 0 || binfo.nbuffers > NBUFS) binfo.nbuffers = NBUFS;

  /* 6) ALLOCBUFFER xN (kernel owns apb) + zero + enqueue */
  for (i = 0; i < binfo.nbuffers; i++)
    {
      struct audio_buf_desc_s desc;

      memset(&desc, 0, sizeof(desc));
      desc.numbytes   = binfo.buffer_size;
      desc.u.pbuffer  = &g_bufs[i];
      if (ioctl(afd, AUDIOIOC_ALLOCBUFFER, (uintptr_t)&desc) !=
          sizeof(desc))
        {
          printf("rec_test: ALLOCBUFFER %d failed\n", i);
          goto err_bufs;
        }

      if (g_bufs[i] != NULL)
        {
          memset(g_bufs[i]->samp, 0, g_bufs[i]->nmaxbytes);
          g_bufs[i]->nbytes  = 0;
          g_bufs[i]->curbyte = 0;
        }
      g_nbufs++;

      if (enqueue_buf(afd, g_bufs[i]) < 0)
        {
          printf("rec_test: ENQUEUEBUFFER %d failed: %d\n", i, errno);
          goto err_bufs;
        }
    }

  /* 7) output file before START */
  ofd = open(OUTPATH, O_WRONLY | O_CREAT | O_TRUNC, 0666);
  if (ofd < 0)
    {
      printf("rec_test: open %s failed: %d\n", OUTPATH, errno);
      goto err_bufs;
    }

  /* 8) START */
  if (ioctl(afd, AUDIOIOC_START) < 0)
    {
      printf("rec_test: START failed: %d\n", errno);
      goto err_bufs;
    }

  printf("rec_test: recording %d s @ %d Hz ... SPEAK NOW\n", seconds, RATE);

  /* 9) drain completions */
  {
    struct audio_msg_s msg;
    struct timespec ts;
    long chunk_target = RATE * 2;
    long chunk_got = 0;
    int sec = 0;
    int16_t peak = 0;
    long sum = 0;
    long samples = 0;

    while (total < target && idle_loops < RATE)
      {
        ssize_t n;

        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += 2;

        n = mq_timedreceive(mq, (char *)&msg, MQMSG_SIZE, NULL, &ts);
        if (n < 0)
          {
            if (errno == ETIMEDOUT)
              {
                idle_loops++;
                continue;
              }
            printf("rec_test: mq_receive err %d\n", errno);
            break;
          }

        idle_loops = 0;

        if (msg.msg_id != AUDIO_MSG_DEQUEUE || msg.u.ptr == NULL)
          {
            continue;
          }

        {
          struct ap_buffer_s *apb = msg.u.ptr;
          int avail = (int)apb->curbyte;   /* bytes the driver filled */
          int16_t *d = (int16_t *)apb->samp;
          int cnt;

          if (avail > 0 && avail <= (int)apb->nmaxbytes)
            {
              write(ofd, apb->samp, avail);
              total += avail;
              chunk_got += avail;
              cnt = avail / 2;
              for (i = 0; i < cnt; i++)
                {
                  int16_t v = d[i] < 0 ? -d[i] : d[i];
                  if (v > peak) peak = v;
                  sum += v;
                  samples++;
                }
            }

          /* mark fully consumed and return */
          apb->nbytes = apb->curbyte;
          if (enqueue_buf(afd, apb) < 0)
            {
              printf("rec_test: re-enqueue failed: %d\n", errno);
              break;
            }
        }

        if (chunk_got >= chunk_target)
          {
            printf("rec_test: sec %d peak=%d avg=%ld\n",
                   sec, peak, samples ? sum / samples : 0);
            sec++;
            chunk_got = 0;
            peak = 0;
            sum = 0;
            samples = 0;
          }
      }
  }

  ioctl(afd, AUDIOIOC_STOP, 0);
  close(ofd);
  ofd = -1;

  printf("rec_test: done, %ld bytes -> %s\n", total, OUTPATH);
  if (total == 0)
    {
      printf("rec_test: NO DATA captured (check MIC path)\n");
    }

err_bufs:
  {
    struct audio_buf_desc_s desc;
    for (i = 0; i < g_nbufs && i < NBUFS; i++)   /* 双保险: 永不越界 */
      {
        if (g_bufs[i] != NULL)
          {
            desc.u.buffer = g_bufs[i];
            ioctl(afd, AUDIOIOC_FREEBUFFER, (uintptr_t)&desc);
            g_bufs[i] = NULL;
          }
      }
  }
err_mq:
  ioctl(afd, AUDIOIOC_UNREGISTERMQ);
  if (mq != (mqd_t)-1)
    {
      mq_close(mq);
      mq_unlink(MQNAME);
    }
err_release:
  ioctl(afd, AUDIOIOC_RELEASE);
err_close:
  close(afd);
  return total > 0 ? 0 : 1;
}
