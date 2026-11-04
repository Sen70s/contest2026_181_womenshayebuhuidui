# 91 — 固件 UI 全页清单与 host 截图验收（只读盘点）

> 范围：Team 181 / SF32LB52 手表固件 `packages/ai_agent/src/ui` 的全部用户可见页面。
> 目的：为「在本机/模拟器上把现有全部 UI 渲染成截图」提供页面清单、状态清单与验收口径。
> 方法：只读源码 + 编译探针（`gcc -c`）核对依赖；**未改任何固件源码、未接触真机/串口**。
> 快照时间：2026-11-04 19:45（host 预览 harness 正在并行演进，数值以当时产物为准）。
> 唯一写入：本文件。

---

## 0. TL;DR

1. **「现有 UI」= 14 个 `ui_page_t` 页面**（`ui_nav.h:24-40`），没有第 15 页。
   其中 **MUSIC 页在应用列表里没有入口**（设计上隐藏），只能通过 NSH `ui goto music` 进入。
2. **没有独立弹窗/对话框/toast/锁屏/通知中心/设置二级页**。`src/ui` 下 0 个 `lv_msgbox` /
   `lv_dropdown` / `lv_roller` / `lv_keyboard` / `lv_textarea`。
3. 真正「像一页」的**页内二级态**有 4 个：应用列表第 2 页、表情第 2 页、闹钟「新增」编辑区、
   AI 对话记录全屏浮层。它们不是 `ui_page_t`，但截图必须覆盖。
4. 当前 host harness（`tools/lumina_preview/scripts/preview_our_pages.sh`，19:43 产物）
   **已经渲出 14/14 页默认态**，产物 `out/our_<slug>.png` + `out/our_pages_contact_sheet.png`，
   `out/our_pages_verify.txt` 14/14 passed（只校验尺寸/非纯色，不校验布局）。
5. 当前 14 张是**空数据/固定演示值**的默认态；还缺 **约 22 个关键状态**（录音中、测量中、
   倒计时到点、闹钟列表/编辑区、天气失败/离线缓存、列表第 2 页、对话记录浮层……）。
   逐条可行性与注入方法见 §8、§9。
6. **陷阱**：`run.sh` 渲的 `out/page_01..14_*.png` 是 **Lumina 设计稿**（14 页，顺序/布局
   都不是固件），**不能**当作我们的 UI 交付。固件 UI 只认 `out/our_*.png`。
7. **死代码/不可见 UI**：`launcher_page.c`、`bubble_grid.c` 未参与编译；
   `lvgl_ui_channel.c` 里那套旧「桌宠 stage + 头顶气泡 + 菜单/历史页」虽被编译，但在
   `ui_build()` 末尾就被 `pet_display_set_stage_visible(false)` / 菜单隐藏，
   气泡默认 HIDDEN 且被整屏不透明页面盖住 —— **现状不可见，不应出现在截图清单里**。

---

## 1. 「全部现有 UI」的准确范围

### 1.1 判定依据（唯一权威）

| 依据 | 位置 | 说明 |
|---|---|---|
| 页面枚举 | `packages/ai_agent/src/ui/ui_nav.h:24-40` | 14 个 `ui_page_t`，顺序即导航 `ui next` 顺序 |
| 创建/销毁分派 | `ui_nav.c:44-109`（destroy）、`:178-262`（create） | 每页一个 create/delete |
| 触屏入口 | `ui_nav.c:115-176` + `applist_page.c:75-93` | 应用列表实际只有 11 项 |
| NSH 入口（隐藏页的旁路） | `ui_cmd.c:42-58` | 14 个页名，含 music |
| 编译清单 | `packages/ai_agent/CMakeLists.txt:133-236` | 确认哪些源码真的进固件 |

### 1.2 哪些算、哪些不算

**算（必须出现在截图交付里）**

- 14 个页面：WATCHFACE / APPLIST / HEART / SPO2 / MUSIC / TIMER / SPORT / SLEEP /
  SETTINGS / AI / ABOUT / ALARM / WEATHER / EXPRESSION。
- 4 个页内二级态（§6）：应用列表 p2、表情 p2、闹钟新增编辑区、AI 对话记录浮层。
- 每页的「空数据 / 有数据 / 错误 / 进行中」等可见状态（§5 逐页表）。

**不算（容易误算）**

| 对象 | 为什么不算 |
|---|---|
| `out/page_01_watchface.png … page_14_music.png` | Lumina **设计稿**预览（`run.sh`），不是固件代码渲染 |
| `src/ui/lumina_watch_ui.c`、`lumina_expressions.c` | 设计稿实现，固件不链接 |
| `src/ui/launcher_page.c` | CMakeLists 明确「已由 watchface_page 取代，不再编译」（`:206`） |
| `src/ui/bubble_grid.c` | 2026-11-02 起 applist 不再使用，未加入编译（`:166-167`） |
| `src/ui/pet_display.c` + `lvgl_ui_channel.c` 旧 stage/气泡/菜单/历史页 | 编译但被隐藏/覆盖，正常操作不可见（见 §7） |
| `src/ui/ui_engine.c` + `assets/ui_json_embedded.c` | 编译但 `ui_build()` 固定走手写页面（`lvgl_ui_channel.c:1229-1248`） |
| `src/ui/qrcode_display.c` | 受 `CONFIG_LV_USE_QRCODE` 保护，本构建 **未开**（`.config`） |
| `src/ui/health_summary.c` / `pet_care.c` | 只生成文本/发气泡，不建 LVGL 控件（0 个 `lv_obj_*` 调用） |
| 手机 App / Web 页面 | 不在手表 UI 范围 |

### 1.3 本报告不重复「设计稿 14 页」的盘点

`run.sh` 的设计页顺序是 watchface/apps/heart/spo2/sport/rest/alarm/timer/weather/settings/
about/ai/expressions/music（`src/host_preview.c:294-309`），与固件枚举顺序不同，不能混用。

---

## 2. 页面总表

