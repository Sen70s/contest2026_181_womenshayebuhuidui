/****************************************************************************
 * care_test.c - 「小云主动关怀」验收/诊断命令（NSH）
 *
 * 目的：久坐提醒与步数里程碑都由 **真 IMU 数据** 驱动
 * （pedometer_get_still_seconds() / pedometer_get_steps()），正常阈值是
 * 45 分钟静止 —— 现场验收显然不能干等 45 分钟。本命令提供三条不改变
 * 生产路径的入口：
 *
 *   care_test                  查看当前静止时长/步数/阈值/免打扰/静默期
 *   care_test trigger <秒>     把久坐阈值临时改成 N 秒（0 = 关闭），
 *                              之后**完全走真实判定路径**：手表静止到
 *                              N 秒就会自己开口。想看完整因果链用这条
 *                              （例如 trigger 120 然后静置 2 分钟）。
 *   care_test sedentary        跳过阈值/冷却立刻弹一次（只验证气泡与
 *                              文案，不验证判定逻辑）
 *   care_test milestone        让下一次 1Hz tick 触发一次步数庆祝
 *                              （数字 = 当前步数 + 100，仍是真数据）
 *
 * 判读（串口）：
 *   [care_test] still=.. steps=.. ...        —— 当前真值
 *   [pet_care] sedentary fire: still=.. trigger=..   —— 判定触发（真路径）
 *   [pet_care] step milestone fire: N steps
 *   [pet_care] fire emo=.. text=...          —— 所有主动关怀的统一留痕
 *
 * 注意：免打扰(DND)开着时 force 会返回失败 —— 与真实行为一致，
 * 现场演示前先确认【设置 → 免打扰】是关的。
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "ui/pet_care.h"

#define TAG "[care_test]"

static void usage(void)
{
  printf("usage:\n");
  printf("  care_test                    dump still/steps/trigger/dnd\n");
  printf("  care_test trigger <sec>      set sedentary trigger (0=off)\n");
  printf("  care_test sedentary          force one sedentary nudge now\n");
  printf("  care_test milestone          fire one step milestone next tick\n");
}

int main(int argc, FAR char *argv[])
{
  if (argc < 2)
    {
      pet_care_debug_dump();
      return 0;
    }

  if (strcmp(argv[1], "trigger") == 0)
    {
      long sec;

      if (argc < 3)
        {
          printf("%s trigger needs seconds (0 = off)\n", TAG);
          return -1;
        }

      sec = strtol(argv[2], NULL, 10);
      if (sec < 0 || sec > 24 * 3600)
        {
          printf("%s out of range: %ld (0..86400)\n", TAG, sec);
          return -1;
        }

      pet_care_set_sedentary_trigger((uint32_t)sec);
      printf("%s sedentary trigger -> %ld s\n", TAG, sec);
      return 0;
    }

  if (strcmp(argv[1], "sedentary") == 0)
    {
      if (pet_care_debug_force_sedentary() != 0)
        {
          printf("%s force failed: DND is on (disable 免打扰 first)\n", TAG);
          return -1;
        }

      printf("%s sedentary nudge fired\n", TAG);
      return 0;
    }

  if (strcmp(argv[1], "milestone") == 0)
    {
      if (pet_care_debug_force_milestone() != 0)
        {
          printf("%s no steps yet (walk a bit first) - won't fake a number\n",
                 TAG);
          return -1;
        }

      return 0;
    }

  usage();
  return -1;
}
