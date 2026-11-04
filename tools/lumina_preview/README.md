# lumina_preview —— 手表 UI 的 PC 离线预览器（LVGL 9.1）

在 PC 上把 **lumina 手表 UI**（外来设计稿的 C 实现）用**真机同款字体**渲染成 14 张
PNG + 一张 contact sheet，并顺带做「对象树缺陷扫描」和「我们自己固件文案的缺字检查」。

一句话定位：**它是版面/字形的静态验证器，不是固件行为验证器。**
不用烧板、不用 SDL、不用 framebuffer —— 直接调 `flush_cb` 把 RGB565 帧抓下来。

```bash
# 从参赛仓根目录
bash tools/lumina_preview/run.sh            # 增量（LVGL 编过就跳过）
bash tools/lumina_preview/run.sh --clean    # 全量重编（删 build/out/生成物）
```

产物在 `tools/lumina_preview/out/`：`page_01_watchface.png` … `page_14_music.png`、
`contact_sheet.png`、`scan.txt`（缺陷扫描）、`golden_diff.txt`（与参考图像素比对）、
`our_font_coverage.txt`（固件缺字）。

---

## 1. 能力边界（先看这个，别把预览结论用错）

| 能证明 | 不能证明 |
|---|---|
| **版面**：控件位置/大小/间距/重叠，文本换行与截断（`scan.txt` 里 TEXT_TRUNCATED / CONTENT_OVERLAP） | **性能**：刷新率、绘图耗时、内存峰值。host 是 x86 + clib，数字没有参考价值 |
| **字形覆盖**：哪个字真机字库里没有（`MISSING_GLYPH`），以及我们固件文案的缺字 | **触摸**：`scan.txt` 的 `TOUCH_TARGET_SMALL` 只是量控件尺寸，不测触摸驱动、不测手感 |
| **颜色/主题**：16bit RGB565 下的实际观感 | **功耗**：完全无关 |
| **图片裁剪/缩放行为**：`IMAGE_NOT_SCALED` 能抓出「设了 size 但 LVGL 不缩放」的坑 | **真实数据**：14 页全是 `fill_demo_state()` 的示意数据（`10:28`、`72 bpm`…），不是真机读数 |
| **改版回归**：改 UI 代码/字体/表情资产后再跑一次，像素级对比 golden | **驱动/中断/线程**：没有 NuttX，没有真实 `lv_tick`，定时器是手动 pump 的 |
| | **跨平台一致性**：host 是 LVGL 的 clib 配置，真机是自己的 lv_conf；字体渲染的 hinting 也不同 |

**特别提醒**：预览用的三份字体是**真机同一份源码**（`ui_font_cjk_24.c` / `ui_font_cjk_18.c` /
`ui_font_sans_88_bold.c`，直接从 `packages/ai_agent/src/ui/assets/` 只读引用），
所以「预览里缺字 = 真机缺字」。但 `lv_conf.h` 是 host 专用的（见 §5），
**不要**拿它当固件配置。

---

## 2. 目录结构

```
tools/lumina_preview/
├── README.md                  ← 本文件
├── run.sh                     ← 唯一入口：10 步流水线
├── .gitignore                 ← build/ out/ assets/generated/ 全部忽略
├── scripts/
│   ├── common.sh              ← 所有路径的唯一来源（禁止各脚本自己写死路径）
│   ├── gen_conf.py            ← lv_conf_template.h -> build/lv_conf.h（host 覆盖项在 OVERRIDES）
│   ├── build_lvgl.sh          ← 编 LVGL 核心 347 个 .c（幂等，build/.lvgl_done）
│   ├── build_assets.sh        ← 编真机 3 份字体 + 表情资产
│   ├── build_preview.sh       ← 编 UI + harness + 链接可执行
│   ├── gen_expr_map.py        ← 表情 id -> lv_image_dsc_t 指针表（硬校验 50 个）
│   ├── raw2png.py             ← RGB565 小端 .raw -> PNG
│   ├── make_sheet.py          ← 14 页 -> 3x5 contact sheet
│   ├── convert_expr.py        ← 50 张原图 -> RGB565A8 C 数组（移植版，算法未改）
│   ├── check_our_fonts.py     ← ★ 扫描我们自己固件 UI 的缺字（退出码非 0 可接 CI）
│   └── verify_golden.py       ← 与 assets/golden 逐像素比对
├── src/
│   ├── lumina_watch_ui.c/.h   ← 上游 UI（逐字节未改）
│   ├── lumina_expressions.c/.h← 上游 50 个表情的 id/label
│   ├── host_preview.c         ← 主程序：lv_init + 全屏 draw buffer + 逐页渲染 + 缺陷扫描
│   └── coverage.c             ← 缺字交叉验证小工具（独立可执行，不参与预览链接）
├── assets/
│   ├── expressions/*.png      ← 50 张原始表情图（4.4 MB，**入库**）
│   ├── golden/*.png           ← 15 张参考渲染图（**入库**，用于回归比对）
│   └── generated/             ← 生成物（lumina_expressions.c / expr_dsc_map.c，**忽略**）
├── build/                     ← 编译产物（**忽略**）
└── out/                       ← 渲染/报告产物（**忽略**）
```

