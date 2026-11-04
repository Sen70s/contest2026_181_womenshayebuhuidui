# 04 系统级数据链路：显示、音频、网络、内存、功耗

> 对照固件：**v131_llm_stream**（md5 `3abe598d71ed3f1dab37176a4eed02e0`，7,435,304 B，2026-11-03 17:30 已烧录）
> 数字来源：`_data_snapshot_v131.md`（v131 口径）。本文凡引用内存占用，一律用 v131 真值，标注版本。
> 证据约定：`文件:行` 指当前工作区源码；`docs_ble/NN §X` 指仓内技术文档；"11-02 观测"指 `.v3_work/logs/serial_ts.log`（该日志对应 v114 系列，**不是 v131**）。

---

## 1. 这一章的角度：系统数据同样是"测出来、判出来"的

心率、血氧、运动那三章讲的是"物理信号怎么变成语义结论"。这一章讲另外五条链路，它们看起来不像传感器数据，但遵循完全一样的模式：有一个测量点、有一个判据、有一个"凭什么说这次是对的"的问题，而且每一条都会骗人。

显示链路会骗人的方式是"系统一切健康、`lcd_flush fail=0`、心跳在涨，但屏幕是黑的"。音频链路会骗人的方式是"播放接口返回 OK，声音却没出来"——因为 NuttX 音频上半部在 `DRAINING` 状态下会把 `START` 静默跳过，只给你一个 OK 的返回值（`nuttx/audio/audio.c:657-658`）。网络链路会骗人的方式是"日志里 313 条 `Unable to allocate` 看起来像在等缓冲，实际是瞬时失败加丢帧"（`external/zblue/zblue/subsys/bluetooth/host/conn.c:1670-1673` 的告警文案在本构建的日志级别下照样打印 "within timeout"，而调用方传的是 `K_NO_WAIT`）。内存链路会骗人的方式是"PSRAM 还有 8MB 空着，为什么加 1.2KB 静态数据就击穿"——因为那块堆在开机早期还没被加进内存管理器。功耗链路则是"数字看起来有，其实一个都没测"。

所以这一章的写法与前三章一致：每条链路都给出**数据从哪来、在哪一步可能失真、用什么字段判读、判读的边界在哪**。凡是我们没测到的，写"未确认"；凡是数字，标明它属于哪个固件版本、由哪条命令或日志产生。

一个贯穿本章的量化事实值得先摆出来：整机所有静态数据（`.bss` + `.data`）吃掉 512KB 内部 SRAM 的 95.2%（v131 实测 499,320 B / 524,288 B），而 8MB PSRAM 在运行期几乎闲置。这意味着"能不能再加功能"这个问题的答案不取决于总容量，取决于**开机瞬间内部 SRAM 还剩多少**——那是本章第 5 节的核心。

---

## 2. 显示：一帧画面的旅程与三种判读

### 2.1 从 LVGL 到面板 GRAM 的四步

这台设备没有 GPU，全部是软件绘制。一帧画面从 LVGL 到玻璃上要经过四步，每一步的实现位置都能指出来。

第一步是**脏矩形生成**。LVGL 只重画"被标记为失效"的区域，不是整屏。这件事在本板上不是可选项而是性能前提：`CONFIG_LV_NUTTX_LCD_BUFFER_COUNT=0`（`out/ble_align/include/nuttx/config.h:1040`）让驱动走 `LV_DISPLAY_RENDER_MODE_PARTIAL` 路径，配合 `CONFIG_LV_NUTTX_LCD_BUFFER_SIZE=60`（同文件 `:1041`）得到一块 `390 × 60 × 2 = 46,800 B` 的分片缓冲（`apps/graphics/lvgl/lvgl/src/drivers/nuttx/lv_nuttx_lcd.c:251-255`）。`board/contest_board/configs/ai_agent/defconfig:216-226` 的注释把代价写得很清楚：一旦改回整屏单缓冲，LVGL 会切到 `RENDER_MODE_FULL`，时钟跳一个字也要重画 `390×450=175,500` 像素，而且 343KB 缓冲放不进 SRAM、只能落到 QSPI PSRAM 上，比 SRAM 慢得多。

第二步是**渲染**。`lv_timer_handler()` 在渲染线程里被调用（`packages/ai_agent/src/ui/lvgl_ui_channel.c:887`），线程栈 32KB（同文件 `:55`），循环周期 5ms（`:54` `RENDER_PERIOD_US 5000`），而 LVGL 自己的绘制周期由 `CONFIG_LV_DEF_REFR_PERIOD=16` 决定（`config.h:968`）。渲染线程还兼三件事：把跨线程投递的 UI 操作落地（`ui_post_flush()`，`:886`）、喂别处的心跳（`hangwatch_ui_tick()`，`:938`）、以及刷新时钟文本。这三件事都在同一个循环里，因此**渲染线程绝不能被阻塞**——这是本章反复出现的"线程"瓶颈的第一处。

第三步是**推送**。渲染完成触发 `flush_cb`（`lv_nuttx_lcd.c:144`），它把区域坐标和像素指针塞进 `struct lcddev_area_s` 后调用 `ioctl(fd, LCDDEVIO_PUTAREA)`。`ioctl` 落到 `nuttx/drivers/lcd/lcd_dev.c:243-253`，再调到驱动的 `putarea`（`vendor/sifli/boards/sf32lb52/drivers/lcd/sf32lb_lcd.c:411`）。驱动在这里做两件容易被忽略的事：把像素按 24 行一批切块写（`:474` `chunk_rows = 24`），以及 `up_clean_dcache()` 把像素刷出 D-cache（`:283`）——因为 LCDC 的 DMA 读的是物理内存，不 clean 就会把旧内容推上屏。

第四步是**进面板 GRAM**。`sf32lb_lcd_wrram()`（`sf32lb_lcd.c:255`）调 CO5300 驱动的 `LCD_WriteMultiplePixels()`（`co5300.c:412`），后者只是 `HAL_LCDC_LayerSetData()`；接口本身是 QADSPI，时钟 50MHz、格式 RGB565（`co5300.c:126-140` `lcdc_int_cfg_qadspi`）。写完不等完成就走，而是用一个 **200ms 上限的有界等待**（`sf32lb_lcd.c:296-308`）——注意这里是"有界"，超时只打一条 `lcd xfer wait timeout` 告警然后继续，不会挂死。这个设计选择很重要：它把"面板不响应"从"整机卡死"降级成"日志里一行告警"。

### 2.2 `lcd_flush` 那行日志的读法

这一行是 `apps/graphics/lvgl/lvgl/src/drivers/nuttx/lv_nuttx_lcd.c:188-191` 打的：

```
[lcd_flush] total=1 fail=0 last_ret=0 area=(0,0)-(389,59)
```

四个字段各自独立。`total` 是 flush 被调用次数的累计值，从 1 开始；`fail` 是 `ioctl` 返回负数的次数；`last_ret` 是最近一次的原始返回值；`area=(x1,y1)-(x2,y2)` 是本次推送的区域。

判读的关键是**不要只看 `fail`**。这个计数器是 2026-10-21 才加的，起因是原来的代码把 `ioctl` 返回值直接丢掉、且无论成败都调 `lv_display_flush_ready()`，于是"刷屏失败"完全静默，LVGL 以为这帧送出去了、继续画下一帧，面板却停在最后一帧（`lv_nuttx_lcd.c:154-162` 注释记录了这段）。现在即使失败也仍然无条件 `flush_ready()`（`:230`），因为不这么做 LVGL 会一直等、整条链路挂死——所以 `fail` 现在的语义是"看得见"，不是"卡住"。

真正的信息在 `total` 的增长速率和 `area` 的形状。11-02 观测里 `total=1..8` 连续八条、每条的 `area` 恰好是 60 行一条带（`(0,0)-(389,59)`、`(0,60)-(389,119)`……），这正是首屏一整屏被切成 8 条带刷出去的样子（450 行 ÷ 60 行 = 7.5，取整 8 条带）。如果开机后 `total` 停在 8 就不再涨，说明**没有任何对象再失效**——界面定在第一帧。这个现象在 2026-10-21 真实发生过，根因是数据喂给 UI 的顺序反了：`ui_engine_tick()` 比较字符串后认为"没有变化"，一个标签都不改，于是没有失效区域（`lvgl_ui_channel.c:908-921` 的注释把这段完整记下来了）。所以 `lcd_flush` 的第一个诊断价值是**区分"驱动坏了"与"上层没让它刷"**：`total` 不涨而 `fail=0`，问题一定不在驱动。

第二个诊断价值是**区分"能刷"与"刷到了"**。11-02 观测里 1339 条 `[lcd_flush]` 全部是 `fail=0`，但同一天仍有整屏黑的现场——因为 `fail=0` 只证明 ioctl 成功返回，不证明玻璃上有内容。屏幕颜色在这块板上没有读回路径：`sf32lb_lcd_getrun()` 里 `DEBUGASSERT(0)`（`sf32lb_lcd.c:542`），`/dev/fb0` 的 `fbmem` 也从不被更新（`lv_nuttx_lcd.c:203-215` 注释）。为此临时加过一段 `[pxprobe]` 像素自检（`lv_nuttx_lcd.c:205-226`），在 flush 时直接采样本次要送的像素角点、打 RGB565 值，判据是页面底色 `#F8FBFD → 0xFFDF`。这段是诊断期的临时手段，代码注释里写明诊断完应删除——它是"没有读回路径时怎么验证颜色"的唯一办法，也就是把"看不见"变成"可判读"。

### 2.3 `lcdtool status` 的三元组

`apps/system/lcdtool/lcdtool.c` 是一个 122 行的 NSH builtin，2026-10-30 为黑屏排查而加。它的 `status` 子命令做三件事：`ioctl(GETPOWER)`、`ioctl(GETCONTRAST)`、以及直接调驱动导出的 `sf32lb_lcd_readid()`，然后打一行（`lcdtool.c:47-49`）：

```
lcdtool: power=1 contrast=0 maxcontrast=63 readid=0x00331100
```

这三元组的判读规则在 `docs_ble/43 §二十一` 里被总结成一句可操作的流程：`readid` 正常 = 面板通信没问题；`contrast=0` = 亮度问题；`contrast` 正常还黑 = 掉电/供电/排线问题。逐项说清依据。

`readid` 来自面板 DCS 的 `0x04` 寄存器，读 3 个字节（`co5300.c:333` `LCD_ReadData(hlcdc, REG_LCD_ID, 3)`）。这块板是 390×450，条件编译选中的期望值是 `0x331100`（`co5300.c:54`）。**六位写法 `0x331100` 与八位写法 `0x00331100` 是同一个值的两种位宽写法**——因为实际只返回 3 字节有效数据，打印时用 `%08x` 就补了两个前导零。这一点在不同文档里写法不一致，容易让人误以为是两个不同读数，`_facts_baseline` §7 第 3 条专门纠正过。判据是"读得到就行"：CO5300 在 USB 供电下偶尔不回应 ID 查询但仍能正常显示，所以驱动只 `lcdwarn` 而继续初始化（`co5300.c:222-231`）。`readid=0xffffffff` 才代表真的没有可用驱动（`sf32lb_lcd.c:1093-1103` 的返回值设计）。

`power` 是驱动内部的 `priv->power` 记录值，不是面板的物理状态。`sf32lb_lcd_setpower()`（`sf32lb_lcd.c:647`）在 `power>0` 时依次做 `DisplayOn`（DCS `0x29`）、`SetBrightness(100)`、把 `priv->contrast` 置满，然后才写 `priv->power`。所以 `power=1` 只说明"我们调用过 DisplayOn 并记录成开"。

`contrast` 是最有判读价值的一位，但它有个陷阱：它是**驱动记录的期望值，不是从面板读回来的**。代码里 `sf32lb_lcd_getcontrast()`（`:704-708`）直接 `return priv->contrast`。所以 `contrast=0` 的含义是"驱动记录里的亮度被写成 0"，不是"面板当前亮度是 0"。反过来，如果显示关闭时亮度寄存器被写成 0、又被某条路径重新 DisplayOn 而不写亮度，就会出现"`power=1`、面板在等亮度、`contrast` 却是 0"的黑屏。