| # | enum | slug（harness） | 屏上标题 | create（文件:行） | delete | 用户入口 | 返回方式 | 状态栏 |
|---:|---|---|---|---|---|---|---|---|
| 0 | WATCHFACE | `watch` | 无标题（时间为主体） | `watchface_page.c:530` | `:688` | 开机默认页 | 上滑→APPLIST（下滑无动作） | 有（仅电量） |
| 1 | APPLIST | `apps` | 「所有应用」+「点击打开 · 上滑查看更多」 | `applist_page.c:299` | `:379` | 表盘上滑 | 下滑：p1→表盘，p2→p1 | 无（自绘标题） |
| 2 | HEART | `heart` | 「心率」 | `heart_page.c:366` | `:497` | 应用格 / 表盘心率卡 | 下滑→表盘 | **无**（自绘） |
| 3 | SPO2 | `spo2` | 「血氧」 | `spo2_page.c:201` | `:311` | 应用格 | 下滑→表盘 | **无**（自绘） |
| 4 | MUSIC | `music` | 「音乐」 | `music_page.c:318` | `:450` | **无触摸入口**（仅 `ui goto music`） | 下滑→表盘 | 有 |
| 5 | TIMER | `timer` | 「计时器」 | `timer_page.c:255` | `:332` | 应用格 | 下滑→表盘 | 有 |
| 6 | SPORT | `sport` | 「运动」 | `sport_page.c:201` | `:249` | 应用格 / 表盘步数卡 | 下滑→表盘 | 有 |
| 7 | SLEEP | `sleep` | 「休息」 | `sleep_page.c:298` | `:361` | 应用格（「休息」） | 下滑→表盘 | 有 |
| 8 | SETTINGS | `settings` | 「设置」 | `settings_page.c:364` | `:485` | 应用格 p2 | 下滑→表盘 | 有 |
| 9 | AI | `ai` | 无标题（问候语） | `pet_page.c:591` | `:809` | 应用格 p1 第一个 / 物理 KEY1 / 收到回复时自动切 | **返回按钮** + 下滑 | 无 |
| 10 | ABOUT | `about` | 「关于」 | `about_page.c:89` | `:161` | 应用格 p2 | **返回按钮** + 下滑 | 无（自绘） |
| 11 | ALARM | `alarm` | 「闹钟」 | `alarm_page.c:912` | `:1003` | 应用格 p2 | 下滑→表盘 | 有 |
| 12 | WEATHER | `weather` | 「天气」 | `weather_page.c:1004` | `:1272` | 应用格 p2 | 下滑→表盘 | 有 |
| 13 | EXPRESSION | `expr` | 「小云表情」+「选择你喜欢的小云 · 上滑翻页」 | `expression_page.c:231` | `:290` | 应用格 p2 | 选一款自动回表盘；下滑 p2→p1，p1→表盘 | 无（自绘） |

补充：

- **只有 AI 和 ABOUT 有屏上「返回」按钮**；其余 12 页只能靠下滑返回（`ui_nav_attach_back_swipe`）。
- **状态栏只有 8 页有**（watch/music/timer/sport/sleep/settings/alarm/weather），
  且电量是**写死 100%**（`ui_theme.c:566-576` 的演示常量，不是真实电量）。
- 「心率/血氧」两页连状态栏都没有，标题是页面自绘的 24px 行 —— 视觉上与其他页不一致，验收时别当成 bug 修。

---

## 3. 导航关系

~~~text
                    ┌──────────────┐
                    │  WATCHFACE   │◄────────────────────────────┐
                    └──────┬───────┘                             │
             上滑(仅此手势) │  点心率卡        点步数卡             │
                    ┌──────▼───────┐   │              │          │
                    │   APPLIST    │   │              │          │
                    │  p1: 6 格    │   │              │          │
                    │  p2: 5 格    │   │              │          │
                    └──┬───┬───┬───┘   │              │          │
        click each app │   │   │       │              │          │
          ┌────────────┘   │   └───────┼──────────────┘          │
          ▼                ▼           ▼                         │
   HEART/SPO2/...     SETTINGS      SPORT                        │
   ALARM/WEATHER/     ABOUT         (步数卡)                     │
   TIMER/SLEEP/       AI/EXPR                                    │
          │                │                                      │
          └────────────────┴──────────── 下滑 / 返回按钮 ────────┘
~~~

规则（`ui_nav.c:268-296`）：

1. **没有页面栈**。`ui_nav_back()` 永远回 `UI_PAGE_WATCHFACE`；从心率页返回也是回表盘，
   不是回应用列表。
2. `ui_nav_goto(page)` 对「已经在当前页」直接 return（不重建）。
3. 切页顺序固定为 **先 destroy 旧页（连同其定时器）→ 再 create 新页**；无转场动画。
4. 应用列表 p2 下滑 → p1；p1 下滑 → 表盘。表情页同理。AI/关于：返回按钮与下滑等价。
5. 物理键（`key_input.c:44-60, 276-330`）：**KEY1=打开 AI 页**，KEY2 短按=语音开/停，
   KEY2 长按 1.5s=让本地模型回答示例问题；**没有物理返回键**。
6. 收到 agent 回复/主动关怀时：若当前在表盘，会自动 `ui_nav_goto(UI_PAGE_AI)`
   （`lvgl_ui_channel.c:789-794`、`:1952-1954`）；在其他页则不打断。

---

## 4. 所有页共享的外壳（截图的「公共基线」）

| 项 | 值/行为 | 代码 |
|---|---|---|
| 画布 | 390×450，RGB565 | `ui_theme.h:120-121` / harness |
| 页面根对象 | 390×450、整屏**不透明**底色 #F8FBFD、padding 20、禁滚动 | `ui_theme.c:238-256` |
| 状态栏 | 高 32，左标题 / 右「100%」+电池图标 | `ui_theme.c:515-578` |
| 字体 | 18px 全集（cjk_18, 3872 字）；24px 子集（cjk_24, 532 字，缺字回退 18px）；88px 仅数字/`.:-` | `ui_theme.h:124-138` |
| 安全区 | 左右 24、顶部 52、底部 40 | `ui_theme.h:167-171` |
| 主题/文案 | 真机加载 `assets/theme.ini` + `text.ini`（烘焙进 ui_json_embedded.c） | `lvgl_ui_channel.c:1209-1210` |

**关键结论（影响截图可信度）**

- 每页都是**整屏不透明**对象，因此：页与页之间不存在「跨页浮层」；AI 页的对话记录浮层是它自己的
  子对象（`pet_page.c:229-294`）。
- `theme.ini` 的每个键都与 `ui_theme_defaults()` 数值一致（逐项核对过），
  所以 host 只调 `ui_theme_defaults()` **不会产生色差**。
- `text.ini` 的键值与代码默认值一致；唯一差异 `settings.brightness.v = 中` 没被代码使用
  （设置页亮度行写死「100%」）。
- 字体缺字：当前文案 **TOFU=0**；有 57 个汉字在 24px 子集里缺失、会回退成 18px（如
  夜/昨/非/医/控/制/提/机/窗/锁…）。**动态文本（对话、闹钟名）都用 18px 全集**，
  注入 fixture 时不会出现方块口。
- 电池 **100% 是演示常量**（`watchface_page.c:135-138`、`ui_theme.c:567-569`），不是读数。

## 5. 逐页清单（数据依赖 + 状态 + host 可行性）

图例：✅ 当前 harness 已覆盖；🟡 改预览 stub/注入即可覆盖（不动固件）；⛔ 需真机/网络/真实时钟。

### 5.1 WATCHFACE 表盘（#0）

