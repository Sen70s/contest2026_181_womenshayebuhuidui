/****************************************************************************
 * mic_capture.c - 板载麦克风采集 (NuttX 音频框架 /dev/audio/audio0)
 *
 * 流程 (与 nxrecorder 一致):
 *   open -> RESERVE -> CONFIGURE(16k/16bit/1ch) -> REGISTERMQ
 *        -> START -> ALLOCBUFFER xN -> ENQUEUEBUFFER xN
 *   read: mq_receive(AUDIO_MSG_DEQUEUE) -> 拷贝 apb->samp -> 重新入队
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <errno.h>
#include <fcntl.h>
#include <mqueue.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <nuttx/audio/audio.h>

#include "mic_capture.h"

#ifndef CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME
#  define MIC_DEV_PATH      "/dev/audio/audio0"
#else
#  define MIC_DEV_PATH      "/dev/audio/" CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME
#endif
#define MIC_NUM_BUFFERS    8
#define MIC_BUFFER_BYTES   4096
#define MIC_MQ_NAME        "/mic_capture_q"
#define MIC_MQ_MSG_SIZE    sizeof(struct audio_msg_s)

/****************************************************************************
 * Private Data
 ****************************************************************************/

static int s_fd = -1;
static mqd_t s_mq = (mqd_t)-1;
static struct ap_buffer_s **s_bufs = NULL;
static int s_nbufs = 0;

/* 当前正在消费的 apb 与偏移 (一次 read 只取部分时, 下次继续,
 * 避免 4096B 缓冲只取 640B 就归还, 丢弃 84% 音频) */
static struct ap_buffer_s *s_cur = NULL;
static int s_cur_off = 0;

/****************************************************************************
 * Private Functions
 ****************************************************************************/

static int mic_enqueue(struct ap_buffer_s *apb)
{
  struct audio_buf_desc_s desc;

  apb->nbytes = apb->nmaxbytes;
  desc.numbytes = apb->nbytes;
  desc.u.buffer = apb;

  return ioctl(s_fd, AUDIOIOC_ENQUEUEBUFFER, (uintptr_t)&desc);
}

static int mic_alloc_buffers(int nbufs, int bytes)
{
  struct audio_buf_desc_s desc;
  int i;

  s_bufs = calloc(nbufs, sizeof(*s_bufs));
  if (s_bufs == NULL)
    {
      return -ENOMEM;
    }

  for (i = 0; i < nbufs; i++)
    {
      desc.numbytes = bytes;
      desc.u.pbuffer = &s_bufs[i];

      if (ioctl(s_fd, AUDIOIOC_ALLOCBUFFER, (uintptr_t)&desc) !=
          sizeof(desc))
        {
          return -EIO;
        }

      /* apb 来自 kmm 分配, 内容是堆残留; 首个缓冲若带垃圾会以假能量
       * 触发 VAD (真机实测 rms~10k 恒定假 onset), 清零消除 */
      if (s_bufs[i] != NULL)
        {
          memset(s_bufs[i]->samp, 0, s_bufs[i]->nmaxbytes);
          s_bufs[i]->nbytes = 0;
          s_bufs[i]->curbyte = 0;
        }
    }

  s_nbufs = nbufs;
  return 0;
}

static void mic_free_buffers(void)
{
  struct audio_buf_desc_s desc;
  int i;

  if (s_bufs == NULL)
    {
      return;
    }

  for (i = 0; i < s_nbufs; i++)
    {
      if (s_bufs[i] != NULL)
        {
          desc.u.buffer = s_bufs[i];
          ioctl(s_fd, AUDIOIOC_FREEBUFFER, (uintptr_t)&desc);
          s_bufs[i] = NULL;
        }
    }

  free(s_bufs);
  s_bufs = NULL;
  s_nbufs = 0;
}

/****************************************************************************
 * Public Functions
 ****************************************************************************/