量程换算也必须说清：NuttX 的 `contrast` 是 `0..CONFIG_LCD_MAXCONTRAST`，本板该值为 **63**（`config.h:292`）；面板要的是 0..100 的百分比，`sf32lb_lcd_setcontrast()`（`:729-748`）做 `percent = contrast * 100 / 63` 再调 `LCD_SetBrightness()`（`co5300.c:544-548`，写 DCS `0x51`，内部再乘 `REG_BRIGHTNESS_MAX=0xFF`）。所以 `lcdtool bright 50` 会打出 `SETCONTRAST(31 50%)`（`lcdtool.c:82-85`），中间那层 63 是由 `maxcontrast` 字段暴露的。少看这个字段，就会把"我设了 50"当成"面板收到 50 屏幕亮度"。

`lcdtool` 还提供 `reinit`（`lcdtool.c:113-118` → `sf32lb_lcd_hw_reinit()`，`sf32lb_lcd.c:1111-1133`），把面板 VADD 掉电 **1500ms** 再重新走 Init + DisplayOn + 满亮度。它不是修 bug 的补丁，而是一个**判别实验**：能恢复 = 面板与排线没坏、是状态残留；不能恢复 = 硬件问题。

### 2.4 黑屏的两类根因，与它们的数据证据链

黑屏在这台设备上有两类根因，证据链完全不同，混淆就会修错地方。

**第一类：亮度寄存器被写成 0。** 证据链是三步。黑屏时 `lcdtool status` 打出 `power=1 contrast=0 maxcontrast=63 readid=0x00331100`——`readid` 正常排除面板/排线，`power=1` 排除没上电，`contrast=0` 指向亮度记录值被清成 0。接着执行 `lcdtool bright 100`，屏幕**立刻亮**；这一条是把"推测"变成"结论"的关键，因为它证明面板本身随时可亮、缺的只是亮度值。第三步是根因修正：`SetBrightness(100)` 被显式补在**三条**路径上——`sf32lb_lcd_ensure_display_on()`（`sf32lb_lcd.c:122`）、`sf32lb_lcd_setpower()` 的 `power>0` 分支（`:669`）、以及面板 `Init()` 之后（`:915`）；同时 `ensure_display_on` 把 `contrast` 记录值置满（`:128`）。此前 `co5300.c` 里的 `LCD_SetBrightness()` 定义了、注册进 ops 表了，但**零调用者**，`sf32lb_lcd_setcontrast` 还是直接 `return -ENOSYS`（`:719-728` 注释），所以"亮度能不能改"这件事在应用层根本走不通。为什么必须在"显示打开之后"写：v85/v86 试过在开机更早的位置写亮度，实测无效，因为面板还没就绪，写进去被忽略（`docs_ble/43 §二十一` 的第 4 条）。

**第二类：异常复位后 VADD 掉电时间不足。** 证据链是两条对照。异常复位（RESET 键、看门狗、崩溃重启、烧录收尾）不会让 AMOLED 真正掉电，面板内部状态残留，后续初始化序列跑不出干净的 POR 状态；此时黑屏，且当时开机只给 300ms 掉电**不能恢复**；而运行时执行 `lcdtool reinit`（掉电 1500ms）**可以恢复**。这一对照把"面板坏"排除，把根因锁定在"掉电时长"。修复是把 `lcd_init_thread_entry()` 里的 `BSP_LCD_PowerDown()` 后的等待从 300ms 改成 **1500ms**（`sf32lb_lcd.c:847-853`，注释逐字记下"300ms 在异常复位后不足以保证面板真正 POR"）。这里还有一个极容易漏的前置条件：必须先 `BSP_PIN_LCD()` 把 PA37(VADD_EN)/LCD_RESET 的 pinmux 配成 GPIO 输出再拉低（`:844-847`），否则软复位后引脚默认是输入态，"拉低"根本改变不了电平——v67 只调了 `PowerDown()` 没做 pinmux，所以刷机/软复位后照样黑屏（`docs_ble/43 §二十一` 记录了 2026-10-26 20:59 的复现）。

**还有第三类，它不是故障。** 复位后固定 **15 秒**全黑，5 次样本零抖动：bootloader 4s + board init ~5s + 应用 P0→P3 5.1~5.6s，这段时间里没有任何代码往 GRAM 写过像素——`LCD_Clear()` 定义了却零调用（`co5300.c:186`），所以屏幕显示的是未定义内容（`docs_ble/54 §3`）。判读方法很直接：等 15 秒自己亮，`lcdtool status` 正常，就是这一类的正常黑窗；若 15 秒内整机卡死（该窗口内看门狗还没 arm，`hangwatch.c:194` 在 P7 才启动），那就只能人工复位。把这三类分开，才不会把"启动耗时"当成"面板坏"。

### 2.5 渲染性能怎么测，以及为什么不报毫秒数

性能数字在本板上有一个特殊纪律：**能测，但不报**。原因不是谦虚，是测量条件撑不起"性能指标"这四个字。

测量手段是 LVGL 自带的 sysmon。`CONFIG_LV_USE_SYSMON` / `LV_USE_PERF_MONITOR` / `LV_USE_PERF_MONITOR_LOG_MODE` 三个开关打开后，LVGL 每秒打一行 `sysmon: N FPS (refr_cnt…), refr Xms (render Yms | flush Zms), CPU N%`（格式见 `docs_ble/31 §2`）。2026-10-16 用它做过一次前后对比：旧基线 render 230ms / flush 22ms / refr 256ms / CPU 94%，改 PARTIAL 缓冲 + LVGL `-O2` + ARMv8-M 汇编 memcpy 之后变成 render 6~8ms / flush 2~3ms / refr ~10ms / CPU 0~5%（峰值 53%）。`defconfig:255-268` 把这两组数和结论都留在了注释里，并把 `LV_DEF_REFR_PERIOD` 从 33 调到 16。

但**这三个开关在出货配置里是关的**（`defconfig:282-284` 三行 `# CONFIG_LV_USE_SYSMON is not set`，`out/ble_align/include/nuttx/config.h` 里搜不到 `CONFIG_LV_USE_SYSMON`）。也就是说，v131 里没有任何 sysmon 输出。那么"render 6~8ms"这个数属于 **2026-10-16 的测量条件**：打开 sysmon、在空闲桌面做一次性测量。把它搬到 v131 上当作"当前渲染性能指标"就是违规——固件版本变了、开关状态变了、测量场景也变了。

第二个原因更根本：**这个数在真实场景里不成立**。同一份报告里记了两个瓶颈：`LV_DEF_REFR_PERIOD=33` 时代一次切页是"销毁 + 重建整页"，实测单次 176ms；改成 16ms 之后动画上限是 62FPS，但大范围重绘时仍见过 33ms 尖峰。所以准确的说法不是"渲染只要 6~8ms"，而是"**小面积重绘**只要 6~8ms，**整页重建**要 176ms 量级，而两者都取决于场景"。一个区间跨两个数量级的数，不能写成性能指标。

本章给的是**方法**：要判"渲染是不是瓶颈"，看三件事的比值——`total` 的增长速率（有没有东西在刷）、`area` 的面积（刷多大）、以及 `[lv_tick]` 是否连续增长（`lvgl_ui_channel.c:868-878` 每 4 秒打一行 `tick=… delta=…`）。11-02 观测里 `[lv_tick] delta=4001` 稳定在 4 秒附近，说明 LVGL 的 tick 在推进、`lv_timer` 会到期。这三项全是**定性判据**，不需要声称毫秒数就能定位"定住不动"与"驱动失败"两类问题。

---

## 3. 音频：PCM 的缓冲栈与两次整机崩溃

### 3.1 从云端到喇叭的六段缓冲

一次 TTS 播报的 PCM 从网络进到喇叭，要穿过六段缓冲。把每一段的容量、单位、以及失效方式标出来，是这一节的全部价值。

第一段是**网络接收**。TTS 走 HTTPS 拉流，响应体是 base64 的 MP3（`docs_ble/90 §1.3` 的读法：104,423 B 里 77,952×4/3 = 103,936 B 是 MP3），解码后是 24kHz 单声道 PCM。`AGENT_TTS_WS_SAMPLE_RATE 24000`（`packages/ai_agent/include/agent_config.h:424`）与 `AGENT_MINIMAX_TTS_BITRATE 64000`（`:509`）两个常量决定了这一段的字节量。

第二段是**解码与重采样**。设备只支持 16kHz/单声道/16bit，所以写进播放器时做线性插值把任意输入率降到 16000（`packages/ai_agent/src/voice/pcm_media_player.c:167` 起，`PCM_OUT_RATE 16000`（`:81`））。这里有一个刻意的取舍：`PCM_TTS_SPEED_PCT 110`（`:72-75`）把步进放大 10%、输出时长缩短到 1/1.1，代价是音调略升——用户反馈"说话速度太慢"，这是能接受的全部代价。

第三段是**环形缓冲**，容量 **256KB**（`pcm_media_player.c:90` `PCM_RING_BYTES (256*1024)`），在 PSRAM 的堆上。这个值有明确的历史：原来是 96KB，v76 改成 256KB。改的理由写在注释里——TTS 下载变快后整段 PCM 会一次性涌入，`100KB MP3 解出 500KB/24kHz PCM（>300KB/16kHz）`，96KB 环会直接丢最旧数据，真机日志 `dropped=28800`（=57.6KB≈1.8s 语音），用户听到的就是"话说不完整"（`pcm_media_player.c:85-90`，现场见 `docs_ble/43 §十九`）。256KB 对应 8.192 秒 @16kHz/16bit（`docs_ble/52 §1.3` 的换算）。

第四段是**APB 池**，8 只、每只 4096 B（`PCM_MAX_APBS 8`（`:91`）；驱动的 `CONFIG_AUDIOIOC_GETCAPS` 报 `buffer_size = TX_APB_BYTES 4096`、`nbuffers = TX_APB_NUM 8`，`app/ai_agent/audio/sf32lb52_audio_tx.c:60-61,791-792`）。这一层是 NuttX audio 上半部与下半部之间的所有权边界：客户端 `AUDIOIOC_ENQUEUEBUFFER` 交出去，驱动播完后用 `AUDIO_CALLBACK_DEQUEUE` 还回来，客户端回收（`pcm_media_player.c:300-311`）。

第五段是**驱动内部的 DMA 双半缓冲**：`TX_HALF_BYTES 2048` 半个缓冲、整缓冲 4096 B（`sf32lb52_audio_tx.c:53-54`），2048B = 1024 样点 = **64ms @16kHz**。DMA 每填完一半触发一次中断，worker 补下一半。这段的容量极小、但它是实时性的锚点：64ms 就是"补数据晚到多久会欠载"的预算。

第六段是**硬件路径**，采集侧注释给了完整链路：`PCM -> /dev/audio/pcm0p -> AUDPRC -> AUDCODEC DAC -> 模拟 DAC1 -> PA10 -> 喇叭`（`pcm_media_player.c:43-44`，另见 `docs_ble/30 §10/§11` 的寄存器级验证）。采集方向同构：`MEMS MIC -> audcodec ADC CH0 -> DMA 循环采集`（`app/ai_agent/audio/sf32lb52_audcodec.c:4-7`），半缓冲同样 2048B = 64ms@16k（`:66`）。

把六段串起来看，**缓冲是逐层变小的**：256KB → 8×4KB=32KB → 4KB/2KB → 硬件 FIFO。每层的失效方式不同：环满了丢最旧（`dropped`）、APB 不归还导致停摆（`nplay`/`reap` 不涨）、DMA 半中断不来就欠载（`underruns`）。判读时必须先确定"哪一层饱和"，否则会去扩一个不是瓶颈的缓冲。

### 3.2 `written=` 与 `dropped=` 怎么判播报完整性

播放器停止时打这一行（`pcm_media_player.c:646-651`）：

```
[pcm_media] stopped (written=314880 dropped=0 enq=64 reap=64 ring_max=168148 wait_ms=0)
```

`written` 是累计成功交给设备的字节数（`:308` `p->written += n`，只在 `AUDIOIOC_ENQUEUEBUFFER` 返回成功后累加）；`dropped` 是环满时丢掉的字节数（`:134-152`，满环时 `rtail += 2`、`rbytes -= 2`、`dropped++`，按**字节对**丢以保持 16bit 样点对齐）。两者的关系就是播报完整性的判据。