`build/`、`out/`、`assets/generated/` 只出现在 `.gitignore` 里，入库的只有
**19 个源码/脚本/文档 + 50 张原图 + 15 张参考图**（`git add -n tools/` 实测 84 个文件、
5.0 MB）。生成物一个都不会进仓 —— `.gitignore` 已逐项用 `git check-ignore -v` 验证。

---

## 3. 运行

### 3.1 依赖

| 依赖 | 用途 | 缺失时的表现 |
|---|---|---|
| `gcc` | 编译 LVGL/UI/harness | run.sh 第 0 步直接报错退出 |
| `python3` + `Pillow` | PNG 读写、contact sheet | 同上 |
| `numpy` | `convert_expr.py` 的预乘 alpha 缩放 | 同上 |
| `fonts-noto-cjk` | contact sheet 的中文标签 | 同上（`NotoSansCJK-Bold.ttc`） |

装法：`sudo apt install -y build-essential python3 fonts-noto-cjk` +
`python3 -m pip install --user pillow numpy`。

### 3.2 环境变量（换机器/换仓时用）

所有路径都由 `scripts/common.sh` 从「脚本自身位置」推导，**没有任何 `/tmp` 或绝对路径**。
需要覆盖时：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `LUMINA_LVGL_DIR` | `<workspace>/apps/graphics/lvgl/lvgl` | LVGL 源码 |
| `LUMINA_DEVICE_UI_DIR` | `<workspace>/packages/ai_agent/src/ui` | 我们固件 UI（只读） |
| `LUMINA_DEVICE_ASSETS` | `<上面>/assets` | 真机字体（只读） |
| `LUMINA_PREVIEW_BUILD` | `<tool>/build` | 编译产物目录 |
| `LUMINA_PREVIEW_OUT` | `<tool>/out` | 渲染产物目录 |

推导规则：`common.sh` 用 `$(dirname $0)/..` 得到 `TOOL_DIR`，再 `../..` 得到
`REPO_ROOT`，再 `..` 得到 workspace 根，从那里拼 `apps/...` 与 `packages/...`。

### 3.3 参数

| 参数 | 作用 |
|---|---|
| `--clean` | 先删 `build/ out/ assets/generated/`，再全量编译 |
| `--no-fonts` | 跳过第 10 步「我们固件缺字检查」 |
| `--no-verify` | 跳过第 9 步「与 golden 像素比对」 |
| `--help` | 打印用法 |

### 3.4 流水线（10 步）

| 步 | 做什么 | 本机耗时 |
|---|---|---|
| 0 | 依赖检查（gcc/python3/PIL/numpy/Noto CJK + LVGL/字体目录存在） | 0s |
| 0.5 | `--clean` 时清理 | 0s |
| 1 | `lv_conf_template.h` -> `build/lv_conf.h`（`gen_conf.py`，幂等比 mtime） | 0s |
| 2 | 编 LVGL 347 个 `.c`（`build/.lvgl_done` 幂等） | 3s |
| 3 | 50 张 PNG -> `assets/generated/lumina_expressions.c`（已存在则跳过）+ 生成 id 映射表 | 2s |
| 4 | 编真机 3 份字体 + 表情资产 | 1s |
| 5 | 编 lumina UI + `host_preview` + `coverage`，链接 | 1s |
| 6 | 渲染 14 页 -> `out/page_*.raw` + `scan.txt` | 0s |
| 7 | `.raw` -> `.png`（RGB565 小端） | 1s |
| 8 | contact sheet | 0s |
| 9 | 与 `assets/golden/` 逐像素比对 | 0s |
| 10 | 我们固件缺字检查（退出码非 0 时可以拦 CI） | 1s |

---

## 4. 关键实现细节（为什么要这么写）

### 4.1 `.o` 命名必须带路径 md5

LVGL 源码里有**重名 basename**（`lv_draw_sw_vector.c` / `lv_draw_vector.c`、
多个 `lv_sdl_mouse*.c`、各 GPU 后端同名文件…）。全部扁平编到 `build/` 会互相覆盖，
链接时随机缺符号。所以 `build_lvgl.sh` 用：

```
lvgl_<md5(相对 LVGL/src 的路径)前 12 位>_<basename>.o
```

并**校验 .o 数量 == 源文件数量**，不符直接失败。

### 4.2 为什么预览用三份「真机字体」

`host_preview.c` 里的 `extern` 与固件符号名一致：