- 入口：`ui_nav_init()`（`lvgl_ui_channel.c:1244`）→ `watchface_page_create()`。
- 交互：上滑→应用列表；点心率卡→HEART；点步数卡→SPORT；语音按钮→`voice_channel_start/stop`。
- 数据依赖：
  - 时间：`time()/localtime_r`，年份不在 2024–2027 时显示 `--:--` +「时间未同步」。
  - 电量：`DEMO_BATTERY_ALWAYS_100=1` → 恒 100%。
  - 步数：真机走 uORB `algo_pedometer`（本构建 `CONFIG_UORB=y && CONFIG_FITNESS_ALGO=y`）；
    host 上这段被 `#ifdef` 排除 → `--`。另有 `#ifdef UI_SIM` 旁路
    （`ui_sim_steps()`），**host 可用 `-DUI_SIM` 注入真实感步数**（改动只在预览编译参数）。
  - 心率：`hr_measure_last_bpm()`，0 表示从未测到 → `--`。
  - 表情：`expression_current()`，1Hz 对齐（改了表情无需重建页面）。
  - 语音：`voice_channel_is_recording()` 每 500ms 刷新按钮文案。

| 状态 | 触发 | 屏上表现 | host |
|---|---|---|---|
| 默认（未测过/未计步） | 开机 | 时间+日期+云+❤-- 👟-- +「说话」+「上滑进入应用」 | ✅ `our_watch.png` |
| 有数据 | 测过心率 / 步数>0 | ❤72、👟8432（≥10000 显示 8.4k） | 🟡 `-DUI_SIM` + stub 返回 72 |
| 录音中 | 录音开始 | 按钮文字「说话」→「停止」 | 🟡 stub `is_recording=1` + 跑 ≥600ms |
| 换表情 | `expression_set(n)` | 云图变化（1.25x） | 🟡 创建前 set |
| 时间未同步 | RTC 年份越界 | `--:--` /「时间未同步」 | ⛔ 需伪造系统时钟，价值低 |

### 5.2 APPLIST 应用列表（#1）

- 2×3 宫格、两页；**11 个 app**，顺序见 `applist_page.c:75-93`：
  p1 = 小云/运动/计时器/心率/血氧/休息；p2 = 设置/关于/闹钟/天气/表情。
- **音乐入口被有意隐藏**（`:73-74` 注释），所以第 2 页只有 5 格。
- 数据依赖：仅 `expression_current()`（「小云」格用当前表情图，scale 160）。
- 状态：
  - p1（6 格）+ 页脚「第 1 / 2 页 · 上滑看更多」→ ✅ `our_apps.png`
  - p2（5 格）+ 页脚「第 2 / 2 页 · 下滑返回」→ 🟡 需触发页面的 swipe 回调（§8.4）
  - 小云格随当前表情变化 → 🟡 创建前 `expression_set()`
- 注意：格子标签用 18px；页脚/标题都是 18px（「所」不在 24px 子集）。

### 5.3 HEART 心率（#2）

- 数据依赖：`hr_measure_*`（后台线程、`/data/ai_agent/health/hr_history.json` 持久化、
  uORB/MAX30102 在其内部）。
- 结构：标题「心率」→ 88px 大数 →「BPM」→「最近测量 HH:MM」/「上次有效值」→ 30 根柱状图
  （84px 高；空时盖「暂无记录」）→ 200×56 按钮 → 提示行。
- 状态：

| 状态 | 触发 | 屏上表现 | host |
|---|---|---|---|
| 从未测过 | 开机/无历史 | `--`、BPM、「暂无记录」、「点按钮开始测量」 | ✅ `our_heart.png` |
| 有历史 | `hr_measure_history_snapshot`>0 | 柱状图 + 最近值 +「最近测量 HH:MM」 | 🟡 stub 返回 30 条 |
| 测量中 | 点按钮 | 「测量中…」、灰按钮、「测量中…保持手臂静止 30~40 秒」 | 🟡 发送 CLICKED + stub |
| 本轮失败 | 测量失败 | 「信号弱，请重试」 | 🟡 stub last_ok=false + is_running=false |
| 只有旧有效值 | last_ok=false 但有历史 | 旧值 +「上次有效值 · 最近测量 HH:MM」 | 🟡 同上 + 历史 |

### 5.4 SPO2 血氧（#3）

- 数据依赖：`g_max30102_spo2_pct` / `g_max30102_spo2_ok`（max30102 全局量）+
  `spo2_measure_start/busy`（独立线程，约 19s）。
- 结构：标题 → 图标 → 88px 数值 →「SpO2 %」→ 状态行 → 200×56 按钮 → 底部固定
  「参考范围 95~100%（非诊断）」。
- 状态：
  - 「尚未测量」/`--` → ✅ `our_spo2.png`
  - 「测量中…保持手臂静止」→ 🟡 点按钮 + `busy=true`
  - 「本次测量有效」+ 98 → 🟡 点按钮 + `busy=false` + 全局量 98
  - 「上次测量（非本次）」/「上次测量偏低，建议复测」→ 🟡 创建前设置全局量
  - 「测量失败，请重试」→ 🟡 `spo2_measure_start` 返回负值
- 注意：状态行**只描述本次/上次测量**，参考范围另起一行，不要混为一谈。

### 5.5 MUSIC 音乐（#4，隐藏入口）

- 入口：应用列表**没有**；只能 `ui goto music`（NSH）或代码直调。
- 数据依赖：`music_local_scan`（扫 `/data/music`）、`music_worker_*`（后台播放线程）、
  `pcm_file_stop`；播放器接口阻塞，UI 线程只读快照。
- 状态：
  - 本地无歌 + 未播放 + 音量 50% → ✅ `our_music.png`（stub 固定）
  - 有歌列表（文件名行）→ 🟡 `music_local_scan` 返回 2 个名字
  - 播放中（「播放中」、进度条/时间）→ 🟡 snapshot `PLAYING` + position/duration
  - 载入中/播放出错 → 🟡 点歌 + snapshot `ERROR`
  - 「已经在播放了」提示 → 🟡 点击时 snapshot=PLAYING
- 截图价值：低（用户不可达），但「全 14 页」口径下必须有。

### 5.6 TIMER 计时器（#5）

- 数据依赖：**无**（纯 LVGL + ui_theme）。到点只有页面内视觉反馈，无声音/震动代码。
- 结构：状态栏 → 88px `MM:SS` → 5 个 64×64 预设（1/3/5/10/15，默认 5）→ 160×64 开始/停止 →
  18px 提示行。
- 状态：
  - 空闲 05:00、5 选中、提示「点开始计时」→ ✅ `our_timer.png`
  - 计时中：数字 accent 色、按钮「停止」（warning 色）、「计时中…」→ 🟡 点「开始」
  - 已停止：恢复时长、提示「已停止」→ 🟡 开始→停止
  - 到点：00:00（warning 色）、「时间到」、整页底色变 UI_SURFACE_HI、按钮回「开始」→ 🟡 点开始 +
    `lv_tick_inc(>300000)` 触发 1Hz 定时器
  - 其它预设选中 → 🟡 点对应数字按钮