第一层判据是 **`dropped == 0` 是否成立**。`dropped=0` 意味着所有云端下来的 PCM 都进了设备，一个字节没丢——这是"播报完整"的必要条件。`dropped` 非 0 就一定有内容缺失，而且缺的是**最旧的那部分**（满环丢尾），对应听感是"开头被吞"。11-02 观测里的五个样本全部 `dropped=0`（`written=67706`、`258260`、`249186`、`57934` 等），说明 v114 上这条通路已经不再丢数据；而 `docs_ble/43 §十九` 记录的 `written=98304 dropped=28800` 是 v76 之前的故障态，两行日志的区别就是"96KB 环 + 无背压"与"256KB 环 + 200ms 背压"的区别。

第二层判据是 **`written` 与"应该是多少"对不对得上**。这里不能凭感觉，要用换算：`written` 对应时长 = `written / (16000 × 2)` 秒。11-02 观测里 `written=258260` → 8.07 秒，`written=249186` → 7.79 秒，`written=57934` → 1.81 秒；同一天 `[pcm_media] stopped` 与 `speak done total` 的时间差可以交叉核对。若 `written` 明显小于"这句话应有的时长"，问题在**上游**（TTS 返回的 PCM 本来就短、或解码丢帧），不在播放器。

第三层判据是 **`enq` 与 `reap` 是否相等**。这两个数必须配对：每 `AUDIOIOC_ENQUEUEBUFFER` 成功一次 `enq_apbs++`（`:307`），每从 mqueue 收到一条 `AUDIO_MSG_DEQUEUE` 且是自己 APB 的 `reap_deqs++`（`:243`，在 `pcm_reap()` 里，`:213-243`）。`enq=64 reap=64` 说明 64 只全部归还，收尾干净；而 `enq=8 reap=0` 是**故障签名**——8 只都交出去了，一只都没回来，等于设备根本没消费，环会一路写满、`dropped` 开始涨、`ring_max` 顶到 262144（`docs_ble/52 §1.3` 把这组读数的唯一解释写死成"lower 没有把任何一个播完的信号交回 upper"）。

第四层判据是 **`ring_max` 与 `wait_ms` 的搭配**。`ring_max` 是环的最高水位（`:147`），`wait_ms` 是 `write_data()` 因背压累计等待的毫秒（`:551-556`）。环一直顶到 256KB 且 `wait_ms` 大幅增长 = 生产远快于消费，属于"设备侧不消费"；环水位低、`wait_ms=0` = 供给与消费匹配。11-02 观测里既有 `ring_max=23256 wait_ms=0`（短句，环几乎没用上）也有 `ring_max=168148 wait_ms=0`（长句，环用了一半但没触发背压），这两种都是健康态。

这里要点明一个我在复核时发现的**样本边界**：`written/dropped` 是**每次会话结束时的累计值**，不是实时值。中途看 `[pcm_media] dbg rbytes=… dropped=…`（每 5 秒一次，`:315-330`）才能看到实时水位。把两个不同用途的日志混读，会得出"环一直在丢"的错误结论。

### 3.3 DRAINING 陷阱：返回 OK 却没有声音

这是音频链路最危险的一处，因为它的表现是"接口全部成功、声音没有"，没有任何错误码。

NuttX audio 上半部有一个设备级状态机。`AUDIOIOC_STOP` 在停止时把状态置为 `AUDIO_STATE_DRAINING`（`nuttx/audio/audio.c:692`）；成功返回后只清理客户端自己的状态、**不复位设备级状态**。能把 `DRAINING` 收敛回 `AUDIO_STATE_OPEN` 的路径**只有一条**：收到 `AUDIO_CALLBACK_COMPLETE`（`audio.c:1558-1561`）。而 `AUDIOIOC_CONFIGURE` 要求状态为 `OPEN` 才真正下发配置（`:479-496`）；`AUDIOIOC_START` 只在 `PREPARED` 或 `XRUN` 才调用 `lower->ops->start()`（`:633-655`），其它状态（含 `DRAINING`）走的是另一个分支——不调 lower->start，直接 `priv->state = RUNNING; return OK`（`:657-658`）。

后果是一个完整闭环：上一次 STOP 之后没人发 COMPLETE → 状态停在 `DRAINING` → 下一次 CONFIGURE 被跳过、没有置 `PREPARED` → 下一次 START 什么都不做但返回 OK → 客户端以为启动成功 → 硬件从未启动 → 没有半/全传输回调 → APB 一只都不归还 → 环写满、`dropped` 涨 → 而 DMA 环形缓冲继续重播最后 128ms 的旧数据，听感是"卡住的一个音在响"。这就是 `docs_ble/52 §2.3`、`docs_ble/56 §2.1` 记录的"卡带段"。

怎么发现：**看 `enq` 与 `reap` 是否分叉**。`enq=8 reap=0` 就是前进信号；配合 `backpressure waited=200ms rbytes=262144 nfree=0`（`pcm_media_player.c:551`）这行，三个数一起出现即确诊。修复是让驱动在 `tx_stop()` 成功之后显式回调 `AUDIO_CALLBACK_COMPLETE`（`sf32lb52_audio_tx.c:708-714`，注释写明"stop 成功后必须回调 COMPLETE……上下文是 ioctl 调用者（非中断），可安全回调"）。这里有一个值得记住的对称性：**采集侧同一个坑早就修过**，`sf32lb52_audcodec.c:960-973` 里有逐字相同的注释与同一条 COMPLETE 回调；TX 侧漏了，是因为两侧代码是不同时间写的。修完之后 11-02 观测里 `enq` 与 `reap` 全部相等、`dma_err=0`（`docs_ble/43 §二十七` 的 v102d 验证段）。

判读上还有一个必须避开的误判：`half_pending`、`halves`、`irq` 这些 DMA 探针涨不涨，判的是"DMA 还在不在跑"，不是"声音有没有"。**DMA 在跑而声音是旧的**，正是 DRAINING 陷阱的签名；反过来"声音正常但 `halves` 不涨"几乎不会发生。所以诊断顺序应当是：先 `reap`/`enq` 看所有权，再看 `halves`/`irq` 看 DMA，听感只作交叉验证。

### 3.4 mq 16 格与 16 条 DEQUEUE：整机冻死的根因

这是本章唯一一处"一个软件 bug 能把整机停住、连最高优先级线程都饿死"的地方，值得讲透。

先讲机制。`media_player_stop()` 先等约 4~5 秒再 `ioctl(AUDIOIOC_STOP)`；`tx_stop()` 把队列里没播完的 APB 逐只归还——对每只都调 `upper(DEQUEUE)`（`sf32lb52_audio_tx.c:663-690`）。上半部的 `audio_sendmsg()` 做这件事的方式是：拿 `spin_lock_irqsave_nopreempt()`（`audio.c:1429`），然后在临界区里对每个注册了 mqueue 的客户端调 `file_mq_send()`（`:1434`）。这个临界区在本构建里不是普通的锁——`CONFIG_SPINLOCK` 未开、`CONFIG_SMP_NCPUS=1`，`spin_lock_irqsave_nopreempt()` 退化成"`up_irq_save()` + `sched_lock()`"，也就是**关中断 + 锁调度器**。

而 `file_mq_send()` 在队列满时会进 `nxmq_wait_send()` 无限期睡眠（`nuttx/sched/mqueue/mq_send.c:325-344`：只有 `up_interrupt_context()` 或 `O_NONBLOCK` 才返回 `-EAGAIN`，否则 `abstime=NULL, ticks=-1` 永久等）。旧代码的 `mq_open` 没有 `O_NONBLOCK`（首次实现的 `pcm_media_player.c:402` 那行），队列上限 16 格。再算一下消息数：`PCM_MAX_APBS=8`，而 `audio_sendmsg()` 是**广播**给所有注册 mq 的客户端，两个客户端 × 一次 STOP 归还 8 只 = **正好 16 条**，`docs_ble/53 §2.3` 把这个算术做成了确定证据（旧代码用固定队列名 `/pcm_media_q`，两个 `mq_open(O_CREAT)` 返回同一个队列对象）。第 16 条发送时队列满，于是在**关中断状态下永久睡眠**。

单核上这一睡就是整机停摆：SysTick 被 PRIMASK 屏蔽 → tick 不来 → 没有任何线程能被唤醒 → 连优先级 240 的 `[alive]` 也不再打印 → 硬件看门狗到点把 SoC 复位。`docs_ble/53 §1.2` 列的判据很有说服力：停摆瞬间 `lcd_flush`（2Hz）、`[alive]`（0.2Hz）、`[lv_tick]`（0.25Hz）、`[pedometer]`、`[pan]`、`uv__async_send` 在同一秒内**全部**停止，此后 5~7 秒一个字都没有；且全程 0 条 `[stall]`、0 条 Assertion/HardFault。这五条排除了"UI 单线程卡"和"panic 路径"，把结论钉在"调度器/中断停摆"。

**怎么发现**：这一路故障在日志里几乎是空白的，因为冻死时来不及打印。可用的间接证据有三类。第一类是**复位空窗长度**：5~7 秒的空窗对应 IWDT 实际超时，而不是配置里写的 20 秒。这里有一条版本差异必须说清——`vendor/sifli/chips/sf32lb52/sf32lb_iwdg.c:153` 在 v102a 之后把 `freq` 从"疑似的 9000"改成 **32768**（注释原文："2026-11-01: 与 SiFli 52x 官方例程 LXT_FREQ 对齐（原 9000 疑致 20s 变 ~5.5s，待实测标定）"），按 20000ms × 32768 / 1000 = 655,360 tick ÷ 32,768 Hz = 20.0 秒。也就是说 `docs_ble/53` 记录的 5.5 秒空窗属于**改 freq 之前的那一版**，v131 上这个口径已经变了。这条差异说明"看门狗标定"本身也需要实测，不能照抄文档。第二类是**[stall] 一条都没有**：`HW_STALL_MS` 从 8000 降到 3000（`packages/ai_agent/src/infra/hangwatch.c:42`，注释直说"8000 比实际 5.5~7s 还长 → [stall] 任务转储永远来不及打印"），改的目的就是下次冻死能在复位前留下栈。第三类是**内部计数器**：这套东西后来被系统性补齐——TX 驱动现在有 `start_calls / irq_count / fills / dma_err / wake_posts / wake_got / loop_idle / run_false / in_fill / stall_detect`（`sf32lb52_audio_tx.c:96-110`）和一秒一次的 5 秒心跳（`:261-300`），`docs_ble/53 §2.4` 的结论就是"没有这些计数，这类故障只能靠猜"。

**修复**是三条一起做的（v102a）：`mq_open` 改 `O_NONBLOCK`、队列上限 16 → **64**、队列名改为每个 player 实例唯一（`pcm_media_player.c:92-97,428-443`）。三条各自解决一层：`O_NONBLOCK` 保证**永远不会阻塞调用者**（这是根治，比"把队列加大"重要得多）；64 格给广播留出余量；唯一名让多播放器并行时不互相丢/串 DEQUEUE。11-02 观测里 `[pcm_media] mq %s opened (maxmsg=64, NONBLOCK)` 这行就是修复生效的指纹。

### 3.5 `audcodec_tx` 的 TCB 复用断言

这条链路的第三次崩溃和声音无关，是**线程生命周期**问题。

早期实现里 TX worker 在 `tx_start` 时 `kthread_create("audcodec_tx")`、`tx_stop` 时 `return` 退出，下次播放再创建。问题在于内核线程的 TCB 释放不是同步完成的：stop 返回时 TCB 可能还没释放完，紧接着重新创建就拿回了同一个 PID，触发 `sched_releasetcb.c:77 refs>0` 断言，然后复位或黑屏。最容易触发的场景是"开机提示音之后紧接 TTS"——两次播放几乎没有间隔（`docs_ble/43 §二十五` 第 1 条把现场断言堆栈记下来了）。

修复是把 worker 改成**常驻**：注册时创建一次、终身复用，`stop` 只置 `running=false` 并唤醒一次（`sf32lb52_audio_tx.c:237-240` 的注释、`:655` "停 worker：只清运行标志 + 唤醒一次，不销毁线程"、`:841-848` 的创建点）。验证方式是压力法：8 次连续重启 0 断言（`docs_ble/43 §二十五`），并且 `tx_stop` 每次都打 `COMPLETE sent`、`dma_err=0`。

这条与 3.4 的区别值得点明：3.4 是"线程被永久阻塞"，3.5 是"线程被过早重建"。两者都表现为复位，但判读口径完全不同——3.4 的现场**没有** Assertion（因为连打印都停了），3.5 的现场**有**明确的 `Assertion failed ... sched_releasetcb.c:77` 和任务名。**有没有断言**是区分这两类复位最快的判据。