```c
extern const lv_font_t ui_font_cjk_24;       // 真机 font_body
extern const lv_font_t ui_font_cjk_18;       // 真机 font_small
extern const lv_font_t ui_font_sans_88_bold; // 真机 font_time
```

这样「预览缺字」就是「真机缺字」，不会被预览器自带的 Montserrat/SimSun 掩盖。
`LV_USE_FONT_PLACEHOLDER=1` 仍开着（LVGL 默认行为，缺字会画占位方块）。

### 4.3 `lv_conf.h` 是 host 专用，不代表固件配置

`gen_conf.py` 的 `OVERRIDES` 明确切成：`LV_COLOR_DEPTH=16`、`LV_USE_OS=LV_OS_NONE`、
`LV_USE_NUTTX=0`、`LV_USE_SDL=0`、`LV_USE_STDLIB_*=LV_STDLIB_CLIB`（4MB 池在 host 上没必要）、
`LV_USE_LOG=0`、`LV_USE_PERF/MEM_MONITOR=0`。**固件用的是它自己的 lv_conf**。

### 4.4 RGB565 -> RGB888 的展开公式必须固定

`raw2png.py` 用 `((v>>11)&0x1F)*255//31`，不是常见的 `(v<<3)|(v>>2)`。
两者在高位相同、低 3 位差 1..3 —— 实测**每页 91~99% 的像素会变**（见 §6.3）。
所以要和 golden 对上，这个公式不能「顺手优化」。

### 4.5 `check_our_fonts.py` 的三层判据

只解析字体 `.c` 里的 cmap（不编 LVGL、不链接），并沿 `.fallback` 链合并：

- **TOFU**：`ui_font_cjk_24` 的有效字形集合里没有 -> 真机空白/方块 -> **退出码 1**
- **DEMOTED**：`cjk_24` 原生没有、fallback 到 `cjk_18` -> 能显示但 24px 设计字变 18px
- **NONRENDER**：字面量只出现在 `strstr/strcmp` 等比较实参里 -> 不上屏，缺字形无害

另外还处理了两个容易误报的点（都是实测踩到的）：

1. **转义 UTF-8 字面量**：`key_input.c` 的 demo 问句写成 `"\xe6\x89\x93..."`，
   直接按文本扫会得到一堆乱码高位字符。脚本按 C 转义规则还原成**字节**再整体
   UTF-8 解码。
2. **数组初始化 + 间接上屏**：`g_demo_questions[]` 是数组初始化（不是任何调用实参），
   但下标被 `question = g_demo_questions[idx]` 取走，再经 `snprintf` 交给
   `pet_page_update_response()` 用 `UI_FONT_SMALL` 显示 —— 这些字**会上屏**。
   脚本会找「数组名被下标/return 转交」的证据，把它们算作会上屏，不误判成 NONRENDER。

### 4.6 幂等与增量

- `build/.lvgl_done` 存在 -> 跳过 LVGL 编译（原型 harness 的机制，保留）。
- `lv_conf.h` 比 `lv_conf_template.h` 新 -> 跳过生成。
- `assets/generated/lumina_expressions.c` 存在 -> 跳过转换（避免每次重跑都花 2s 重算 50 张图）。
- 其余步骤（编 UI/渲染/转 PNG）每次重跑，总开销 3~4s。

---

## 5. 验证记录（本机实测，2026-11-02）

环境：Ubuntu 22.04，gcc 11.4.0，Python 3.10.12，Pillow 9.0.1，numpy 2.2.6，20 核。

### 5.1 干净状态一条命令跑通 —— ✅

```bash
cd <REPO 根>
rm -rf tools/lumina_preview/build tools/lumina_preview/out tools/lumina_preview/assets/generated
bash tools/lumina_preview/run.sh --clean
```

结果：**退出码 0**，14 张 PNG + `contact_sheet.png` + `scan.txt` + 两份报告全部生成。

### 5.2 与参考图像素级一致 —— ✅ 0 像素差异

`/tmp/lumina/preview/` 的 15 张参考图已复制进 `assets/golden/`（**逐 md5 相同**）。
本次渲染逐像素比较（Pillow 解码为 RGB888 后比较）：

```
page_01_watchface.png    IDENTICAL      page_09_weather.png      IDENTICAL
page_02_apps.png         IDENTICAL      page_10_settings.png     IDENTICAL
page_03_heart.png        IDENTICAL      page_11_about.png        IDENTICAL
page_04_spo2.png         IDENTICAL      page_12_ai.png           IDENTICAL
page_05_sport.png        IDENTICAL      page_13_expressions.png  IDENTICAL
page_06_rest.png         IDENTICAL      page_14_music.png        IDENTICAL
page_07_alarm.png        IDENTICAL      contact_sheet.png        IDENTICAL
page_08_timer.png        IDENTICAL
合计: 0/3098186 像素不同 = 0.000000%
```