### 5.7 SPORT 运动（#6）

- 数据依赖：`pedometer_*`（真实读 `/dev/mpu0`，25Hz；本构建有 IMU 驱动）。
- 结构：状态栏「运动」→ 4 行（步数/步频/距离(米或公里)/消耗）→ 脚注（估算假设）。
- 状态：
  - IMU 不可用：全部 `--`、脚注「IMU 不可用」→ ✅ `our_sport.png`（stub alive=false）
  - 有数据：8432 步 / 96 步每分 / 5.90 公里 / 421.6 kcal → 🟡 stub alive=true
  - 静止（步数 0 或步频 0）：步频 `--`、脚注追加「静止时不估算步频」→ 🟡
- 注意：距离 <1000m 显示「米」1 位小数，≥1000m 显示「公里」2 位小数，单位标签会变。

### 5.8 SLEEP 休息（#7）

- 数据依赖：`pedometer_*` + `rest_track_*`（`rest.json` 持久化；夜间=连续静止≥45min 且
  本地 22:00–08:00）。
- 结构：状态栏「休息」→ 卡「昨夜休息」(350×120) → 注「基于体动估算，非医疗级」→ 卡「当前静止」→
  行「本次活动」→ 状态行（夜间休息/静止中/清醒）。
- 状态：
  - 无记录/无 IMU：卡内 `--`、「暂无昨夜记录（需连续静止 45 分钟）」、「IMU 不可用」→ ✅
  - 有记录：`7时32分（体动估算）` + `23:10 - 06:40`、当前静止 `12分` + `自 22:31 起`、
    本次活动 `1时5分`、状态「夜间休息」→ 🟡 stub 返回
  - 记录超过 24h / 时钟不可信 → 显示 `--`（同上，不易区分，低价值）

### 5.9 SETTINGS 设置（#8）

- 数据依赖：`/dev/lcd0` ioctl（亮度/息屏）、`pet_care` 勿扰状态、`lvgl_ui_post`、`ui_str`。
- 结构：状态栏「设置」→ 组「显示与提醒」（亮度 100% 可点切档 / 音量 70% / 勿扰 关 可点）→
  组「连接与 AI」（蓝牙 已连接 / AI 配置 未配置）→ 行「息屏 轻触灭屏」。
- **没有二级页**；所有项都是行内动作或只读。
- 状态：
  - 默认 6 行 → ✅ `our_settings.png`
  - 勿扰「开」（accent 色）→ 🟡 stub `pet_care_get_dnd=true`
  - 亮度 20/40/60/80/100%（行内点击）→ 🟡 创建前后调 `settings_page_brightness_set(0..4)`
  - AI 配置被更新（host 文案）→ 🟡 `settings_page_update_host()`
  - 息屏（黑屏）→ ⛔ LCD 断电，不是可截图的「页面」

### 5.10 AI 小云对话（#9）

- 入口：应用格 p1 第一格 / KEY1 / 收到回复自动切；返回按钮 + 下滑。
- 数据依赖：`lvgl_ui_history_*`（RAM 环 20 条 + `/data/ai_agent/chat_history.dat` 100 条）、
  `pet_care`、`voice_channel`、`expression`、`hr/spo2_measure`（测量结果也写这页文本层）、
  时段问候（`greeting_build`）。
- 结构：浮动「返回」(24,52)/「记录」(282,52) → 3 行截断文本层(66px) → 云图(1.5x) →
  底部 350×60「开始说话」（绝对定位 y=350）。
- 状态：
  - 时段问候（早/中午/下午/晚）→ ✅ `our_ai.png`（host 当前时段）
  - 录音中：「我在听…说完再点一下云朵」；识别中：「收到，正在识别…」；
    上一轮未完：「等我说完再问～」→ 🟡 `pet_page_update_response()` 直接注入
  - 思考中：「小云思考中…」（`STREAM_THINKING_TEXT`）→ 🟡 同上
  - 回复（≤3 行，超出 DOT 截断）→ 🟡 同上；可顺便验证长文本截断
  - 对话记录浮层（空态 / 有气泡 / 滚动）→ 🟡 `pet_page_open_history()` +
    stub `lvgl_ui_history_count/get`（当前恒 0 → 只能出空态）
- 注意：真正的「AI 气泡」在 2026 UI 里是**本页文本层 + 历史浮层**；`lvgl_ui_channel.c` 里
  那个头顶气泡是旧 stage 的死代码（§7）。

### 5.11 ABOUT 关于（#10）

- 数据依赖：**只有编译期宏** `AGENT_BUILD_VERSION`（`agent_config.h:38-43`）。
- 结构：浮动返回 →「关于」→「HerSen AI Agent」→ 350×150 信息卡（版本/平台/队伍）→
  页脚「openvela AI 硬件大赛 2026」。
- 状态：仅一态 → ✅ `our_about.png`。
- ⚠️ 版本值：真机是固件构建宏；host 编译会显示**预览编译时刻**。当前截图显示
  `Nov 4 2026 19:43:05`，是 host 编译时间，**不是固件版本**。要一致就加
  `-DAGENT_BUILD_TIMESTAMP="..."`（固件 .bin 里可搜到 `Nov  4 2026 16:08:07` /
  `18:44:00` 两个候选，以实际 `AGENT_BUILD_VERSION` 为准）。

### 5.12 ALARM 闹钟（#11）

- 数据依赖：`infra/cron_service`（`/data/cron.json` 真实持久化任务表）；页面不造假数据。
- 结构：状态栏「闹钟」→ 最多 5 行列表（48px/行，可点=删除）→ 提示行（「轻点一行即可删除」/
  「还有 N 条未显示（最多 5 条）」）→ 弹性空档 →「+ 新增闹钟」→ 内联编辑区（默认 HIDDEN）。
- 编辑区：标题「新增闹钟」+「仅一次 · 到点响一次」+ 时/分 各 [-] HH [+] + 取消/确定 + 错误行。
- 状态：
  - 空表：居中卡片（当前表情 +「还没有闹钟」/「点下方按钮新增」）+ 卡外「语音说的提醒不会显示在这里」→ ✅
  - 列表 1–5 条（名字左、HH:MM 右；disabled 用三级灰）→ 🟡 `cron_list_jobs` 填充
  - >5 条：提示「还有 N 条未显示（最多 5 条）」→ 🟡 填 7 条
  - 编辑区展开（列表缩到 2 行）→ 🟡 给「+ 新增闹钟」发 CLICKED
  - 添加失败：「添加失败」→ 🟡 展开后给「确定」发 CLICKED + `cron_add_job` 返回 ERROR
  - 「已顺延到明天 HH:MM」→ 🟡 依赖真实时间，价值低
- 注意：列表时间只显示 HH:MM，**不标「明天」**；next_run<=now 显示 `--`。