### 3.6 采样率、位宽、通道与码率的取舍

音频参数在这台设备上没有多少选择空间，但每一处选择都有依据，值得逐个说明。

**播放侧固定 16kHz/单声道/16bit**。这是驱动的能力上限（`TX_DEFAULT_RATE 16000`、`TX_DEFAULT_CHS 1`、`TX_DEFAULT_BITS 16`，`sf32lb52_audio_tx.c:56-58`），`docs_ble/30 §10/§11` 做过寄存器级验证（16kHz、零欠载）。所以上游 24kHz 的 TTS PCM 必须重采样到 16kHz（`pcm_media_player.c:167` 起的线性插值，注释承认"任意输入率 → 16000"）。

**采集侧 16kHz/单声道/16bit**（`AGENT_VOICE_SAMPLE_RATE 16000`（`agent_config.h:398`）、`AGENT_VOICE_CHANNELS 1`（`:399`）、`AGENT_VOICE_BITS 16`（`:400`））。采集走 `/dev/audio/pcm0c`（`:388` `AGENT_AUDIO_CAPTURE_DEV`），ALSA 的 period 是 `sample_rate / 10` = 1600 帧，buffer 是 period 的 4 倍（`packages/ai_agent/src/voice/audio_capture.c:131-139`）。

**上传压到 8kHz**——这是全章最值得讲的一个取舍。`MIMO_ASR_RATE_OUT 8000u`（`packages/ai_agent/src/voice/mimo_asr.c:59`），注释写的是"上传降到 8kHz: 文件减半, 慢网省一半上传时间"，实现是"每两个样点取平均（半带低通 + 2:1 抽取）"，并且明确承认"目的只是把上行 WAV 体积减半; 语音识别对 8kHz 的普通话足够"（`:97-99`）。量化看：3.2 秒语音在 16kHz/16bit 单声道是 102,400 B，压到 8kHz 是 51,200 B——11-02 观测的 ASR 上行 `68,497 B`（含 44 字节 WAV 头 + base64 膨胀）正好落在这个量级（`docs_ble/90 §1.6`）。

为什么这个"降质"是对的：蓝牙 PAN 上每 1KB 约 65~85ms（`docs_ble/90 §1.6` 的换算尺），省 51KB 就是省 3.3~4.3 秒。而语音识别的输入带宽需求远低于 4kHz 的人声基频范围的两倍——8kHz 采样率覆盖 4kHz 带宽，对普通话识别够用。这就是"用可接受的质量换确定的时延"，并且代价是可解释的。

**TTS 码率 64kbps** 是当前值（`agent_config.h:509` `AGENT_MINIMAX_TTS_BITRATE 64000`），也是全轮字节的最大单笔：TTS 下行 104KB 占一轮 194.6KB 的 54%（`docs_ble/90 §1.6`）。`docs_ble/90 §4.1` 把它列为 ROI 第一的优化（64 → 32 kbps，下行减半、省 3.3 秒/轮），但**这一项在那份报告里没有实施**（报告明确写"本文未执行，仅列出供后续会话实施"）。所以当前 v131 仍是 64kbps。这一点在写"取舍"时不能写成"我们已经降到 32kbps"。

还有一个容易被当成"参数"、其实不是参数的项：**mp3 解码后的 PCM 不随码率变**。降 TTS 码率只减少传输字节，解码输出仍是 24kHz PCM，所以播放路径、环容量需求都不变——这也是为什么 `docs_ble/90 §4.1` 说"代价是音质、不是架构"。

---

## 4. 网络：从选路到 487 字节的物理上限

### 4.1 三路选路怎么判

设备有三条可能的出网通道：4G（`ppp0`）、蓝牙 PAN（`bt-pan`）、BLE GATT（备用）。选路逻辑在 `packages/ai_agent/src/infra/network_manager.c` 的 `iface_poll_thread()`（`:632`）里，一个循环反复判三件事，优先级是 **4G > bt-pan > BLE GATT**（`:644-648` 注释与 `:651-688` 分支）。

判据是三个独立的"有没有 IP"函数，都基于 `getifaddrs()` 遍历网卡、只看 `AF_INET` 地址、并且都排除 `0.0.0.0` 与 `127.x`：

- `check_ppp_has_ip()`（`:444`）：网卡名前缀 `ppp`，命中即认为 4G 有 IP；
- `check_btpan_has_ip()`（`:408`）：网卡名前缀 `bt-pan`，同样的有效性检查；
- `check_backup_channel_has_ip()`（`:477`）：BLE 备用通道的对应检查，且额外要求 `ble_net_is_connected()` 或 `ble_gatt_net_is_connected()` 为真（`:676-678`）。

`Active channel:` 那行日志就是判读入口，一共四种取值：`Active channel: 4G cellular (ppp0)`（`:661`）、`Active channel: bt-pan (primary)`（`:668`）、`Active channel: BLE GATT (backup)`（`:682`），以及全部掉线时的 `All channels down, was: %s`（`:685-687`）。11-02 观测里出现三次 `[netmgr] Active channel: bt-pan (primary)`（15:49:54 / 16:13:10 / 16:36:30），说明当天实际走的是 PAN，4G 硬件没打通（`_facts_baseline` §6.2 同口径）。

这行日志的**判读价值在于它只在通道切换时打印一次**（`if (active_channel != "bt-pan")` 这类比较），不是周期性打印。所以看到它出现 = 刚刚发生了一次切换；长时间没有它 = 通道稳定。反过来，如果在一次会话里反复看到 `Active channel:` 交替，那就是链路在抖，而不是"日志吵"。

还有一条不太显眼但很重要的判据：**选中通道后还会作废 TLS 连接池**。`:690-710` 附近（v125 注释）的逻辑是：`has_ip` 为假时把池里 keep-alive 连接全部标为 stale，理由是"池里的 keep-alive 连接此时已静默失效；下一次请求很可能复用一条死连接：写可能进内核缓冲，读要等 SO_RCVTIMEO（30s）才失败"。这条解释了为什么"网络断了又恢复"之后第一轮请求容易卡：不是 DNS 慢，是在等一个已经死掉的连接的读超时。

### 4.2 487 字节：从 SoC 内存映射一路推到 ACL 净荷

这是本章最硬的一段定量推导，每一层都有代码级出处，且**每一层都不可配**。

第一层，SoC 邮箱通道缓冲 **512 B**。`HCPU2LCPU_MB_CH1_BUF_SIZE (512)`（`vendor/sifli/chips/drivers/cmsis/sf32lb52x/mem_map.h:47`）。这是硬件内存映射里的常量，不是我们能改的。为什么它是天花板：HCPU 往 LCPU 发 HCI 帧走的是这条邮箱 ring，而 LCPU 只接受"一次 ring 写入装完一帧"的 H4 帧；超过就得拆成多次写入，那会触发 `read_idx_mirror` 的写回竞态，让 LCPU 抛 Hardware_Error（`vendor/sifli/chips/sf32lb52/sf32lb52_bt_adapter.h:27-34` 的注释把这段机制讲得很完整）。

第二层，单帧 H4 **≤ 492 B**。`SF32LB52_BT_MAX_H4_FRAME 492`（`sf32lb52_bt_adapter.h:35`），推导是 `(512 − 20 字节 circular_buf 头) 向下按 4 字节对齐 = 492`（注释原文："holds … (512) bytes minus the 20-byte struct circular_buf header, rounded down to a word: 492"）。

第三层，ACL 净荷 **487 B**。`SF32LB52_HCI_ACL_TX_LEN (SF32LB52_BT_MAX_H4_FRAME - 1 - 4)`（`vendor/sifli/chips/sf32lb52/sf32lb52_bth4.c:51`）= 492 − 1（H4 type）− 4（ACL 头）= **487**。这个值不只是个宏，它被真的报给了协议栈：`:729-731` 把 `SF32LB52_HCI_ACL_TX_LEN` 与 `SF32LB52_HCI_ACL_TX_PKTS` 写进合成的 `HCI_Read_Buffer_Size` 响应参数里。

第四层，在飞信用 **4 包**。`SF32LB52_HCI_ACL_TX_PKTS 4`（`sf32lb52_bth4.c:61`）。这一层有一个**已被真机否决的历史**必须讲：`:52-61` 的注释记录 2026-10-12 把 4 改成 8 的尝试——本意是抬 ~12KB/s 的天花板，结果在硬件上把数据通路打断了，Edge TTS 报 `pass 1: delivered 0.00s / ~8.00s expected`，语音回复整个消失。结论是"报出去的信用数不能超过 LCPU 实际能排空的数量，否则丢包被搬进 LCPU 闭源固件里"，所以**这个值必须留 4**。这条也解释了为什么 §4.4 说"加 buffer 不提吞吐"。

把它换算成分片数：L2CAP 协商的 TX MTU 是 **1691**（`CONFIG_BT_L2CAP_TX_MTU=1691`，`out/ble_align/include/nuttx/config.h:831`），BNEP 对一个 1500 B 以太帧加 15 B 头得到 1515 B，再加 ACL 头 4 B = 1519 B，而 `1519 / 487 = 3.12 → 4 片`。`1691 / 487 = 3.47 → 4 片`。所以一个 TCP 段（MSS 1460）在物理上要拆成 **4 个 H4 帧**，每帧一次邮箱写入、一次等待。

这里还有一处必须强调的"以代码为准"：`sf32lb52_bth4.c:719-731` 附近的注释残留着旧说法"report 4 + 1691 = 1695 and zblue never fragments"，但**代码报的是 487**、而且系统今天确实在走主机侧分片（1500B ping 要 4 片）。`docs_ble/90 §1.2` 专门为此写了"以代码为准，勿以注释为准"——这是本仓反复出现的取证纪律。

### 4.3 31ms 的帧节拍与 10ms tick 量化

有了 487 B/帧，吞吐就等于"487 B ÷ 每帧节拍"。四个真实下载样本反算出 **27.1 / 31.3 / 33.8 / 41.1 ms/帧**（`docs_ble/90 §1.3`，样本来自 `serial_ts.log:1162/1506/1880/2291` 的 `[vela_tls] body=… reads=… NNNNms` 行），对应 **11.8–18.0 KB/s**。四个样本全部落在 30±10ms，这个"贴着 30 的阶梯"不是巧合。

节拍的来源是主机侧自己的等待。`sf32lb52_bt_wait_tx_idle()`（`vendor/sifli/chips/sf32lb52/sf32lb52_bt_adapter.c:362-392`）在每次发帧前轮询邮箱排空，循环体里是 `usleep(1000)`（`:388`）；而本构建 `CONFIG_USEC_PER_TICK=10000`（`config.h:168`），即 **1 tick = 10ms**。`usleep` 走 `nxsig_usleep()` → `nxsig_nanosleep()`（`nuttx/sched/signal/sig_usleep.c:70-83`），睡眠时长最终按 `clock_time2ticks()` **向上取整**（`nuttx/include/nuttx/clock.h:316-318`，以及 `USEC2TICK()` 用的 `div_const_roundup`，`:155`）。于是 `usleep(1000)` 请求 1ms，实际睡**整整 1 个 tick = 10ms**——请求值比 tick 小，取整永远得到 1。

这一条把"主机的等待"和"物理的帧率"连了起来：每帧最少 10ms、典型 2~3 个 tick（20~30ms）的纯等待，正是实测 27~41ms 的主要成分。为什么这个推导有边界：`docs_ble/90 §9` 把它列为**未证实项**——"31 ms/帧里主机 tick 等待到底占多少，必须靠 P0 计数，本文只给区间（10–30 ms）"。做过的判别实验设计写在 `§6.2`：在 `wait_tx_idle` 里累计 `g_tx_frames / g_tx_wait_ticks / g_tx_wait_calls`，一次 TTS 下载就能判——`wait_ticks ≈ 2–3 × tx_frames` 则 tick 量化成立，`wait_ticks ≈ 0` 而节拍仍 31ms 则瓶颈在 LCPU/手机侧。**这个 P0 计数在 v131 里没有实现**（`sf32lb52_bt_adapter.c` 里没有这些静态计数），所以严格说：节拍=31ms 是实测；"其中多少是 tick 等待"仍是区间推断。这一层区分必须在文档里留住，否则就把"推断"当成了"实测"。