更硬的证据（不用 Pillow 解码，直接比文件字节）：
**14 个 `.raw` 帧与原型 harness 的 `/tmp/lvhost/raw/*.raw` 逐 md5 相同**；
15 张 PNG 与参考图逐 md5 相同；`scan.txt` 与原型 `diff` 无输出。

> 本任务允许「编译器浮点/抗锯齿差异 ≤0.1%」，**实测差异率是 0.000000%**，不是接近阈值。

### 5.3 换优化级别不改变像素 —— ✅

把 `CFLAGS_COMMON` 的 `-O1` 换成 `-O2` 全量重编（`rm build/.lvgl_done`）后重跑：
**仍是 0/3098186 像素不同**。说明这份渲染对编译器优化级别不敏感（LVGL SW 渲染
走定点/查表路径，没有浮点累积差异）。之后已恢复 `-O1`。

### 5.4 「换色」实验（说明颜色公式的敏感度）

同一批 `.raw`，只把 RGB565->RGB888 的展开公式从 `*255//31` 换成 `(v<<3)|(v>>2)`：

| 页面 | 不同像素 | 比例 | 单像素最大色差 |
|---|---|---|---|
| watchface | 163,702 / 175,500 | 93.28% | 3 |
| expressions | 160,598 / 175,500 | 91.51% | 3 |
| weather | 174,179 / 175,500 | 99.25% | 3 |
| **3 页合计** | | **94.68%** | 3 |

结论：**换色公式会让 9 成像素「差 1~3 个色阶」**，肉眼几乎看不出，但 md5/逐像素比对
会立刻全部报红。所以这个公式固定写在 `raw2png.py` 里，不作为“可优化项”。

### 5.5 我们固件的缺字检查 —— ✅ 能跑，结论见下

`python3 scripts/check_our_fonts.py`（`run.sh` 第 10 步，输出同时落
`out/our_font_coverage.txt`）：

```
扫描: packages/ai_agent/src/ui
来源: 32 个 .c + text.ini/theme.ini，去重字符 544 个（非 ASCII 456 个）
字体: ui_font_cjk_24  字形 532 个, fallback 链: ui_font_cjk_24 -> ui_font_cjk_18
字体: ui_font_cjk_18  字形 3872 个, fallback 链: ui_font_cjk_18

TOFU      : 0  （真机会显示空白/方块）
DEMOTED   : 57 （24px 设计字被降级成 18px）
NONRENDER : 1  （仅比较用，缺字形无害）
-> 退出码 0
```

- **TOFU = 0**：所有会上屏的字至少能在 `cjk_24 -> cjk_18` 的 fallback 链里拿到字形，
  真机不会出现空白方块。
- **DEMOTED = 57**：这 57 个汉字没烘焙进 24px 的 `ui_font_cjk_24`（它只有 532 个字形），
  靠 fallback 用 18px 的 `ui_font_cjk_18` 显示。**能看清楚，但比设计意图小一号**，
  在标题/大字号位置会显得偏小。典型来源：`health_summary.c`（做/健/康/结/足/昨/夜…）、
  `key_input.c` 的 demo 问句（客/厅/卧/室/灯/空/调…）、`sleep_page.c`（医/疗/级/清）。
  改版若要 24px 正常显示，需把这些字加进 `packages/ai_agent/tools/gen_font.py`
  的扫描/`EXTRA_CHARS` 后重烘字体。
- **NONRENDER = 1**：`❤`（U+2764）只出现在 `lvgl_ui_channel.c:372` 的
  `strstr(text, "❤")` 里，用于判断情绪，从不上屏 —— 缺字形无害，**不计入失败**。
  其它字体（cjk_24/cjk_18/sans_16/sans_16_bold/sans_24）都没有它，但也不需要。
- 退出码策略：**TOFU 有 -> 1；只有 DEMOTED/NONRENDER -> 0**。想要更严可加
  `--include-nonrender`。

> ⚠️ 交叉检查：出厂固件的 `ui_text_runtime.c: ui_text_check_font()` 只在**启动时**
> 检查 `text.ini` 的 key（运行时可改的文案），不覆盖源码里的硬编码字面量。
> 本脚本覆盖全部 32 个 `.c` 的字面量，是它的超集。两边结论不同时以本脚本为准。

### 5.6 耗时

| 场景 | 实测 |
|---|---|
| **首次全量**（`--clean`，20 核，含 50 张图转换） | **8.7~8.8 s**（LVGL 3s / 资产转换 2s / 其余 ~3.7s） |
| **二次增量**（无改动，全跳过） | **4.0~4.1 s** |
| 首次全量，**单核**（`taskset -c 0`，最坏情况） | **22.4 s**（LVGL 编译从 3s 涨到 ~15s） |
| 二次增量，单核 | 3.9 s |

