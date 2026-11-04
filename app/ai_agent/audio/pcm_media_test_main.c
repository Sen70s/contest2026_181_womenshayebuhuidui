/****************************************************************************
 * pcm_media_test_main.c - 验证"嘴"：media_player API -> /dev/audio/pcm0p -> 喇叭
 *
 * 背景: ai_agent 的 voice_channel_speak() 用 media_player_* 流式播放 TTS PCM，
 * 而本工作区没有 media 框架（CONFIG_MEDIA 编不过）。我们已在
 * packages/ai_agent/src/stubs.c 里把这套 API 实现在真机验证过的播放设备上
 * (docs_ble/30 §10/§11)，本命令用于端到端证明这条"嘴"是通的。
 *
 * 关键点: 按 TTS 的真实参数来 —— 24kHz 单声道 s16le 流式写入（设备 16kHz，
 * 由实现内部做线性插值重采样）。
 *
 * 用法: pcm_media_test [秒数=3] [频率=1000]
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <media_player.h>

#define TTS_RATE   24000          /* AGENT_TTS_WS_SAMPLE_RATE */
#define CHUNK_MS   40
#define CHUNK_N    (TTS_RATE / 1000 * CHUNK_MS)

int main(int argc, FAR char *argv[])
{
  int seconds = (argc > 1) ? atoi(argv[1]) : 3;
  int freq    = (argc > 2) ? atoi(argv[2]) : 1000;
  int total   = 0;
  int chunks;
  short buf[CHUNK_N];
  int i, c;
  const char *opts = "format=s16le:sample_rate=24000:ch_layout=mono";

  if (seconds <= 0 || seconds > 30) seconds = 3;
  if (freq <= 0 || freq > 8000) freq = 1000;
  chunks = seconds * 1000 / CHUNK_MS;

  printf("pcm_media_test: %d s @ %d Hz, %dHz mono s16le, %d chunks\n",
         seconds, freq, TTS_RATE, chunks);

  void *pb = media_player_open(MEDIA_STREAM_MUSIC);
  if (!pb)
    {
      printf("pcm_media_test: media_player_open failed\n");
      return 1;
    }

  if (media_player_prepare(pb, NULL, opts) < 0)
    {
      printf("pcm_media_test: prepare failed\n");
      media_player_close(pb, 0);
      return 1;
    }

  if (media_player_start(pb) < 0)
    {
      printf("pcm_media_test: start failed\n");
      media_player_close(pb, 0);
      return 1;
    }

  for (c = 0; c < chunks; c++)
    {
      for (i = 0; i < CHUNK_N; i++)
        {
          double t = (double)(c * CHUNK_N + i) / (double)TTS_RATE;
          buf[i] = (short)(sin(2.0 * 3.14159265358979 * (double)freq * t)
                           * 11000.0);
        }

      ssize_t n = media_player_write_data(pb, buf, sizeof(buf));
      if (n < 0)
        {
          printf("pcm_media_test: write failed at chunk %d: %d\n", c, (int)n);
          break;
        }
      total += (int)n;

      /* 模拟 TTS 推流节奏: 每 40ms 一块 */
      usleep(CHUNK_MS * 1000);
    }

  printf("pcm_media_test: wrote %d bytes, stopping...\n", total);
  media_player_stop(pb);
  media_player_close(pb, 0);
  printf("pcm_media_test: done\n");
  return 0;
}