还有第二个机制与它同时存在：4 × 487 = 1,948 B 的在飞窗口。`1948 B / 12.25 KB/s = 159 ms`，与 `docs_ble/21 §10` 实测的 PAN RTT 159ms 完全吻合。但 `docs_ble/90 §1.5` 的判读是"两者都在，主因是 tick 量化，因为在飞窗口实际只有 1 帧"——邮箱 ring 只有 1 帧深，`wait_tx_idle()` 在每次 send 前都要等它排空（`sf32lb52_bt_adapter.c:970`），所以 4 个信用在物理上用不掉，1,948 B 只是在 LCPU 内部排队。这个"1 帧深"的事实顺带解释了 §4.2 的结论：**加 TX buffer 不会提高吞吐，只决定"突发时会不会丢"**。

### 4.4 丢帧 → TCP 停等：221 条告警的正确读法

PAN 发送路径分配 buffer 用的是 `K_NO_WAIT`：

```c
buf = bt_l2cap_create_pdu_timeout(NULL, 0, K_NO_WAIT);   /* sal_pan_interface.c:1732 */
if (!buf) { g_pan.tx_nobuf++; ... return BT_STATUS_NOMEM; }   /* :1734 */
```

拿不到就**直接丢帧**并计数。而 zblue 的告警文案是误导的：`conn.c:1670-1673` 在非 DBG 日志级别下走 `net_buf_alloc(pool, timeout)` 直通分支，**即使 timeout 是 `K_NO_WAIT` 也打印 "Unable to allocate buffer within timeout"**。所以正确的读法是：这不是"等了 2 秒才失败"，是**瞬时失败 + 丢帧**（`docs_ble/90 §1.4`）。

丢帧为什么贵：一个 1500 B 的上行帧会占住 1 个 `acl_tx` buffer 直到它的 4 个 ACL 片全部发完 = `4 × 31ms ≈ 124ms`；池子只有 8 个（`CONFIG_BT_L2CAP_TX_BUF_COUNT=8`，`config.h:829`），`8 × 1691 = 13.5KB`，而一轮 LLM 请求上行是 20.7KB——**只要 TCP 连续灌 8 个段就必然开始丢**。丢的是 TCP 段 → 对端 RTO 重传（本机 `CONFIG_NET_TCP_RTO=3` 秒，`config.h:433`；另有 SACK 与乱序队列开启，`config.h:439-441`）→ 吞吐塌到个位数 KB/s。这一条同时解释了一个看似矛盾的现象：**同一台设备同一分钟里 11.8 与 18.0 KB/s 并存**。

关于"221 条"这个数，必须按样本边界引用，否则会写错。`docs_ble/90 §1.4` 的口径是：`serial_ts.log` 全文件 221 条，对应 **14:00–14:06 的 6 分钟窗口**，按秒分布集中在 ASR 握手完成/大包上行（14:03:15 一次 33 条、14:05:16/17 一次 31+13 条等）。我复核了工作区里现有的 `.v3_work/logs/serial_ts.log`（11-02 **15:49–20:10**，19,988 行，不是同一段日志），全文件共 **313 条**，按分钟聚集在 16:09/16:10/16:11（44/45/100 条），其余时间多为每分钟 7 条左右。两个数是**不同会话窗口的样本**，都成立，都不能写成"现在有 221 条"或"现在有 313 条"——`_facts_baseline` §5 与 §7 第 2 条把这条纪律写得很明确："这是 90 号文那个窗口里的丢帧样本，不要说现在 221 条"。

还有一个独立的丢点必须同时数：**TUN 单槽**。`panu_service.c:1040-1050` 的注释记录了 2026-10-26 的一次真机事故——tun 只有一个"反向发送槽"，上一个反向包（TCP ACK）没被读走时，新到的下行包 `write(tun)` 直接 `EAGAIN` 被丢，下行塌到 1–5 KB/s。修法是"EAGAIN 时先把槽里的包读出、经 BT 发走，再重试写"。`docs_ble/43 §十八` 的验证是 `rx_tun_write_fail` 从每 15 秒 16 次降到 **0**。这个丢点与 L2CAP 池丢帧是**两个独立事件**，P0 计数必须两个都数，否则会把 tun 丢包误判成 L2CAP 池不够。

### 4.5 TLS 握手与 chunked 结束判定

网络链路的第三个坑不在字节量，在"什么时候算读完"。

天气接口（Open-Meteo）的响应是 `Transfer-Encoding: chunked` + `Connection: keep-alive`，**没有 Content-Length**。旧实现没有 Content-Length 时只能一直 `mbedtls_ssl_read()` 到对端关闭或读超时；而服务端保持连接不关，于是 **1KB 数据也要干等约 20 秒**（读超时，`packages/ai_agent/src/infra/network_manager.c:225` 的 `network_get_read_timeout()` 默认 30 秒，`config.h` 里 `net.read_timeout` 默认 30）。这就是"天气卡 20 秒"的全部机制，`docs_ble/43 §十六` 记录的现象是"语音问天气，按键到播报要 2 分钟，其中天气数据只有 ~1KB 却固定等 ~20-26s，主机 curl 同一请求 <1s"。

修复是在读循环里检测 chunked 的末尾块 `\r\n0\r\n\r\n`，一到就结束读取（`packages/ai_agent/src/infra/vela_tls.c:703-704` 处理"首个数据块里已带完整末尾块"的情形、`:725-726` 在处理随后的每次读），然后原地解码（`decode_chunked()`，`:71-110`）。同一份修复还留下了一条吞吐取证日志（`:760-763`）：

```
[vela_tls] body=28167 reads=3 1963ms chunked=0 cl=28167 status=200
```

11-02 观测里这行出现过多次，`reads=3~4`、`chunked=0`、`cl=` 有效值——说明当天走的响应是 Content-Length 型（`chunked=0`），而这个字段本身就是判据：`chunked=0 且 cl=有效值` = 有长度、可直接判完；`chunked=1` = 走末尾块判定。若某次请求 `reads` 明显大于 body 应有的分片数而耗时不涨，说明读循环在空转。

修复后天气冷缓存从 21–26 秒降到 **4 秒**（`docs_ble/43 §十六` 的真机验证：`20:52:54 Weather query` → `20:52:58 Weather via Open-Meteo OK`），且**保留了 keep-alive**（不退化成 `Connection: close`），每次省一次 TLS 握手。

握手本身还有一层现状要写准。连接池 `CONN_POOL_SIZE=2`（`vela_tls.c:141-144`）。`pool_acquire()` 里的复用条件是 `if (s->reuses >= 2 || (tls_mono_ms() - s->last_used_ms) > 10000)`（`:228`）——即**复用上限 2 次、空闲门限 10 秒**。而同一段上方的注释（`:211-227`）写的是"v125：复用上限 2→8、空闲门限 10s→120s"，并把放宽理由记成了"第 3 次复用导致 TTS 读取阶段整机停摆的根因（chunked 末尾块未检测）已修掉，所以可以放宽"。**注释与代码不一致**：代码仍是 2 次 / 10 秒。这是一处我在复核时确认的矛盾，写作时必须按代码写"当前实际是 2 次 / 10 秒"，不能照抄注释里的 8 次 / 120 秒。

第六条网络侧的判读纪律：**不要把"握手耗时"当归因终点**。`docs_ble/90 §1.7` 的账是每轮固定 2 次握手（ASR + TTS，TTS 侧 `minimax_tts.c:1457` 写死 `Connection: close`），合计 1~3 秒；剩下的时间在传输与服务端。把 194.6KB 的字节账按 15.6KB/s 算是 12.5 秒、按 11.8KB/s 是 16.5 秒（`docs_ble/90 §1.6`），这才是端到端时间的主体。所以网络优化的方向不是"等网络变好"，是"把要传的字节变小"——与 §3.6 的 8kHz 取舍是同一个逻辑。

---

## 5. 内存：三块结构与时序差

### 5.1 三块内存的真实占用

设备上有三块物理上完全不同的存储，各自的紧张程度差得很远。

**内部 SRAM 512KB** 是真正紧张的那块。v131 实测（命令与输出见下）：

```
$ ./prebuilts/gcc/linux-x86_64/arm-none-eabi/bin/arm-none-eabi-size \
    .v3_work/fw_store/v131_llm_stream/nuttx.elf
   text      data       bss       dec       hex   filename
7419782     15520    483800   7919102    78d5fe   .../v131_llm_stream/nuttx.elf
```

`.bss 483,800 B + .data 15,520 B = 499,320 B`，对 524,288 B 是 **95.2%**。剩下那 24,968 B 里还要容下 8,192 B 的 IDLE 栈，以及开机期唯一可用的那一小块堆（见 §5.2）。这个百分比不是"够不够用"的答案，是"还剩多少缓冲"的答案。

**外部 PSRAM 8MB** 几乎闲置。它通过 `arm_addregion()` 被加进堆：`kumm_addregion((void *)PSRAM_START, PSRAM_SIZE)`（`vendor/sifli/chips/sf32lb52/sifli_allocateheap.c:198-205`），常量 `PSRAM_START 0x60000000`、`PSRAM_SIZE 0x00800000`（`:70-71`）。`docs_ble/26 §一` 记录的真机 `free` 是 `Umem` 总 8,444,912 B、空闲 7,584,472 B（89.8% 空）。所以音频环 256KB、TFLM arena 这类大块都能放进去——`apb_alloc()` 用的是 user heap（`lib_umalloc`，`nuttx/libs/libc/audio/lib_buffer.c:68-69`），因此**音频缓冲对 SRAM 是零成本**（`docs_ble/26 §五` 的证据链）。这里要点明一处版本边界：`docs_ble/26` 的 8,444,912 B 是 10-10 时点的真机读数，属于更早的固件；v131 的 PSRAM 空闲值我没有找到实测记录，所以只能写"结构上存在这块堆"，不能写具体空闲数。

**NOR Flash 16MB** 余量充足。v131 镜像 7,435,304 B ≈ 7.09 MB。`docs_ble/26 §四` 的库级分布（`libapps_ai_agent.a` 占 64.4%、其中 `ai_lm.cxx.o` 单符号 2.86MB 是模型权重；`libmbedtls.a` 5.1%、`liblvgl.a` 4.6%）说明 Flash 不是瓶颈：加 TTS 权重、提示音都有地方放。

一句话概括三块的关系：**紧的是 SRAM，空的是 PSRAM，够的是 Flash**。`docs_ble/26 §一` 的原话是"所谓'内存太小'是分配结构问题，不是容量问题"，这句话在 v131 上仍然成立。

### 5.2 早期堆是什么，为什么 8MB PSRAM 帮不上忙

这是本章最需要讲清的一段时序，因为它是"PSRAM 明明空着为什么还会 ENOMEM"的全部答案。

开机时内存管理器分两步建立。第一步在 `nx_start()` 里：`up_allocate_heap(&heap_start, &heap_size)` → `kumm_initialize(heap_start, heap_size)`（`nuttx/sched/init/nx_start.c:587-588`）。这一步建立的堆起点是 `g_idle_topstack`，终点是 `SRAM_RESERVED_TOP`——即只有内部 SRAM 的剩余部分。第二步是 `up_initialize()`（`nx_start.c:714`），它的第一个动作是 `arm_addregion()`（`nuttx/arch/arm/src/common/arm_initialize.c:65`），后者才 `kumm_addregion(PSRAM_START, PSRAM_SIZE)` 把 8MB PSRAM 加进来。

关键是这两步的**前后顺序**：`idle_group_initialize()` 在 `nx_start.c:642`，而 `up_initialize()` 在 `:714`。也就是说，**内核 IDLE 组的初始化发生在 PSRAM 堆加入之前**。`idle_group_initialize()` 里 `DEBUGVERIFY(group_initialize(...))`（`:485`）要给 idle 组分配结构体，这一次分配只能来自内部 SRAM 的那一小块堆。`docs_ble/43 §二十三` 记下的断言就是这条路径：`Assertion failed: /sched/init/nx_start.c:485`（idle group 初始化失败）。

这块"只能来自内部 SRAM 的堆"就是**早期堆**，它的容量公式很短：

```
早期堆 = SRAM_RESERVED_TOP − __bss_end__ − CONFIG_IDLETHREAD_STACKSIZE
```