峰值内存 171~175 MB（`/usr/bin/time -v` 多次实测）。LVGL 编译阶段吃满 20 核（644% CPU）。

---

## 6. 原 harness 的问题（如实记录，有的已修、有的保留）

搬移自 `/tmp/lvhost` + `/tmp/lumina/preview/harness`。发现的问题：

| # | 问题 | 状态 |
|---|---|---|
| 1 | **全部脚本硬编码 `/tmp/lvhost`、`/tmp/lumina` 绝对路径** —— 换机器/换目录直接跑不起来，也无法入库 | ✅ 已改为 `common.sh` 统一推导（`REPO_ROOT`/`VENDOR_ROOT`），可用环境变量覆盖 |
| 2 | `convert_expr.py` 的 `measure()` **引用 `main()` 的局部变量 `src_dir`**，`--measure-all-sizes` 路径必然 `NameError`（原 harness 从没跑过这个分支） | ✅ 已补上 `src_dir` 形参（默认路径不变，`--all`/`--measure-only` 行为不变） |
| 3 | `host_preview.c` 把 `page_manifest.txt` / `scan.txt` 写死在 `/tmp/lvhost/`，同时 raw 帧写 `argv[1]` —— 产物分裂在两个目录 | ✅ 已改为都写 `<outdir>/` |
| 4 | `convert_expr.py` 的 banner 写「原型输出，勿直接入库」，但它现在是构建中间件 | ✅ 已改文案并说明生成方式 |
| 5 | `coverage.c` 硬编码了一份**手工维护的 `TEXTS[]`** lumina 文案表（60 条），与 `lumina_watch_ui.c` 实际字面量已不一致；且它只验 lumina 的文案，对我们自己的固件没用 | ⚠️ **保留但降级**：`coverage.c` 仍编译成独立可执行（`build/coverage`）作交叉验证，但 `run.sh` 不依赖它；我们固件的缺字检查改用 `check_our_fonts.py`（直接解析 cmap + 自动扫字面量） |
| 6 | 原型 `literals.h` 是手工跑正则生成的中间件，`TEXTS[]` 里含 `"lumina_watch_ui.h"`、`"%u 次/分"` 这类**格式串/头文件名**，会把 `%`、`u` 当成待检查字符 | ⚠️ 未搬移该文件（`literals.h` 未入库）；新脚本扫字面量时包含格式串，但 `%u` 只查 ASCII，不影响中文结论 |
| 7 | `scan.txt` 的 `TOUCH_TARGET_SMALL` 对所有可点对象报 `72x44`（同一组返回键），**阈值 60x60 是硬编码的判断标准**，不是真机触摸规格 | ⚠️ **保留原样（诚实标注）**：这是原型里定的启发式阈值，本任务不允许改 `host_preview.c` 的扫描逻辑（避免“搬移”变成“改设计结论”），如需调整请另开任务 |
| 8 | `host_preview.c` 的 `fill_demo_state()` 里 `weather_status=STALE`、`ai_status=LOADING` 是**为了截图好看挑的状态**，不是真机默认态 | ⚠️ 保留并在此标注：14 页截图不代表真机首屏状态 |
| 9 | 渲染的 `pump_display()` 用 `lv_tick_inc(LV_DEF_REFR_PERIOD)` 手动喂 tick，**不是真实时钟** | ⚠️ 保留：静态截图够用，但不适合验证动画时序 |
| 10 | `build_preview.sh` 用 `-w` 静音全部告警，且把 `coverage.c` 与 `host_preview.c` 混在 `ui_*.o` 里链接（两个 `main()` 会冲突，原脚本靠“只编不链”侥幸避开） | ✅ 已拆开：预览源码用 `-Wall -Wextra`（告警留档到 `build/preview_warnings_*.txt`），`coverage` 单独链接 |
| 11 | `coverage.c` **缺 `<string.h>`**，`memset` 隐式声明（C99 下 2 条 warning，原脚本用 `-w` 静音所以没人发现） | ✅ 已补 `#include <string.h>`（0 告警）；`build_preview.sh` 现在会把它编成独立 `build/coverage` 作交叉验证工具 |

  修复后 `build/preview_warnings_*.txt` 四个文件全部 **0 行告警**。

---

## 7. 以后怎么扩展

### 7.1 把预览目标从 lumina 换成我们自己的 ui_nav 页面

现在的 harness 只认 `lumina_watch_ui.h` 的三个 API（`lumina_ui_init/set_state/show`）+
一个 `lumina_ui_port_t` 适配层。要预览我们自己的页面（`packages/ai_agent/src/ui/`），
需要做**适配层替换**，大致工作量：1 个新 `host_preview_ours.c` + 1 个 `port` 实现。

步骤：

1. **找 UI 入口**：`packages/ai_agent/src/ui/ui_nav.c` 的 `ui_nav_goto(UI_PAGE_*)` +
   各 `*_page.c` 的 `*_page_create(lv_obj_t *parent)`。我们的页面不像 lumina 有统一
   `set_state`，数据来自 `lvgl_ui_channel.c` 的 channel 结构体。