### 5.13 WEATHER 天气（#12）

- 数据依赖：`vela_https_get`（Open-Meteo，TLS；超时最长 45s）、`weather_cache`（进程级共享
  缓存，TTL=600s）、`cJSON`、`time()`。HTTP/解析全在 worker 线程，UI 只读结果。
- 状态机（`weather_page.c:869-923`）：

| 状态 | 触发 | 屏上表现 | host |
|---|---|---|---|
| 获取中 | 无缓存/点刷新 | 大字 `--`、描述「获取中…」、风速/更新 `--`、状态行空 | 🟡 缓存 miss + 网络桩阻塞 |
| 成功 | 拉到/新鲜缓存 | 18.4 °C、描述、96px 图标、风速/更新卡、「更新于 HH:MM」 | ✅ `our_weather.png`（24.0/多云/12.0/19:42 固定值） |
| 失败（无旧数据） | 无缓存+拉取失败 | 「获取失败」+ 具体原因（连接失败/TLS/HTTP 码…）+ warning 色 | 🟡 缓存 miss + 桩返回错误 |
| 旧缓存 + 更新中 | 缓存过期 + 正在拉 | 旧数据 +「离线缓存 · N 小时前 · 更新中…」 | 🟡 缓存 stamp 拨老 + 桩阻塞 |
| 旧缓存 + 刷新失败 | 缓存过期 + 拉取失败 | 旧数据 +「…· 刷新失败」，不整页报错 | 🟡 同上 + 桩返回错误 |

- 注意：host 的 24.0 °C 是 **PREVIEW-FIXTURE**（`stubs.c` 注释），不是真机读数。

### 5.14 EXPRESSION 表情（#13）

- 数据依赖：`expression.c`（8 款，`/data/expression` 单字节持久化）+ `assets/ui_expressions.c`。
- 结构：自绘标题/副标题 → 3×2 格（104×104，缩略图 scale 160）+ 名字 → 页脚
  「第 N / 2 页 · 共 8 款 · 当前：xx」。
- 状态：
  - p1（微笑…哀，6 款；当前选中 3px accent 描边）→ ✅ `our_expr.png`
  - p2（奥利给/保持微笑，2 款；页脚「第 2 / 2 页」）→ 🟡 手势回调
  - 换当前选中（描边+页脚「当前」变化）→ 🟡 创建前 `expression_set(n)`
- 交互：点格子=选中并**自动回表盘**；p1 下滑=回表盘。

---

## 6. 页内二级态 / 浮层（截图必须单独出）

| 二级态 | 归属页 | 不是独立 `ui_page_t` 的原因 | 打开方式 | 现状 |
|---|---|---|---|---|
| 应用列表第 2 页 | APPLIST | 同页 `s_start` +6，20ms 定时器重建 | 上滑 | 未截 |
| 表情第 2 页 | EXPRESSION | 同页 `s_start` +6 | 上滑 | 未截 |
| 闹钟「新增」编辑区 | ALARM | 页内 HIDDEN 容器，展开时列表缩到 2 行 | 点「+ 新增闹钟」 | 未截 |
| AI 对话记录浮层 | AI | 页内全屏子对象（390×450，IGNORE_LAYOUT） | 点「记录」/ `pet_page_open_history()` | 未截（空态可出） |
| 设置「勿扰开」/亮度档位 | SETTINGS | 行内文字/颜色变化 | 点行 | 未截 |
| 表盘「录音中」 | WATCHFACE | 按钮文字变化 | 语音开始 | 未截 |
| 计时器「计时中/到点」 | TIMER | 同页状态变化 | 点开始 / 到点 | 未截 |
| 天气 获取中/失败/旧缓存 | WEATHER | 同页状态机 | 无缓存/断网/旧缓存 | 部分（成功态已截） |

**明确不存在的东西**（不要去找/不要造）：

- 通用弹窗、`lv_msgbox`、确认框、toast、下拉菜单、键盘、输入框、滚轮选择器。
- 设置二级页、「AI 配置」详情页、蓝牙配对页。
- 锁屏、通知中心、低电量提示、开机动画、充电界面。
- 二维码页（`CONFIG_LV_USE_QRCODE` 未开；`qrcode_display.c` 不参与本构建）。
- 独立「心率历史/健康摘要」页（健康摘要只进对话文本，不建控件）。

---

## 7. 死代码 / 不可见 UI（截图不应包含）

| 对象 | 现状 | 证据 |
|---|---|---|
| `launcher_page.c` | 不编译 | `CMakeLists.txt:206` |
| `bubble_grid.c` | 不编译（applist 已改宫格） | `CMakeLists.txt:166-167` |
| `pet_display.c` 旧桌宠 stage | 编译；`ui_build()` 末尾 `set_stage_visible(false)` | `lvgl_ui_channel.c:1259` |
| 旧「头顶气泡」 | 编译；默认 HIDDEN；只有 NSH `show_chat` 子命令才 `show`，且被整屏页面盖住 | `lvgl_ui_channel.c:969-1014, 1504-1517` / `nsh_commands.c:1350` |
| 旧「菜单/历史页」 | 菜单按钮创建后立即 HIDDEN；历史页默认 HIDDEN | `:1017-1018, 1048-1070, 1260-1262` |
| `ui_engine.c` + `ui_json_embedded.c` | 编译但固定走手写页面；load/build 被 `(void)` 掉 | `lvgl_ui_channel.c:1229-1248` |
| `qrcode_display.c` | 受 `CONFIG_LV_USE_QRCODE` 保护，未开 | `.config` |
| `APPLIST_APP_MUSIC` 分支 | `s_apps[]` 里没有 music，switch 分支不可达 | `applist_page.c:73-93` / `ui_nav.c:127-130` |

> 若「全部现有 UI」按**用户可达**口径，MUSIC 页也应算「隐藏页」；按**代码存在**口径（本报告采用）
> 它是第 5 页，必须截。

---

## 8. host harness 现状与覆盖矩阵

### 8.1 它现在怎么工作

- `scripts/common.sh` 解析出 **真机同一份** LVGL 源码、字体、`ui_icons.c` / `ui_expressions.c`。
- `scripts/preview_our_pages.sh` 编译 14 个 `*_page.c` + `ui_theme.c` + `expression.c` +
  `stubs.c` + `main.c`，链接 `build/lvgl_*.o` 与三份真机字体。
- `src/our_pages/main.c`：390×450 RGB565 PARTIAL 显示，`flush_cb` 把像素抄进 `g_fb`，
  跑 60 帧（每帧 5ms tick）后写 PPM；脚本用 PIL 转 PNG。
- `src/our_pages/fakeinc/` 提供 `nuttx/config.h`（OK/ERROR、CONFIG_LCD_MAXCONTRAST=255）、
  `nuttx/lcd/lcd_dev.h`、`cJSON.h`。