int mic_capture_start(void)
{
  struct audio_caps_desc_s cap_desc;
  struct mq_attr attr;
  struct ap_buffer_info_s binfo;
  int ret;
  int i;

  if (s_fd >= 0)
    {
      return 0;   /* 已启动 */
    }

  /* O_RDWR: 与 nxrecorder 一致 (内核 file_mq_send 要向我们的 mq 写,
   * O_RDONLY 的 mq 会静默收不到 DEQUEUE 消息) */
  s_fd = open(MIC_DEV_PATH, O_RDWR);
  if (s_fd < 0)
    {
      printf("[mic] open %s failed: %d\n", MIC_DEV_PATH, errno);
      return -errno;
    }

  printf("[mic] opened, RESERVE...\n");

  if (ioctl(s_fd, AUDIOIOC_RESERVE) < 0)
    {
      ret = -errno;
      goto err_close;
    }

  printf("[mic] reserved, CONFIGURE...\n");

  /* CONFIGURE: 16kHz/16bit/单声道 */
  memset(&cap_desc, 0, sizeof(cap_desc));
  cap_desc.caps.ac_len = sizeof(cap_desc.caps);
  cap_desc.caps.ac_type = AUDIO_TYPE_INPUT;
  cap_desc.caps.ac_channels = MIC_CAPTURE_CHANNELS;
  cap_desc.caps.ac_controls.hw[0] = MIC_CAPTURE_RATE;
  cap_desc.caps.ac_controls.b[2] = MIC_CAPTURE_BITS;
  cap_desc.caps.ac_subtype = AUDIO_FMT_PCM;

  if (ioctl(s_fd, AUDIOIOC_CONFIGURE, (uintptr_t)&cap_desc) < 0)
    {
      ret = -errno;
      printf("[mic] CONFIGURE failed: %d\n", errno);
      goto err_reserve;
    }

  /* 消息队列 (收 apb 完成通知; O_RDWR: 内核要向其 mq_send, 只读打不开) */
  attr.mq_maxmsg = 16;
  attr.mq_msgsize = MIC_MQ_MSG_SIZE;
  attr.mq_flags = 0;
  s_mq = mq_open(MIC_MQ_NAME, O_CREAT | O_RDWR, 0644, &attr);
  if (s_mq == (mqd_t)-1)
    {
      ret = -errno;
      goto err_reserve;
    }

  if (ioctl(s_fd, AUDIOIOC_REGISTERMQ, (uintptr_t)s_mq) < 0)
    {
      ret = -errno;
      goto err_mq;
    }

  printf("[mic] mq registered, buffers...\n");

  /* 缓冲区 */
  memset(&binfo, 0, sizeof(binfo));
  if (ioctl(s_fd, AUDIOIOC_GETBUFFERINFO, (uintptr_t)&binfo) != OK)
    {
      binfo.buffer_size = MIC_BUFFER_BYTES;
      binfo.nbuffers = MIC_NUM_BUFFERS;
    }

  ret = mic_alloc_buffers(binfo.nbuffers, binfo.buffer_size);
  if (ret < 0)
    {
      goto err_mq;
    }

  printf("[mic] %d buffers alloc'd, enqueue...\n", s_nbufs);

  for (i = 0; i < s_nbufs; i++)
    {
      if (mic_enqueue(s_bufs[i]) < 0)
        {
          ret = -errno;
          goto err_bufs;
        }
    }

  printf("[mic] enqueued, START...\n");

  if (ioctl(s_fd, AUDIOIOC_START) < 0)
    {
      ret = -errno;
      printf("[mic] START failed: %d\n", errno);
      goto err_bufs;
    }

  printf("[mic] capture started (%s)\n", MIC_DEV_PATH);
  return 0;

err_bufs:
  mic_free_buffers();
err_mq:
  ioctl(s_fd, AUDIOIOC_UNREGISTERMQ);
  mq_close(s_mq);
  s_mq = (mqd_t)-1;
err_reserve:
  ioctl(s_fd, AUDIOIOC_RELEASE);
err_close:
  close(s_fd);
  s_fd = -1;
  return ret;
}

/* 冲刷陈旧数据: 上一次会话停止后, 驱动侧 drain 与 DMA 尾巴可能仍向
 * 消息队列投递残留缓冲 (含满幅停止瞬态)。新会话开始时先取空队列、
 * 再丢弃 0.3s 采集, 保证适应期读到的是真实环境音 (否则底噪=0,
 * 阈值退化为下限, 残留毛刺照样假触发 onset)。 */