2. **写 port**：把 `lvgl_ui_channel.c` 里读硬件/网络的部分换成 host 假数据
   （照抄 `host_preview.c: fill_demo_state()` 的写法）。
3. **绕开平台依赖**：`lvgl_ui_channel.c` 依赖 pthread/蓝牙/framebuffer。两个办法：
   - （推荐）只链**页面文件**（`watchface_page.c`、`heart_page.c`…）+ `ui_theme.c`，
     把 channel 的数据注入点用 `#ifdef` 或桩函数替换；
   - 或者给 `host_preview` 加 `-DLUMINA_HOST_PREVIEW`，在我们自己的源码里加条件编译
     （但这就要改固件源码了，本任务禁止）。
4. **字体宏**：我们的页面用 `UI_FONT_BODY/SMALL/TIME`（`ui_theme.h` 的宏），
   它们的 `extern` 已经是真机字体，直接复用 `build_assets.sh` 编出来的 `.o`。
5. **页面表**：把 `host_preview.c` 的 `PAGES[]` 换成 `ui_nav` 的页面枚举，
   每页调 `ui_nav_goto(page)` 再渲染。
6. **缺陷扫描**：`collect()/analyse()` 是**通用的 LVGL 对象树遍历**，与 lumina 无关，
   可直接复用（`scan.txt` 的四类判据都能用在我们页面上）。

### 7.2 换 LVGL 版本

`gen_conf.py` 的 `OVERRIDES` 是按宏名改的，只要 LVGL 还叫这些宏就不用动；
宏改名会**明确报错**（`模板里找不到这些宏`），不会静默生成错配置。

### 7.3 换表情资产规格

`convert_expr.py` 支持 `--size 96/120/144`、`--variant pad/crop`。
`gen_expr_map.py` 只要求「50 个 id 且每个都有 `ui_expr_<id>`」，与尺寸无关。
换尺寸时同步更新 `build_assets.sh` 里的 `--size`（在 `run.sh` 第 3 步）。

### 7.4 更新 golden 参考图

确认新渲染是**有意的改版**（不是回归）后：

```bash
cp tools/lumina_preview/out/page_*.png tools/lumina_preview/out/contact_sheet.png \
   tools/lumina_preview/assets/golden/
```

改版前务必先看 `out/golden_diff.txt` 里到底哪些页变了、差异比例多少。

### 7.5 接 CI

`run.sh` 的两条退出码已经可以直接用：

- **像素回归**：非 0 表示与 golden 不一致（或有页面没生成/尺寸不对）。
- **字体回归**：非 0 表示我们固件文案里出现了字体没有的 TOFU 字。

只跑字体检查（最轻，不编译）：
`python3 tools/lumina_preview/scripts/check_our_fonts.py`

---

## 8. 已知限制（不做即为不支持）

1. **只渲染静态页**：动画、滚动、过渡帧看不到。
2. **不支持触摸/手势验证**：`lumina_ui_swipe/key1` 有接口但 harness 没调。
3. **不支持多语言/动态文案验证**：`text.ini` 的运行时替换在 host 上是另一条路径，
   本 harness 直接给 `lumina_ui_state_t` 塞死字符串。
4. **不验证 50 张表情在真机上的 flash 占用**：`assets/generated/lumina_expressions.c`
   的数据量（914,976 B）只是参考，真机是 RGB565A8 原样入 flash，需自己算。
5. **耗时数字是本机 x86 的**，不能推导到 SF32LB52（那颗是 Cortex-M55 @ ~240MHz，
   且没有 4MB 的 PSRAM 池）。

## 固件资产生成（批次 3 新增）

`scripts/gen_firmware_expressions.py` 把表情 PNG 烘焙成固件可用的
`packages/ai_agent/src/ui/assets/ui_expressions.c`（RGB565A8 常量数组）：

```bash
# 前 8 款（= 当前固件出货形态）
python3 tools/lumina_preview/scripts/gen_firmware_expressions.py --count 8
# 全量 50 款（约 1.4 MB，先确认 flash 余量）
python3 tools/lumina_preview/scripts/gen_firmware_expressions.py --all
```

顺序取自 `assets/reference_lumina_expressions.c`（CloudAvatars.kt 顺序），
**不按文件名排序**：表盘、表情页、`/data/expression` 里存的数字都依赖这个
顺序，扩量时只能在尾部追加。

---

## 预览「我们自己固件的页面」（批次 2/3 新增）

`run.sh` 渲染的是 lumina 原型的 14 页；`scripts/preview_our_pages.sh` 渲染
**我们固件里真实的页面源码**（`ui_nav.h` 的全部 14 页），用的是同一份
LVGL、同一份字体、同一份表情/图标资产、同一份 `ui_theme.c`：

