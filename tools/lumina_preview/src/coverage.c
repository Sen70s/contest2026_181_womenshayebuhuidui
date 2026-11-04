/* 字形覆盖检查：把 lumina 用到的所有字符逐个丢给真机字体，报缺字。
 *
 * 注：本文件从原型 harness 搬入。原版缺 <string.h>（memset 隐式声明），
 * 搬移时补上（见 README「原 harness 的问题」#11）。
 * 主要用途：交叉验证 check_our_fonts.py 的 cmap 解析结论（用真 LVGL 查字，
 * 而不是我自己的解析器）。run.sh 会把它编成 build/coverage，但不强制运行。
 */
#include "lvgl.h"
#include <stdio.h>
#include <stdint.h>
#include <string.h>

extern const lv_font_t ui_font_cjk_18;
extern const lv_font_t ui_font_cjk_24;
extern const lv_font_t ui_font_sans_88_bold;

/* lumina_watch_ui.c 里所有字符串字面量（含 UI 里硬编码的符号） */
static const char *TEXTS[] = {
"返回表盘","返回","所有应用","点击打开 · 上滑查看更多","心率","血氧","运动","休息","闹钟","计时器",
"天气","设置","关于","小云","表情","音乐","小云表情","选择你喜欢的小云","第 1 页 · 上滑看更多",
"第 2 页 · 下滑回表盘","上午好，今天也要开心","小云一直在这里","♥ 最近心率","◉ 今日步数","● 已连接",
"○ 未连接","%u 次/分","%lu 步","未测量","今天的活动记录","今日步数","距离约 %u 米","活动 %u 分钟",
"依据静止与体动估算","仅供自我了解，不代表睡眠分期或医疗结论","暂无休息记录","到点提醒你","还没有闹钟",
"已设置 %u 个 · 第 %u 页","开启","关闭","+","倒计时进行中","选择时长后开始","5 分钟","10 分钟","停止",
"更新时间未知","正在获取天气…","暂时无法获取","暂无天气数据","天气未知","离线缓存 · 可能已过时",
"想聊聊吗？","小云正在想…","连接失败，可稍后重试","我在这里，随时可以聊聊","开始说话",
"第 %u / 9 页 · 共 50 款","设备与显示","亮度","勿扰","已开启","已关闭","小云表情     >","息屏",
"设备信息","小云手表","固件版本未知","可选页面，默认不在应用入口显示","暂无播放内容","暂停","播放",
"测量中…","本次测量有效","请保持手腕静止","请保持佩戴稳定","测量失败","尚无数据","请确认佩戴后重试",
"开始测量","上午好","11月2日 · 周一","10:28",
0
};

typedef struct { const char *name; const lv_font_t *f; } FONT;
static FONT FONTS[] = {
  {"simsun_16_cjk（font_body/font_small 备用）", &lv_font_simsun_16_cjk},
  {"ui_font_cjk_24（真机 font_body）",           &ui_font_cjk_24},
  {"ui_font_cjk_18（真机 font_small）",          &ui_font_cjk_18},
  {"ui_font_sans_88_bold（真机 font_time）",     &ui_font_sans_88_bold},
};

static int decode(const char *p, uint32_t *cp) {
  if ((p[0] & 0x80) == 0) { *cp = (uint8_t)p[0]; return 1; }
  if ((p[0] & 0xE0) == 0xC0) { *cp = ((p[0]&0x1F)<<6) | (p[1]&0x3F); return 2; }
  if ((p[0] & 0xF0) == 0xE0) { *cp = ((p[0]&0x0F)<<12) | ((p[1]&0x3F)<<6) | (p[2]&0x3F); return 3; }
  *cp = ((p[0]&0x07)<<18) | ((p[1]&0x3F)<<12) | ((p[2]&0x3F)<<6) | (p[3]&0x3F); return 4;
}

int main(void) {
  lv_init();
  unsigned char seen[65536]; /* 去重 */
  for (int fi = 0; fi < 4; fi++) {
    memset(seen, 0, sizeof(seen));
    printf("== %s  line_height=%d\n", FONTS[fi].name, FONTS[fi].f->line_height);
    int total = 0, missing = 0;
    for (int t = 0; TEXTS[t]; t++) {
      const char *p = TEXTS[t];
      while (*p) {
        uint32_t cp; int adv = decode(p, &cp);
        if (cp < 65536) {
          total++;
          if (!seen[cp]) {
            seen[cp] = 1;
            lv_font_glyph_dsc_t d;
            if (!lv_font_get_glyph_dsc(FONTS[fi].f, &d, cp, 0)) {
              missing++;
              printf("  MISSING U+%04X  '%s'\n", cp, p);
            }
          }
        }
        p += adv;
      }
    }
    /* 去重统计 */
    int uniq = 0; for (int i = 0; i < 65536; i++) if (seen[i]) uniq++;
    printf("  字符引用 %d 次 / 去重 %d 个 / 缺字 %d 个\n\n", total, uniq, missing);
  }
  return 0;
}