- `stubs.c` 把硬件/网络/持久化打成空实现或 **PREVIEW-FIXTURE** 固定值。

### 8.2 当前产物（2026-11-04 19:43）

~~~text
out/our_watch.png    out/our_apps.png   out/our_heart.png   out/our_spo2.png
out/our_music.png    out/our_timer.png  out/our_sport.png   out/our_sleep.png
out/our_settings.png out/our_ai.png     out/our_about.png   out/our_alarm.png
out/our_weather.png  out/our_expr.png
out/our_pages_contact_sheet.png     4×4 拼图（带页名）
out/our_pages_verify.txt            14/14 passed（只查尺寸/非纯色）
~~~

这些是**默认/空数据态**；各页当前占位值见 §8.3。

### 8.3 当前各页占位数据（截图别当读数）

| 页面 | host 当前状态 | 来源 |
|---|---|---|
| WATCHFACE | 时间=host 时钟；电量 100%；心率 `--`；步数 `--` | 演示常量 + 未定义 UI_SIM |
| APPLIST | p1 6 格；小云=当前表情 | 真实资产 |
| HEART | 无历史、`--`、「暂无记录」 | hr_measure stub 全 0 |
| SPO2 | `--`、「尚未测量」 | 全局量 0 |
| MUSIC | 「本地没有歌」、未播放、音量 50% | scan 0 + IDLE snapshot |
| TIMER | 05:00 空闲、5 分钟选中 | 真实逻辑 |
| SPORT | 全 `--`、「IMU 不可用」 | pedometer alive=false |
| SLEEP | 卡内 `--`、「暂无昨夜记录…」、「IMU 不可用」 | rest_track 无数据 |
| SETTINGS | 默认 6 行；勿扰关；亮度 100% | dnd stub false |
| AI | 时段问候（截图时「晚上好！今天怎么样？」）；无历史 | host 时间 + history 0 |
| ABOUT | 版本=host 编译时刻 | `__DATE__ __TIME__` |
| ALARM | 空表 +「+ 新增闹钟」 | cron 0 条 |
| WEATHER | 24.0°C/多云/12.0km/h/更新于 19:42 | **PREVIEW-FIXTURE** 缓存 |
| EXPRESSION | p1、当前「微笑」 | 真实逻辑 |

### 8.4 想覆盖更多状态，最小改动路径（全部在 `tools/lumina_preview` 内）

1. **状态参数**：`main.c` 增加 `argv[3]=state`，在 create 前设置 stubs 里的全局
   （`stubs.c` 提供 `preview_set_state(const char*)`）。默认 NULL → 保持现有 14 页不变。
2. **点击注入**（不需要触摸/indev）：遍历 `lv_screen_active()` 树，找到「唯一子 label 文本==」
   的按钮，`lv_obj_send_event(btn, LV_EVENT_CLICKED, NULL)`。用于心率/血氧「开始测量」、
   计时器「开始/预设」、闹钟「+ 新增闹钟 / 确定」、天气「刷新」。
   （`ui_gesture_was_drag()` 在 host 恒 false，所以点击不会被防误触闸门拦。）
3. **手势注入**（第 2 页）：`ui_gesture_attach` 目前是空实现。改成记录 `cb` 到全局，
   提供 `preview_swipe(dir)`；页面创建后调用 `SWIPE_UP`，再跑 20ms 让
   `s_rendered_start` 定时器重建宫格。**不需要真 indev**（ui_gesture.c 不进 host 构建）。
4. **时间跳变**：`lv_tick_inc(ms)` + `lv_timer_handler()`。用于计时器到点（+300000ms）、
   心率/血氧 1s 轮询、语音按钮 500ms 文案。
5. **历史/列表数据**：直接填 `stubs.c` 里对应函数的返回值
   （`hr_measure_history_snapshot` / `cron_list_jobs` / `lvgl_ui_history_count/get` /
   `music_local_scan` / `music_worker_snapshot`）。
6. **天气三态**：`weather_cache_get` 返回 false/true；`weather_cache_age_s` 与
   `stamp` 控制新鲜/过期；`vela_https_get` 控制阻塞/失败/成功。当前成功态走缓存命中，
   **完全离线**。
7. **表盘有数据**：编译加 `-DUI_SIM`，并提供 `ui_sim_steps()`（心率走
   `hr_measure_last_bpm` stub）。注意 `DEMO_BATTERY_ALWAYS_100` 仍强制 100%。

### 8.5 覆盖矩阵（页面 × 关键状态）

| 页面 | 默认 | 有数据 | 进行中 | 错误 | 第2页/编辑区 | 结论 |
|---|---|---|---|---|---|---|
| WATCHFACE | ✅ | 🟡 | 🟡 录音 | — | — | 缺数据/录音 |
| APPLIST | ✅ | — | — | — | 🟡 p2 | 缺 p2 |
| HEART | ✅ | 🟡 | 🟡 | 🟡 | — | 缺 4 态 |
| SPO2 | ✅ | 🟡 | 🟡 | 🟡 | — | 缺 4 态 |
| MUSIC | ✅ | 🟡 | 🟡 | 🟡 | — | 低优先 |
| TIMER | ✅ | — | 🟡 | — | 🟡 到点 | 缺 3 态 |
| SPORT | ✅ | 🟡 | — | — | — | 缺有数据 |
| SLEEP | ✅ | 🟡 | — | — | — | 缺有数据 |
| SETTINGS | ✅ | — | — | — | 🟡 勿扰/亮度 | 缺 2 态 |
| AI | ✅ | 🟡 | 🟡 | — | 🟡 记录浮层 | 缺 4 态 |
| ABOUT | ✅ | — | — | — | — | 完成（版本宏需修） |
| ALARM | ✅ | 🟡 | — | 🟡 | 🟡 编辑区 | 缺 4 态 |
| WEATHER | ✅ | 🟡 | 🟡 | 🟡 | — | 缺 3 态 |
| EXPRESSION | ✅ | — | — | — | 🟡 p2 | 缺 p2 |

### 8.6 harness 不能证明的东西

- 触摸/手势的**真实**行为（目前手势是空实现）：只能证明「布局长对了」，不能证明「滑得动」。
- 真实传感器/网络/音频/文件系统：所有数据都是桩或固定值。
- 真实帧率/耗时：`lv_timer_handler` 跑 60 帧 ≠ 真机 74–115ms 全屏重绘。
- 真实电量（恒 100%）、真实 RTC（host 时钟）、真实字体子集缺失行为
  （`CONFIG_LV_USE_FONT_PLACEHOLDER` 在 host 没定义；但当前文案 TOFU=0，暂无差异）。

---

## 9. 最终截图清单建议

命名沿用现有约定：`out/our_<slug>.png`（默认态）+
`out/our_<slug>__<state>.png`（关键状态）。全部出完后追加进 contact sheet，并在文件名/清单中
标注 **「host 预览，数据为占位」**。