```bash
bash tools/lumina_preview/run.sh                    # 先跑一次，产出 host LVGL 目标文件
bash tools/lumina_preview/scripts/preview_our_pages.sh            # 14 页全渲 + 拼图 + 校验
bash tools/lumina_preview/scripts/preview_our_pages.sh watch      # 只渲表盘
```

产物：`out/our_<page>.png`（共 14 张）与 `out/our_pages_contact_sheet.png`
（PIL 4x4 拼图，每格标注页名）。页面名与 `ui_page_t` 一致：

```
watch apps heart spo2 music timer sport sleep settings ai about alarm weather expr
```

最后一步 `scripts/verify_our_pages.py` 会检查每张图存在、390x450、像素方差
不为 0（排除全黑/全白/纯色），并打印 mean/stddev/颜色数表格。

能验证：布局越界/重叠、`lv_image_set_scale` 是否真的缩放、字体缺字、配色。
不能验证：真实触摸驱动/手势判定、真实定时器时序、真实数据、帧率。
心率/血氧/运动/休息页是"没有传感器"的空状态；音乐页是空列表 + 合法
IDLE 快照；天气页用 `stubs.c` 里显式标注 `PREVIEW-FIXTURE` 的共享缓存
演示值（24.0°C / 12.0 km/h），不发起网络请求。所有演示值都只用于布局审查，
不是真机读数。

> 2026-11-02 用它拦下了两个真问题：表情图在表盘上 1:1 会溢出垂直预算
> （98px 预算 vs 103px 图），以及应用入口的标签会压到下一行卡片上。

固件源码里的 `#include <nuttx/config.h>` 由脚本生成一个空头，并通过
`-include` 强制包含（`alarm_page.c -> cron_service.h -> agent_compat.h`
这条链自己不 include）；只补 `OK/ERROR`、`CONFIG_LCD_MAXCONTRAST`。
`settings_page.c` 需要的 `nuttx/lcd/lcd_dev.h` 和 `weather_page.c` 需要的
`cJSON.h` 在 `src/our_pages/fakeinc/` 下提供了最小声明。所有 `CONFIG_*`
都不定义，所以预览走"没有传感器数据"的分支。

---

## 关键状态截图（22 张，2026-11-04 新增）

14 页默认态只能看"空数据"布局。`scripts/preview_our_states.sh` 在**不改固件源码**
的前提下，用 host harness 注入 22 个关键状态，产出
`out/state_<page>__<state>.png`：

```bash
bash tools/lumina_preview/scripts/preview_our_states.sh
# 只渲指定状态（可多个，按 page__state）
bash tools/lumina_preview/scripts/preview_our_states.sh apps__page2 timer__finished
```

产物（全部在 `out/`，该目录已被 `.gitignore` 覆盖）：

```text
out/state_<page>__<state>.png          22 张关键状态
out/state_screens_contact_sheet.png    5x5 拼图（标注 page__state + 中文状态）
out/state_screens_verify.txt           校验报告（22/22 passed）
```

状态清单只维护一份：`scripts/state_screens.tsv`（page / state / 中文 label）。
构建入口也已统一为 `scripts/build_our_pages.sh`，14 页脚本与状态脚本共用。

### 22 张清单与注入方式