三个量都能查到。`SRAM_RESERVED_TOP = 0x2007fb00`（`sifli_allocateheap.c:65`，注释说明再往上是 LCPU 邮箱 ring，碰了就 HardFault）；`__bss_end__` 是链接产物的符号（v131 = `0x2007c418`，`_ebss` 同址）；`CONFIG_IDLETHREAD_STACKSIZE = 8192`（`board/contest_board/configs/ai_agent/defconfig:159`）。按 v131 的 `__bss_end__` 算：`0x2007fb00 − 0x2007c418 − 8192 = 0x2007fb00 − 0x2007e418 = 5,864 B`。

`g_idle_topstack` 这个只读变量就是公式的中间结果：它等于 `_ebss + CONFIG_IDLETHREAD_STACKSIZE`（`vendor/sifli/chips/sf32lb52/sifli_start.c:53` `#define HEAP_BASE ((uintptr_t)_ebss + CONFIG_IDLETHREAD_STACKSIZE)`，`:141` `const uintptr_t g_idle_topstack = HEAP_BASE`）。我用 objdump 直接读出 v131 ELF 里这个变量的值验证过：地址 `0x121c41b4` 处的内容是 `18e40720` → 小端即 **`0x2007e418`**，与手算一致。同法读 11-02 那份旧产物是 `0x2007d518`，早期堆 9,704 B——两者差的正是 `.bss` 差 3,392 B 加上 `data` 差 416 B 的量级（详细差值与 §5.4 的符号 diff 一致）。

这套机制解释了为什么 PSRAM 帮不上忙：**它不是没容量，是还没被加进来**。开机早期那次分配发生的时候，`kumm_addregion()` 还没执行，堆里只有内部 SRAM 的 5.8KB。`docs_ble/26 §五` 的"音频缓冲走 PSRAM、SRAM 零成本"说的是运行期；早期堆是启动期，两个时点不能混。

### 5.3 v93 击穿事故的完整数据链

这条链有六个环节，每一环都有数或日志，是本章最完整的一条事故记录。

**起点**：v92 的 `__bss_end__ = 0x2007b258`。按当时 `CONFIG_IDLETHREAD_STACKSIZE=16096`（旧值，见 `out/nuttx_contest_board_ai_agent/.config.prev:741` 的 `16096`）算早期堆 = `0x2007fb00 − 0x2007b258 − 16096 = 18,600 − 16,096 =` **2,504 B**。这个余量在 `docs_ble/43 §二十三` 的表里被记成"v92 2504 B"。

**恶化**：v93 新增休息检测与每日摘要两个模块，加了约 **1,216 B** 静态数据，`__bss_end__` 从 `0x2007b258` 推到 `0x2007b718`（`+1216B`，两值差 `0x4C0` = 1216，逐字对得上）。于是早期堆 = `18,600 − 16,096 =` **1,288 B**。

**击穿**：`idle_group_initialize()` 里那次分配在 1,288 B 的堆里失败 → `DEBUGVERIFY(group_initialize(...))` 断言 → `nx_start.c:485` → 启动中断 → **黑屏**（面板已初始化、但应用没跑到画第一帧）。这就是"新加 1.2KB 导致黑屏"的完整因果，而不是"内存不够用"。

**修复**：把 `CONFIG_IDLETHREAD_STACKSIZE` 从 16,096 改成 **8,192**。依据是实测 CPU0 IDLE 栈用量只有 **584 B**（`docs_ble/43 §二十三`），16KB 是 27 倍余量、纯浪费。改完之后早期堆 = `18,600 − 8,192 =` **9,192 B**，`docs_ble/43` 记的"早期堆回到 9192 B"我按两个 `__bss_end__` 分别复算过：v93 的 `0x2007b718` → 9,192 B、v92 的 `0x2007b258` → 10,408 B，与文档给的那个数吻合。

**后续**：v131 的 `__bss_end__` 已到 `0x2007c418`，比 v93 又涨了 `0x0D00 = 3,328 B`（`0x2007c418 − 0x2007b718`），所以 v131 的早期堆是 **5,864 B**，比 v93 修复后的 9,192 B 少了 3,328 B。这两个数都成立，差别就是 `.bss` 在 v93→v131 之间涨了 3,328 B。**这一条是我复核时算出来的，原文档只有 v92/v93 两个点**，值得记下来：修复后的"9,192 B"不是永久值，它会随 `.bss` 增长而下降。

### 5.4 怎么判"还能不能加功能"

判据只有一个式子，但要用对：

```
还能加多少静态数据 = __bss_end__ 到 0x2007fb00 的余量 − IDLE 栈  − 给运行期留的余量
```

具体操作三步。第一步取 `__bss_end__`：对 ELF 用 `arm-none-eabi-nm` 查符号（v131 = `0x2007c418`），或对 map 文件查 `__bss_end__ = .` 那一行（`out/ble_align/nuttx.map` 的链接脚本尾部有）。第二步减 `0x2007fb00` 与 8,192。第三步再留余量：`docs_ble/90 §6.1` 给的硬校验是 `_ebss ≤ 0x2007db00` 且 `0x2007fb00 − (_ebss + 8192) ≥ 4096`——即**至少留 4KB 早期堆**。按这条判据，v131 的 5,864 B 里只剩 1,768 B 可加，所以**现在再加超过 1.7KB 的静态数据，早期堆就会跌破 4KB 安全线**。

这个结论有一个必须同时给出的对照：**"加功能"不等于"加 .bss"**。运行期的大块可以进 PSRAM（`lib_umalloc`）。`docs_ble/26 §六` 的可挪移清单就是为此排的：`agent_logbuf` 16KB（代价极低）、`vela_tls` 的 `s_tls_raw_buf` 16KB（TLS 非热循环）、TFLM arena 64KB（`_ZL7s_arena`，代价中高——arena 是每层激活值、高频访问，PSRAM 走 MPI1 QSPI 延迟远高于 SRAM）。所以正确的判断顺序是：先问"这块是必须在 SRAM 的热路径、还是可以放 PSRAM"，再问"早期堆够不够"。

### 5.5 从 map 怎么看哪个库占了多少

`out/` 下的 `nuttx.map`（29MB）是链接器的完整账本，但直接 grep 会得到错的数——这条我实际踩过，值得写下来。

**第一个坑是重复计数**。同一个 `.bss` 段地址在 map 里会出现多次（`.bss` 名重复、`(size before relaxing)` 行、`load address` 行），直接按"每一行 `.bss` 后面的 size 相加"会把 480KB 加成 480MB 量级。正确做法是**按段地址去重**：只认 `addr` 唯一的那些，或者干脆换一条路径。

**第二个坑是归属**。map 里 `.bss.<sym>` 后面跟的是 `libxxx.a(obj.o)`，所以库级归属看起来是现成的。但实际的 map 里 `.bss` 有两种行形态：带名字的 `.bss.symbol`（换行后跟地址+大小+来源）与不带名字的合并行；而且 `libsupc++` 之类的路径是绝对路径。我按"`.bss` 行 + 下一行 `0xADDR 0xSIZE source`"解析并把 `addr` 去重后，从 `out/ble_align/nuttx.map`（19:44 构建，`text 7421350 / data 15552 / bss 483800`，与 v131 的 bss 完全相同）得到：

| 库 | `.bss` | 说明 |
|---|---:|---|
| `libapps_ai_agent.a` | 192,039 B（187.5 KB） | AI 框架 + 端侧 LM + UI 通道 |
| `libmm.a` | 99,663 B（97.3 KB） | 几乎全是 `g_iob_buffer`（96.75 KB） |
| `libzblue.a` | 95,274 B（93.0 KB） | 蓝牙协议栈（含 32KB `sys_work_q_stack`） |
| `libsched.a` | 37,362 B（36.5 KB） | 调度器（含两个 16KB 工作队列栈） |
| `liblibbluetooth.a` | 22,900 B（22.4 KB） | 蓝牙 SAL |
| `libapps_audio_setup.a` | 9,388 B（9.2 KB） | 音频设备注册包装 |
| `libarch.a` | 8,029 B（7.8 KB） | |
| `libnet.a` | 6,318 B（6.2 KB） | |
| `libapps_audio_beep.a` | 4,348 B（4.3 KB） | |
| `libmbedtls.a` | 2,882 B（2.8 KB） | SSL 上下文在堆上，不在 `.bss` |

合计 483,591 B，与 `size` 报的 483,800 B 差 209 B（段内对齐填充）。这张表的读法与 `docs_ble/26 §二` 一致（那份表是 10-10 的旧口径，量的相对次序没变），但**绝对值变了**：那份写 `libapps_ai_agent.a` 139.87 KB，这里是 187.5 KB——涨的 47KB 是 v93→v131 之间新增的 UI 事件导出缓冲（`s_hist_export` 1,632 B）、`s_kv` 文本表（24KB）、`lvgl_ui_channel.c` 的通道缓冲等。所以引用库级数字时**必须标年份/版本**，否则两份材料会打架。

**第三个坑是单符号核对**。库级之外，最有用的是单符号 Top（`nm -S --size-sort`，对 v131 ELF）：

| 符号 | 大小 | 归属 |
|---|---:|---|
| `g_iob_buffer` | 99,075 B（96.75 KB） | `libmm.a`，NuttX I/O 缓冲池 |
| `_ZL7s_arena` | 65,536 B（64.00 KB） | TFLM Tensor Arena |
| `sys_work_q_stack` | 32,768 B（32.00 KB） | zblue 系统工作队列栈 |
| `s_kv` | 24,576 B（24.00 KB） | `ui_text_runtime.c:28` 的 128 项文案表 |
| `net_buf_data_hci_rx_pool` | 17,000 B（16.6 KB） | 蓝牙 RX 池 |
| `g_hp_work_stack.1` / `g_lp_work_stack.0` | 16,384 B ×2 | 内核工作队列 |
| `s_tls_raw_buf` | 16,384 B（16.00 KB） | `vela_tls.c` TLS 原始缓冲 |
| `s_buf` | 16,384 B（16.00 KB） | `agent_logbuf.c` 日志环 |
| `net_buf_data_acl_tx_pool` | 13,600 B（13.28 KB） | 蓝牙 ACL TX 池（= 8 × 1700） |
| `net_buf_data_rfcomm_tx_pool` | 10,040 B（9.8 KB） | RFCOMM |
| `net_buf_data_att_pool` | 8,510 B（8.31 KB） | ATT |
| `bt_lw_stack_area` | 8,192 B（8.00 KB） | 蓝牙长工作队列 |
| `g_tx` / `g_audio` | 4,832 / 4,544 B | 音频 TX 驱动 / 音频集合 |

Top 12 就占了 ~350KB，剩下 700 多个符号摊掉余下 130KB。这就是 `docs_ble/26 §二` 那句"平台开销差不多是 AI 两倍"的量化依据在 v131 上的复现：`libmm + libzblue + libsched + liblibbluetooth + libnet = 261.5 KB`，`libapps_ai_agent = 187.5 KB`。

**`.rdata` 的已知工具局限**要一起说：`codesize` 的 Overview 把 `.rodata` 报成 0 B，但 By Library 表里各库 `.rodata` 有值——Overview 那一行不可信（`docs_ble/26 §八` 已记）。另外 By Library 的 "Total Size" 列（如 `libarch.a` 75MB）含未链接/调试段，只有它分列的 `.text/.rodata/.data/.bss` 可用。

**一个必须留住的复核结论**：`out/nuttx_contest_board_ai_agent/nuttx` 这份产物（mtime 11-02 22:11，`.bin` md5 `33e043d8…`）**不是 v131**。我遍历 `.v3_work/fw_store/*/nuttx.bin` 比对 md5，它等于 `rollback_before_ble/nuttx.bin`（7,385,220 B）。用它的 `size` 得到的 `bss 480,408 / data 15,104` 是 11-02 那份旧产物，`.bss` 比 v131 少 3,392 B。所以本章所有内存数字都以 **v131 的 ELF** 为准，`out/` 的 map 只在需要"逐库归属"时用（`out/ble_align/nuttx.map` 的 bss 与 v131 完全一致：483,800 B，可直接对照）。

---

## 6. 功耗：为什么只能给估算

这一节的内容是"为什么没有数字"，而不是"数字是多少"。

**没有电池，就没有测量对象**。板子用的是官方开发板（SF32LB52-DevKit-LCD），`_facts_baseline` §2 明确写着"电池 1000mAh 规划中（未装机）"，`design/README_硬件整合方案_v1.md` §5 的标题就是"功耗/续航粗估"。没有装机电池就没有放电回路，任何电流/功率读数都无从取；开发板 USB 供电下的电流也不代表目标形态。