### P0 — 14 页默认态（必须，已完成）

| # | 文件 | 页面 | 状态要求 |
|---:|---|---|---|
| 01 | our_watch.png | WATCHFACE | host 时钟、100%、❤-- 👟--、说话 |
| 02 | our_apps.png | APPLIST | p1 六格 + 页脚「第 1 / 2 页」 |
| 03 | our_heart.png | HEART | 无历史、--、「暂无记录」 |
| 04 | our_spo2.png | SPO2 | --、「尚未测量」 |
| 05 | our_music.png | MUSIC | 空列表、未播放 |
| 06 | our_timer.png | TIMER | 05:00 空闲、5 选中 |
| 07 | our_sport.png | SPORT | IMU 不可用 |
| 08 | our_sleep.png | SLEEP | 无记录 |
| 09 | our_settings.png | SETTINGS | 默认 6 行 |
| 10 | our_ai.png | AI | 时段问候 |
| 11 | our_about.png | ABOUT | 版本/平台/队伍 |
| 12 | our_alarm.png | ALARM | 空表 + 新增按钮 |
| 13 | our_weather.png | WEATHER | 成功态（固定缓存） |
| 14 | our_expr.png | EXPRESSION | p1、当前微笑 |

### P1 — 关键状态（建议本轮补齐，约 22 张）

| 文件 | 页面 | 状态 | 期望屏上 | 注入 |
|---|---|---|---|---|
| our_apps__page2.png | APPLIST | 第 2 页 | 5 格 +「第 2 / 2 页 · 下滑返回」 | 手势回调 SWIPE_UP |
| our_expr__page2.png | EXPRESSION | 第 2 页 | 奥利给/保持微笑 +「第 2 / 2 页」 | 手势回调 SWIPE_UP |
| our_heart__history.png | HEART | 有历史 | 柱状图 + 72 +「最近测量 HH:MM」 | 历史 stub 30 条 + last_ok/bpm |
| our_heart__measuring.png | HEART | 测量中 | 「测量中…」+ 静止提示 | 点「开始测量」+ is_running |
| our_heart__failed.png | HEART | 失败 | 「信号弱，请重试」 | 点按钮 + last_ok=false/is_running=false |
| our_spo2__result.png | SPO2 | 本次有效 | 98 +「本次测量有效」 | 点按钮 + 全局量 98 + busy=false |
| our_spo2__last.png | SPO2 | 上次测量 | 98 +「上次测量（非本次）」 | 创建前全局量 98 |
| our_timer__running.png | TIMER | 计时中 | 「停止」红钮 +「计时中…」 | 点「开始」 |
| our_timer__finished.png | TIMER | 到点 | 00:00 +「时间到」+ 浅色底 | 点开始 + tick 跳 5min |
| our_alarm__list.png | ALARM | 3 条任务 | 名字 + HH:MM +「轻点一行即可删除」 | cron_list_jobs 填 3 条 |
| our_alarm__editor.png | ALARM | 新增编辑区 | 列表 2 行 + 时/分 + 取消/确定 | 点「+ 新增闹钟」 |
| our_alarm__editor_error.png | ALARM | 添加失败 | 「添加失败」 | 展开后点「确定」+ cron_add_job=ERROR |
| our_weather__loading.png | WEATHER | 获取中 | -- +「获取中…」 | 缓存 miss + 网络桩阻塞 |
| our_weather__error.png | WEATHER | 获取失败 | 「获取失败」+ 原因 | 缓存 miss + 网络桩错误 |
| our_weather__stale.png | WEATHER | 离线缓存 | 旧数据 +「离线缓存 · N 小时前 · 刷新失败」 | 旧 stamp + 网络错误 |
| our_ai__history_empty.png | AI | 空记录浮层 | 云图 +「还没有对话记录」 | `pet_page_open_history()` |
| our_ai__history_list.png | AI | 有记录 | 「我」/「小云」气泡列表 | 历史 stub 4 条 + 打开浮层 |
| our_sport__data.png | SPORT | 有数据 | 8432/96/5.90 公里/421.6 | pedometer alive + 值 |
| our_sleep__data.png | SLEEP | 有记录 | 7时32分 + 23:10 - 06:40 + 夜间休息 | rest_track 返回 |
| our_settings__dnd_on.png | SETTINGS | 勿扰开 | 「开」accent 色 | dnd stub true |
| our_watch__data.png | WATCHFACE | 有数据 | ❤72 👟8432 | `-DUI_SIM` + hr stub |
| our_ai__reply.png | AI | 回复 | 3 行文本（长回复截断） | `pet_page_update_response()` |

> 优先级再排：apps__page2、heart__history、timer__finished、alarm__editor、
> weather__error、ai__history_list 这 6 张信息量最大，先做。

### P2 — 可选（有时间再做）

- music 播放中/出错（用户不可达，价值低）
- alarm 7 条「还有 N 条未显示」
- heart「上次有效值」、spo2「偏低」、sport 静止、sleep 超 24h 记录
- watch 录音中（「停止」）、about 版本宏对齐固件
- 天气「旧缓存 + 更新中…」（与 stale 只差状态行后缀）

---

## 10. 复现命令（全部本机，不碰板子/串口）

~~~bash
cd /home/aila/projects/vela_contest/contest2026_181_womenshayebuhuidui

# 0) 只需一次：编 host LVGL（run.sh 也会顺带渲设计稿，可忽略其 page_*.png）
bash tools/lumina_preview/run.sh

# 1) 固件 UI 14 页（当前能力）
bash tools/lumina_preview/scripts/preview_our_pages.sh
#    -> out/our_<slug>.png ×14
#    -> out/our_pages_contact_sheet.png
#    -> out/our_pages_verify.txt（14/14 passed）

# 2) 单页/多页
bash tools/lumina_preview/scripts/preview_our_pages.sh heart
bash tools/lumina_preview/scripts/preview_our_pages.sh heart spo2 timer

# 3) 直接调渲染器（调试布局时最快；参数：page out.ppm）
tools/lumina_preview/build/our_pages/our_pages_preview watch /tmp/watch.ppm
~~~

产物约定：一律写 `tools/lumina_preview/out/`（该目录已在 `.gitignore` 覆盖），
不改 `packages/ai_agent/**`，不烧录、不接串口、不启动新服务。

---

## 11. 验收 checklist（照着图看，不只看「生成了」）

**机器校验（已有）**

- [ ] 14 张都是 390×450、非全黑/全白/纯色（`verify_our_pages.py`，当前 14/14）。
- [ ] 拼图 `our_pages_contact_sheet.png` 页名/序号与枚举顺序一致。

**人工校验（必须）**

