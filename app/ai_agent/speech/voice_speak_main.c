/* voice_speak_main.c - 从 NSH 触发一次"说话"（完整 TTS -> 嘴 -> 喇叭）
 *
 * 2026-11-03 改动：改用 voice_channel_speak_async()（入队 + 常驻 speak_worker）。
 * 原因：原实现从短生命周期的 NSH app 里创建 detached 线程，实测线程可能
 * 在 app main 返回后被任务组清理而永远不执行（"speak worker started" 打了
 * 但无任何 TTS 日志）；speak_worker 现在由 voice_channel_init() 在
 * ai_agent 主进程上下文预创建，worker 生命周期与 ai_agent 一致。
 *
 * 用法: voice_speak <文本...>
 */
#include <nuttx/config.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int voice_channel_speak_async(const char* text);

int main(int argc, FAR char *argv[])
{
  char text[256];
  char *heap_text;
  int i;
  int rc;

  if (argc < 2)
    {
      printf("用法: voice_speak <文本...>\n");
      return 1;
    }

  memset(text, 0, sizeof(text));
  for (i = 1; i < argc; i++)
    {
      strncat(text, argv[i], sizeof(text) - strlen(text) - 1);
      if (i < argc - 1)
        {
          strncat(text, " ", sizeof(text) - strlen(text) - 1);
        }
    }

  heap_text = strdup(text);
  if (heap_text == NULL)
    {
      printf("voice_speak: no memory\n");
      return 1;
    }

  rc = voice_channel_speak_async(heap_text);
  printf("[voice_speak] \"%s\" queued rc=%d\n", text, rc);
  free(heap_text);
  return rc == 0 ? 0 : 1;
}