| # | 文件 | 期望画面 | 注入 |
|---:|---|---|---|
| 1 | `state_apps__page2.png` | 设置/关于/闹钟/天气/表情 5 格 +「第 2 / 2 页」 | 记录 `ui_gesture` 回调，调 `preview_swipe(UP)` |
| 2 | `state_expr__page2.png` | 奥利给/保持微笑 +「第 2 / 2 页」 | 同上 |
| 3 | `state_heart__history.png` | 柱状图 + 72 +「最近测量 HH:MM」 | 历史 stub 30 条 + `last_ok/bpm` |
| 4 | `state_heart__measuring.png` | 「测量中…」+ 静止提示 | 点「开始测量」+ `is_running=true` |
| 5 | `state_heart__failed.png` | 「信号弱，请重试」 | 点按钮 + 1s 后 `is_running=false/last_ok=false` |
| 6 | `state_spo2__result.png` | 98 +「本次测量有效」 | 点按钮 + 全局量 98 + `busy=false` |
| 7 | `state_spo2__last.png` | 98 +「上次测量（非本次）」 | create 前写全局量 98 |
| 8 | `state_timer__running.png` | 04:59 + 红色「停止」+「计时中…」 | 点「开始」+ 跳 1.1s |
| 9 | `state_timer__finished.png` | 00:00 +「时间到」+ 浅色底 | 点「开始」+ 跳 5min |
| 10 | `state_alarm__list.png` | 3 行名字 + HH:MM +「轻点一行即可删除」 | `cron_list_jobs` 填 3 条 |
| 11 | `state_alarm__editor.png` | 2 行列表 + 时/分 + 取消/确定 | 点「+ 新增闹钟」 |
| 12 | `state_alarm__editor_error.png` | 「添加失败」 | 展开后点「确定」+ `cron_add_job=ERROR` |
| 13 | `state_weather__loading.png` | -- +「获取中…」 | 缓存 miss + 网络桩阻塞 |
| 14 | `state_weather__error.png` | 「获取失败」+「连接失败」 | 缓存 miss + 网络桩错误 |
| 15 | `state_weather__stale.png` | 旧数据 +「离线缓存 · 3 小时前 · 刷新失败」 | 3h 前 stamp + 网络错误 |
| 16 | `state_ai__history_empty.png` | 云图 +「还没有对话记录」 | `pet_page_open_history()` |
| 17 | `state_ai__history_list.png` | 「我」/「小云」气泡列表 | 历史 stub 4 条 + 打开浮层 |
| 18 | `state_ai__reply.png` | 3 行文本（长回复 DOT 截断） | `pet_page_update_response()` |
| 19 | `state_sport__data.png` | 8432 / 96 / 5.90 公里 / 421.6 | `pedometer alive` + 值 |
| 20 | `state_sleep__data.png` | 7时32分 + 23:08 - 06:40 + 夜间休息 | `rest_track` + `pedometer` |
| 21 | `state_settings__dnd_on.png` | 勿扰「开」accent 色 | `pet_care_get_dnd()=true` |
| 22 | `state_watch__data.png` | ❤72 👟8432 | `-DUI_SIM` + `ui_sim_steps()=8432` |

`scripts/verify_state_screens.py` 检查每张图存在、390x450、非全黑/全白/纯色；
额外打印「与 `out/our_<page>.png` 默认态的差异像素占比」（0% 表示状态注入
可能没生效，只报告不判失败）。

### harness 侧实现（都在 `src/our_pages/`）

1. **状态夹具**：`stubs.c` 的 `preview_set_state(const char *)`，在页面
   `create()` 之前设置心率历史 / 血氧全局量 / cron 任务 / 天气缓存 /
   `rest_track` / `pedometer` / AI 历史等桩的返回值。空 state 时全部保持
   "没有数据"，所以 14 页默认态行为不变。
2. **点击注入**：`main.c` 遍历对象树找文本完全匹配的 label，向上找到第一个
   `CLICKABLE` 祖先，直接 `lv_obj_send_event(obj, LV_EVENT_CLICKED, NULL)`。
   不依赖触摸 indev；`ui_gesture_was_drag()` 在 host 恒 false，不会被防误触闸门拦。
3. **手势注入**：`stubs.c` 的 `ui_gesture_attach()` 记录页面注册的回调，
   `main.c` 调 `preview_swipe(UI_GESTURE_SWIPE_UP)`。页面自己的 20ms
   重建定时器随后刷新宫格（不需要真 indev）。
4. **时间跳变**：`main.c` 不再用错误的 `lv_tick_set_cb`，改成每帧
   `lv_tick_inc()` + `lv_timer_handler()`；状态动作可一次性推进 5 分钟
   触发计时器到点，或推进 1s 让心率/血氧页的轮询收结果。
5. **天气三态**：`weather_cache_get()` / `weather_cache_age_s()` /
   `vela_https_get()` 三个桩按 state 切换；weather 页自己的 worker 线程
   是真实 pthread，所以 error/stale 要留 ~1s 真实时间给它跑完再截图。

### 已知的 host 伪影（不是固件布局 bug）

- **alarm 编辑区默认时间差 8 小时**：`edit_reset_time()` 用
  `time(NULL) % 86400`（固件假设 epoch 是"本地墙钟"，见源码注释），
  而列表时间用 `localtime_r()`。host 上 `time(NULL)` 是真实 UTC epoch，
  于是同一张图里列表显示 20:30、编辑区显示 12:05。真机上两者都是本地时间。
- **sport 有数据时脚注折行**：`距离/消耗为估算（步长 0.70m、体重 60kg）`
  在 350px 宽下折成两行，末行只剩一个「）」。这是固件文案的真实折行，
  本轮只截图、不改固件。
- **AI 记录浮层滚到底**：列表按"最新在下方"滚到最底，最早的「我」气泡
  可能刚好在视口上方；这是 `lv_obj_scroll_to_y(LV_COORD_MAX)` 的预期行为。
- 点击/手势/跳时都是 host 侧注入，只能证明"页面在这些状态下布局正确"，
  不能证明真实触摸/手势识别/定时器时序。

回归：`preview_our_pages.sh` 仍为 14/14 passed；`-DUI_SIM` 只影响表盘
步数分支，默认态 `ui_sim_steps()` 返回 -1，所以 `out/our_watch.png` 仍是
「👟--」。

---