- [ ] 每页底部内容不越过 y=410（圆角安全区 40），左右不越过 24/366。
- [ ] 有状态栏的 8 页右上角都是「100% + 电池图标」；无状态栏的 6 页标题不被顶部圆角切。
- [ ] 所有中文都能显示（无 □/空白）；24px 行没有明显「掉字号」（57 个降级字：夜/昨/非/医/控/制/提…）。
- [ ] 图片缩放正确：表盘云 1.25x、AI 云 1.5x、表情格 0.625x（不得溢格/盖邻格）。
- [ ] 长文本：AI 文本 3 行截断；闹钟名按 UTF-8 边界截断加「…」；applist 标签不换行。
- [ ] 列表/编辑区：闹钟编辑区展开时列表只剩 2 行、编辑区不贴底。
- [ ] 颜色：浅色主题下深色按钮配白字（心率/血氧/天气/确定）、浅色按钮配深字。
- [ ] 失败态不「假装正常」：心率失败不显示成测量完成；天气失败不把整页变错误页（有旧数据时）；
      血氧「尚未测量」不拿参考范围冒充结果。
- [ ] 页面里**没有**滚动条；没有残留的旧桌宠/气泡（§7）。
- [ ] 截图元数据：about 版本号应来自固件（或明确标注是 host 编译时刻）；
      天气/AI/心率里的时间是 host 时间，交付说明里要写清。

---

## 12. 附录 A — 每页外部依赖（`nm -u` 实测，已剔除 LVGL/libc）

> 用途：给新增页/新增状态时查「还差哪个桩」。当前 `stubs.c` 已全部覆盖这些符号。

~~~text
watchface : expression_current expression_get hr_measure_last_bpm voice_channel_is_recording
            voice_channel_start voice_channel_stop ui_button_create ui_card_create
            ui_statusbar_* ui_gesture_* ui_icon* ui_nav_goto ui_page_init ui_str
applist   : expression_current expression_get ui_gesture_attach ui_icon ui_nav_goto ui_page_init
heart     : hr_measure_start hr_measure_is_running hr_measure_last_ok hr_measure_last_bpm
            hr_measure_history_snapshot ui_nav_attach_back_swipe ui_str ui_text_on_bg
spo2      : g_max30102_spo2_pct g_max30102_spo2_ok spo2_measure_start spo2_measure_busy
            ui_icon ui_nav_attach_back_swipe ui_str ui_text_on_bg
music     : music_local_scan music_worker_init music_worker_post music_worker_snapshot
            pcm_file_stop ui_button_* ui_nav_attach_back_swipe ui_statusbar_create
timer     : ui_button_create ui_button_set_color ui_button_set_text
            ui_nav_attach_back_swipe ui_statusbar_create
sport     : pedometer_get_steps pedometer_get_cadence pedometer_is_alive
            ui_nav_attach_back_swipe ui_statusbar_create
sleep     : pedometer_get_active_seconds pedometer_is_alive rest_track_start
            rest_track_get_state rest_track_current_quiet_s rest_track_get_last_night
            ui_card_create ui_row_create ui_nav_attach_back_swipe ui_statusbar_create
settings  : lvgl_ui_post pet_care_get_dnd pet_care_set_dnd ui_gesture_was_drag
            ui_nav_attach_back_swipe ui_statusbar_create ui_str
ai        : expression_current expression_get lvgl_ui_history_count lvgl_ui_history_get
            pet_care_note_activity voice_channel_is_recording voice_channel_start
            voice_channel_stop voice_channel_turn_active ui_back_button_create
            ui_button_create ui_float_button_create ui_text_on_bg ui_nav_attach_back_swipe
about     : ui_back_button_create ui_card_create ui_nav_attach_back_swipe
alarm     : cron_add_job cron_list_jobs cron_remove_job expression_current expression_get
            ui_card_create ui_gesture_was_drag ui_nav_attach_back_swipe ui_statusbar_create
            ui_text_on_bg
weather   : cJSON_Delete cJSON_GetObjectItem cJSON_IsNumber cJSON_Parse vela_https_get
            weather_cache_age_s weather_cache_get weather_cache_mark_fail weather_cache_update
            ui_card_create ui_icon ui_icon_is_baked ui_statusbar_create ui_text_on_bg
            ui_nav_attach_back_swipe
expr      : expression_count expression_current expression_get expression_set
            ui_gesture_attach ui_nav_goto ui_page_init
~~~

编译期还需要的 host 垫片（当前已具备）：

- `nuttx/config.h`：`OK/ERROR`、`CONFIG_LCD_MAXCONTRAST`（设置页亮度换算用；
  host 给 255、固件实为 63，仅影响无设备的 ioctl 参数，不影响画面）。
- `nuttx/lcd/lcd_dev.h`：`LCDDEVIO_SETCONTRAST/SETPOWER`（设置页）。
- `cJSON.h`：天气页包含（即使走缓存命中也需要头文件/链接符号）。
- 不定义 `CONFIG_UORB/…`：让表盘/运动/休息走「无数据」分支，这是**有意的**
  （与「板子没有数据」的渲染结果一致）。

## 13. 附录 B — 证据索引（关键位置）

| 主题 | 文件:行 |
|---|---|
| 页面枚举 | `ui_nav.h:24-40` |
| 页面切换/销毁顺序/返回 | `ui_nav.c:44-109, 268-296, 313-345` |
| 应用清单（music 隐藏） | `applist_page.c:69-93` |
| 表盘卡片/语音/电量/步数 | `watchface_page.c:135-180, 530-681` |
| 心率状态机 | `heart_page.c:209-358, 448-454, 478-482` |
| 血氧状态机 | `spo2_page.c:88-125, 171-191, 244-300` |
| 计时器状态 | `timer_page.c:111-169, 208-249` |
| 运动降级 | `sport_page.c:56-71, 140-146` |
| 休息降级 | `sleep_page.c:160-207, 234-247` |
| 设置行/亮度/DND/息屏 | `settings_page.c:82-249, 364-441` |
| AI 页布局/浮层/录音态 | `pet_page.c:229-309, 431-492, 591-760` |
| 关于页 | `about_page.c:89-154` / `agent_config.h:38-43` |
| 闹钟空态/编辑区/刷新 | `alarm_page.c:255-373, 714-782, 840-1001` |
| 天气状态机/缓存 | `weather_page.c:386-456, 830-946, 1004-1270` |
| 表情 8 款/持久化 | `expression.c:48-58, 176-210` / `expression_page.c:231-288` |
| 状态栏 100% | `ui_theme.c:515-590` |
| 主题/安全区/字体 | `ui_theme.h:97-138, 161-174` |
| 旧气泡/旧历史页（死） | `lvgl_ui_channel.c:969-1014, 1048-1070, 1251-1262` |
| NSH 页名/隐藏页入口 | `ui_cmd.c:42-58` |
| host 渲染入口 | `src/our_pages/main.c` / `scripts/preview_our_pages.sh` |
| 当前截图 | `out/our_*.png` / `out/our_pages_contact_sheet.png` |