void mic_capture_flush(int discard_ms)
{
  struct audio_msg_s msg;
  struct timespec ts;
  int flush_frames = discard_ms / 20;   /* 每帧 20ms */

  if (s_fd < 0)
    {
      return;
    }

  /* 1) 取空消息队列 — 必须用零超时 (非阻塞尝试):
   * NuttX 的 mq_receive 是阻塞语义, 空队列上会永久挂起
   * (真机: 打断后队列恰为空, worker 卡死在 flush, busy 永卡) */
  ts.tv_sec = 0;
  ts.tv_nsec = 0;

  while (mq_timedreceive(s_mq, (char *)&msg, MIC_MQ_MSG_SIZE,
                         NULL, &ts) >= 0)
    {
      if (msg.msg_id == AUDIO_MSG_DEQUEUE && msg.u.ptr != NULL)
        {
          /* 标记消费完并归还, 防止驱动 pendq 积压 */
          struct ap_buffer_s *apb = msg.u.ptr;

          apb->nbytes = apb->curbyte;
          mic_enqueue(apb);
        }
    }

  /* 2) 丢弃 0.3s 新采集 (每 20ms 一帧) */
  {
    static int16_t discard_buf[320];

    for (int i = 0; i < flush_frames; i++)
      {
        mic_capture_read(discard_buf, sizeof(discard_buf), 100);
      }
  }
}

int mic_capture_read(int16_t *buf, int max_bytes, int timeout_ms)
{
  struct audio_msg_s msg;
  struct timespec ts;
  ssize_t n;
  int copied = 0;

  if (s_fd < 0 || buf == NULL || max_bytes <= 0)
    {
      return -EINVAL;
    }

  while (copied < max_bytes)
    {
      /* 先消费上一个未取完的 apb */
      if (s_cur != NULL)
        {
          int avail = (int)s_cur->nbytes - s_cur_off;
          int take = max_bytes - copied;

          if (take > avail)
            {
              take = avail;
            }

          memcpy((uint8_t *)buf + copied, s_cur->samp + s_cur_off, take);
          copied += take;
          s_cur_off += take;

          if (s_cur_off >= (int)s_cur->nbytes)
            {
              mic_enqueue(s_cur);   /* 消费完, 归还缓冲 */
              s_cur = NULL;
              s_cur_off = 0;
            }

          continue;
        }

      if (timeout_ms > 0)
        {
          clock_gettime(CLOCK_REALTIME, &ts);
          ts.tv_sec += timeout_ms / 1000;
          ts.tv_nsec += (timeout_ms % 1000) * 1000000L;
          if (ts.tv_nsec >= 1000000000L)
            {
              ts.tv_sec++;
              ts.tv_nsec -= 1000000000L;
            }

          n = mq_timedreceive(s_mq, (char *)&msg, MIC_MQ_MSG_SIZE,
                              NULL, &ts);
        }
      else
        {
          n = mq_receive(s_mq, (char *)&msg, MIC_MQ_MSG_SIZE, NULL);
        }

      if (n < 0)
        {
          if (errno == ETIMEDOUT)
            {
              break;
            }

          return -errno;
        }

      if (msg.msg_id != AUDIO_MSG_DEQUEUE)
        {
          continue;
        }

      s_cur = msg.u.ptr;
      s_cur_off = 0;

      if (s_cur == NULL)
        {
          continue;
        }
    }

  return copied;
}

void mic_capture_stop(void)
{
  if (s_fd < 0)
    {
      return;
    }

  ioctl(s_fd, AUDIOIOC_STOP);
  ioctl(s_fd, AUDIOIOC_UNREGISTERMQ);
  mic_free_buffers();
  ioctl(s_fd, AUDIOIOC_RELEASE);
  close(s_fd);
  s_fd = -1;
  s_cur = NULL;
  s_cur_off = 0;

  if (s_mq != (mqd_t)-1)
    {
      mq_close(s_mq);
      mq_unlink(MIC_MQ_NAME);
      s_mq = (mqd_t)-1;
    }

  printf("[mic] capture stopped\n");
}