**没有电量计，就没有库仑计数**。`docs_ble/49 §1` 与 `.config` 都确认这台设备"无电量计"。`config.h` 里也查不到任何 `CONFIG_BATTERY` / `CONFIG_CHARGER` 相关项。板载的 ETA9640P 只负责充电（`_facts_baseline` §2），不提供电量或功率计量。没有库仑计，就只能靠外部仪器串在电池回路里测，而那需要电池先装。

**没有功耗模式，就没有待机数据**。这台设备没有进入低功耗休眠的路径：`config.h` 里查不到 `CONFIG_TICKLESS` / `CONFIG_SCHED_TICKLESS`，`CONFIG_PM` 也未启用。整机在"什么都不做"时仍在 10ms tick 上跑（`CONFIG_USEC_PER_TICK=10000`），渲染线程 5ms 一轮（`lvgl_ui_channel.c:54`），看门狗 1 秒喂一次（`hangwatch.c`）。真正的低功耗待机不存在，所以"待机功耗"这个指标在软件上还没有对应的状态可以测。

那么 `design/README_硬件整合方案_v1.md` §5 那张表是什么？它是**按模块估的**：仅手表待机/低亮 ~0.15–0.3W（12–24h）、手表 + 4G 待机注册 ~0.3–0.5W（7–12h）、手表 + 4G 偶尔传输 ~0.8–1.5W（2.5–4.5h）、4G 持续大数据 2–5W（<1.5h），电池假设 1000mAh / 3.7Wh。这张表的性质是**工程估算**，来源是模块典型值，不是本机实测。三条证据说明它不能当指标：其一，分母（1000mAh）本身是规划值，电池没装机；其二，分子里最不确定的 4G 部分，模块硬件根本没打通（`_facts_baseline` §6.2："软件通路就绪，硬件未打通，模块能上电、手表 UART 收不到数据"），所以那两行 4G 相关的估算连"方案"都还没落地；其三，表里没有一行是"亮屏 + 蓝牙 + 语音播报"这个产品真实工作态的组合。

缺的实测清单可以具体列出来（列清单是为了说明缺什么，不是给承诺）：电池装机后的整机放电曲线、亮屏态与熄屏态电流、单次语音问答的瞬时峰值（这是 4G/PA 峰值最容易踩到的地方）、蓝牙 PAN 持续传输时的平均电流、以及各状态占空比下的加权续航。这五项一项都没测，所以本章不给续航数字。

对外能说的一句话应该是：**"电池未装机、未实测，只能按模块给量级估算，且 4G 通路硬件未打通，4G 相关的估算不能当承诺。"** 这比编一个续航数字更经得起追问，也是 `_facts_baseline` §6.3 的标准句式。

---

## 7. 总图：一条数据跑完全程

### 7.1 以一次语音问答为例，标出每一跳的字节量与耗时来源

选语音问答是因为它一条路径就穿过全部五条链路。下面每一跳都标注字节量与时间的**来源**（实测 / 换算 / 未证实），不给"性能指标"。

**第 1 跳 麦克风 → PCM 缓冲。** MEMS 麦 → audcodec ADC → DMA 循环缓冲，半缓冲 2048 B = 64ms@16kHz/16bit/单声道（`sf32lb52_audcodec.c:66`）。采样参数 16kHz/单声道/16bit（`agent_config.h:398-400`）。VAD 以 320 样点（20ms）为一帧判定起说与说完（`packages/ai_agent/src/voice/vad_trim.c:42`），保留 300ms 前置（`:43`）与 200ms 后置（`:44`）。字节量由说话时长定，没有固定值。

**第 2 跳 采集 → 降采样 → WAV。** 16kHz→8kHz 每两点取平均（`mimo_asr.c:97-99`），得 3.2 秒语音 = **51,200 B** PCM + 44 B WAV 头。这一步是整条链路唯一"主动降质换时间"的地方，省掉的是 51,200 B。

**第 3 跳 上传走 bt-pan。** base64 后 1.5 倍膨胀、加 TLS/HTTP 头，11-02 观测实测上行 `68,497 B`（`docs_ble/90 §1.6`）。按 487 B/帧算是 ~141 帧；帧节拍 27~41ms 时，这一跳 **3.8~5.8 秒**（换算，非实测口径）。

**第 4 跳 云端 ASR → 文本。** 时间主要由服务端决定，本机不可测分解。11-02 观测里 ASR 的 TLS 握手 ~1 秒（`docs_ble/90 §1.7`），识别本身的服务端耗时没有本机打点。

**第 5 跳 文本 → LLM 请求。** 上行 **20,742 B**（system 4,570 + 工具表 13,156 + 历史，`docs_ble/90 §1.6`），约 42 帧。工具表 13,156 B 是 47 个注册工具（`_facts_baseline` §3：28 个带参 + 19 个无参）的描述。这一跳 1.1~1.5 秒（换算）。

**第 6 跳 LLM → 回答。** v131 新增了 SSE 流式：`agent_loop` 的流式回调驱动 `lvgl_ui_channel_stream_begin/delta/end`，回答边生成边显示，日志打 `[turn] first_token_ms=… stream_deltas=…`（`packages/ai_agent/src/core/agent_loop.c:1437`）。v131 的 meta 记录实测 `ask 你好 total 5460ms / first_token 4063ms / 6 deltas`。这个 `first_token` 是**本机与服务端一起的结果**，不是"网络时延"，只用于说明流式让首字更早出现，不作性能指标。

**第 7 跳 回答 → TTS 请求。** 上行 286 B。TTS 走独立 TLS 上下文并写死 `Connection: close`（`minimax_tts.c:1457`），所以每轮固定一次新握手（~0.5–1 秒，`docs_ble/90 §1.7`）。

**第 8 跳 TTS 响应 → 解码。** 下行 **104,423 B**（base64 的 MP3，MP3 本体 77,952 B），约 214 帧，是**全轮最大单笔**。这一跳 4.4~6.7 秒（换算）。解码后是 24kHz PCM，重采样到 16kHz（`pcm_media_player.c:167`）。

**第 9 跳 PCM → 环形缓冲。** 256KB 环（`pcm_media_player.c:90`），满则 200ms 背压、仍满才丢最旧（`:520-556`）。水位判据是 `rbytes` / `ring_max` / `wait_ms`。

**第 10 跳 环 → APB → DMA → 喇叭。** 8 只 × 4096 B（`PCM_MAX_APBS 8`、`TX_APB_BYTES 4096`），DMA 半缓冲 2048 B = 64ms（`sf32lb52_audio_tx.c:53`）。播完检查 `written/dropped/enq/reap`（§3.2）。

**第 11 跳 同时：显示。** 回答文本经 `lvgl_ui_channel_stream_delta` 进 UI，渲染线程 5ms 一轮、LVGL 16ms 绘制周期，flush 走 PUTAREA → QADSPI → GRAM（§2.1）。可见的判据是 `[lcd_flush] total=` 在涨、`[lv_tick] delta≈4000`。

把这条链的账合起来：一轮上下行合计 **≈194.6 KB**（89.5 KB 上 + 105.1 KB 下，`docs_ble/90 §1.6`），按 15.6KB/s 是 12.5 秒、按 11.8KB/s 是 16.5 秒。所以"慢"的主体在**字节量 × 单条链路的物理帧率**，而不是某一跳的代码效率。这也是 `docs_ble/90 §5` 的结论：能拿回的时间是 3.4~7 秒（保守），最多 ~12 秒（tick 量化若成立）；剩下 6~10 秒在服务端（LLM TTFT + TTS 合成），不在本机范围内。

### 7.2 五条链路共享的三个瓶颈

把五条链路摆在一起看，瓶颈不是五个，是三个，而且每一个都在本章前面出现过。

**瓶颈一：线程。** 渲染线程（`lvgl_ui_channel.c` 的 `render_thread`，栈 32KB、周期 5ms）兼做 UI 投递、时钟刷新、心跳喂狗，**绝不能被阻塞**——因为 `LV_OS_NONE`（`config.h:970` `CONFIG_LV_OS_NONE 1`）意味着 LVGL 内部没有操作系统抽象，所有 LVGL 调用必须落在同一个线程上。音频 TX worker 与采集 worker 都是常驻单线程（`audcodec_tx` / `audcodec_rx`），一旦其中一个在临界区里睡死，单核上**全部**线程都停（§3.4）。网络侧 PAN 的收发也跑在同一个 service loop 线程里（`docs_ble/90 §0-3`：`hci_h4.c:326-336`、`panu_service.c:809-822`），所以"每发一帧就把整条接收通路停 10–30ms"。三个不同的子系统，同一个失效模式：**单线程 + 阻塞 = 整机停**。

**瓶颈二：内存时序。** 静态 SRAM 已到 95.2%，但真正约束加功能的是早期堆——它只有 `0x2007fb00 − __bss_end__ − 8192`（v131 = 5,864 B），因为 PSRAM 堆在 `up_initialize()` 之后才加入，而 `idle_group_initialize()` 在它之前（§5.2）。这一条与显示、音频、网络都相关：新增 UI 页面加静态结构、新增音频缓冲（若误放 `.bss`）、新增网络缓冲池（`BT_L2CAP_TX_BUF_COUNT` 8→12 是 +6,960 B 静态）都会直接吃早期堆。`docs_ble/90 §4.4` 把"8→14/16"列为禁止项，理由就是"`_ebss` 会推过 `0x2007fb00`，`heap_size` 下溢 ~4GB"。

**瓶颈三：单条蓝牙链路。** 所有出网数据共用一条 PAN：每帧 487 B、4 包信用、27~41ms 节拍（§4.2/§4.3）。这意味着**任何一处字节量变化都会改变整轮耗时**，而且 1KB ≈ 65~85ms。所以三个看似无关的决策其实是同一个决策：ASR 降到 8kHz（省 51KB）、TTS 码率 64→32kbps（省 52KB）、LLM 工具表 13KB 裁到 4KB（省 9KB）——它们都在"把要传的东西变小"，因为吞吐的上限是物理的，不是可调的。

三个瓶颈互相咬合的地方也值得指出来：**蓝牙 service loop 的等待会同时伤到音频**。因为 TX worker 和 PAN service loop 都是被调度器唤醒的线程，一旦 PAN 侧在 `wait_tx_idle()` 里连睡 2~3 个 tick，DMA 半中断的 64ms 预算就会被挤压（`underruns` 会上涨）；反过来音频 worker 在 `tx_fill` 里做 `memcpy` + `up_clean_dcache` 也会占 CPU。11-02 观测里 `[audio_tx] hb … underruns=` 与 `[vela_tls] body=` 出现在同一批时间戳里，就是这个耦合的现场痕迹。判断这类耦合的办法不是猜，是看两个计数器的相关性：`underruns` 涨的时间段是否与 `[vela_tls]` 大下行重合。

---

## 8. 本章证据索引

**显示**
- `apps/graphics/lvgl/lvgl/src/drivers/nuttx/lv_nuttx_lcd.c:144-230`（`flush_cb`、`total/fail/last_ret/area`、无条件 `flush_ready`、`pxprobe`）
- 同文件 `:251-255`（分片缓冲计算）、`:284`（`lv_display_set_flush_cb`）
- `out/ble_align/include/nuttx/config.h:968`（`LV_DEF_REFR_PERIOD 16`）、`:1040-1041`（`BUFFER_COUNT 0` / `BUFFER_SIZE 60`）
- `board/contest_board/configs/ai_agent/defconfig:216-232`（PARTIAL 缓冲的三条理由）、`:255-284`（16ms 依据与 sysmon 关闭）
- `vendor/sifli/boards/sf32lb52/drivers/lcd/sf32lb_lcd.c:102-132`（`ensure_display_on` + 亮度）、`:255-308`（`wrram` + 24 行切块 + 200ms 有界等待）、`:411-500`（`putarea`）、`:542`（`getrun` 的 `DEBUGASSERT(0)`）、`:647-690`（`setpower`）、`:719-748`（`setcontrast` 量程换算）、`:829-853`（开机 1500ms 掉电）、`:1093-1103`（`readid`）、`:1111-1133`（`hw_reinit`）
- `vendor/sifli/boards/sf32lb52/drivers/lcd/co5300.c:54`（`LCD_ID 0x331100`）、`:112/122/281-282`（`0x51` 亮度 + 初值 `0x7F`）、`:126-140`（QADSPI 50MHz / RGB565）、`:186`（`LCD_Clear` 零调用）、`:333`（`ReadID` 读 3 字节）、`:412-414`（`WriteMultiplePixels`）、`:544-548`（`SetBrightness`）
- `apps/system/lcdtool/lcdtool.c:32-51`（status 三元组）、`:69-87`（bright）、`:113-118`（reinit）
- `nuttx/drivers/lcd/lcd_dev.c:243-253`（PUTAREA）、`:285-300`（GET/SETPOWER、GET/SETCONTRAST）
- `docs_ble/43 §二十一`（黑屏两类根因 + lcdtool 判读规则）、`docs_ble/54 §3`（15 秒正常黑窗 + `LCD_Clear` 零调用）、`docs_ble/31 §2/§3`（sysmon 实测与两个瓶颈）

**音频**
- `contest2026_181_womenshayebuhuiduidui/app/ai_agent/audio/sf32lb52_audio_tx.c:53-61`（半缓冲/DMA/APB 常量）、`:96-110`（诊断计数）、`:237-240`（常驻 worker 的理由）、`:261-300`（5 秒心跳）、`:655-690`（stop 归还 APB）、`:708-714`（COMPLETE 回调）、`:841-848`（注册时创建 worker）
- `contest2026_181_womenshayebuhuiduidui/app/ai_agent/audio/sf32lb52_audcodec.c:4-7`（采集路径）、`:66`（64ms 半缓冲）、`:69-70`（4096 B × 8）、`:960-973`（采集侧 COMPLETE）、`:1508-1514`（worker 创建）
- `packages/ai_agent/src/voice/pcm_media_player.c:43-44`（六段硬件路径）、`:72-75`（TTS 语速 110%）、`:81`（16kHz）、`:85-91`（96KB→256KB 的原因）、`:92-97`（mq 修复三条）、`:134-152`（环满丢最旧 + `dropped++`）、`:167`（重采样）、`:300-330`（`written` / `dbg` 日志）、`:428-443`（`O_NONBLOCK` + 64 格 + 唯一名）、`:520-556`（背压）、`:646-651`（`stopped` 日志）
- `nuttx/audio/audio.c:479-496`（CONFIGURE 前置）、`:633-658`（START 前置与静默 no-op）、`:692`（置 DRAINING）、`:1423-1440`（`audio_sendmsg` 临界区 + `file_mq_send`）、`:1558-1561`（只有 COMPLETE 回 OPEN）
- `nuttx/sched/mqueue/mq_send.c:325-344`（满队列永久等待）
- `packages/ai_agent/include/agent_config.h:369/388/395/398-400/424/509`（语音各项常量）
- `docs_ble/52 §1.3/§2.3`（计数含义、DRAINING 单向陷阱）、`docs_ble/53 §1.2/§2.2/§2.3`（整机停摆判据、临界区阻塞、16 格算术）、`docs_ble/56 §2`（卡带段/冻死段/复位三段）、`docs_ble/43 §十九/§二十五/§二十七`（丢数据、TCB 断言、v102a 修复）

**网络**
- `vendor/sifli/chips/drivers/cmsis/sf32lb52x/mem_map.h:47`（512 B 邮箱）
- `vendor/sifli/chips/sf32lb52/sf32lb52_bt_adapter.h:27-35`（492 推导）
- `vendor/sifli/chips/sf32lb52/sf32lb52_bth4.c:51`（487）、`:61`（4 信用）、`:52-61`（4→8 失败记录）、`:719-731`（信用合成 + 注释残留）
- `vendor/sifli/chips/sf32lb52/sf32lb52_bt_adapter.c:362-392`（`wait_tx_idle` 与 `usleep(1000)`）、`:970`（每次 send 前等排空）
- `out/ble_align/include/nuttx/config.h:168`（`USEC_PER_TICK 10000`）、`:433`（`TCP_RTO 3`）、`:439-441`（SACK/乱序）、`:829`（`L2CAP_TX_BUF_COUNT 8`）、`:831`（`TX_MTU 1691`）
- `nuttx/include/nuttx/clock.h:155/316-318`（tick 向上取整）、`nuttx/sched/signal/sig_usleep.c:70-83`（`usleep` 路径）
- `frameworks/connectivity/bluetooth/service/stacks/zephyr/sal_pan_interface.c:1732-1734`（`K_NO_WAIT` 丢帧）；`external/zblue/zblue/subsys/bluetooth/host/conn.c:1670-1673`（告警文案）
- `packages/ai_agent/src/infra/network_manager.c:225`（读超时）、`:408/444/477`（三个 `check_*_has_ip`）、`:632-688`（选路与 `Active channel:`）、`:690-710`（断链作废池）
- `packages/ai_agent/src/infra/vela_tls.c:71-110`（`decode_chunked`）、`:141-144`（池大小）、`:211-228`（复用条件，**注释与代码不一致**）、`:703-726`（末尾块判定）、`:760-763`（`body=/reads=/ms`）
- `docs_ble/90 §1.2-§1.7/§3.1/§4/§9`（帧上限、四样本、丢帧、字节预算、SRAM 账、未证实清单）、`docs_ble/43 §十六/§十八`（chunked 卡 20 秒、PAN 吞吐修复）、`docs_ble/99 §3`（MTU/数值对照）

**内存**
- 实测命令（本章全部内存数字的出处）：
  - `./prebuilts/gcc/linux-x86_64/arm-none-eabi/bin/arm-none-eabi-size .v3_work/fw_store/v131_llm_stream/nuttx.elf` → `7419782 15520 483800`
  - `arm-none-eabi-nm -n …/nuttx.elf | grep __bss_end__` → `0x2007c418`；`g_idle_topstack` 值经 `objdump -s` 读出 = `0x2007e418`
  - 同命令对 `out/nuttx_contest_board_ai_agent/nuttx` → `7370114 15104 480408`（**旧产物，非 v131**）
- `vendor/sifli/chips/sf32lb52/sifli_allocateheap.c:45/65/70-71/182-186/198-205`（SRAM_END、PSRAM、`up_allocate_heap`、`arm_addregion`）
- `vendor/sifli/chips/sf32lb52/sifli_start.c:53/141`（`HEAP_BASE`）
- `nuttx/sched/init/nx_start.c:578-588`（堆初始化）、`:642`（idle 组）、`:714`（`up_initialize`）；`nuttx/arch/arm/src/common/arm_initialize.c:65`（`arm_addregion`）
- `board/contest_board/configs/ai_agent/defconfig:159`（`IDLETHREAD_STACKSIZE 8192`）
- `docs_ble/26 §一/§二/§四/§五/§六/§八`（三块账、库级、可挪移、工具局限）、`docs_ble/43 §二十三`（v93 击穿链与 idle 栈修复）
- `docs_ble/device_tech/_data_snapshot_v131.md §2`（v131 内存权威值）

**功耗**
- `contest2026_181_womenshayebuhuiduidui/design/README_硬件整合方案_v1.md §5`（四个状态的估算表）、`§6`（装配步骤）
- `_facts_baseline.md §2/§6.2`（电池未装机、无电量计、4G 硬件未打通）
- `out/ble_align/include/nuttx/config.h`（无 `CONFIG_TICKLESS` / `CONFIG_PM` / `CONFIG_BATTERY`）

**总图**
- `docs_ble/90 §1.6/§1.7/§5`（字节预算、握手次数、收益合计）
- `packages/ai_agent/src/core/agent_loop.c:1437`（`[turn] first_token_ms`）
- `packages/ai_agent/src/voice/mimo_asr.c:59/97-99/185-190`（8kHz 降采样）
- `.v3_work/fw_store/v131_llm_stream/meta.txt`（v131 实测：`ask 你好 total 5460ms / first_token 4063ms / 6 deltas`；`TTS 短句 total 2.86s（首块 1.66s）`）

---

## 附：本章复核中发现的矛盾与未确认项

写这一节是因为技术文档的价值有一半在"哪里不确定"。以下每一条都不是猜测，是我在核对源码/产物/文档时对不上的地方。

**矛盾一：内存数字的版本错配（已修正）**。`_data_snapshot_v131.md` 初版把 `out/nuttx_contest_board_ai_agent` 的 `size` 输出（`bss 480,408 / data 15,104 = 495,512 B / 94.5%`）当成 v131 口径。我比对 md5 发现该产物（`33e043d8…`，mtime 11-02 22:11）等于 `fw_store/rollback_before_ble/nuttx.bin`，v131 真值是 `483,800 + 15,520 = 499,320 B / 95.2%`、镜像 `7,435,304 B / 7.09 MB`。快照已据此修正，本章全部采用修正值。**教训**：`out/` 的产物不保证是当前基线，引用内存必须先对 md5。

**矛盾二：TLS 连接池注释与代码不一致**。`packages/ai_agent/src/infra/vela_tls.c:211-227` 注释写"v125：复用上限 2→8、空闲门限 10s→120s"，但 `:228` 的代码仍是 `s->reuses >= 2 || (tls_mono_ms() - s->last_used_ms) > 10000`。本章按**代码**写"2 次 / 10 秒"。这条要么是注释写在了改造计划上、要么改造没落地。

**矛盾三：看门狗超时口径的版本差**。`docs_ble/53 §0` 记"实测等效超时 ≈5.5s，而不是配置的 20000ms"，依据是 `sf32lb_iwdg.c:153` 的 `freq=9000`。但当前源码该行是 `freq = 32768`（注释写"原 9000 疑致 20s 变 ~5.5s，待实测标定"），按此算是 20.0 秒。所以 5.5 秒属于**改 freq 之前的固件**。本章两者都标注了版本，没有混用。

**矛盾四：`.bss` 在 v93 修复后继续增长，早期堆从 9,192 B 掉到 5,864 B**。`docs_ble/43 §二十三` 的结论是"早期堆回到 9192 B"，那在 v93 上成立；v131 的 `__bss_end__` 已到 `0x2007c418`，早期堆只剩 5,864 B。差 3,328 B。这条是我复算的，原文档没有后续点，**建议补一条 v131 的早期堆基准**。

**矛盾五：丢帧计数的样本边界**。`docs_ble/90 §1.4` 的"221 条 / 6 分钟窗口（14:00–14:06）"与我在现有 `.v3_work/logs/serial_ts.log`（11-02 15:49–20:10）里查到的"313 条 / 4h20m、聚集在 16:09–16:11（44/45/100 条）"是两个不同会话的样本。两数都成立，都不能当"当前实时计数"。

**未确认一：31ms 帧节拍里 tick 等待占多少。** 实测节拍是 27.1/31.3/33.8/41.1 ms（`docs_ble/90 §1.3`），但"其中 10–30ms 是 `usleep(1000)`→1 tick 的量化"仍是**推断**。判别实验（`wait_ticks/tx_frames` 计数，`docs_ble/90 §6.2`）在 v131 里**没有实现**，`sf32lb52_bt_adapter.c` 里查不到这些静态计数。要确认必须补这段 instrumentation 并跑一次 TTS 下载。

**未确认二：v131 的 PSRAM 空闲量。** `docs_ble/26 §一` 的 `Umem` 空闲 7,584,472 B 是 10-10 时点的读数。v131 的对应值我没有找到实测记录，也没有设备在连，所以本章只写"结构上存在 8MB 的 PSRAM 堆"，不写具体空闲数。

**未确认三：`LV_USE_SYSMON` 关闭时的渲染耗时。** 出货配置里三个 sysmon 开关是关的（`defconfig:282-284`），所以 v131 上没有任何 render/flush 毫秒读数。`docs_ble/31` 的 6~8ms / 176ms 属于 2026-10-16 打开 sysmon 时的一次性测量，本章只把它当方法学与历史证据，不作性能指标。

**未确认四：`written=` 到"应有字节数"的对照基线。** 我能给的是换算公式（`written / 32000` 秒），但没有为每一轮 TTS 建立"这句话应该产生多少 PCM"的独立参照，所以"完整"的判定目前只能靠 `dropped=0` 与 `enq==reap` 两个必要条件，充分条件（内容逐字可核）未建立。

---

*04 系统级数据链路 ｜ 2026-11-03 ｜ 对照 v131_llm_stream（md5 3abe598d）｜ 数字来源 `_data_snapshot_v131.md` ｜ 本文所有内存数字经 `arm-none-eabi-size -A/-S` 与 `nm/objdump` 现场核对*
