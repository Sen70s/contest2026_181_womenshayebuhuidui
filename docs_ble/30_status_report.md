# 30. 状态报告与下一步（2026-10-11）

> 面向：Team 181 / 需要快速决策的人
> 结论先行：**屏幕黑 = 刷的是"音频验证镜像"（没有 UI），不是没刷进去**；
> **麦克风软件侧已证明正确，但拾音未证实**（问题在模拟/器件侧）；
> **MAX30102 心率可以写**，板子有可用的 I2C 引脚，需要写驱动 + 心率算法。

---

## 一、屏幕为什么没显示

**不是没刷入。** 现在板子里跑的是 `out/audio_test/nuttx.bin`（856 KB，2026-10-11 11:02 构建）——这是**为了定位麦克风问题专门裁出来的音频验证镜像**：

| 配置 | `configs/audio_test`（当前在板上） | `configs/nsh` / 出货镜像 |
|---|---|---|
| LCD / CO5300 驱动 | ✅ 有 | ✅ 有 |
| **LVGL 图形界面（`CONFIG_GRAPHICS_LVGL`）** | ❌ **没有** | ✅ 有 |
| **桌宠应用（`CONFIG_EXAMPLES_AI_AGENT`）** | ❌ **没有** | ✅ 有 |
| 音频驱动（`CONFIG_AUDIO` + audcodec） | ✅ 有 | ❌ 没有（出货版是 9/19 构建，早于音频工作） |

没有 LVGL、没有应用去画屏 → **屏幕就是黑的**。

### 怎么恢复

| 方案 | 做法 | 代价 |
|---|---|---|
| A. 先看回界面 | 刷回 `out/nuttx_contest_board_ai_agent/nuttx.bin`（7.5 MB，9/19 构建，有 UI、**无音频**） | 一次烧录（需拔插 USB 进 bootrom 窗口）；麦克风相关代码不再运行 |
| **B. 推荐：一个镜像同时有 UI + 音频（+心率）** | 在出货配置上打开 `CONFIG_AUDIO`、挂上音频应用包（这就是 29 号文档 §7 说的"构建挂载"问题），重新构建后烧录 | 需要重建，工作量：改配置 + 编译 + 烧录验收 |
| C. 维持现状 | 继续用音频镜像做麦克风/心率实验 | 屏幕继续黑 |

---

## 二、设备当前状态

| 项 | 状态 |
|---|---|
| 板子 | SF32LB52-DevKit-LCD，供电正常，串口 `/dev/ttyACM0 @ 1 Mbps` 通 |
| 固件 | `Oct 11 2026 11:02:41`（音频验证镜像 + v3 驱动 + 修好越界释放的测试程序） |
| 音频设备 | `/dev/audio/audio0` 已注册，可反复 open/start/stop |
| 稳定性 | **30/30 轮 + 10 轮复测：无挂死、无断言、无 panic**；`nobuf=0` |
| 内存 | `used=37584 / nused=107 / maxused=84888`，30 轮前后**完全一致**（无泄漏） |
| 采集链路 | DMA/中断正常（每轮 `irq=cplt=18`，40 轮累计无误），交付 32768 B/轮 |
| 代码 | 驱动 v3 `bbf9b80`；测试程序修复 `4851778`；文档 `84e6502/…/8e09ca8`，工作区干净 |

---

## 三、麦克风：现在能不能用？

**结论：驱动/框架能用，但"能录到声音"这件事没有被证实 —— 作为产品功能，目前还不能算可用。**

### 已经证实的（软件侧是对的）

1. **生命周期全通**：30 轮 start/stop 不挂死、不泄漏、`nobuf=0`（v3 所有权模型 + shutdown 非阻塞）。
2. **配置与官方 SDK 逐条一致**（29 号文档 §8.10，依思澈官方《Mic 增益调试》[文档](http://docs.sifli.com/projects/sdk/latest/sf32lb52x/audio/mic.html)）：
   - 麦克风 = 数字通道 **CH0**，16 bit，`HPF_COEF=7`
   - 数字增益 `ADC_CH0_CFG_ROUGH_VOL=0xc`（+12 dB）、`FINE_VOL=0`
   - `MICBIAS_EN=1`、`REFGEN_EN=1`、`BG_CFG0.VREF_SEL=0xc / MIC_VREF_SEL=4`
   - 两个模拟核 `ADC1_CFG2=ADC2_CFG2=0x0e`（EN/RSTB=1）
3. **板子是模拟 MEMS 麦**（官方 DevKit-LCD V1.2.0 原理图：M0100 = WMM7037ATEN0，模拟输出 → `MIC_ADC_IN` → 模组 pin37，`MIC_BIAS` → pin36），**不是 PDM 麦**。

→ 因此"驱动选错通道/增益配错/漏初始化"这一类**基本可以排除**。

### 没有证实的（问题在现场）

- 三次受控测试（拍手/敲桌、说话、1 kHz 播放音 ×2）中，PCM 都只有 **RMS≈13、peak 40~65** 的底噪级波形，**没有可辨认的事件响应**（`.v3_work/mic_knock_run.log`、`mic_speech_run.log`、`mic_tone_timed_run.log`）。
- `pcm-audio` 分析：0% 削波、无异常静音、无爆音，SNR 68 dB（低电平下的结论，不能当"有声音"的证据）。

### 只剩两条路可以定论（各 5~10 分钟）

1. **量电**：万用表量模组 **pin36（MIC_BIAS）对 GND** 是否有偏置电压；有示波器的话看 **pin37（MIC_ADC_IN）** 放音时是否有波形。
2. **换件**：换一块 DevKit / 换一个模组交叉验证（有备件的话）。

没有这两条中的任一条，软件侧我不再能得出新结论（官方 SDK 对照已做完）。

---

## 四、MAX30102 心率：可以做，方案如下

### 现状

- 产品里的"心率"是**模拟**的：`app/ai_agent/health/hr_monitor.c` 随机游走 + `hr_set` 注入，用于演示告警链路（已验证 121/143/138 bpm）。
- **云端工具已现成**：`packages/ai_agent/src/tools/tool_health.c:52` 已经在读 `ORB_ID(algo_heartrate)`，关键词路由也在（`agent_loop.c:351`）；**只缺这个 uORB 主题的真值**。
- 开发板**没有板载心率传感器**（nsh defconfig 只有 `CONFIG_SENSORS_LSM6DSL` IMU）；NuttX 树里 99 个传感器驱动中**没有任何 PPG/心率驱动** → 需要新写（可以用树内驱动当模板）。

### 接线（板子已有可用 I2C，不需要飞线到芯片）

| MAX30102 模块 | 接到板子 | 说明 |
|---|---|---|
| VIN | 3V3 | 模块一般自带 1.8V LDO，可吃 3.3V |
| GND | GND | — |
| SCL | **PA30**（40-pin 排针 24 脚，触摸屏 I2C1 SCL） | 与触摸屏共用 I2C1 |
| SDA | **PA33**（40-pin 排针 15 脚，触摸屏 I2C1 SDA） | 地址不冲突（MAX30102=0x57，FT6146=0x38） |

> 若不想共用触摸总线：文档明确"所有 GPIO 都可复用为 I2C，推荐 PA39~PA42"，可用 8080 数据线那几根空闲脚另起一路 I2C。

### 软件工作量

| 步骤 | 内容 | 规模 |
|---|---|---|
| 1 | **MAX30102 驱动**（NuttX 传感器 upper/lower-half + uORB）：MODE_CONFIG(HR/SpO2)、SPO2_CONFIG(100 Hz/18bit/4 平均)、LED1_PA/LED2_PA 电流、FIFO 读、INT_STATUS、TEMP | ~250 行，+Kconfig/Make.defs/CMakeLists |
| 2 | **心率算法**：0.5–3.5 Hz 带通 + 自适应峰检 + 8 拍中位数 → bpm/置信度，1 Hz 发布 | ~200 行 |
| 3 | **uORB 主题**：补 `nuttx/include/fitness/algo_heartrate.h` 与 topic 定义（现在树里没有） | ~60 行 |
| 4 | **挂进构建**：在出货配置里打开传感器 + 应用（顺带把音频一起挂上，方案 B） | 配置改动 |
| 5 | **验收**：手指按住 → 串口打印 bpm 与参考设备（手表/血氧仪）对比；再走一次云端 `get_heartrate` 拿真值 | 30 分钟 |

预计：**驱动+算法 1~2 天**（含真机调试），之后每次验收只要一次烧录。

### 风险

- MAX30102 的 LED 电流/采样率参数需要按手指贴合度调；运动伪影下 bpm 会抖，首版按"静息测量"验收。
- 共用 I2C1 时必须有总线互斥（NuttX I2C 驱动自带 lock），触摸与 PPG 交替读没有冲突风险，但要压测一次。

---

## 五、建议的执行顺序

1. **（今天，30 分钟）** 恢复屏幕：如果你只要"先看到界面"，我烧回出货镜像；如果你要"界面+音频+心率一起"，我直接开始方案 B 的重建（见下）。
2. **（今天，1~2 小时）** 我在出货配置上启用音频（挂载 + 配置），重建"UI+音频"镜像并烧录验收 —— 这一步同时解决"麦克风在正式固件里不可用"的挂载问题。
3. **（拿到 MAX30102 接线后，1~2 天）** 写 MAX30102 驱动 + 心率算法 + uORB 主题，走通云端 `get_heartrate`。
4. **（并行，等你 5 分钟测量）** 量 MIC_BIAS / MIC_ADC_IN，给麦克风一个最终结论：偏置正常但无波形→换麦/换板；偏置异常→查 MICBIAS 电路。

## 六、需要你决定/提供

- [ ] 屏幕：先刷回旧出货镜像（快），还是直接做"UI+音频+心率"合并镜像（推荐）？
- [ ] MAX30102：模块是否能接到 40-pin 排针的 **PA30/PA33**（或告诉我你打算接哪两个脚）？
- [ ] 麦克风：能否量一下 MIC_BIAS（或换板交叉验证）？
---

## 七、进展更新（2026-10-11 12:30）

### 7.1 合并镜像已构建（UI + 音频 + 语音）

在出货配置 `board/contest_board/configs/ai_agent/defconfig` 上打开板载音频后重建（同时在 `out/nuttx_contest_board_ai_agent/.config` 落了同样 5 个符号；构建目录陈旧，需先让 CMake 重新 configure）：

```
CONFIG_AUDIO=y
CONFIG_AUDIO_NUM_BUFFERS=8
CONFIG_AUDIO_BUFFER_NUMBYTES=4096
CONFIG_EXAMPLES_AUDIO_SETUP=y
CONFIG_EXAMPLES_AUDIO_REC_TEST=y
```

产物：`out/nuttx_contest_board_ai_agent/nuttx.bin`（**7.25 MB**，12:29 构建）。符号核对：`audio_setup_main` / `audio_rec_test_main` / `sf32lb52_audio_start_capture` / `lv_init` / `ai_agent_main` / `mic_capture_open` 都在镜像里 ✅
→ 烧进去以后：**屏幕有界面、`/dev/audio0` 可用、ai_agent 的语音采集走同一条链路**（首次使用前在 NSH 跑一次 `audio_setup` 注册）。

> 踩坑记录：往 defconfig 里加**中文注释**会让该项目 `cmake/nuttx_kconfig.cmake` 的 kconfig 导出宏报 `string sub-command REGEX ... needs at least 5 arguments`（重新 configure 直接失败）。改成纯 ASCII 注释后正常。

### 7.2 麦克风通道问题已彻底查清：驱动是对的

厂商开源 SDK 与官方手册给出结论（详见 [29 §8.11](29_v3_design.md)）：

- SDK `audio_server.c` 注释直接写 `ADC_CH0 ... // ADC1`、`ADC_CH1 ... // ADC2`，**单声道录音默认就是 CH0**；
- 模组规格书：pin37 `MIC_ADC_IN` = 模拟音频输入，芯片侧网络名 **ADC1P**；
- 芯片规格书：只有一个音频 ADC 输入脚（ADCP），单端模拟 MIC 输入；
- 本驱动：CH0 + `ROUGH_VOL=0xc`(+12 dB) + 模拟 ADC1 GC=0x4(=18 dB) —— **与官方 mic 路径逐项相同**。

→ **软件侧没有可改的东西**。剩下的只能是模拟/器件：MICBIAS 实际电压、MEMS 本体、模组到 ADCP 的连接。

### 7.3 心率：官方外接方案参考

- **DevKit-LCD 没有板载心率传感器**（厂商板级配置/框图均无生物传感模块）。
- 官方 Solution 适配的外接 PPG 是 **VC32S / VC9202**（心率+血氧+血压）与 **HRS3300**，都是 **I2C**，算法库由器件原厂提供（`customer/peripherals/sensor/`）。
- 你手上的 **MAX30102 不在官方适配清单**，但它是标准 I2C PPG、寄存器公开 → 我们自己写驱动即可（NuttX sensor uORB 模式 + 峰检算法），不依赖原厂算法库。
## 八、事故记录：合并镜像里“音频测试 + agent 抢麦”把整机搞挂（2026-10-11 12:40）

**现象**：合并镜像（UI+音频）烧入后一切正常 —— 界面在、`/dev/audio0` 注册成功、手机 BT/PAN 也连上了（`[pan] ACL up` → `set_security ret=0`）。随后在 **`ai_agent` 正在运行（它自己会用麦克风做语音采集）** 时又执行了 `audio_rec_test 3`，串口随即彻底静默，复位/拔插后仍无启动输出。

**原因**：两个程序同时打开同一个**单会话**音频设备（`CONFIG_AUDIO_MULTI_SESSION` 未开）。出货 defconfig 注释里本来就记着 voice/LVGL 的堆损坏老问题，这次把它踩响了。**与 v3 驱动无关** —— v3 的 30 轮验收是在没有 agent 的音频镜像上做的。

**恢复过程**：回刷备份的已知good出货镜像 `/home/aila/backups/vela_contest_2026-10-10/firmware/nuttx.bin`（md5 `4b38036a6c3914f129238ff18c6400c4`，9/19 构建，带界面+蓝牙、不带音频）。

- RTS 自动复位路径**再次确认不可用**：`sftool --before default_reset` → `Failed to download stub: TimedOut`；可行路径仍是**冷上电窗口**（脚本 `.v3_work/recover_flash.py`：先试 RTS，失败后等拔插，30 分钟窗口）。
- 回刷后已验证：`uname` = `Sep 19 2026 22:17:32 arm ai_agent`，`ps` 中 `bluetoothd` / `ai_agent -d` / `lcd_async_init` 均在。

**三条规矩（已加进流程）**：

1. 合并镜像里**不要**跑 `audio_rec_test` —— 麦克风归 `ai_agent` 的语音路径；要单独测音频就用音频验证镜像，或先 `ps` 找到 `ai_agent` 的 pid 并 `kill` 掉；
2. 大镜像（7.25 MB）烧录**单次尝试要给足超时**（≥ 900 s）：曾用 60 s 超时，在 70% 处被 kill，留下半截 flash；
3. 往该项目 defconfig 加注释**只能用 ASCII**：中文/长破折号会让 `cmake/nuttx_kconfig.cmake` 的导出宏报 `string sub-command REGEX ... needs at least 5 arguments`，configure 直接失败。
## 十、扬声器验证结果：链路可用 ✅（2026-10-11 13:35 真机）

**结论：喇叭响了（听到 1 kHz 音调）** —— DAC → 板载 Class-D 功放(PA10) → 喇叭 这条链路是通的。用的是新写的 `audio_beep`（CPU 直喂 DAC FIFO，绕开 DMA）。

### 关键事实（都有真机证据）

| 项 | 证据 / 结论 |
|---|---|
| 寄存器状态 | 播放中读回 `CFG=0x1a`（DAC_ENABLE=1）、`DAC_CFG=0x194`（osr=4/clk_src=1/clk_div=1，与官方 16k 表一致）、`DAC_CH0_CFG=0x029ba589`（ENABLE=1 / DOUT_MUTE=0 / DMA_EN=1 / 16bit / SINC_GAIN=0x14D） |
| DAC 是否在消费 | CPU 喂数时 `APB_STAT.DAC_CH0_FIFO_CNT` min=0 / max=8 → 进出都有，DAC 确实在消费样点 |
| **52x 没有 DAC DMA 实例** | 官方 `dma_config.h` 只给 `AUDCODEC_DAC0/1_DMA_REQUEST`(41/42)，**没有 INSTANCE/IRQ**（56x/57x 也没有，58x 才有 `DMA2_Channel1`）；官方 RT-Thread 驱动在 52x 上 `#ifdef AUDCODEC_DAC0_DMA_INSTANCE` 编不进去 → 官方数据流是 **AUDPRC → AUDCODEC → 喇叭** |
| 自选 DMA 不通 | 第一版用 `DMA2_Channel1` + 请求 41 直连 DAC：只有咔哒、无音调（DMA 没搬数据） |
| 播放顺序 | 官方 HAL 文档：`Config_DACPath(1)` 静音 → `Config_Analog_DACPath` → `__HAL_AUDCODEC_DAC_ENABLE` → `Config_DACPath(0)` 解静音；PA 拉高后等 ~100ms 再出声 (`audio_pa_api.c`) |
| 音量 | `HAL_AUDCODEC_Config_DACPath_Volume(h,ch,vol)` 单位是 **0.5 dB**、范围 −36…54 |

### 顺带结论

- 这次同时证明了 **PLL / REFGEN / 模拟前端的 bring-up 是对的**（DAC 能出声），给"麦克风问题的软件侧无误"再加一条旁证。
- 麦克风侧仍未闭环（见 §8.5~8.11）：数字配置与官方逐条一致，剩下只能靠外部测量/换件。

### 下一步（播放正式化，二选一）

1. **定时器喂数 + NuttX audio TX lower-half（推荐，今天可完成）**：用一个 GPTIM 中断每 500 µs 往 `DAC_CH0_ENTRY` 写 8 个样点（FIFO 深度正好 8，2 kHz 中断开销很小），上层按 NuttX `audio_ops` 的 TX 侧实现 `enqueuebuffer/start/stop`；再打开 `CONFIG_AUDIO`+`CONFIG_MEDIA`，现成的 `audio_playback.c` / 火山 TTS 就能出声。
2. **按官方架构接 AUDPRC**：AUDPRC 做 DMA/采样率转换，再喂 AUDCODEC。更正统（也是官方唯一支持的 52x 播放路径），但要新写 AUDPRC 驱动，工作量明显更大。

测试程序：`app/ai_agent/audio/audio_beep_main.c`（`audio_beep [秒] [Hz] [0.5dB] [cpu|cpu1|dma]`；`cpu`=CH0、`cpu1`=CH1 数字通道）。
## 十一、正式播放链路（方案 2：AUDPRC → AUDCODEC → PA）已实现并跑通时钟 ✅（2026-10-11 14:14 真机）

### 实现

| 文件 | 内容 |
|---|---|
| `app/ai_agent/audio/sf32lb52_audio_tx.c` | NuttX 播放 lower-half：apb 队列 + worker + `DMA1_Channel1`(REQ 51) 循环 DMA（半/全传输中断驱动补数）+ AUDPRC `TX_CH0` + `DAC_PATH(DST_SEL=0=CODEC)` + AUDCODEC `DAC_CH0`（`opmode=0`）+ PA10；欠载补静音并计数；音量走 `AUDIO_TYPE_FEATURE/AUDIO_FU_VOLUME` |
| `app/ai_agent/audio/audio_play_test_main.c` | 框架级验收：CONFIGURE → GETBUFFERINFO → REGISTERMQ → ALLOCBUFFER×8 → 填 1kHz（16 点查表）→ ENQUEUE×8 → START → DEQUEUE 回填 → STOP |
| Kconfig / CMake / `audio_setup` | `CONFIG_EXAMPLES_AUDIO_SETUP_TX`（设备名 `pcm0p`，与 `AGENT_AUDIO_PLAYBACK_DEV` 一致）、`CONFIG_EXAMPLES_AUDIO_PLAY_TEST` |

### 真机实测（`audio_play_test 3 1000`）

```
audio_tx: halves=47 (=47 半缓冲), underruns=0, deq=24, 297 ticks
audio_tx: 平均 16 半缓冲/秒 (16kHz 播放应为 15.6)
play_test: done, 98304 bytes queued        # = 3.07 s 音频, 实时播完
```

- **速率 16 半缓冲/秒 ≈ 理论 15.6** ✅ → AUDPRC 时钟（XTAL 48M，`STB div=3000`）与 DMA 节拍正确；
- **underruns=0** ✅ → 8 只 apb 的流水线没有饿死 DMA；
- 24 次 DEQUEUE × 4096 B = 98304 B = 3.07 s ✅ 与请求的 3 秒一致。

### 踩到的两个框架坑（都记下来）

1. **播放方向 DEQUEUE 时 core 会清空 buffer**：`nuttx/audio/audio.c:1490-1494` 对 `AUDIO_TYPE_OUTPUT` 会 `apb->nbytes = 0` 并 `memset(apb->samp, 0, nmaxbytes)`。客户端**不能用 `apb->nbytes` 计进度**（会永远为 0、循环不结束），必须自己计数。
2. **mq 属性两头都要对齐**：`mq_msgsize` 超过 `MQ_MAX_BYTES=CONFIG_MQ_MAXMSGSIZE(32)` 会在 `nxmq_alloc_msgq()` 断言（`mq_msgqalloc.c:76`）；而 `mq_receive` 又要求“缓冲 ≥ 队列 maxmsgsize”（`mq_receive.c:98`）。传 NULL attr 时队列取 32，收 8 字节消息反而 EMSGSIZE。照采集侧写法 `mq_msgsize = sizeof(struct audio_msg_s)` 即两头都对。

### 与麦克风的关系

播放用 XTAL 48M，**不占 audio PLL**，所以播放与采集（走 PLL/ADC 通路）互不干扰；两侧可同时开。
### 播放期间寄存器实测（10 秒长播，客观证据）

| 块 | 寄存器 | 值 | 判读 |
|---|---|---|---|
| AUDPRC | `CFG` | `0x000a0000` | 模块 ENABLE + DAC_PATH_EN 都置位 ✅ |
| AUDPRC | `STB`(+0x08) | `0x0bb80bb8` | ADC/DAC 分频 **3000/3000** → 48MHz/3000 = **16 kHz** ✅ |
| AUDPRC | `TX_CH0_CFG` | `0x00000031` | 通道使能、数据在流 ✅ |
| AUDCODEC | `CFG` | `0x0000001a` | **DAC_ENABLE=1** ✅ |
| AUDCODEC | `DAC_CFG` | `0x00000104` | osr=4、**clk_src_sel=0(XTAL)**、clk_div=1、**opmode=0(AUDPRC→codec)** —— 与我的 xtal 表逐字段一致 ✅ |
| AUDCODEC | `DAC_CH0_CFG` | `0x029b0609` | ENABLE=1、**DOUT_MUTE=0(未静音)**、DMA_EN=1、16bit、SINC_GAIN=0x14D ✅ |

10 秒长播统计：`halves=157`（理论 156）、`underruns=0`、`deq=103`、`1001 ticks` → **速率 16 半缓冲/秒 = 精确 16 kHz，全程零欠载**。
结合用户听觉反馈（扬声器已接、有声音）与 §10 的 CPU 直喂出声实验，**整条扬声器链路（AUDPRC → AUDCODEC DAC → 模拟 DAC1 → PA10 → 喇叭）验证通过** ✅
## 十二、出货镜像音频集成与真机验证 ✅（2026-10-11 14:55）

### 集成内容（出货 defconfig）

```
CONFIG_AUDIO=y / NUM_BUFFERS=8 / BUFFER_NUMBYTES=4096
CONFIG_EXAMPLES_AUDIO_SETUP=y
CONFIG_EXAMPLES_AUDIO_SETUP_DEVNAME="pcm0c"      # 采集, 对齐 AGENT_AUDIO_CAPTURE_DEV
CONFIG_EXAMPLES_AUDIO_SETUP_TX=y
CONFIG_EXAMPLES_AUDIO_SETUP_TX_DEVNAME="pcm0p"   # 播放, 对齐 AGENT_AUDIO_PLAYBACK_DEV
CONFIG_EXAMPLES_AUDIO_REC_TEST=y / AUDIO_PLAY_TEST=y / AUDIO_PLAY=y
```

设备名改成由 Kconfig 统一驱动：采集驱动、`audio_setup`、`audio_rec_test`、`mic_capture.c` 都取同一个宏，避免"应用开 pcm0c、驱动注册 audio0"这类错位。

### 真机结果（出货镜像，UI + ai_agent + BT 同时运行）

```
audio_setup: /dev/audio/pcm0c ready (mic capture 16kHz/16bit/mono)
audio_setup: /dev/audio/pcm0p ready (speaker 16kHz/16bit/mono)

# audio_play_test 3 1000   (框架级)
audio_tx: halves=47, underruns=0, deq=24, 297 ticks
audio_tx: 平均 16 半缓冲/秒 (16kHz 播放应为 15.6)

# audio_play tone 3        (产品级, 直接播 PCM)
audio_play: tone @ 16000 Hz -> /dev/audio/pcm0p
audio_tx: halves=47, underruns=1, deq=48, 297 ticks
audio_play: done, 65536 bytes  (+ 预填 8×4096 = 98304 B = 3.07 s)
```

两条路径都**实时 16 kHz、几乎零欠载**，播放期间系统保持存活（agent/BT 未受影响）。

### 踩坑记录

1. **不要在 agent 启动风暴期间测音频**：BT/PAN/网络初始化时控制台日志量巨大，串口看起来像"死机"（实测 idle 6 s 内 0 字节）。等 60–90 s 后再操作即可。
2. **测试一律后台跑** (`audio_play ... &`)，前台 shell 永远可用；早期版本 `audio_play` 的收尾判断依赖框架 flag，会无限等待 → 已改成"最后一只 apb 回来即结束" + `mq_timedreceive` 3 s 超时。
3. **media 框架（`media_player`）在本工作区不可用**：`frameworks/multimedia/media/utils/media_utils.c:25` 需要 `cutils/properties.h`（openvela cutils + property 服务），本构建没有 → `CONFIG_MEDIA` 只能保持关闭。替代：TTS/提示音直接写 `/dev/audio/pcm0p`（`audio_play` 即此用途），或用 `audio_play <file.pcm>`。

## 十三、MAX30102 心率：调研结论与落地计划（2026-10-11）

### 三个关键事实（都有文件行号）

1. **应用读的是"算法话题"且全树没有发布者**：`packages/ai_agent/src/tools/tool_health.c:52` → `orb_read_once(ORB_ID(algo_heartrate), &hr)`；`algo_heartrate` 的 `ORB_DEFINE` 在 `frameworks/system/topics/src/fitness/algo_heartrate.c:23`，唯一消费者是 `frameworks/runtimes/feature/modules/health_impl.cpp:91`，**没有 producer**。结构体 `{timestamp_us, bpm, 3×section}`（`frameworks/system/topics/include/fitness/algo_heartrate.h:29-37`）。
2. **三个开关全没开**：出货 `.config` 里 `# CONFIG_USENSOR is not set`、无任何 `UORB` 符号，且 `CONFIG_FITNESS_ALGO` 在整个工作区**只出现在 tool_health.c 的 4 个 `#if` 里**（没有 Kconfig 生产它）→ 该工具现在必然返回 `{"error":"fitness topics not available"}`。
3. **I2C 可挂**：总线 0 = `/dev/i2c0` = I2C1，最终 mux 在 **PA30(SCL)/PA33(SDA)**（`bsp_pinmux.c:234-235` 覆盖 `sifli_ap.c:480` 的 PA37），现只有 FT6146 触摸 @0x38；MAX30102 固定 **0x57**，不冲突，且 sifli master 全程持锁（`sifli_i2c.c:485→571`）。总线 1 的 PA40/PA39 mux 被关在 `#if defined(CONFIG_SENSORS_LSM6DSL)` 里，暂不可用。

### 器件级要点（Linux + SparkFun + 树内 Zephyr 三源逐位一致）

| 项 | 值 |
|---|---|
| 地址 | 0x57（无 ADDR 引脚；0xAE/0xAF 是 8 位写法，别直接填 `msg.addr`） |
| 仅心率模式 | `MODE_CONFIG(0x09)=0x02`（RED only）；0x03 = RED+IR |
| 100Hz/4096nA/411us-18bit | `SPO2_CONFIG(0x0A)=0x27` |
| FIFO 配置 | `0x08 = SMP_AVE\|ROLLOVER\|A_FULL`；**A_FULL 写的是"剩余空槽数"**（0x00=满32，0x0F=17） |
| 样本解码 | 3 字节大端 18bit：`((b0<<16)\|(b1<<8)\|b2)&0x3FFFF`，通道顺序 **RED→IR→GREEN** |
| 采样率陷阱 | `SMP_AVE` 会再除速率：SR=100Hz+4平均 → 实际只有 25Hz；要真 100Hz 用 **SR=400Hz+4平均**（Linux 做法） |
| 溢出 | 读 `0x05`；非 0 → 清 FIFO（`0x04/0x05/0x06=0`）并复位用户态算法状态 |

### 落地计划（本次采用）

- **内核驱动**（抄 `nuttx/drivers/sensors/ltr308_uorb.c` 的纯 I2C + kthread 骨架）注册 **`SENSOR_TYPE_PPGD` → `/dev/uorb/sensor_ppgd0`**，只出"带时间戳的原始 RED 计数"（100Hz 或 25Hz 由 `0x08/0x0A` 决定）；LPWORK 轮询（本板无空闲中断脚），`activate(false)` 用 `work_cancel_sync`。
- **算法放用户态**（`hr_monitor.c`）：0.5–3.5Hz 带通 + 峰检 + 中位数 → `orb_advertise(ORB_ID(algo_heartrate))` + `orb_publish()` → **`tool_health`/`pet_care` 链路不用改**。
- **配置**：`CONFIG_USENSOR=y` → `CONFIG_UORB=y`；新增 `CONFIG_SENSORS_MAX30102`（**不要 `select I2C`**，否则会像 LSM6DSL 那样连带拖垮触摸）；`CONFIG_FITNESS_ALGO` 需要真正定义（现无 Kconfig 生产者）。
- **坑**：`hr_monitor.c` 在工作区有**两份相同副本**（`packages/ai_agent/src/health/` 与 `app/ai_agent/health/`），改一处必须同步另一处；`sifli_ap.c:257` 抄 LSM6DSL 会抢走触摸的 PA30，别抄那行。
- **验收**：`i2c get -b 0 0x57 0xff 1` 应回 `0x15` → 驱动起来后 `sensortest ppgd0 -i 10000` → 端到端调 `get_heartrate`。
### 实现状态（2026-10-11 15:00，用户态路线已进镜像）

| 文件 / 配置 | 内容 |
|---|---|
| `app/ai_agent/sensors/max30102.c` | 用户态驱动 + 心率算法，NSH 命令 **`hr_sensor start\|stop\|status\|info`** |
| 器件配置 | 0x57 / `0x09=0x02`(仅RED) / `0x0A=0x2F`(4096nA + SR=400Hz + 411us-18bit) / `0x08=0x5F`(4平均+ROLLOVER) → **真实 100Hz 输出** / LED1=0x24(7.2mA) / 轮询(无中断脚) |
| 算法 | DC 跟踪(0.16Hz) → 0.5–3.5Hz 带通 → 自适应阈值峰检(最小间隔300ms) → 8 拍 RR 中位数 → bpm；FIFO 溢出则清 FIFO 并复位算法 |
| 发布 | `orb_advertise(ORB_ID(algo_heartrate))` + `orb_publish`（本树 API 是 **fd 形式**：`int orb_advertise(meta,data)` / `orb_publish(meta,fd,data)`，不是 `orb_advert_t` 句柄） |
| 配置 | `CONFIG_USENSOR=y` `CONFIG_UORB=y` `CONFIG_FITNESS_ALGO=y`(本项目 Kconfig 新增) `CONFIG_EXAMPLES_SENSOR_MAX30102=y`；**不 `select I2C`**（避免 LSM6DSL 那种连带伤害） |
| I2C 访问 | 走字符设备 `/dev/i2c0` + **`I2CIOC_TRANSFER`**（注意：`I2C_TRANSFER` 那个宏是 master ops 用的，**不是 ioctl 号**，踩过一次） |

### 真机现状（2026-10-11 15:00）

- 镜像已含 `hr_sensor`（符号核对通过），`i2c bus` 显示 Bus0/Bus1 均存在；
- **对照实验**：触摸 `i2c dev -b 0 0x38 0x38` → 0x38 正常应答 ✅（说明 i2ctool 与总线都好）；
- **MAX30102**：`i2c dev -b 0 0x57 0x57` → 显示 `--`（无器件），`i2c get -b 0 -a 0x57 -r 0xff` → `Transfer failed: 1`（NACK）→ **模块尚未接上/未上电**；
- `hr_sensor start` 因此报"初始化失败"并给出接线提示（VIN=3V3, GND, SCL=PA30, SDA=PA33）。

接线后无需重新烧录：直接 `hr_sensor start` → `hr_sensor status` 即可（期望每秒打印 `bpm=.. (samples=.., ovf=0, raw=..)`）。

### 后续（可选，更正统的形态）

内核驱动 + `SENSOR_TYPE_PPGD` → `/dev/uorb/sensor_ppgd0`（骨架与可抄段落见子代理调研：抄 `nuttx/drivers/sensors/ltr308_uorb.c`），与当前用户态实现可并存。
## 九、“初次能连、之后连不上”的现场判定（2026-10-11 13:05，真机取证）

用户在恢复镜像上反馈：蓝牙初次连接成功，之后再也连不上。真机状态：

```
ifconfig   → eth0 UP  inet 10.0.0.2  DRaddr 10.0.0.1  Mask 255.255.255.0   (bt-pan 网段)
route      → default via 10.0.0.1
ping 10.0.0.1 → ERROR: sendto failed at seqno 0: 101 (ENETUNREACH)，100% packet loss
```

**判定**：界面/`eth0` 还留着上一次 DHCP 拿到的 IP 和默认路由，但底层 BR/EDR ACL 已经断了 —— 看着“连着”，实际一条包都发不出去（`sendto ... 101`）。这正是项目**已知**的链路密钥问题：

- 板子侧 BR/EDR link key **没有持久化**：LCPU（闭源固件）不存 key，zblue 重启后 RAM 里也没有；手机拿旧 bond 主动重连时，控制器发 `Link_Key_Request (0x17)` → zblue 回 `Link_Key_Neg_Reply (0x040C)` → `Authentication Complete (0x06) status=0x05` → 断开（[LOG.md:92-105](LOG.md)、[tools/zblue_pair_debug_patch.md:57-60](tools/zblue_pair_debug_patch.md)）。
- 因此 PAN 路径刻意 `PAN_SAL_SKIP_SECURITY 1`（BNEP 不需加密），靠**板子主动发起 + SSP user_confirm 自动接受**重新配对（[24 号文档 §P0](24_open_issues.md)：实测 7 秒内重新拿到 IP，不需要在手机上“忘记设备”）。
- 另见 [24.2](24_open_issues.md)：BD_ADDR 是硬编码假值 `cd:ab:78:56:34:12`，多台设备同场会撞地址。

**现场处置（按文档验证过的路径）**：① 手机打开**蓝牙网络共享**、不要手动点“连接手表”；② **重启板子**让它主动连（自动重连 + 自动重新配对）；③ 仍不行才在手机上“忘记设备”，再让板子连。

**根治（待做）**：实现 BR/EDR link key 的文件式持久化（LOG.md 里记的 TODO：port 层挂钩 `bt_keys_link_key_store`，把 key 落到 `/data`），这样手机侧主动重连也能成功。

## 十四、BR/EDR link key 持久化：两条路都被堵，正确落点是 UNQLITE 存储（2026-10-11 16:10）

真机现象（换 MiniMax key 后）：`ask` 云调用在 DNS 就失败
（`[vela_tls] net_connect api.minimaxi.com:443 ret=0x52` = mbedTLS `UNKNOWN_HOST`），
因为 `bt-pan` 没起来；板子 `eth0 10.0.0.2` 是上一次 DHCP 的残留（§9 已记录）。

配对现场（串口抓取）：手机侧忘记设备后重新配对成功 ——
`[SAL] SSP passkey_confirm: 266820, auto-accepting (headless)` +
`[bt_storage] set BtBonded len=484 ret=0`，但 `[pan] no pending conn for ACL`，
未见 `ifup dev=bt-pan` / `dhcp_ok` / `Active channel`。

### 为什么旧 bond 不能复用（协议层）
手机存着旧 link key，板子掉电即失（LCPU 不存、zblue 侧 hook 被编译掉）。手机主动连 →
控制器发 `HCI_Link_Key_Request(0x17)` → 主机取不到 key → `Link_Key_Neg_Reply(0x040C)` →
对端 `Authentication_Complete status=0x05` → ACL 断开 → BNEP/PAN 起不来 → 无 DHCP/DNS/路由。
全新 SSP 配对（忘记设备后）可用，但只要板子断电就再次失效。

### 两条"配置级"根治路都被堵（本次实测）

| 路径 | 结果 |
|---|---|
| `CONFIG_SETTINGS_ZBLUE`（vendor 侧 hook，`sal_adapter_interface.c:488`） | ✗ 依赖 `BLUETOOTH_STORAGE_PROPERTY_SUPPORT` → 依赖 **KVDB**；本项目用 **UNQLITE**（defconfig:404 记录 KVDB 与 log_server 冲突） |
| `CONFIG_SETTINGS` + `CONFIG_SETTINGS_FILE` + `CONFIG_BT_SETTINGS`（Zephyr 原生 link key 持久化，handler 已存在于 `classic/keys_br.c:317`） | ✗ **编译不过**：`host/gatt.c:1516` 引用 `bt_dev_gatt_ctx.gatt_delayed_store`，本 port 头文件没有该成员（`CONFIG_BT_SETTINGS` 未移植） |

### 正确落点（待实现）
在 vendor 的 **UNQLITE bt_storage** 上加 link key 记录：
- 保存：`conn_cb.link_key_notify`（`conn.h:2620`）/ `adapter_on_link_key_update`（`adapter_service.c:1826`）时落盘；
- 恢复：BT init 时把 key 注入主机 key pool（`bt_keys_get_link_key()`，`keys.h:263`），并回填 `device_set_link_key`（`adapter_service.c:513` 已有回填点）；
- 顺带：BD_ADDR 硬编码 `cd:ab:78:56:34:12` 应改为 eFuse/配置读取（多机同场会撞，见 24.2）。

### 现场处置（已验证有效，作为过渡）
板子每次断电重启后：手机「忘记 Agent-Watch」→ 让板子主动连（SAL 自动接受 SSP，bond 会写入存储）。
本次抓到两次成功的 SSP 自动接受即为此路径。
## 十五、云模型链路验收通过 + 三种"连不上"的判定表（2026-10-11 16:30）

### 验收结果（真机，MiniMax M3.1-Flash-Preview）

```
set_llm https://api.minimaxi.com/v1 MiniMax-M3.1-Flash-Preview <key>   # host 侧 curl 先验 200
[llm] OpenAI API with tools (model: MiniMax-M3.1-Flash-Preview, 16301 bytes)
[vela_tls] Handshake OK: TLSv1.2 ...
[llm] Response: 5 bytes text, 0 tool calls, finish=end_turn
[trace:...] iter=0 tool=(none) llm=ok latency=11856ms backend=0     <- llm=ok
[Agent]: T7240      <- 回答带回唯一 token, 证明是真实云端往返(非缓存)
```

- key 只在中国站有效：`api.minimaxi.com` / `api.minimax.chat` = 200；`api.minimax.io`（国际站）= 401。
- 全程 key 未出现在日志/输出里（脚本掩码）。

### PAN 打通的完整成功签名（与 LOG.md:469 一致）

```
[pan] security ok, connecting L2CAP to phone NAP
[pan] setup req sent (7 bytes, dst=NAP src=PANU)
[pan] BNEP setup OK, tx_mtu=1691
[pan] state=ifup dev=bt-pan tx_mtu=1691 if_mtu=1500
[pan] state=dhcp_ok dev=bt-pan ip=192.168.44.140
[pan] state=dhcp_dns dns=192.168.44.1
[netmgr] Active channel: bt-pan (primary)
```

### 三种"连不上"必须分清（本轮实测 + 记录出处）

| # | 日志特征 | 根因 | 记录 | 固件问题 |
|---|---|---|---|---|
| A | `state=connecting` 超时 `disconnected`；或 `reason 0x13` 断 ACL；无 `ifup/dhcp_ok` | **手机侧没应答**（蓝牙网络共享没真正生效/不在范围） | `LOG.md:82`、`LOG.md:537-539` | 否 |
| B | `Link_Key_Request(0x16)` -> `Neg_Reply(0x040C)` -> `Auth Complete status=0x05` | **link key 不持久化**（重启即失） | `LOG.md:87-95`、`24 §P0`、`§9` | 是 |
| C | 串口 0 字节、回车无回显 | nsh 与 agent `vela>` 抢同一 console，命令刷多了把控制台弄哑 | `LOG.md:533-536` | 操作方式 |

**本轮教训（已固化为规矩）**：
1. 自动化脚本**只做只读监听**；要执行命令时**先确认提示符是 `nsh>`**（`vela>` 会吃掉并刷屏）；
2. 每次只发**一行**命令（A/C 都因此复现过）；
3. 控制台哑掉后 RTS 断电重启**无效**，必须**重烧镜像**（本轮实测：重烧后一次恢复，PAN 随即打通）。
## 十六、语音"说"链路：嘴已通、TTS 双后端就绪、等一次烧录（2026-10-11 18:55）

### 已完成

| 环节 | 证据 |
|---|---|
| 🔊 **嘴**（media_player API → /dev/audio/pcm0p） | `packages/ai_agent/src/voice/pcm_media_player.c`（新，强符号，替换 stubs.c 的弱桩）：TTS 推流 24kHz s16le → 线性插值重采样 → 96KB 环形缓冲 → worker → apb 入队 → pcm0p。真机实测 `pcm_media_test 3 1000`：`prepared: in=24000Hz out=16000Hz, 8 x 4096 B`、`written=96000`（= 精确 3.0 秒）、`dropped=0` |
| 🧠 云模型 | MiniMax `MiniMax-M3.1-Flash-Preview`：`llm=ok latency=11856ms`，回答带回唯一 token（真实云端往返，非缓存） |
| 🗣️ Edge TTS（免凭证） | `edge_tts.c`（新，1627 行）+ 回退链 `当前后端 → volc WS → volc HTTP → edge`；输出 `raw-16khz-16bit-mono-pcm`；含 Sec-MS-GEC(DRM 5 分钟时间窗) 与 403 时钟校正重试；镜像 17:22 / 7,312,064 B 编译通过 |
| 🔧 顺手修的隐患 | 播放设备原来**硬编码按 24kHz 打开**；现在按当前后端采样率打开，跨后端时由重采样垫片保证语速/音调正确 |

### 真机验证步骤（烧录后执行，一次一行命令）

```
set_voice_tts            # 看当前后端（默认 volcengine）
set_voice_tts edge       # 强制 Edge（16kHz 原生直通）
ask 你好，这是小云在说话   # 云模型回答 → TTS → 嘴 → 喇叭
```
失败关键字：`TLS handshake failed`（直连被 RST → `set_proxy`）、`WS upgrade failed: HTTP 403`（出口 IP，含自动时钟校正）、`no audio received`、`all TTS backends failed`。

### 阻塞项（都不是代码问题）

1. **板子未接入**：三次烧录均 `TIMEOUT`（`/dev/ttyACM0` 不存在、`lsusb` 无串口桥）→ Edge 版镜像还没进板子；
2. **火山 TTS 账号未授权**：两套凭证 × 多接口/集群全部返回
   `45000030 / 3001 [resource_id=volc.service_type.10029] requested resource not granted`
   （APPID=1661538303）→ 需核对"语音合成控制台显示的 APP ID 是否就是 1661538303"或走客服；
3. **Edge 出口网络**：本机实测该域名被按 SNI 阻断（ClientHello 后 RST），走代理 WS 升级 403（官方 edge-tts 7.2.8 同样 403）→ 设备侧走手机热点可能通，失败按日志用 `set_proxy`。

### 待确认的听感项

### 听感验证完成（2026-10-11 22:00，用户确认）

| 测试 | 路径 | 结果 |
|---|---|---|
| `audio_beep 3 400 0 cpu` | CPU 直推 DAC（opmode=1 + PLL 时钟） | ✅ 听到 400Hz |
| `pcm_media_test 3 1200` | **AUDPRC → codec（opmode=0 + XTAL 表）= 嘴** | ✅ 听到 1200Hz |

→ **两条路径都能发声，嘴（AUDPRC 播放链路）可听性已验证**。此前听不到的原因是
**蓝牙共享网络（BT PAN）把 TTS 音频流截断**（21:47 只送达 0.12 s；21:23 链路好时完整送达 2.16 s）。

### 完整说话链路（每一环均有真机证据）

```
云模型(MiniMax, llm=ok) → Edge TTS(MP3, close 1007 证明端点拒绝 PCM)
  → minimp3 解码(first frame: 24000 Hz, mono, 48 kbps, 8.00:1)
  → 重采样 24k→16k → /dev/audio/pcm0p → AUDPRC → AUDCODEC DAC
  → 模拟 DAC1 → PA10 → 喇叭（已听到 1200Hz 验证）
```

### 遗留问题（均为蓝牙侧，非本链路）

1. **PAN 抖动**：Unable to allocate buffer within timeout 风暴、DNS 失败、LLM call failed、音频流被截断；
2. **控制台被刷哑**：同一风暴把 1 Mbps 串口淹掉（现象像板子死了，实为告警洪水）→ 需在启动后安静窗口发命令；
3. **屏幕卡死已修**：voice_channel_speak 原在 LVGL 线程同步阻塞数十秒 → 已改后台线程异步（voice_channel_speak_async）。

## 十七、待办清单（2026-10-11 22:20 记录）

### 语音"说"链路（已完成、可用，待办均为增强/环境）

| # | 事项 | 类型 | 说明 |
|---|---|---|---|
| 1 | **治好蓝牙链路**（根因 B） | 阻塞级 | BR/EDR link key 不持久化 + PAN 抖动：`Unable to allocate buffer within timeout` 风暴、DNS 失败、`LLM call failed`、**TTS 音频流被截断**（21:47 只送达 0.12 s）、控制台被告警洪水刷哑。这是让"说话"稳定可用的唯一关键 |
| 2 | **抗截断重试** | 增强 | TTS 实际送达时长明显短于文本预期时自动重试一次（改动小、收益大） |
| 3 | **命中格式持久化** | 增强 | 把 Edge 命中的 outputFormat 落盘到 config store，首句延迟从 ~7 s 降到 ~3 s |
| 4 | **演示脚本** | 演示 | 用短句（约 3 KB MP3）规避链路抖动；`speak <文本>`（agent CLI）或 `voice_speak <文本>`（NSH） |
| 5 | 火山 TTS 授权 | 可选 | 账号侧一直 `45000030 / 3001 [resource_id=volc.service_type.10029] not granted`（APPID=1661538303，待核对控制台显示的 APP ID 是否一致）；通了就是双后端，音质更好 |

### 其它既有待办

- **心率**：MAX30102 接线（见 §四）→ `i2c get -b 0 -a 0x57 -r 0xff` 期望 `0x15` → `hr_sensor start`；
- 麦克风模拟侧未闭环（软件已对齐官方，疑在 MIC_BIAS 或模块）。

### 硬件接线注意（I2C 不能热插拔）

1. **先断电**（拔 USB；RTS 即板子电源）再接线，接好检查无误后再上电；
2. MAX30102 模块 **VIN 必须接 3V3**（若模块带 5V 稳压且上拉电阻接 5V，会把 5V 灌进 3.3V 的 MCU，务必确认）；
3. GND 先接、VIN 后接；SDA/SCL 别接反（PA33=SDA / PA30=SCL，与触摸屏共用 I2C1）；
4. 接线期间**不要让上位机烧录/复位板子**（RTS 会断电，等于带电插拔）。

5. **【已更正 22:35】PA41/PA42 在本板是可用的 I2C**（我先前查错了 `#if` 分支）：
   - 先前结论错误：`bsp_pinmux.c:278-279` 把 PA41/PA42 配成 `LCDC1_8080_DIO5/DIO6`，但那些行在
     `#elif defined(BSP_LCDC_USING_DBI)` 分支里 —— 本板走的是 **QADSPI** 分支：
     `CONFIG_LCD_USING_CO5300=y` → `select BSP_LCDC_USING_QADSPI`（已验证 `.config` 两者都为 y），
     该分支只配 PA02–PA08/PA00/PA10(/PA37)，**不碰 PA41/PA42**；
   - 芯片引脚表也确认：`bf0_pin_const.c:1009-1010` 中 PA41/PA42 的可用功能包含 `PA41_I2C_UART`/`PA42_I2C_UART`；
     且 `HAL_PIN_SetI2cFunc()` 是**按 pad 号写 I2Cx_PINR 寄存器**（pad 无关），任意支持 I2C 的 pad 都能分给任一 I2C 实例；
   - 我们镜像里没有任何东西占用 41/42 脚（`.config` 无 `_PIN=41/42`；触摸 IRQ=PA31；音频 PA EN=PA10）。

   因此本项目有两条都可行的接法：

   | 方案 | 引脚 → 总线 | 固件改动 | 评价 |
   |---|---|---|---|
   | A | PA30(SCL)/PA33(SDA) → `/dev/i2c0`（I2C1） | **零改动** | 与触摸共用 I2C1（0x57 vs 0x38 不冲突），但触摸驱动持锁可能干扰 |
   | B | **PA41(SCL)/PA42(SDA) → `/dev/i2c1`（I2C2）** | 加 pinmux + app 路径改一行（约 20 分钟 + 1 次烧录） | **独立总线、无争用，推荐；已采用并真机打通 ✅** |

   注：I2C2 实例本身已编译并注册（`CONFIG_BSP_USING_I2C2=y` → `/dev/i2c1`），只是它的 pinmux 目前仅在
   `CONFIG_SENSORS_LSM6DSL` 打开时才设置（本板未开），所以走方案 B 需要我们自己补 `HAL_PIN_Set(PAD_PA41, I2C2_SCL, ...)`。

6. **【历史记录·已作废】** 先前写的"PA41/PA42 不能用"段落（保留以备追溯）：
   - 本板 `bsp_pinmux.c:278-279`（`BSP_PIN_LCD()`，开机由 `bsp_lcd_tp.c:33` 调用）把
     `PAD_PA41 = LCDC1_8080_DIO5`、`PAD_PA42 = LCDC1_8080_DIO6` —— **PA41/PA42 是 LCD 的 8080 数据线**，
     接 I2C 传感器会与显示屏抢线（可能损坏引脚）；
   - 说 PA41/PA42 的是**芯片/模组通用复用表或另一块开发板**（如 `sf32lb52_lchspi_ulp`/黄山派，
     其 README 明确列出与本板的差异：触摸 SCL 本板是 PA30、-ULP 才是 PA37）；
   - 同理 **PA37 在本板是 LCD DIO2**（不是触摸 SCL）。本板排针可用的 I2C 就是 **PA30(SCL) / PA33(SDA)**。

---

## 十九、心率：agent 链路修通 + 唯一阻塞是电气裕量（2026-10-11 23:22）

### 已完成

1. ✅ **agent 工具链路修通**：`packages/ai_agent/src/tools/tool_health.c` 的 `orb_read_once()` 原来 subscribe 后立刻 copy
   （拿不到数据 → 恒报 `sensor not available`）；改为 **poll 等一次新更新（最多 500ms）再 copy** 并打日志。
   修复后 `ask 我的心率是多少` 正常返回 `{"bpm":0}` ✓（话题存在时不再报错）；
2. ✅ 传感器侧已多次成功：`PART_ID=0x15` → `初始化完成` → `采样线程启动 (100Hz)` → `/dev/uorb/algo_heartrate0` 出现 ✓；
3. 软件兜底：I2C **25 kHz** + PART_ID **50 次重试 × 20ms**（越低速越容忍弱上拉）。

### 唯一阻塞：I2C 电气裕量 ⚠️

同一根线、同一地址：`i2c` 工具连读 5 次得到 `0d / 04 / 15 / 15 / 15`（**前几次错、之后稳定**）；
app 有时第 1 次就读到 0x15（初始化成功），有时 50 次全败（`PART_ID=0x00`）→ **成功与否取决于电气质量，不是代码**。

### 请用户做（按有效性排序）

1. **万用表量 SDA/SCL 空闲电平（对 GND）**：
   - ≈3.3V → 上拉偏弱/线太长 → **SDA/SCL 各加 2.2~4.7k 上拉到 3V3**，并缩短跳线 ✓（首选）；
   - ≈1.8V → 模块内部是 1.8V 总线、无电平转换 ⚠️ → 需**电平转换模块**（切勿直接把 3.3V 拉上去，可能损坏模块）；
2. 量 VIN 对 GND 应为 3.3V；换短杜邦线、避免面包板接触不良。

### 血氧（SpO2）现状

硬件支持（MAX30102 有 RED+IR 双 LED）✅，**固件未实现** ✗：当前 `0x09=0x02` 仅 RED（心率模式）。
要加需：模式改 `0x03`、FIFO 每样本 6 字节（RED3+IR3）、算 R=(AC_red/DC_red)/(AC_ir/DC_ir) 再查曲线。
⚠️ MAX30102 数据手册**不含血氧算法/标定**（Maxim 官方算法绑定其配套 IC）→ 自拟合曲线只能做趋势参考，非医用。

---

## 二十、完成清单与详细计划（2026-10-11 23:50）

### A. 已完成（每条都有真机证据）

| # | 项 | 证据 | 提交 |
|---|---|---|---|
| A1 | 音频采集驱动（AUDCODEC RX → /dev/audio/pcm0c） | 真机 16kHz/16bit/mono 注册成功；采到数据（软件对齐官方，模拟侧未闭环） | c33a8ee 等 |
| A2 | **音频播放驱动**（AUDPRC → AUDCODEC DAC → PA10 → 喇叭） | **听觉验证**：CPU 直推 400Hz ✅ + AUDPRC 1200Hz ✅；`written=96000`=3.0s、`dropped=0` | 0f2d92b/c33a8ee |
| A3 | 开机自注册音频设备（不再依赖手工 audio_setup） | `P1: audio_tx_register(playback) (rc=0)` | 98c88bb |
| A4 | 语音"嘴"：media_player API 实现在 pcm0p（含 24k↔16k 重采样） | `pcm_media_test`: in=24000 out=16000、written=96000、dropped=0 | 3e8232c/449a45a |
| A5 | **Edge TTS（免凭证）** + minimp3 MP3 解码 | 真机：WS upgrade OK → `first frame: 24000Hz mono 48kbps` → `8.00:1` → `delivered 5760 PCM bytes` → `voice_speak: OK` | dde3e0a/c45d634 |
| A6 | 异步说话（修"屏幕卡死"）+ 手机 App 回答念出来 + `speak` 命令 | `voice_channel_speak_async`；UI 不再阻塞 | d94d631/e40fada |
| A7 | 云模型（MiniMax M3.1-Flash-Preview） | `llm=ok latency=11856ms`，唯一 token 回声（真实云端往返） | aadb358 |
| A8 | MAX30102 心率（独立 I2C2：PA41=SCL/PA42=SDA） | `PART_ID=0x15` ✅ → 初始化 ✅ → `采样线程启动(100Hz)` ✅ → `/dev/uorb/algo_heartrate0` ✅ | cb994bc/f412a04 |
| A9 | agent `get_heartrate` 修通 | 修前恒报 `sensor not available`；修后 `{"bpm":0}`（poll 等新数据） | af57324/6744e8c |
| A10 | 出货镜像 + 文档 | `nuttx.bin` 7,345,616 B（23:11），defconfig 206 个开关；docs_ble/30 §1–§20 | 多笔 |
| A11 | 大赛四项 apb ownership 改动 + 30 轮验收 | 30/30 轮，heap `used=37584` 恒定、`nobuf=0` | 早期提交 |

### B. 未闭环（按优先级）

| # | 项 | 现状 | 影响 |
|---|---|---|---|
| B1 | **I2C 电气裕量** ⚠️ | 读值在 0x00/0x11/0x15 间跳（工具连读 5 次：0d/04/15/15/15） | **测不出真实 bpm**；血氧更敏感，更做不了 |
| B2 | **蓝牙根因 B**（link key 不持久化 + PAN 抖动）✗ | 每次重启丢失配对；PAN 风暴刷哑控制台、截断 TTS 音频流（0.12s vs 完整 2.16s）、偶发 `LLM call failed` | **语音/云模型不稳定**、控制台不可用 |
| B3 | 血氧（SpO2） ✗ | 硬件支持、固件未实现（仅 RED 模式） | 功能缺失（加分项） |
| B4 | 麦克风模拟侧 ✗ | 软件已对齐官方，疑 MIC_BIAS/模块 | 语音输入闭环缺失 |
| B5 | 语音链路未在稳定链路上跑完整句 | 链路好时成功（21:23 完整 2.16s）、链路差被截断 | 演示风险 |
| B6 | 火山 TTS 授权 ✗ | 账号侧 `45000030/3001 not granted`（APPID=1661538303） | 可选（Edge 已可用） |

### C. 详细计划

**P0（阻塞验收，必须先做）**

| 任务 | 谁 | 步骤 | 验收标准 | 预估 |
|---|---|---|---|---|
| P0-1 修 I2C 电气 | **用户**（10 分钟） | 万用表量 SDA/SCL 空闲电平：≈3.3V → SDA/SCL 各加 2.2~4.7k 上拉到 3V3、换短线；≈1.8V → 需电平转换模块（勿直接拉 3.3V） | `hr_sensor start` **一次**读到 0x15；`ask 我的心率是多少` 返回**真实 bpm**，与手机/手表对照 **±5 bpm** | 10 分钟 + 一次复测 |
| P0-2 蓝牙根因 B | 我 | ① 把 BR/EDR link key 存进 UNQLITE（`bt_storage`），重启后回灌 host key pool；② 修硬编码 BD_ADDR；③ PAN 抖动定位（缓冲区/吞吐） | 连续 **10 次**重启后仍能自动连上 PAN；`speak 你好` 的 TTS 音频**完整送达**（日志 `delivered ≈ 文本时长`）；控制台不再被刷哑 | 1 天 + 2–3 次烧录 |

**P1（演示质量，做完就能上台）**

| 任务 | 步骤 | 验收 | 预估 |
|---|---|---|---|
| P1-1 心率可视化 | 加 `hr_sensor mon` 前台监视；心率进桌宠页/健康卡片 | 手表屏幕显示实时 bpm；`ask` 回答带 bpm | 2 小时 |
| P1-2 TTS 健壮性 | 送达时长明显短于文本预期 → 自动重试一次；Edge 命中格式持久化到 config store | 首句延迟 7s → ~3s；短句成功率 >90% | 1 小时 |
| P1-3 一键演示脚本 | 封装：开机自检（音频+传感器+网络）→ 问一句 → 手表念出来 | 一条命令跑完整演示，输出判定表 | 1 小时 |

**P2（加分项，按剩余时间）**

| 任务 | 说明 | 验收 | 预估 |
|---|---|---|---|
| P2-1 血氧 | 模式 0x03 + RED/IR 双通道 + R 比值曲线（**注明非医用**） | 静止时 SpO2 读数落在 95–99% 且随呼吸/按压力度有合理变化 | 2–4 小时 |
| P2-2 麦克风闭环 | 用户测 MIC_BIAS / 换模块；跑通"按键说话 → ASR → 回答 → 念出来" | 语音问答全链路 | 2 小时 + 硬件 |
| P2-3 代码审查与收尾 | 用 `driver-code-reviewer` 审查新增音频/传感器代码；清理调试开关 | 评分报告 + 无 Critical | 2 小时 |
| P2-4 远程控制台（可选） | MQTT / OpenClaw Gateway（REST/WS 已启用）| 手机远程看状态/发指令 | 2–4 小时 |

### D. 建议顺序（依赖驱动）

```
今晚      : P0-1（用户加上拉）→ 我复测出真实 bpm（10 分钟）
第 1 天   : P0-2 蓝牙根因（我）→ 语音完整句可复现
第 2 天   : P1-1 心率可视化 + P1-2 健壮性 + P1-3 演示脚本
第 3 天+  : P2-1 血氧 → P2-2 麦克风闭环 → P2-3 审查收尾 → P2-4（可选）
上台前    : 连续 30 分钟演示彩排（问 10 次、每次都能听到回答）
```

---

## 二十一、下一阶段计划（2026-10-12 09:40，经 6 个独立子代理交叉核查后定稿）

### A. 必须先记住的三个核查更正

| # | 原结论 | 核查更正（有代码/日志证据） |
|---|---|---|
| A1 | "BR/EDR link key 不持久化，需新建持久化" | **已存在且已编入出货镜像**：`keys_br.c` 写/读 `/data/misc/bt/br_key.bin`（32B 记录 0xA5\|type\|flags\|rsvd\|addr[6]\|key[16]`），`ssp.c:530` 存、`ssp.c:593-608` 惰性读回；真机日志有成功例（`restored key` → `encrypt_change status=0x00` → `dhcp_ok`）。**不要新建第二套持久化** |
| A2 | "修 `bt_sal_set_bonded_devices` 的桥" | framework 的 UNQLITE `BtBonded` 记录**本来就含 `link_key[16]`**（`bluetooth_define.h:73-85`，`bonded_device_loaded()` 已回填 framework 侧）。缺口在 `STACK_CALL(set_bond)`（`sal_adapter_interface.c:1813-1824`）只在 `CONFIG_SETTINGS_ZBLUE` 下工作，而该符号无 Kconfig 生产者 → 空实现。但这条桥只对"br_key.bin 缺失"做兜底，**不是主路径** |
| A3 | "加 `conn_cb.link_key_notify` 钩子" | 该回调**全树无调用点**（死代码）；真正保存点是 HCI 层 `bt_hci_link_key_notify`（`ssp.c:454-538`） |

### B. 蓝牙（P0-2）真实候选根因，按证据强度

| # | 候选 | 证据 | 处置 |
|---|---|---|---|
| **R1** | **惰性恢复 vs `/data` 晚挂载竞态**（首要嫌疑） | 恢复只在 `Link_Key_Request` 时现读文件，而 `/data`(littlefs) 约 **10.6~11.1s** 才挂载，控制器主动回连可早至 **1.2s** → open 失败 → neg reply → auth 0x05 | **先插桩证实**，再决定 |
| R2 | 写文件撕裂 | `O_TRUNC` + 单次 write，无 fsync/CRC/版本/tmp+rename；读取只查 `rec[0]!=0xA5` | tmp+rename+fsync+CRC16 |
| R3 | remove bond 不删文件 | `bt_keys_link_key_clear_addr()` 只 memset RAM；手机"忘记设备"后本地留旧 key → 反复 auth 失败 | unpair 同步删/失效记录 |
| R4 | 只有 1 个 bond 槽 | `CONFIG_BT_MAX_PAIRED=1`、`BT_KEYS_OVERWRITE_OLDEST` 未开 → 第二台手机覆盖第一台 | 可选提升 |

### C. 分阶段计划

**Phase 1 — 蓝牙（我，约 0.5 天 + 1~2 次烧录）**

1. **P1a 插桩定性（不改行为，只加日志）**：`ssp.c` 的 `bt_hci_link_key_req` / `bt_keys_link_key_load_file` 入口与返回打日志（记录 open 结果、是否命中、addr）；冷启抓 `littlefs mounted on /data` 与 `link_key_req` 的**先后**；nsh `ls -l /data/misc/bt` + hexdump 看 `br_key.bin`（是否 32B、首字节 0xA5）。
   判据：文件在但仍 neg reply ⇒ R1；文件缺失/0B ⇒ R2；恢复成功 ⇒ 只剩 R3/R4。
2. **P1b 按判据修**：
   - R1 → 改为**`/data` 挂载后、LCPU 唤起前预加载** br_key.bin 进 RAM pool（保留惰性读兜底）；**不得**在 `link_key_req` 里阻塞等挂载（HCI 需同步应答）；
   - R2 → magic+version+len+CRC16、tmp+rename+fsync；
   - R3 → `adapter_remove_bond` / `bt_keys_link_key_clear*` 同步删记录；
   - R4 → `BT_MAX_PAIRED=2` + overwrite-oldest（可选）。
3. **P1c 验收（冷启 10 轮）**：手机**不点连接、不忘记设备**、蓝牙网络共享常开，完整断电后上电；10/10 轮满足：① `link_key_req` 后**无** `no key - neg reply`；② 出现 `encrypt_change status=0x00 enc=0x01`；③ `[pan] state=dhcp_ok`（IP 非残留）；④ 手机侧**无配对弹窗**。另加 1 轮"配对成功后立即拔电"验 R2。

**Phase 2 — 语音回复（我，约 0.5 天 + 1 次烧录）**

| 项 | 现状（已核实） | 改动 |
|---|---|---|
| P2a **只念最终回答** | `CONFIG_AI_AGENT_SPEAK_WS_REPLIES=y` 已在 WS 分支调 `voice_channel_speak_async`；但 `send_working_status()`（`agent_loop.c:898-923`）的"正在思考中…"**不在跳过名单**（只跳 FEISHU/VOICE/QUICKAPP/WEIXIN）→ **状态语也被念**；错误语/低内存语同理 | 给 `agent_msg_t` 加 `kind`（FINAL/STATUS/ERROR），只在 FINAL 时念；同时把 WEBSOCKET/LVGL_UI 加入 `send_working_status` 跳过名单（双保险）|
| P2b **队列与背压** | `voice_channel_speak_async()` 每次 `pthread_create`+detach，**无队列/无深度限制/默认栈**（要跑 mbedtls TLS） | 单个 speak worker + 深度 1~2 队列（**新答案覆盖旧答案**），显式栈 ≥24KB；`s_tts_start/first_chunk` 等共享静态量改为每轮上下文 |
| P2c **抗截断重试** | `edge_recv_loop` 在 audio 开始后遇 ETIMEDOUT/ECONNRESET **直接 break 返回成功** → 0.12s 当成功 | 交付时长 < 0.5×预期 或 < 0.3s 时**重试 1 次**；底层返回"截断"标志以区分正常 turn.end |
| P2d **首句延迟** | `s_format_index` 只在 RAM | 命中后持久化到 config store（7s → ~3s）|
| P2e **CLI 通道也念** | 只有 WS/UI/VOICE 通道念；`ask`（cli）不念 | 复用 P2a 的 kind 机制，把 cli 纳入（可选开关）|

**Phase 3 — 心率（你 10 分钟 + 我）**

1. **你先做（P0-1）**：换**≤10cm 短线、取消拼接**；万用表量 SDA/SCL 空闲电平：≈3.3V → 各加 2.2~4.7k 上拉到 3V3；≈1.8V → 模块内部是 1.8V 轨，需电平转换（**不要**强拉 3.3V）；≈2.8V → 换模块；
2. 我随后：`i2c get -b 1 -a 0x57 -r 0xff` 连读 **20/20 = 0x15** 才算电气合格 → `hr_sensor start` 一次过 → `ask 我的心率是多少` 出**真实 bpm**，与手机/手表对照 **±5 bpm**（消费级参考，非医用）；
3. 可选：`hr_sensor mon` 前台监视 + 心率进桌宠页；SpO2 只做**趋势展示**并标注**非医用**（数据手册无标定，Maxim 算法绑定配套 IC）。

### D. 关键风险 / 依赖

- **P1a 必须先于 P1b**：R1 尚未被日志证实，直接改可能做无用功；
- 蓝牙验收每轮需**完整断电**（RTS 是板子电源，接线/上电期间不得触发烧录）；
- 传感器电气未修好前，SpO2 无从谈起（更敏感）；
- 语音改动集中在 `packages/ai_agent`，蓝牙插桩在 `apps/external/zblue`，两棵树互不冲突可并行。

### 21.1 蓝牙修复落地记录（2026-10-12 09:45）

独立审查又发现一个**新的、可证的**根因（记为 R5），并已与 R1/R3 一起修掉：

| # | 根因 | 修法 | 提交 |
|---|---|---|---|
| **R1** | `bt_hci_link_key_req()` 的 `!conn` **早退分支直接 neg reply** —— 冷启后手机主动回连时，控制器常在本机建立 `bt_conn` 之前就发 `Link_Key_Request`，于是 `br_key.bin` 的恢复代码**永远不执行** | 该分支改为：先查 RAM key pool，再查 `br_key.bin`，命中就用 `link_key_reply()`（应答不需要 conn 对象） | `df8e29b2c08` |
| **R3** | unpair（手机"忘记设备"）只清 RAM，**不删文件** → 板子重启后拿陈旧 key 应答 → 反复 auth 失败 | `bt_keys_link_key_clear_addr()` 清完 RAM 后同步重写 `br_key.bin`（整池重写即失效） | `df8e29b2c08` |
| **R5** | `br_key.bin` 的读写路径**不创建目录**：`/data`(littlefs) 约 10.6s 才挂载，而 bluetoothd 可能更早就 `mkdir /data/misc/bt`（落在 rootfs 上、挂载后被遮住）→ `open()` ENOENT → 存/读全失败且**无声无息** | store/load 前自建 `/data/misc`、`/data/misc/bt`（EEXIST 容忍），持久化不再依赖启动时序；同时 `load_file` 打开失败会打 `br_key load: open %s failed (fd %d)` | `4ea82681d80` |

**已编入镜像**：`nuttx.bin` 09:43（7,349,128 B）—— 但**先不烧**，等语音修复一起烧一次。

**冷启验收时先看这三条日志**（一次就能定性）：

```
br_key store: N key(s)                       <- 保存侧成功(配对后)
br_key load: open /data/misc/bt/br_key.bin failed (fd -1)   <- 文件不存在/没挂载(R5 若仍出现)
link_key_req: no conn for xx:xx:.., try pool/file           <- 走了新的 !conn 分支
link_key_req: (no conn) replied from pool/file              <- 关键成功标志
```

仍未做（可选）：R2 写文件撕裂防护（tmp+rename+fsync+CRC）、R4 多 bond 槽、以及把 UNQLITE 记录桥接到 host key pool（Option B，需先补 `adapter_on_link_key_update` 的生产者）。

### 21.2 代码侧继续修复 + 验收工具（2026-10-12 09:55，板子未接）

| 项 | 内容 | 状态 |
|---|---|---|
| **R2 原子写** | `br_key.bin` 改为 `br_key.bin.tmp` + `fsync` + `rename` 原子替换；记录带 **CRC16**（`[26..27]`，覆盖 `[0..25]`，`[28]`=layout version=1）。旧格式（CRC 字段为 0）仍被接受，向后兼容 | ✅ 提交 `573ca8f0d5f`（zblue 仓） |
| **P2e CLI 也念** | 控制台 `ask` 的回答同样走异步 TTS（与手机 App 同一开关），便于演示与验收 | ✅ 提交 `fa74f7f`（packages 仓） |
| **状态语恢复上屏** | "正在思考中…" 仍上屏（手机页/手表气泡），只靠 `kind=STATUS` 阻止出声 —— 保留原 UX | ✅ 同上 |
| **Option B 桥接** | 让 framework 的 UNQLITE 记录真正带上 link key（补 `adapter_on_link_key_update` 的生产者）并在 BT 启动时注入 host key pool，作为文件持久化之外的第二道保险 | 🔄 子代理进行中 |

**验收工具（主机侧，板子插上即可跑）**：

| 脚本 | 用途 | 判据 |
|---|---|---|
| `.v3_work/bt_cold_boot_accept.py [轮数] [每轮秒数]` | RTS 冷启 N 轮 + 抓启动日志 | 每轮需同时满足：命中 `replied from pool/file` 或 `br_key load: restored key`；**无** `no key - neg reply`；出现加密成功；出现 `[pan] state=dhcp_ok`。第 1 轮原始日志落 `/tmp/bt_accept_round1.log` |
| `.v3_work/voice_accept.py [文本]` | 发一句 `ask` 并校验语音新行为 | `speak worker started (stack=24576, queue=2)`；`pass 1: delivered x.xxs / ~y.yys expected`；状态语不被念（`skipped: kind=`/`no TTS for kind=`）；格式缓存 `cached output format index N`/`persisted as edge_fmt` |

**真机测试前置条件**（等板子接上）：① 手机开启"蓝牙网络共享"、**不要**手动点连接、**不要**忘记设备；② 首次配对完成后保持 bond；③ 心率测试需要先换短线（当前长线/拼接仍会导致 PART_ID 抖动）。

---

## 二十二、蓝牙音频截断：两个独立根因已定位并修复（2026-10-12 11:10，经两个独立子代理只读排查）

### 现象

手机蓝牙网络共享(PAN/BNEP)下，TTS 音频流(TLS/TCP，单次数百 KB)被截断：
`[edge_tts] no turn.end after 414720 PCM bytes (8.64s), truncated` / `rc=-32`，
伴随 `<wrn> Unable to allocate buffer within timeout`（zblue `conn.c:1671`）与
`sf32lb52 bth4 drop invalid h4 type=.. pending=..`。

### 根因 1：主机→控制器 ACL 在飞窗口只有 2 个包（吞吐天花板 ≈12 KB/s）

`conn.c` 四个闸门：`dont_have_viewbufs()`(=`CONFIG_BT_CONN_FRAG_COUNT`=2) × `cannot_send_to_controller()`
(`hdev->br.pkts`=4，来自驱动**伪造**的 Read_Buffer_Size) × `CONFIG_BT_CONN_TX_MAX`=5 × 每连接 `in_ll<3`。
取最小 ⇒ **全局在飞 2 个 ACL 包** ✗。而一个 1500 B TAP 帧 = 1533 B PDU，按 487 B 需 **4 个分片** ✗，
父 buf 要等整条 PDU 发完才出队 ⇒ 吞吐上限 = 4×487B/RTT159ms ≈ **12 KB/s**，
**与实测 11.6 KB/s 完全吻合** ✓（100 KB/8.64 s）。

**修复（+312 B SRAM）**：`CONFIG_BT_CONN_FRAG_COUNT` 2→8（原无 prompt，已加 ✓）、
`CONFIG_BT_CONN_TX_MAX` 5→10、`CONFIG_BT_BUF_ACL_TX_COUNT` 5→10（host-only 不占 RAM，但会让
`BT_L2CAP_TX_BUF_COUNT` 隐式跟着涨 ✗ ⇒ 已显式钉回 5，避免 +8.7 KB）、驱动 `SF32LB52_HCI_ACL_TX_PKTS` 4→8 ✓。
SRAM 88.66% → **88.73%** ✓。

### 根因 2：共享 ring 头部**缓存伪共享写回**（`drop invalid h4` 的来源）

`struct circular_buf` 里 `read_idx_mirror`(偏移 8..11) 与 LCPU 拥有的 `write_idx_mirror`(12..15) **同一条 cache line** ✗。
`sf32lb52_bt_adapter.c:531-538` 发布读指针后 `up_clean_dcache()` 会把**本端缓存的陈旧写指针**一起写回 ✗ →
LCPU 每次 `circular_buf_put()` 都从共享内存重读该字段 ⇒ **写指针回退** ⇒ 覆盖尚未 drain 的字节 ⇒ H4 失步 ✓。
同一文件 414-435 行已记录 **TX 方向**同机制并已缓解，**RX 方向没有** ✗。
加重因素（新发现）：**厂商把 SRAM 标为非缓存的 MPU 配置在本 NuttX 端口从未被调用** ✗
（`mpu_config()` 无调用者、被 gc-sections 丢弃；`CONFIG_ARMV8M_DCACHE=y` 非 write-through）⇒
SRAM 是 write-back cacheable ✓，那些 clean/invalidate 从 SDK 里的"空操作"变成"承重" ✗。

**已做（F2 缓解，0 RAM）**：发布读指针**前**重新 `up_invalidate_dcache()` 并重读写指针 ✓ ——
把竞争窗口从"整段 memcpy(µs)"缩到"几条指令" ✓（概率降 1~2 个数量级，非零 ✓）。
**根治候选（F1，未做）**：给两个 512 B mailbox ring 单独配 MPU 非缓存属性 ✓（0 RAM，平台级改动 ✓）
或临时 `up_disable_dcache()` 做 A/B 验证 ✓。

### 另一个必须修的**配置丢失**问题（新）

过程中发现设备上 `/data/ai_agent/config/config.json` **只剩 DNS 两项** ✗，LLM 的 url/model/key 丢失 ⇒
`[llm_router] No available backend` ✗。最可能是**多写者并发读改写同一份 JSON**（netmgr 写 DNS、新增的
`edge_fmt` 持久化也写同一份 ✗）。建议：`edge_fmt` 改用独立小文件 ✓ + config 写入加锁 ✓。
（本次已用分块 `echo` 手工恢复；工程约束：nsh 行缓冲仅 **64 字符**、镜像内**无 `cat`**、`echo -n` 不支持 ✗）

### 22.1 回退：驱动侧 ACL 信用 4→8 造成数据面故障（2026-10-12 11:40）

**证据链**（时间顺序，均为真机实测）：

| 时间 | 镜像 | 结果 |
|---|---|---|
| 10:47 | 只含 SO_RCVBUF + 重试阈值 0.7 | 语音正常 ✓ `delivered 8.64s/14.30s` |
| 11:00 | 同上 | 语音正常 ✓ `12.34s/14.50s`、`7.82s/11.43s` |
| 11:19 | **+ FRAG_COUNT/TX_MAX + 驱动信用 4→8 + F2** | ✗ `pass 1: delivered 0.00s / ~8.00s expected`（TTS 零交付）|
| 11:32 | 同上 | ✗ 完全没有 `[voice] speak:` 行（TTS 未被调用/无音频）|

**排除项**：`CONFIG_AI_AGENT_SPEAK_WS_REPLIES=y` 仍在（.config:4236 ✓，defconfig:459 ✓），
`speak(cli) dispatch failed` 串在镜像里 ✓ ⇒ 代码路径在 ✗；
NuttX `setsockopt(SO_RCVBUF)` 只把值存进 psock（nuttx/net/socket/setsockopt.c:245）✗，
不改变 TCP 窗口 ⇒ 不是 SO_RCVBUF 的锅 ✗。

**结论**：`SF32LB52_HCI_ACL_TX_PKTS` 是驱动**伪造**的 `Read_Buffer_Size`（控制器信用上限），
bth4.c:40-52 的注释明确写了 LCPU 侧约束（ring 仅 492 B、每写只接受一个完整 H4 帧）✗ ⇒
把信用从 4 抬到 8 属于**虚报控制器能力** ✗，超出 LCPU 可吸收量，损失被转移到闭源固件里 ✗。

**处置**：已回退为 4 ✓（保留主机侧安全旋钮 `CONFIG_BT_CONN_FRAG_COUNT=8` / `CONFIG_BT_CONN_TX_MAX=10` ✓，
它们只改 zblue 的软件在飞窗口，不涉及对外宣称的能力 ✓）。SRAM 88.73% 不变 ✓。

**教训（写入规范）**：任何"抬高控制器能力/超发"的改动必须先对照 (a) 驱动注释与 vendor 文档、
(b) 上游 zblue Kconfig 语义，再上真机；**只做能往回退的小步** ✓。

### 22.2 挂死判别第一轮结果 + 待办（2026-10-12 14:05）

**实验**：用两份只差桥接的镜像对比（`.v3_work/nuttx_nobridge.bin` md5 `0ffe9af8…` / `nuttx_withbridge.bin` md5 `7f1416e9…`；
编译器 `-Wunused-function` 证明三处调用点确被移除 ✓；SRAM 差 216 B / flash 差 1644 B ✓；唯一其它差异是 `lib_utsname.c` 的编译时间戳 ✓）。

**结果**：**关桥接版同样在 `security ok` + `dhcp_ok` 之后静默** ✗ ⇒ **桥接被排除（或至少不是唯一元凶）** ✗。
两份镜像都观察到：`security ok x1` / `ACL up x1` / `dhcp_ok x1` / `drop invalid h4 x0` ✓ 然后 15 秒 0 字节 ✗。

**⚠️ 未定论（判据可能偏严）**：本板控制台**本来就有安静期** ✓（健康时也曾出现 5 秒仅 88~346 字节 ✓）⇒
"连续 15 秒 0 字节"可能是**假阳性** ✗。

**下一步（按性价比，尚未执行）**：
1. **探针判据重测**（0 次烧录 ✓）：每 15 秒发一次无害探针（空行 ✓），看是否有提示符回应 ✓；
   有回应 = 系统活着 ✓（把"安静"与"挂死"彻底分开 ✓）；两份镜像各测 5 分钟 ✓。
2. 若确认**真挂** ✗ ⇒ 转 **AI 侧二分** ✓：两份镜像共用同一份 AI 代码（`edge_tts.c`/`voice_channel.c` ✓），
   回退语音那组改动再跑同一负载 ✓（审查备好的第二方案 ✓）。
3. 若判据显示"只是安静" ✓ ⇒ 无挂死问题 ✓，放宽观察窗口 ✓ 继续做功能 ✓。

### 22.3 心率传感器待办（2026-10-12 14:05，用户准备换线）

**现象**：用户看到 MAX30102 模块**LED 不亮** ✗ —— 这是**正常现象** ✓：
该模块的 LED **只在测量时才亮** ✓，而本工程的心率采集是**用户态 app `hr_sensor`** ✓，**开机不会自动启动** ✓ ⇒ 不跑 app 时 LED 本来就是灭的 ✓。

**换线时的硬性检查（务必按序）**：
1. **断电**（拔 USB ✓）后再接线 ✓；**VIN 必须 3V3**，**绝不能接 5V** ✗。
2. 接线：**SCL → PA41**、**SDA → PA42**、**GND 共地**、**VIN → 3V3** ✓（对应 `/dev/i2c1` = I2C2 ✓，从机地址 **0x57** ✓）。
3. 线要**短（≤10 cm）**、**不要拼接** ✗；有条件就加 **2.2~4.7 kΩ 上拉到 3V3** ✓。
4. 上电后**先量空闲电平**（SDA/SCL 对 GND）：
   - ≈3.3 V ⇒ 补上拉即可 ✓；
   - ≈1.8 V ⇒ 模块 I/O 是 1.8 V 轨 ✗ ⇒ **必须电平转换** ✓（**切勿**直接拉到 3.3 V ✗）；
   - ≈2.8 V 或跳动异常 ⇒ 换模块 ✓。
5. **验收线**：`i2c get -b 1 -a 0x57 -r 0xff` **连读 20 次全部 0x15** ✓（PART_ID ✓）。
6. 然后 `hr_sensor start &` ✓ ⇒ **此时模块 LED 应当亮起** ✓（这是"它在测"的可靠信号 ✓）。
7. 最后验证：`ask 我的心率是多少` ✓ 应返回**真实 bpm** ✓（通过 uORB `algo_heartrate` ✓；注意 `hr_sensor status` 是**独立进程**、看不到别的实例的计数 ✗，不要用它判断 ✓）。

**已知历史坑（避免重踩）**：`msg[1].flags` 缺 `I2C_M_READ` ✗（已修 ✓）、25 kHz + 多次重试 ✓、
`orb_read_once()` 需**先 poll 再 copy** ✗（已修 ✓），否则永远报 `sensor not available` ✓。

---

## 二十四、2026-10-12 全天攻关总报告（心率 / 语音 / 蓝牙 / 端到端）

### 24.1 最终验收（真机原始数据）

| 功能 | 状态 | 铁证 |
|---|---|---|
| 语音播报 | ✅ 可用 | `[voice] speak` → `[minimax_tts] HTTP 200` → `body: 29172 MP3 bytes -> 168192 PCM bytes` → `delivered in 8436 ms` → `speak done (play wait 10199ms)` |
| 蓝牙回连 | ✅ 可用 | `[SAL] br_key restore: injected` → `security ok` → `dhcp_ok` |
| 心率采集链路 | ✅ 可用 | `samples=20623`、`n_poll=8/16`、`fresh=8/8`、`rd_int=5500/0`、`rd_fifo=2750/0`（**零 I/O 失败**）、`int1=0x40`、`rst=0`、`ovfs=0` |
| 心率信号质量 | ✅ 优秀 | `acdc=0.09~0.14%`（生理范围 0.1~2%，**教科书级 PPG**）、`pk5s=5~11`、`dup=1~2%`、`dc≈148000` |
| 端到端问答 | ✅ 跑通 | `ask 我的心率是多少` → `[tools] get_heartrate` → `[Agent]: {\"bpm\":95}` → 手表念出来 |
| 心率数值准确性 | ❌ **不准** | 静息 25 秒序列 `142 142 142 142 142 142 142 136 136 122 116 116 122 122 122 122 122 122 95 95 95 95 106 107`，**波动 47 bpm**；`rr_med=559ms`(≈107bpm) 却输出 `bpm=95` |

### 24.2 今天定位并修掉的 7 个真根因（每条都有源码/数据依据）

| # | 根因 | 修法 | 证据 |
|---|---|---|---|
| 1 | **25 kHz 不可达**：FLV 上限 511 ⇒ 本 SoC 最低 ≈46.6 kHz；硬发踩 `HAL_ASSERT` 的 `while(1)` **挂死整机** | 自检对 25 kHz 只打印不执行；默认 50 kHz | `i2c get -f 25000` 后板子静默 |
| 2 | **NuttX 组合读在本板驱动丢寄存器地址**：`sifli_i2c.c:492-519` 只认第二条消息带 `I2C_M_NOSTART` | 改路径 B（写地址+STOP 再单独读） | selftest：A 路径 0/8，B 路径 8/8 |
| 3 | **`I2CIOC_RESET` 选不上**：`ARCH_CHIP_SF32LB52` 缺 `select ARCH_HAVE_I2CRESET` | 已补（后为黑屏排查临时关闭） | `nm` 从 0 命中变 1 |
| 4 | **100 Hz 采样把总线搞坏** | 轮询降为 `HR_POLL_MS=50` + 去掉"溢出分支整轮 return" | 空闲 selftest 8/8，采样中全败 |
| 5 | **每轮清 FIFO 指针致 97~100% 重复样本** | 只在 start 清一次 + `fresh/rdup` 取证 | `dup=97~100%` → 改后 `dup=1~2%` |
| 6 | **算法两处真根因**：阈值 `1.6×EMA(|y|)=1.02A` 数学上检不到峰；溢出分支每 50 ms 复位滤波器 | 改 `0.6×包络`；去掉该分支复位 | host 仿真 75/110/150bpm → 75/109/150 |
| 7 | **三次单字节读不构成完整样本读** ⇒ 器件不推进读指针 | `HR_FIFO_BYTE_WISE=0` + `HR_FIFO_BULK_READ=1`（一次读 3×N 字节，Zephyr/Linux 做法） | 改后 `ac_pp` 从 0 变 174~207，`wr` 开始推进 |

### 24.3 遗留问题 A：心率数值不准（**算法层，已定位到 3+1 个点**）

**结论：硬件与数据链路已完全正常，问题在算法。**

已排除：
- 硬件/光源 ✅（`acdc=0.09~0.14%` 是生理范围内的干净脉搏波）
- 数据链路 ✅（零 I/O 失败、无复位、`fresh=8/8`）

已定位的算法缺陷（按数据指向排序）：

| # | 缺陷 | 证据 | 修法（待实施） |
|---|---|---|---|
| 1 | **`hr_close()` 容差 12% 太松** | `hr_close(95,107)` 与 `hr_close(122,136)` 都判"一致" ⇒ `stab=8~10` 却在 95↔142 漂移 | 容差收到 5% |
| 2 | **固定 300ms 不应期与 dicrotic notch(150~300ms) 擦边** | `142≈95×1.5`、`122≈95×1.28`（整数倍=双检指纹） | 自适应 `clamp(0.5×RR_prev, 300, 600)ms` |
| 3 | **离群 RR 只统计不剔除** | `rr_med=559ms`(107) 与输出 `95` 不一致 | 偏离中位数 >30% 真剔除 |
| 4 | **峰时刻用墙钟 `now_ms()`**（`max30102.c:1190`），在"一轮喂 5~16 样本"的循环内调用 | 同一轮时间戳几乎相同 ⇒ 量化到 ~50ms 网格 | 改用**样本序号 × 10ms**（Maxim RD117 做法） |

⚠️ **诚实标注**：调研报告称"50ms 网格量化是主因"，但把观测值反推网格位置得 k=8.45/8.82/9.84/10.34/11.21/12.63，**全部落在网格中间** ⇒ 网格量化真实存在，但**不是当前跳变主因**（置信度：中）。主因更可能是第 1、2 条。

### 24.4 遗留问题 B：屏幕偶发全黑（**未定位，已排除 6 项**）

**现象**：UI 正常渲染（`[PetPage] Response updated` 连续出现）、`ps` 正常、心率/语音/蓝牙全部正常，但屏幕完全无光；**拔插 USB（电源循环）可恢复**；发生时**不确定是否与特定操作相关**。

已用单变量实验/源码检查排除：

| 假设 | 排除依据 |
|---|---|
| 应用层关屏 | 应用内无任何背光/`LCDIOC` 调用 |
| 面板 sleep-in | 冷启动（拔插等 10s）仍黑 |
| `CONFIG_I2C_RESET` 硬件复位 | 关掉重烧仍黑 |
| F2 dcache 改动 | 14:00 屏幕正常时它已在 |
| 抢了 LCD 的 PA41/PA42 | **本人判断错误两次**：那两脚只在**未编译**的 `BSP_LCDC_USING_DBI` 分支使用；本板走 QADSPI |
| PA10 被音频抢走 | **刚被实验推翻**：出声 10.1 秒后屏幕仍亮 |

**已知硬事实**：① 电源循环可恢复 ⇒ 是**运行期状态**被改掉；② UI 线程始终存活。

**下一步（未实施）**：打开 `vendor/sifli/boards/sf32lb52/drivers/lcd/sf32lb_lcd.c` 的 `sf32lb_lcd_setpower()` 里的 `lcdinfo("power: %d")` 日志，看是谁把 power 设为 0；控制点为 `CONFIG_LCD_MAXPOWER=100`、面板 CO5300 + QADSPI。

### 24.5 参考实现调研结论（已确认许可，可抄）

| 实现 | 心率算法 | 许可 | 结论 |
|---|---|---|---|
| **Maxim RD117 `algorithm.cpp/h`** | ✅ 完整（**用样本序号算 RR，零墙钟依赖**） | MIT 风格，免费下载 | **可用**，唯一绑定 companion IC 的是 MAX32664（不提供源码） |
| SparkFun PBA `heartRate.h` | ✅ 过零点 + pp 门 | Maxim 版权，MIT 风格 | 可参考（其"4 拍平均"无中位数/限速，会跳） |
| Zephyr `max30101` | ❌ 仅原始样本 | Apache-2.0 | 只可参考 FIFO 读法 |
| Linux IIO `max30102` | ❌ 仅原始样本 | GPL-2.0-or-later | **传染风险，勿抄** |
| MAX32664 sensor hub | ✅ 但黑盒 | 需外挂 IC | 不用 |

RD117 关键参数：`FS=25`、100 点/批、整批均值去 DC + 反相、4 点 MA、阈值硬钳 30~60、`min_distance=4`、`hr=1500/平均峰间距`。
落地需改 3 处：`FS=25→100`、`BUFFER_SIZE=FS*2`、阈值钳位改自适应（30/60 是 16bit 截断量程手调的，在 18bit/7.2mA/dc≈148000 下失效）。

**更正**：AN6410 是 SNR/SQI 白皮书（非心率算法）；AN6409 是 MindMotion 无感 BEMF，与心率无关。

### 24.6 今日提交索引

- **参赛仓**（`feat/ai-agent-contest`）：`9aea61a` 写校验+I2C 速率/LED → `4ff2751` selftest+50kHz → `6707972` 读路径 B → `db22e0d` 周期诊断 → `8e4abaf` FIFO 排空读 → `c54e576` INT_STATUS+固定 N → `8c6798f` 算法两处根因 → `0389601` 停止每轮清指针 → `efcca48` SKIP_CLEAR=0 → `136820e` LED 7.2mA + 幅度门限 10
- **vendor/sifli**：`2f6504a` ACL 信用 + RX ring invalidate、`1d25be7` 回退虚报信用、`08b0d39` select ARCH_HAVE_I2CRESET（**已临时关闭**）
- **zblue**：`b7fc8ced944` FRAG_COUNT prompt + CRC marker、`56fcd491a11` hdev->keys 判空
- **frameworks**：`36cc9456` 桥接去跨线程读

### 24.7 已知待办（按优先级，均未实施）

1. **心率算法修正**（4 条：容差 5% / 自适应不应期 / 离群 RR 真剔除 / 样本序号时间戳）—— 需手机或手环对照验证；
2. **屏幕全黑定位**（打开 `lcdinfo("power")` 日志看真凶）—— 只在长跑后偶发，拔插可恢复；
3. **`hr_sensor` 开机自启**（当前需每次手动 `hr_sensor start &`，拔插/重烧后即失效）；
4. **偶发串口静默/挂死**（部分原因已定位：25 kHz 会挂死整机，已规避）；
5. `docs §23` 内容已并入本节（原计划的 §23 取消）。

---

## 二十五、2026-10-13 夜：心率修好 + 麦克风打通 + 冻屏定性（全部真机取证）

### 25.1 心率：之前整条排查线追的是一个**坏指标**

**先推翻旧结论**：`gate=warm` 恒现 **不是** warm-up 没过。diag 行里
`gate = (warm < HR_WARMUP_SAMPLES) ? "warm" : g_hr.gate`，而 `hr_algo_diag_reset()`
**每次打印后都把 `g_hr.gate` 置回 `"warm"`**；只要这一窗口没检到峰，它就恒显示 `warm`。
补上 `warm=` 实测后：`warm` 早已到 **1985**。"把 warm 复位点搬走"的方向是错的。

**真根因：自适应阈值的启动瞬态。** 新增 `nmax`（本窗口 3 点局部极大数）与
`nth`（其中被阈值挡掉的数）后一眼可见：

| 时刻 | env | thr=0.6·env | nmax | nth |
|---|---|---|---|---|
| warm=229 | 23399 | 14040 | 15 | **14** |
| warm=1549 | 587 | 352 | 167 | **164** |
| 修后 warm=255 | **1523** | 914 | 8 | **2** |
| 修后 warm=668 | **240** | 144 | 48 | 2 |

LED 上电 / DC 建立把包络抬到 2.3 万，而包络衰减半衰期 2 s ⇒ 阈值约 **20 s** 高于真实脉搏峰，
每一个局部极大都被挡掉（`nth == nmax`）。这就是「按钮 15 s 窗口必然 timeout」的根因。

**修法**：复位后 500 样本（≈5 s）内包络下降改用 `HR_ENV_FAST_DECAY`（0.97/样本，
@100 Hz 半衰期 0.23 s），上升仍限速 0.25；`env_raw` 同步处理。

**真机验收**（手指按住 + 点「测心率」）：
```
[hr_measure] candidate bpm=94 at 11937ms
[hr_measure] success: bpm=94
rr_med=632ms -> cand=94   gate=ok   q=100   stab=3
```
`cand == 60000/rr_med` 每次都对上（640→93 / 650→92 / 700→85 / 770→77），
`stable` 曾连续累到 46，锁定耗时从"永远不出"变成 **11.9 s**。

**顺带修掉的真 bug**：`hr_measure.c` 的 argv 写成 `{name, cmd, NULL}`，而 NuttX
`nxtask_setup_stackargs()`（`sched/task/task_setup.c:538`）会自己补 `argv[0]`
⇒ 子命令参数变成程序名 ⇒ **按钮从来没真正启动过采样**。

### 25.2 麦克风：从"对声音无响应"到有响应

F1 判别实验（`HAL_AUDCODEC_Config_ADCPath_Volume` 12 → 30 dB）终于拿到真机数据：

| | peak | avg |
|---|---|---|
| 静音 | ≈1500 | ≈300 |
| 用户喊话 | **19036** | **933** |

峰值 10 倍响应 ⇒ ADC 前端是活的。
保留意见：没有同一工具下的改前数据，"是增益改好的"属推断。

### 25.3 冻屏：定性结论（今晚最有价值的取证）

**复现率**：10 次冷启动里 **3 次**冻屏（≈30%），位置固定在开机后 1~3 s 的
**蓝牙 PAN 建链**阶段。

**不是优先级饿死**：把看门狗线程提到 **优先级 240**（压过 hpwork=224 / audcodec_rx=120 /
sysworkq=110 / bt_service=103，`ps` 已确认 `hangwatch 240 FIFO`）后，冻屏时它
**一行都打不出来** ⇒ 整个调度器停摆（关中断自旋 / 持调度器锁 / 无法打印的故障）。

**不是硬故障**：`CONFIG_DEBUG_ALERT=y`，真 fault 会打 dump，现场没有。

**触发点**：每次开机都会出现一次 `<err> Unexpected L2CAP continuation`
（`external/zblue/zblue/subsys/bluetooth/host/conn.c:424`：`BT_ACL_CONT` 到达时
`conn->rx` 为空 ⇒ START 分片丢了），紧跟 `[pan] L2CAP connect ret=0` 之后。
但它在**每次**开机都出现、只有部分开机真冻 ⇒ 是**相关**而非充分条件，指向 vendor HCI 层
分片成帧：启动日志里有 `sf32lb52 bth4: swallowed CC for injected op` /
`forwarded + synth CC for op 0x0c24`（vendor 驱动在合成命令完成事件）。
`CONFIG_BT_BUF_ACL_RX_COUNT=0` 曾疑似元凶但已排除：`CONFIG_BT_HCI_ACL_FLOW_CONTROL`
未开，实际用 `hci_rx_pool = BT_BUF_RX_COUNT`（≈10 个缓冲）。

**兜底（真机验证 3/3）**：新增 `hangwatch` 挂 `/dev/watchdog0`（20 s 超时），
**只在 UI 心跳正常时才喂狗**；UI 心跳停 >8 s 即停止喂狗 ⇒ 20 s 后硬件复位。
三次冻屏全部自动重启，**不再需要人工拔插 USB**。
喂狗判据必须用 UI 心跳而不是"我自己被调度到"——看门狗优先级最高，
无条件喂狗会让硬件看门狗永不触发。

### 25.4 新增取证设施（`packages/ai_agent/src/infra/hangwatch.c`）

- 每 5 s 一行 `[alive] up= ui= stuck= tasks= st=[各状态任务数]`；
- UI 心跳（launcher 的 1 Hz `lv_timer`）停 ≥8 s ⇒ `[stall]` + `nxsched_foreach`
  全量 dump 所有任务的 `pid/state/prio/stack/name`；
- 刻意**不调 `mallinfo()`**（`core/agent_mem.h:49` 记录它会 DEBUGASSERT 打死 agent）、
  不碰网络/文件系统/锁。

**另外两个会骗人的诊断陷阱**：
1. `gate=` 那一列不可单独采信（见 25.1）；
2. **nsh 与 agent 的 `vela>` CLI 抢同一个 stdin**（两个读者）—— 命令"发不进去"往往
   只是被 CLI 吃掉了，不是板子卡了；且 `audio_rec_test` 是内建命令，它卡住会把 nsh
   一起堵死（真机：录 25 s 到第 17 s 采集 DMA 停发，app 永久卡在 `mq_receive`）。

### 25.5 今晚提交

- **参赛仓**：`4a96c41` 心率屏蔽窗+取证打印 → `d4a04d0` 麦克风增益 30 dB
- **packages/ai_agent**：`48fcff2` hr_measure argv/窗口 → `284203f` hangwatch 看门狗
  → `e8ba5eb` 硬件看门狗兜底

### 25.6 待办更新

1. **冻屏根因**仍在 vendor HCI 分片成帧（`sf32lb52 bth4` 合成 CC 那条路径）；
   现有手段只有 20 s 自愈兜底，无 JTAG 难以再深入；
2. 麦克风底噪 ≈300 LSB、信噪比 ≈10 dB，做 ASR 可能偏紧（需跑一次真实 ASR 验证）；
3. 开机麦克风探针在 `agent_main.c` 里仍是注释状态，需真机确认后再打开；
4. `hr_sensor` 开机自启（§24.7 第 3 条）仍未做。

### 25.7 「测心率」第二次起不出数：uORB advertise 被整段跳过（真根因）

**症状**（23:46 真机）：按按钮 → 倒计时走完 → 屏幕「测量失败: 请把手指放在传感器上」，
但同一时刻算法侧完全正常：
`algo ... pk5s=8 rr_med=720ms q=100 gate=ok bpm=83 cand=83 stab=16`、
`stat ... bpm=83 hb=1 state=bpm-ok`，并且逐秒在打 `hr_sensor: bpm=83`。
即"算出来了、也在发布，订阅端收不到"。

**根因**：`g_hr_pub` 是**文件级 static**。flat build 下应用不是"重新加载"，只是同一
二进制里的函数再跑一遍，**静态变量保留上一轮的值**。于是第二轮起
`if (g_hr_pub < 0)` 为假、**整段 `orb_advertise` 被跳过**，采集线程拿着上一轮那个
已随进程退出而失效的 fd 去 publish(EBADF)。第一次能出数只是因为那一次它还是初值 -1。

**定位手段**：给 advertise/publish 加返回值打印后，三次测量里**只有第一次**打出
`uORB advertise ok (fd=4)` —— 一眼坐实。

**修法**：`main()` 的 `!inited && !running` 守卫里把 `g_hr_pub` 复位成 -1
（刻意不 `close()` 旧 fd：它早已关闭，编号可能已被别的文件复用，close 会误关别人的）；
`orb_publish` 返回值首次异常时打印一次。
真机验证：连测三次，三次都 advertise ok、三次都上屏（143 / 136 / 85 bpm）。
提交：参赛仓 `7c87d96`、ai_agent `06632cb`。

**同源陷阱（一并记录）**：uORB 主题节点 `/dev/uorb/<name><inst>` 只注册一次、
不会被自动删除，而 `SNIOC_SET_BUFFER_NUMBER` 只在 `!circbuf_is_init()` 时生效
（`nuttx/drivers/sensors/sensor.c:1054`）⇒ 第一个 advertiser 之后该节点的环形缓冲
配置就固化了。所以 `hr_measure` 每轮测量前先 `orb_unlink()` 再 `orb_subscribe()`，
让订阅端重新注册一个干净节点。

### 25.8 语音识别走云端，硬件本身不做识别（已核代码，非推测）

- **没有本地 ASR**：`voice/thirdparty/` 只有 `minimp3`（MP3 **解码**，给 TTS 放音用）；
  全 app 唯一的端侧模型是 `llm/local_lm.c` 的 TFLite-Micro **文本**语言模型
  （且受 `CONFIG_TFLITEMICRO` 开关控制），不是声学模型。语音目录里也**没有任何
  唤醒词 / KWS**。
- **识别 100% 在云端**：`voice/volc_asr.c` 连
  `wss://openspeech.bytedance.com/api/v2/asr`（火山引擎/豆包流式 ASR，
  TLS + WebSocket + V2 二进制协议），PCM 流式上行、文本下行。
- **需要凭证**：`app_id` + `access_token`（+ cluster），存在 config store，
  用 `set_volc_key` / `set_volc_asr <id> <tok> <cluster>` 设置
  （实现见 `channels/cmd_voice.c:93`）。
- **当前没配**：真机 `/data/ai_agent/config/config.json` 只有两个 DNS 键
  `{"net.dns_primary":"223.5.5.5","net.dns_secondary":"8.8.8.8"}`。

⇒ 结论：**麦克风硬件只负责"把音频采集得够干净"，识别精度由云端决定。**
硬件侧能优化的只有信噪比，所以 25.2 那条 "SNR ≈10 dB" 直接决定识别上限；
在配好火山凭证之前，"识别准不准"无法验证。
验证路径：`set_volc_asr` 配凭证 →（a）`voice_test_asr <file>` 拿已录 PCM 跑一遍，
把"音质"与"实时链路"分开；（b）`voice_start` 走完整链路实测。

### 25.9 下一步（在 25.6 基础上）

1. **心率准确度：接受前要求足够的稳定时间。** 25.7 修好之后能出数了，但连测三次得到
   143 / 136 / 85 bpm，前两次明显偏高。数据指向**测量前期的暂态**：第一次第 13 秒
   就发布，`pk2s=6 pk5s=7 rr_med=419ms` —— 5 秒内只有 7 个峰（≈84 bpm）却按
   `rr_med=419ms` 报 143 bpm，两者自相矛盾，说明 RR 集合被接触瞬间的双检污染；
   第三次一直到第 27 秒才收敛到 `pk5s=6 rr_med=700ms → 85`。而 `hr_measure` 现在的
   规则是"第一批被连续确认两次的值就接受"，于是锁定了暂态值。
   **改法**：要求跑满 15~20 s 且确认窗口拉长后才接受 —— 代价是出数变慢，换来数值可信。
   需真机复测。
2. **麦克风识别精度**：先配火山 ASR 凭证，再按 25.8 的两条路径验证。
---

## 二十六、2026-10-14 凌晨完成情况（MiniMax 语音识别 + 心率第二轮 + 蓝牙崩溃根因）

### 26.1 一句话状态（全部真机验证过）

| 功能 | 状态 | 依据 |
|---|---|---|
| 心率测量（按钮 → 上屏） | ✅ 每轮都能出数 | 连测三次 143/136/85 bpm，三次都有 `uORB advertise ok` |
| 心率 → 语音播报 | ✅ | `[voice] speak: "您的心率是 85"` + `[minimax_tts] ... api status_code=0` |
| 麦克风采集 | ✅ 对声音有响应 | 静音 avg≈300，喊话 peak=19036 / avg=933 |
| **语音识别 ASR** | ✅ **已接入 MiniMax 并识别出中文，火山已移除** | 见 26.3 |
| 语音合成 TTS | ✅ MiniMax | 同上 |
| 蓝牙 PAN 上网 | ✅ 已连 | `dhcp_ok dev=bt-pan ip=192.168.44.140` / `netmgr State: CONNECTED` |
| 整机冻屏 | ⚠️ 偶发（≈30% 冷启动） | 有 20s 硬件看门狗自愈兜底 |
| 识别准确率量化 | ⏸ 未完成 | 需要一句已知的话落在 ≤4s 窗口内 |

### 26.2 心率「只有开机后第一次能出数」—— 真根因与修复

**症状**（23:46 真机）：按按钮 → 倒计时走完 → 屏幕「测量失败: 请把手指放在传感器上」，
但算法侧完全正常：`algo ... pk5s=8 rr_med=720ms q=100 gate=ok bpm=83 stab=16`、
`stat ... bpm=83 hb=1 state=bpm-ok`，并且逐秒在打 `hr_sensor: bpm=83`。
即"算出来了、也在发布，订阅端收不到"。

**根因**：`g_hr_pub` 是**文件级 static**。flat build 下应用不是"重新加载"，只是同一
二进制里的函数再跑一遍 —— **静态变量保留上一轮的值**。于是第二轮起
`if (g_hr_pub < 0)` 为假、**整段 `orb_advertise` 被跳过**，采集线程拿着上一轮那个
已随进程退出失效的 fd 去 publish(EBADF)。第一次能出数只因为那次它还是初值 -1。

**定位手段**：给 advertise/publish 加返回值打印后，三次测量里**只有第一次**打出
`uORB advertise ok (fd=4)` —— 一眼坐实。

**修复**：`main()` 的 `!inited && !running` 守卫里把 `g_hr_pub` 复位成 -1
（刻意不 `close()` 旧 fd：它早已关闭，编号可能已被别的文件复用，close 会误关别人的）；
`orb_publish` 返回值首次异常时打印一次。
顺带 `hr_measure` 每轮先 `orb_unlink()` 再 subscribe（uORB 主题节点注册一次就永不删除,
而 `SNIOC_SET_BUFFER_NUMBER` 只在 `!circbuf_is_init()` 时生效，
见 `nuttx/drivers/sensors/sensor.c:1054`），并把 `s_last_bpm` 改成只统计本轮
（它以前会把上一轮的旧值带进失败信息，真机排查时被它误导过）。

### 26.3 语音识别：接入 MiniMax，移除火山

**用户要求**："把录音送去识别，不要火山"。

**先说结论（已核代码，非推测）**：识别**完全是云端**的，硬件本身不做识别。
- 没有本地 ASR：`voice/thirdparty/` 只有 `minimp3`（MP3 **解码**，给 TTS 放音用）；
  全 app 唯一的端侧模型是 `llm/local_lm.c` 的 TFLite-Micro **文本**语言模型，
  不是声学模型；语音目录里**没有任何唤醒词 / KWS**。
- 云端接口：`POST https://api.minimaxi.com/v1/speech_to_text`，
  `Authorization: Bearer <key>`，`multipart/form-data`（`model=asr-1.0` + `file=wav`），
  返回 JSON `{text, duration, trace_id}`。
  文档：https://platform.minimax.cn/docs/api-reference/speech-to-text
- **官方明确"不支持无容器的裸 PCM"**（支持 wav/aiff/flac/m4a/mp3/aac/opus/ogg），
  所以设备上只加 44 字节 RIFF/WAV 头，不做任何编码。

**实现**（提交见 26.6）：
- `minimax_tts.c` 导出两个可复用件，**不重复抄一套 mbedtls**：
  `minimax_get_api_key()`（与 TTS 完全相同的四道 key 来源链：tts_api_key →
  api_key → llm_backend_0 → 编译进镜像的本地 key；两条链必须一致，否则会出现
  "能说话但不能识别"这种最难查的分裂状态）与 `minimax_https_post()`
  （任意 path/content-type，复用 `mm_tls_*` 与 `mm_read_response_head/mm_reader_read`，
  自动处理 Content-Length 与 chunked）。
- 新增 `voice/minimax_asr.c`：套 WAV 头 → 组 multipart → cJSON 取 `text`；
  `base_resp.status_code != 0` 时把 status_code 与 status_msg 一起打出来。
- `voice_channel.c` 用 `minimax_asr_register()` 替代 `volc_asr_register()`。
  火山后端仍留在源码里，换回一行即可（它还需要 app_id/token/cluster 三个凭证，
  而本机 config 从来没配过）。

**真机验证**（4 秒录音 / 131072 字节）：
```
[00:34:04] [voice_asr] Registered backend: minimax        ← 只有 minimax
[00:34:04] [minimax_asr] ready (model asr-1.0, key len=125)
[00:39:56] [minimax_asr] POST /v1/speech_to_text: pcm=131072 bytes (4.1s)
[00:40:36] [minimax_asr] text="你真是说的不知道，我不知道。"
[00:40:36] ASR result: 你真是说的不知道，我不知道。
```
而且识别结果被送进 agent，紧接着 `[minimax_tts] HTTP 200` —— **手表自己开口回复**，
"听到 → 理解 → 说话"整条闭环打通。

**两个必须记住的约束（实测）**：
1. **BT PAN 上行只有约 3 KB/s**（128 KB 上传约 40 秒），而 16kHz/16bit 实时音频是
   32 KB/s —— **实时流式识别在这条链路上跑不动**，只能传短片段。
2. 代码里 `AGENT_VOICE_PCM_BUF_SIZE = 128 KB`（= 4 秒）是硬上限，
   10 秒录音会被 `read_pcm_file` 以 `File too large` 挡掉。
3. 附带发现的用法坑：`voice_test_asr` 属于 **`vela>` CLI** 而不是 nsh，
   而 nsh 的 readline 总排在 stdin 等待队列前面 —— **要先敲一个占住 nsh 的命令
   （例如 `sleep 20`），再敲 `voice_test_asr <file>`**，cli_thread 才抢得到输入。

### 26.4 蓝牙「共享开着也连不上」—— bluetoothd 崩溃根因

**症状**：手机蓝牙共享已打开，手表永远连不上；`ps` 里 `bluetoothd` /
`bt_service_*` / `sysworkq` / `BT LW WQ` **全部消失**；只有重启板子能恢复。

**取证**（用 ELF 对固件 `Oct 14 2026 00:31:13` 做 addr2line，两次崩溃都断在同一行）：
```
z_fatal_error                 port/kernel/fatal.c:49
bt_hci_cmd_send_sync          host/hci_core.c:507     ← 断言在这里
bt_conn_enter_sniff_mode      host/conn.c:4671        ← ①让链路进 sniff 低功耗模式
zblue_set_power_mode          sal_adapter_interface.c:2259
——
bt_br_write_local_name_mc     host/classic/br.c:1473  ← ②设置设备名"小云手表"
bt_set_name_mc                host/hci_core.c:4767
```
即：**控制器对某条 HCI 命令永远不应答 → k_sem_take 超时 → BT_ASSERT_MSG 升级成
致命错误 → z_fatal_error → bluetoothd 整个进程死掉。** 换任何一条命令都可能触发。

**控制器为什么不应答**，日志里有直接证据（vendor HCI 层在合成/吞掉命令完成事件）：
```
sf32lb52 bth4: swallowed CC for injected op
sf32lb52 bth4: forwarded + synth CC for op 0x0c24
<err> OpCode 0x0c24 completed instead of expected 0x0c18
<wrn> opcode 0x0803  status 0x0c      (0x0803 = SNIFF_MODE, 0x0c = 命令被拒)
```
主机的"命令↔应答"配对因此错位，某些命令就等不到回应。

**修复**：把 `BT_ASSERT_MSG(err == 0, "Controller unresponsive ...")` 换成
`LOG_ERR` + 归还 net_buf + `return -ETIMEDOUT`（调用者 `sal_adapter_interface.c` 用
`SAL_CHECK(..., 0)`，记录后继续，栈不死）。
★ **返回前必须先把 `cmd(buf)->sync` 置 NULL**：那条命令的完成事件若**迟到**，
完成处理会 `k_sem_give(cmd(buf)->sync)`（同文件 2588 行），而 `&sync_sem` 是
`bt_hci_cmd_send_sync` 的**栈变量** —— 不摘就是 use-after-free。

**验证**：上电单次启动、`ACL up` / `BNEP setup OK` / `dhcp_ok ip=192.168.44.140` /
`netmgr CONNECTED`、**0 次 Assertion**。

**应急手段**（记住）：不重启板子也能拉回蓝牙 —— 串口里敲 `bluetoothd`。
（旧固件下它 5 秒后又崩，打完补丁后应能活。）

### 26.5 自己踩的坑：看门狗启动误判导致约 20 秒重启循环

刷完固件后出现 `00:54:20 → 00:54:43 → 00:55:03` 连续三次启动、间隔正好约 20 秒，
**且没有任何崩溃 dump** —— 不是 assert，是 25.4 那个硬件看门狗在复位。

原因：`stall` 判定用 `ui_stuck_ms = now - last_ui_change`，而 `last_ui_change` 在
**看门狗线程启动**时就设成了当前时间。开机阶段 launcher 的 1Hz lv_timer 还没建起来，
UI 心跳一次都没来过，`ui_stuck_ms` 一路涨；只要开机稍慢（> 8s）就判 stall →
停止喂狗 → 约 20s 后把**正在正常启动**的板子复位。
修法：`stall = (ui_seen != 0) && (ui_stuck_ms >= HW_STALL_MS)` —— UI 心跳"来过一次"
之后才启用卡死判定；之后它再停（真正的冻屏）照旧生效。
验证：单次启动、up 稳定越过 20s 窗口、`[stall]` 计数 0。

### 26.6 本轮提交索引

| 仓库 | 提交 | 内容 |
|---|---|---|
| 参赛仓 | `7c87d96` | 心率每轮强制重新 advertise + publish 失败不再静默 |
| 参赛仓 | `12b880a` | docs §25.7-25.9 |
| packages/ai_agent | `06632cb` | hr_measure 清 uORB 节点 + s_last_bpm 只统计本轮 |
| packages/ai_agent | `9cac365` | **新增 MiniMax ASR 后端，语音链路改用它，不再用火山** |
| packages/ai_agent | `79505a6` | 看门狗启动宽限（修 20s 重启循环） |
| external/zblue | `78ba2004999` | **HCI 命令超时不再断言自杀（修"共享开着也连不上"）** |

三个仓 `git status` 均为空。

### 26.7 待办（更新 §25.9；其中"配火山凭证"已作废 —— 已改用 MiniMax）

1. **心率准确度**：接受前要求足够的稳定时间（跑满 15~20s + 确认窗口拉长），
   避免锁定接触瞬间的暂态值（143/136 vs 收敛后的 85）。需真机复测。
2. **识别准确率量化**：蓝牙已恢复，录 2~4 秒说一句**已知的话**，用
   `voice_test_asr` 发一次即可给结论。注意 ≤4s / ≤128KB 上限与上面的用法坑。
3. **vendor HCI 层**（`sf32lb52 bth4` 合成命令完成事件）仍是蓝牙不稳的更深根因；
   现在只是"不再致命"，没有修好。
4. **实时流式识别不可行**（上行 ~3 KB/s vs 音频 32 KB/s）——语音交互设计必须
   按"短片段上传"来做。
5. 开机麦克风探针（`agent_main.c`）仍是注释状态；`hr_sensor` 开机自启仍未做。



---

## 二十七、2026-10-14 白天：语音上行 57 秒 → 8 秒（Ogg Opus）

> 承接 §26：昨天打通了识别链路，但**每个 4 秒的句子要传 50~57 秒**。本节是把这件事
> 从"能跑"推到"能用"的完整过程，含**一次失败的修复**与**四个踩过的坑**。

### 27.1 一句话结论

| 结论 | 证据 |
|---|---|
| 蓝牙 PAN 上行有**结构性上限约 12KB/s** | 本次 A/B 实测 TLS 上传 4.6~4.9KB/s；§22 根因1 早测过 11.6KB/s，理论上限 = 4×487B/RTT159ms ≈ 12KB/s |
| 16kHz/16bit 原始 PCM（32KB/s）**结构上不可能实时上传** | 比上限高 2.7 倍 |
| **Opus 16kbps（2KB/s）是唯一出路，且已跑通** | 真机：131072 → **8471 字节（15.5×）**，整轮 **57s → 8s** |
| MiniMax **只收带容器的 Opus** | 裸码流稳定 HTTP 400；Ogg 封装返回 200 且识别完全正确 |
| 官方背书 Opus 不伤精度 | 文档原文 "Convert to mono 16 kHz, or use mp3 / aac / opus — **accuracy is unaffected**" |

### 27.2 蓝牙上行吞吐：A/B 实测与结构性上限

**基线**（未修复固件，`/data/mic_4s.pcm`，body=131369 字节）：

```
[11:34:52] POST /v1/speech_to_text: pcm=131072 bytes (4.1s), body=131369 bytes
[11:35:42] text="你真是说的不知道，我不知道。"
本轮 rx_tun_write_fail: 53 次
```

墙钟 50 秒 ⇒ ≈2.6KB/s。期间 `rx_tun_write_fail len=54 ret=-1 errno=11` 刷了 53 条。

**我的假设与修复**：`len=54` = 14(Eth)+20(IP)+20(TCP)，是**纯 TCP ACK**；EAGAIN 来自
`tun_write()` 走的 `iob_trycopyin()`（**非等待版**，`nuttx/mm/iob/iob_copyin.c:244`
→ `iob_tryalloc()` 池满即返回 NULL），而全系统只有 `CONFIG_IOB_NBUFFERS=64` 个 IOB。
假设"IOB 池瞬时紧张"，于是加了**有界退避重试**（20 次 × 150µs）。

**实测结果：无效，已 revert。**

| | 修复前 | 修复后 #1 | 修复后 #2 |
|---|---|---|---|
| 上传实测 | （无计时） | 27731ms = 4.6KB/s | 26406ms = 4.9KB/s |
| `rx_tun_write_fail` | 53 | 48 | 42 |
| 墙钟 | 50s | 57s | 39s |

**判据是日志里的 `tries=20`** —— 每一条丢包都是重试**全部耗尽**：

```
[11:39:10] [pan] state=rx_tun_write_fail len=54 ret=-1 errno=11 tries=20
```

即 3ms 内 20 次 write 全部 EAGAIN ⇒ IOB 池是**持续**告罄，不是瞬时紧张，
"等一会儿就有空闲"的前提不成立。而代价是实在的：每次丢包在 BT 接收线程白等 3ms，
一轮约 48 次 ≈ 144ms。**已 `git revert`（`8a706af1`）并更正结论。**

**更正后的认识**：IOB 被持续占满的直接原因是**上行 TX 排队** —— BT PAN 的 TX 吞吐低，
排队的帧一直占着 IOB 不放，于是 RX 方向（ACK）拿不到 IOB 而被丢，**典型的缓冲膨胀
反馈**。真正的修法在 TX 侧或池容量（SRAM 已 82%，加到 256 个 IOB 需要约 300KB，装不下），
**不在 RX 侧的重试**。

### 27.3 libopus：能编，但整个库是 `-O0`

**启用过程有个坑**：`.config` **不是 ninja 的依赖**，改了它 `ninja` 不会重新配置，
`build.ninja` 里 `if(CONFIG_LIB_OPUS)` 整块仍是空的（`apps/external/opus` 只是个
空壳目标）。必须显式重跑 `cmake -S nuttx -B <builddir>`，opus 的编译规则才从
**0 条变成 568 条**。

**然后发现真正的性能问题**：真机测编码速度只有 0.68× 实时（4.096 秒音频要 6 秒），
偏低得可疑。查 `build.ninja` 里 opus 目标文件的 FLAGS：

```
apps/external/opus/CMakeFiles/libopus.dir/... FLAGS = -mtune=cortex-m33 ... -nostdlib
                                                 -g3 -DEMBEDDED_ARM=1 -DHAVE_CONFIG_H
                                                 -DOPUS_WILL_BE_SLOW
                                          ↑ 一个 -O 都没有
对照: 同一次构建里 lvgl 等目标是 -O2/-O3 (全树 -O2 x656 / -O3 x150)
```

原因：`external/opus/CMakeLists.txt` 用 `target_compile_options(libopus PRIVATE ${CFLAGS})`
自己设 flags，`CFLAGS` 只拼了 `EMBEDDEDCFLAGS`/`OPUS_FLAGS`，
**绕过了 NuttX 给其他库的统一优化标志**，GCC 于是默认 `-O0`。

修法 `list(APPEND CFLAGS -O2)`（不用 O3：编码器是 DSP 密集代码，O3 收益有限但体积与
栈都会涨）。**真机实测：**

| complexity | -O0 | **-O2** | 实时倍率（-O2） |
|---|---|---|---|
| 10（默认质量） | 19s | **6s** | 0.68× |
| **0** | 6s | **2s** | **2.05×** ✅ |

**顺带白捡 160KB**：固件 7867100 → 7706508（`-O0` 的代码本来就臃肿）。

### 27.4 MiniMax 到底收不收裸 Opus —— 实测回答

官方文档的格式列表是 `wav / aiff / flac / alac(m4a) / mp3 / aac / opus / ogg`，
并把 `opus` 与 `ogg` **并列**，容易读成"支持裸 Opus"。**实测不是。**

在主机上做的对照实验（用 MiniMax 自己的 T2A 合成一句**已知**的话
"今天天气怎么样"，再分别编码回传识别）：

| 格式 | 大小（1.407 秒） | ASR 结果 |
|---|---|---|
| 16k PCM WAV | 45034 | `今天天气怎么样？` |
| **Ogg Opus 16kbps** | **2765** | `今天天气怎么样？` ✅ |
| **裸 Opus 码流** | 2502 | ❌ **HTTP 400** |
| µ-law 8k WAV | 11332 | `今天天气怎么样？` |
| IMA ADPCM 16k WAV | 12382 | `今天天气怎么样？` |

裸流的错误：

```
{"type":"bad_request_error",
 "message":"invalid params, Invalid data found when processing input (2013)"}
```

**换成 `.ogg` 后缀也一样** ⇒ 它看的是**内容**，不是扩展名。

> **测试中的陷阱（值得记住）**：第一次测"`a.opus` 成功"，但一看文件头是 `OggS` ——
> **ffmpeg 的 `-f opus` 也是输出 Ogg**。把 Ogg 里的音频包抽出来拼成真正的裸包序列，
> 才拿到真实的 400。**不要凭"文件名/后缀/工具参数"判断格式，要看魔数。**

另外两条官方信息很有价值：
- "**Not supported**: Raw PCM without a container; **audio-side streaming input
  (do client-side VAD for pseudo-streaming)**" ⇒ 官方推荐的就是"客户端分段"方案。
- "Convert to mono 16 kHz, or use mp3 / aac / opus — **accuracy is unaffected**"
  ⇒ 实测印证：16kbps Opus 与原 WAV 识别结果**一字不差**。

### 27.5 Ogg Opus 集成与真机结果

**先原型后移植**：树里没有 libogg，但 Ogg 的"写"这一侧简单（复杂的是"读"）。
在主机上用 Python **从零实现** Ogg 封装（CRC32 多项式 `0x04c11db7`、不反射；
27 字节页头 + 段表(255 递降) + OpusHead/OpusTags），先对着 MiniMax 验证通过
（返回 200 且文字完全正确、体积 2738 字节 vs ffmpeg 2765），**再原样移植到 C**。

**实现**：新增 `voice/opus_ogg.c/.h`；`minimax_asr.c` 优先走 Ogg Opus，编码失败
自动回退 WAV；`voice_channel.c` 的 `ASR_THREAD_STACK` 24KB → 64KB
（`opus_encode()` 栈开销大，真机上 16KB 栈跑它直接栈溢出硬 fault，
backtrace 落在 `celt_encode_with_ec`）。

**真机验证**（`/data/mic_4s.pcm`，与 27.2 是同一个文件）：

```
[12:47:36] ASR: recognizing 131072 bytes from /data/mic_4s.pcm ...
[12:47:38] [opus_ogg] 131072 PCM bytes -> 8471 Ogg Opus bytes (16000 bps)
[12:47:38] [minimax_asr] POST ... -> audio/ogg 8471 bytes, body=8724 bytes
[12:47:44] [minimax_asr] text="对对对对，就是这个，不知道，我不知道。"
```

| | 之前（原始 PCM+WAV） | **现在（Ogg Opus）** |
|---|---|---|
| 上传体积 | 131369 字节 | **8471 字节（15.5×）** |
| 编码耗时 | 0 | 2 秒 |
| POST → 出文字 | **57 秒** | **6 秒** |
| **整轮（含编码）** | **57 秒** | **8 秒** |

### 27.6 这一轮踩的四个坑

1. **指针别名（最迷惑的一个）**：`og_add()` 最初只记录 `opus_encode()` 输出缓冲的
   **指针**，而调用方每帧都写回**同一个缓冲** ⇒ 等攒够一页再落页时，所有指针都指向
   最后一帧，整页数据全错。
   **症状极具误导性：编码"成功"、体积正常、HTTP 200，但 `text=""`（空字符串）。**
   修法：每包拷进一块 staging 区再存指针。
2. **`defconfig` 的注释只能写 ASCII**：加了中文注释后 CMake 配置直接失败
   （`nuttx_kconfig.cmake:48` → `string sub-command REGEX, mode MATCH needs at
   least 5 arguments`）。根因是 `nuttx/CMakeLists.txt:236` 用
   `nuttx_export_kconfig_by_value()` 解析 defconfig，它是 `file(STRINGS)` +
   逐行正则，处理不了非 ASCII 字节。
3. **`opus_enctest` 在这块板上必然崩，但那是测试自身的缺陷**：
   `mscbr_encode_fail10()` 里有 `unsigned char data[627300]` 的**栈上数组**
   （为 8MB 主机栈设计），100KB 栈直接溢出。**不是 Opus 的 bug** —— 同一条音频换
   `opus_demo` 跑就正常。已写进 defconfig 注释免得重踩。
4. **我加的 upload 计时在小 payload 下没有意义**：它测的是 `mm_tls_write_all()` 的返回，
   而 8.7KB 直接进了 socket 发送缓冲 ⇒ 打出 `99.9 KB/s` 这种漂亮但失真的数字。
   128KB 时（超过缓冲）它才有意义。**判断这条链路只能用 POST→响应的整轮时间。**

### 27.7 提交索引

| 仓库 | 提交 | 内容 |
|---|---|---|
| frameworks/connectivity/bluetooth | `96af0602` + `8a706af1` | PAN 重试修复 **及其 revert**（附推翻过程） |
| packages/ai_agent | `22b5ef2` | HTTPS 上传吞吐计时（判断依据） |
| external/opus | `7ba2a2d` | **补 `-O2`（此前全库 `-O0`，快 3 倍 + 小 160KB）** |
| 参赛仓 | `9825f12` | 打开 libopus 并记入板级 defconfig |
| 参赛仓 | `df0aa57` | defconfig 注释去非 ASCII（修 CMake 配置失败） |
| packages/ai_agent | `44e27cd` | **Ogg Opus 集成：上传体积降 15.5 倍，端到端 57s → 8s** |

### 27.8 待办

1. **边录边编码**：complexity 0 实测 **2.05× 实时**，有 2 倍余量 ⇒ 可省掉那 2 秒编码等待。
2. **分段上传（官方推荐的伪流式）**：每 1~2 秒传一段、服务端边收边出，可再省 3~4 秒。
   两项做完有望进到 **5~6 秒**，即真正可用的对话节奏。
3. **识别准确率量化**：仍然缺一句"已知的话落在采集窗口内"的对照（§26.7 遗留）。
   现在有了 Ogg Opus，这条更容易做 —— 主机侧的对照实验已经证明 16kbps 不伤精度。
4. **µ-law / IMA ADPCM 是实测可用的低 CPU 退路**（见 27.4 表）：若哪天 CPU 吃紧，
   WAV 容器 + µ-law 只需约 20 行代码、CPU ≈ 0，代价是上传量是 Opus 的 4 倍。
5. §26.7 的其余各项（心率准确度、vendor HCI、开机探针等）仍然有效。


---

## 二十八、2026-10-14 下午：实时语音打通（边录边编码），并发现采集从未工作

> 承接 §27：那时打通的是"**先录成文件，再送去识别**"。这一节把它推到真正的
> 实时交互，过程中挖出一个更根本的问题 —— **实时语音采集在这块板上从来没工作过**。

### 28.1 一句话结论

| 结论 | 证据 |
|---|---|
| **实时采集在板上从未工作** | 固件里 `media_recorder_*` **全部是 W(weak)**；real ALSA 路径的开关没开且依赖树里不存在的头 |
| 已修：强符号直驱 `/dev/audio/pcm0c` | 真机 `[pcm_rec] opened/prepared/started`，chunk 峰值 24864 |
| 已修：边录边编码 | 停录那一刻日志直接是 `[opus_ogg] encoded 12144 Ogg Opus bytes`，**没有 2 秒编码等待** |
| 全链路真机跑通 | 麦克风 → 采集 → 边录边 Opus → Ogg → MiniMax → 中文文字 |

### 28.2 更根本的问题：实时采集从来没工作过

做"边录边编码"时先撞上的是这个：

```
[voice] ASR pre-connected
[audio_cap] media_recorder_open failed, errno=11
[voice] capture open failed
```

逐层查到符号表，问题一目了然：

```
$ arm-none-eabi-nm nuttx | grep media_recorder_
120ed8d0 W media_recorder_close
120ed8ba W media_recorder_open
120ed8e8 W media_recorder_prepare
120ed934 W media_recorder_read_data
120ed904 W media_recorder_start
120ed91c W media_recorder_stop
```

**全部是 `W`（weak）** —— 也就是 `src/stubs.c` 里那组"永远返回 NULL/失败"的桩，
而 `frameworks/multimedia/media` 里的真实现**压根没编进固件**。

代码里采集本来有两条路，但那一条也走不通：

| 路径 | 开关 | 状态 |
|---|---|---|
| direct ALSA（`snd_vela_pcm_open`） | `CONFIG_AI_AGENT_AUDIO_ALSA_DIRECT` | **没开**，且它 `#include <aw-alsa-lib/pcm.h>`，而全树都没有这个头（那是 Allwinner R528 的东西） |
| `media_recorder` | 无 | 只有 weak 桩 |

**所以：实时语音采集从未工作过。** 之前"语音能用"的演示全部走的是离线路径
—— `audio_rec_test` 录成 `/data/mic_test.pcm`，再 `voice_test_asr` 识别。
这也解释了为什么这个功能一直没被发现：**离线路径能出结果，掩盖了实时路径完全不通。**

### 28.3 修法一：`pcm_media_recorder.c`（强符号直驱）

新增 `src/voice/pcm_media_recorder.c`，用**强符号**实现整套 `media_recorder_*`，
直接驱动 `/dev/audio/pcm0c`。采集序列**照抄已被真机验证的**
`contest.../app/ai_agent/audio/audio_rec_test_main.c`：

```
open(dev, O_RDWR) -> AUDIOIOC_RESERVE -> AUDIOIOC_CONFIGURE(16k/16bit/mono)
  -> mq_open(O_RDWR) -> AUDIOIOC_REGISTERMQ -> AUDIOIOC_GETBUFFERINFO
  -> AUDIOIOC_ALLOCBUFFER xN + AUDIOIOC_ENQUEUEBUFFER
  -> AUDIOIOC_START
  -> 循环 mq_timedreceive(AUDIO_MSG_DEQUEUE) -> 取 apb->curbyte -> 重新 ENQUEUE
```

**★ 同时必须删掉 `stubs.c` 里那组弱桩。** 只添加强实现是不够的 —— 真机验证过：
留着桩时 `nm` 仍然显示 `W`，因为**弱符号会先被链接器选中**，之后归档
（`libapps_ai_agent.a`）里定义同名强符号的那个成员就再也没有机会被拉进来。
这与 `stubs.c` 自己写的约定一致：`media_player_*` 的实现在
`pcm_media_player.c`（强符号），所以 stubs.c 里**本来就没有** player 的桩。

### 28.4 修法二：`read_data` 超时的返回语义

第一版实现里 `media_recorder_read_data()` 在 mq 超时时**返回 0**，结果：

```
[13:15:49] [voice] capture read returned 0, stopping
[13:15:49] [voice] recording thread exit: 122 chunks, 249856 read, 249856 sent (abnormal)
[13:15:49] [voice] recorded 0 bytes (stream=0x60071560)
```

采了 249856 字节（全喂进了编码器），却因为一次超时被判成异常退出，
`recording_cleanup_locked()` 把流 `abort` 掉，**整段白采**。

根因是返回值的约定：调用方 `recording_thread` 只把 **`-EAGAIN`** 当作
"暂时没数据"重试，而把 `n <= 0` 当作**致命错误**。所以超时必须返回
`-EAGAIN`。顺带把超时从 2 秒收到 **200 ms**，停录响应更利落。

### 28.5 边录边编码

有了可用的采集，正题才真正开始。三处改动：

1. **`opus_ogg.c` 改成增量式**：`opus_ogg_begin/feed/finish/abort`，
   原来的批量 `opus_ogg_encode()` 退化成它的薄封装。`feed()` 内部攒够
   20 ms(640 字节)才编一帧，输出缓冲按需 `realloc`。
2. **`voice_asr.h` 的 ops 增加 4 个可选流式回调**，`voice_asr.c` 的分派从
   "**硬编码只连火山**"改为优先用后端回调，火山那套 WebSocket 实现作为兼容
   路径保留。
3. **`minimax_asr.c` 实现流式四件套**：open 建编码器、send 喂帧、
   finish 收尾成 Ogg 再上传；顺带把"组 multipart + POST + 解析"抽成
   `mm_asr_post_payload()`，批量与流式两条路共用。

另外 `AGENT_VOICE_STACK` 16KB → 64KB —— 采集线程现在还要跑 `opus_encode()`，
而 16KB 栈跑它会栈溢出硬 fault（backtrace 落在 `celt_encode_with_ec`）。

### 28.6 真机验证（2026-10-14 13:19）

```
[13:19:13] [voice] ASR pre-connected
[13:19:13] [pcm_rec] opened /dev/audio/pcm0c
[13:19:13] [pcm_rec] prepared 8 bufs x 4096 bytes @ 16000Hz 1ch
[13:19:13] [voice] recording thread started (streaming)
[13:19:13] [voice] using pre-connected ASR stream
[13:19:14] [voice] PTT recording started
[13:19:21] [voice] recording thread exit: 95 chunks, 194560 read, 194560 sent
[13:19:21] Voice: streaming ASR active, finishing...
[13:19:21] [opus_ogg] encoded 12144 Ogg Opus bytes
[13:19:21] [minimax_asr] POST /v1/speech_to_text: audio/ogg 12144 bytes, body=12397 bytes
[13:19:29] [minimax_asr] text="然后跟我讲，现在我们的话，他就很直接的，就他直接跟我们讲，嗯，对。"
```

6.08 秒录音 → 12144 字节（正好 16 kbps）；**编码耗时体现在录制期间**，
停录那一刻 Ogg 已经就绪，只剩上传+识别。识别出的是屋里真实说话内容。

### 28.7 性能对照

| 阶段 | 停说 → 出文字（同一量级的一句话） |
|---|---|
| 最初：录音 → 传原始 PCM | **约 57 秒** |
| §27：录音 → 整段 Opus 编码(2s) → 上传 | 约 8 秒 |
| **本节：录音(同时编码) → 上传** | **编码等待 0**，只剩上传+识别 |

### 28.8 这一轮的坑与教训

1. **弱符号会遮蔽归档里的强实现**。只加"真实现"不够，必须把弱桩删掉。
   判断方法：`nm <elf> | grep <symbol>` 看是 `T` 还是 `W`。
2. **超时该返回 `-EAGAIN` 还是 0，取决于调用方的约定** —— 这一处把 0 当致命错误，
   于是"一次超时"变成"整段录音作废"。改之前要先读调用方怎么判。
3. **"能出结果"不等于"链路是通的"**。离线路径一直能识别，掩盖了实时路径
   100% 失败。演示脚本要覆盖**真实入口**，不能只覆盖等价的离线路径。
4. **链接期问题要看符号表，不要只看代码**。代码里 `open_media_recorder_capture()`
   写得完全正常，是链接结果让它在运行时永远失败。

### 28.9 提交索引

| 仓库 | 提交 | 内容 |
|---|---|---|
| packages/ai_agent | `baf044c` | **实时语音全链路：pcm_media_recorder + 流式接口泛化 + 边录边编码** |

（本轮其余改动见 §27.7。）

### 28.10 待办

1. **分段上传**（官方推荐的 "client-side VAD for pseudo-streaming"）：
   每 1~2 秒把已编码的 Ogg 分片传一段，服务端边收边出 ⇒ 进一步压缩等待。
2. **识别准确率量化**：仍缺"一句已知的话落在采集窗口内"的对照（§26.7/§27.8 遗留）。
   现在实时链路通了，可以用 T2A 合成一句已知的话从喇叭放出来、再用麦克风收，
   形成闭环对照。
3. **按键/自动断句**：目前靠 `voice_start`/`voice_stop` 手动控制，
   产品化需要静音检测自动收尾。
4. §27.8 的其余各项仍然有效。


### 28.11 识别准确率对照测试（已知句子）—— 长期挂着的待办就此关闭

§26.7 起一直挂着"**识别准确率未量化**"（缺一句"已知的话"落在采集窗口内的对照）。
2026-10-14 13:32 用实时链路做了这个对照：

```
[13:32:50] [voice] PTT recording started        (录了 22 秒)
[13:33:15] [opus_ogg] encoded 42766 Ogg Opus bytes
[13:33:15] [minimax_asr] POST /v1/speech_to_text: audio/ogg 42766 bytes
[13:33:27] [minimax_asr] text="明天北京天气怎么样？"
```

**说话人实际说的就是「明天北京天气怎么样」—— 识别结果一字不差。**

> 一个测试方法上的教训：测试者故意把城市换成"北京"（而不是提问时说的"上海"），
> 最初拿"上海"去比对，得出了"听错了一个词"的**错误结论**。
> **对照实验里，"基准"必须是说话人确认过的实际内容，而不是提问时给出的提示词。**

**同时量到的信噪比**（同一段采集的 peak）：

| | 环境底噪 | 说话时 |
|---|---|---|
| peak | 6774 ~ 8184 | 16176 ~ 19674 |

只有约 **2.5 倍**差距 —— 因为之前为了修"麦克风无响应"把 ADC 增益加到了 **+30 dB（量程上限）**，
噪声被一起放大了。另外这次说 3 秒却录了 22 秒，**送给识别引擎的音频 85% 是噪声**。
识别结果仍然正确，但这两个都是明确的优化点：

1. **降低 ADC 增益**（+30 → +18~24 dB）：底噪与语音一起降，但语音占比提高；
2. **VAD 自动裁剪**：只送"有声音"的那一段 ⇒ 上传量 42KB → 约 6KB（快约 7 倍），
   且没有噪声上下文干扰，**同时解决"必须手动 voice_start/stop"的产品化问题**（见 §24.19）。


---

## 二十九、2026-10-14 下午：流式 VAD —— 只把有语音的音频送去识别

> 承接 §28：那时实时链路已经通了，但**整段录音**（含大量环境噪声）都会被送去识别。
> 本节把它裁到只剩语音，并顺带用 pcm-audio 技能把音频质量量化了一遍。

### 29.1 一句话结论

| 结论 | 证据 |
|---|---|
| VAD 只放行有语音的音频 | 18.3 秒录音 → 放行 5.7 秒（**裁掉 70%**），Ogg 18.7KB → 11.7KB |
| 识别一字不差 | 说话人念「小云手表，播放一首歌」，识别结果**逐字相同** |
| 全段无语音时不再空传 | 105 秒静音 → 0 帧放行 → 跳过上传（此前会传空 Ogg 被拒） |
| 音频质量已量化 | 削波 **0.00%**、**SNR 29.2 dB**；"802 个爆破音"**复核后判为误报** |

### 29.2 为什么需要

真机实测：说话人只说了 3 秒，录音却有 22 秒 —— **送给识别引擎的音频 85% 是噪声**，
上传量也从约 6KB 白涨到 42KB（而蓝牙上行结构性上限只有约 12KB/s，见 §24.15）。

### 29.3 设计与取舍

新增 `src/voice/vad_trim.c`，每 20ms 一帧算平均绝对幅度，与自适应底噪比较：

- **只裁首尾，不裁中间** —— 中间一裁就切断词间停顿，反而伤识别；
- 起说前留 **300ms 预滚**，说完后留 **500ms 后滚**，免得削掉第一个音素/尾音；
- 起说需**连续 2 帧**超阈值，抗敲击/爆音这类单帧脉冲；
- 底噪慢速 EMA 跟踪，且**只在判为静音时更新**（判为语音就冻结）——
  否则说着话底噪被拉高，后段就再也触发不了；
- 因为底噪是自适应的，**阈值与 ADC 增益无关**，改增益不用重新标定。

### 29.4 踩到的坑：底噪不能拍脑袋给初值

第一版把底噪初值写成 `200`，而实际环境帧能量远高于 `3x200`，于是：

```
第一帧就被判成语音 -> in_speech=1 -> 底噪估计被冻结 -> 再也退不出语音态
-> 整段 40.7 秒一帧都没裁:  "VAD: kept 2035 frames, dropped 0"
```

改成**开头 300ms 专门标定底噪**（取均值 + 粗野值剔除，防止标定期正好在说话）后正常。

### 29.5 真机验证（2026-10-14 14:28）

```
[14:28:44] [vad] noise floor calibrated: 8911 (15 frames)
[14:29:05] [voice] recording thread exit: 286 chunks, 585728 read   (18.3 秒)
[14:29:05] [vad] noise=4526 e_max=26976 kept=283 dropped=643
[14:29:05] [minimax_asr] VAD: kept 283 frames, dropped 643 (5.7s speech)   (裁掉 70%)
[14:29:05] [opus_ogg] encoded 11689 Ogg Opus bytes
[14:29:16] [minimax_asr] text="他已经发三四次了。小云手表播放一首歌。小云手表播放一首歌。"
```

说话人念的是「**小云手表，播放一首歌**」—— 识别结果**逐字相同**（出现两次是因为
说了两遍：第一遍没看到反馈又重复了一次）。同段里还准确转出了旁人的一句
「他已经发三四次了」。

另一次全程静音的 105 秒录音，VAD 正确地**一帧都不放行**并跳过上传。

### 29.6 音频质量实测（用 pcm-audio 技能的 audio_analyzer.py）

把采集到的原始 PCM 落盘拉回主机分析（该诊断代码**用完即删**）：

| 指标 | 结果 | 判读 |
|---|---|---|
| 削波率 | **0.00%** | 增益并没有过载 |
| SNR | **29.2 dB** | 技能判为偏低（阈值 40dB） |
| 爆破音 | 报 802 个 | **复核后判为误报**，见下 |
| 静音段 | 无异常 | ✓ |

**"802 个爆破音"是误报**，两条证据：

1. 位置完全散乱（相邻间隔 30 / 8646 / 2784 / 6336 / 1320 / 1680 / 10094 / 4 /
   66782 / 3200 采样），**没有一个等于缓冲区边界 2048 采样** ——
   缓冲区切换缺陷应该在固定位置聚簇；
2. 数量随阈值**平滑单调下降**（3000→4267、5000→802、8000→256、12000→95、
   16000→41、20000→11、25000→1）—— 这是**连续分布**（真实信号斜率）的特征。

结论：这是**语音本身的高频斜率**超过了检测器 5000 的默认阈值（该段峰值达 27000，
16kHz 下 1kHz 分量在 20000 幅度时的逐样本斜率就有约 7850），不是采集缺陷。

> 教训：**第三方分析工具的默认阈值要结合信号电平判断**，否则会把正常内容报成缺陷。
> 复核成本很低（换个阈值 + 看位置分布），但不复核就会把假问题写进结论。

### 29.7 落盘诊断代码的两条经验

1. **落 tmpfs(`/tmp`)，绝不落 `/data`** —— `/data` 在 NOR flash 上，而代码是
   XIP 从**同一片 NOR** 执行的；边录音边以 32KB/s 写 flash 会长时间占住 flash
   控制器。真机上录制十几秒后板子**无 fault dump 静默复位**（`SFBL`），
   改成 `/tmp` 后不再复现。
2. 要留**最后 N 秒**（环形缓冲），不是开头 N 秒 —— 说话人通常在按"停录"之前才开口，
   留开头往往只抓到环境声。

### 29.8 一个已知局限（留给下一步）

**能量型 VAD 在多人环境里会挑"最响的那个声源"。** 真机遇到过一次：房间里有人
在聊天（底噪标定值 7015，是安静时的 3 倍），VAD 放行的是**环境对话**，
说话人对表说的那句反而因为不够响没被放行。

这不是实现 bug，是能量型 VAD 的固有局限。可选方向：
- 降低倍数阈值，让 ASR 自己去应付噪声（代价是上传量回升）；
- 或做**近场优先**（例如双麦/波束，本板没有硬件条件）；
- 或产品层面要求"靠近手表说"（本次验证就是这么做的，效果很好）。

### 29.9 提交索引

| 仓库 | 提交 | 内容 |
|---|---|---|
| packages/ai_agent | `9ff59d3` | **流式 VAD + 无语音不上传** |

### 29.10 待办

1. **VAD 自动断句（endpointing）**：现在还得手动 `voice_stop`。VAD 已经知道
   "一句话说完了"，把它接成自动收尾就能去掉按键（§24.19）。
   本次交互中多次出现"用户没注意到提示、录音空跑十几分钟"，
   正说明这个功能是刚需。
2. **ADC 增益是否需要回调**：削波率 0% 说明增益没过量，而 SNR 低可能是**环境噪声**
   （声学）而非电路噪声。要判断得做对照：同一环境下降增益再测底噪 ——
   若底噪跟着等比下降，说明噪声在 PGA 之前，**降增益并不能改善 SNR**。
3. **多人环境下的声源选择**（见 29.8）。


---

## 三十、2026-10-14 傍晚：自动断句 + 物理按键 —— 语音交互闭环

> 承接 §29：那时 VAD 已经会**裁剪**，但"什么时候开始录、什么时候停"仍然全靠人工。
> 本节把边界也交给设备：说话自动收尾，按键触发录音。

### 30.1 一句话结论

| 结论 | 证据 |
|---|---|
| **说完自动收尾**（不用按任何键） | 真机：`[vad] utterance done: 114 frames (2.3s speech, 1.2s silence)` → 自动停录 → 识别出「小银手表现在几点了？」 |
| **物理按键触发** | 真机：`[key] KEY2: start voice` → 录音 → 自动断句 → 上传 |
| 全程零操作、说完到出文字 **5 秒** | 14:51:52 说完 → 14:51:57 出文字 |

### 30.2 自动断句

链路：

```
VAD(utterance_done) -> voice_asr_ops.stream_utterance_done
  -> 采集线程 -> 信号量叫醒监督线程 -> voice_channel_stop()
```

**为什么收尾要单独一个线程**：采集线程**不能自己**调 `voice_channel_stop()` ——
那里面有 `pthread_join(rec_thread, ...)`，等于线程 join 自己，直接死锁。
所以采集线程只负责"检测 + 叫醒"，收尾交给平时睡在信号量上的监督线程
（`auto_stop_thread`，栈 16KB，不占 CPU）。

### 30.3 坑一：「裁剪的后滚」不等于「判断说完的静音」

第一版两件事都用 **500ms**，于是说话人在「小云手表」和「现在几点了」之间
自然停顿了一下，VAD 就以为说完了：

```
[vad] utterance done: 42 frames (0.8s)
-> 只放行 0.8 秒 -> text="" (空串)
```

改成：

| 用途 | 时长 | 理由 |
|---|---|---|
| 裁剪的**后滚** | 500ms | 尾部留多少静音无所谓 |
| 判断**说完** | **1.2s** 连续静音 | 要能容忍词间自然停顿 |

且"连续静音计数"必须与 `in_speech` **无关地**跟踪 —— 退出语音态之后仍要继续累计。

### 30.4 物理按键

KEY2(PA11) 原本独占"随机问模型一个问题"的演示功能，现在：

| 动作 | 行为 |
|---|---|
| **短按** | 开始/结束语音。有了自动断句，正常用法是"按一下、说一句、什么都不用管"；再按一下是提前结束 |
| **长按 ≥1.5s** | 原来的"问模型"演示（原样保留） |

KEY1(PA34，打开宠物页) 不动。按键循环原本只认**按下沿**，现在需要区分短按/长按，
所以加了松开沿检测；KEY2 按下时只记时间、**动作等松开再定**；长按在 poll
超时路径上也会被检查（`KEY_POLL_MS=1000`），所以按满 1.5s 就触发，不必等松开。
新增 `voice_channel_is_recording()` 供按键做开始/结束切换。

### 30.5 坑二：VAD 标定被"启动爆音"毁掉，而且**失效得毫无声息**

真机现象：按键触发录音正常，但说了话**一帧都不放行**，最终 "no speech detected"。
查到的链条：

1. 录音刚启动时音频通路会出现**满量程爆音**：`chunk#1: peak=32768`；
2. 旧标定逻辑拿这 300ms 的**均值**当底噪 → 标定出 **14068**；
3. 阈值 = 3 × 14068 = **42210**，而"平均绝对幅度"根本到不了这个数
   → **任何一帧都触发不了**，VAD 完全失效；
4. 等底噪自适应降回 3344 时，说话人早已说完。

**日志上完全看不出"VAD 坏了"**，只看到"没有语音"。三条防护一起加：

- 标定前先跳过 **100ms**（`VAD_CALIB_SKIP`）启动瞬态；
- 底噪取标定期的**最小值**而非均值（底噪应代表"最安静时的背景"）；
- 底噪**封顶** `VAD_NOISE_MAX=6000` —— 标定坏了也不会把 VAD 废掉。

### 30.6 真机验证（2026-10-14）

**自动断句**（14:51，全程未发送任何停止命令）：

```
[14:51:42] [vad] noise floor calibrated: 5721
[14:51:52] [vad] utterance done: 114 frames (2.3s speech, 1.2s silence)
[14:51:52] [voice] auto-stop: utterance done after 139 chunks
[14:51:52] [voice] recording thread exit: ... (auto-stop)
[14:51:52] Voice: speech ended, finishing...
[14:51:52] VAD: kept 114 frames, dropped 331 (2.3s speech)
[14:51:52] [opus_ogg] encoded 4776 Ogg Opus bytes
[14:51:57] [minimax_asr] text="小银手表现在几点了？"
```

说话人念的是「小云手表，现在几点了」—— 除"小云"听成同音的"小银"，其余逐字正确。

**物理按键**（15:02，按一下 + 说一句，其余零操作）：

```
[15:02:56] [voice] PTT recording started
[15:02:59] [voice] auto-stop: utterance done after 49 chunks
[15:03:00] [voice] recording thread exit: ... (auto-stop)
[15:03:00] VAD: kept 116 frames, dropped 36 (2.3s speech)
[15:03:00] [opus_ogg] encoded 4817 Ogg Opus bytes
[15:03:00] [minimax_asr] POST /v1/speech_to_text: audio/ogg 4817 bytes
[15:03:02] [minimax_https] HTTP 429
[15:03:02] [minimax_asr] request failed: -5
```

链路每一环都对，**失败在服务端限流**（见 30.7）。

### 30.7 HTTP 429：反复测试把配额打满了

当晚为验证反复发起识别请求，MiniMax 返回 **429 Too Many Requests**
（TTS 同样 429）。这是外部原因、不是链路缺陷，但说明**需要重试/退避**：

- 当前实现把 429 当成普通失败直接放弃，用户看到的是"识别失败"；
- 应当：识别到 429 时**按 Retry-After 退避重试**（1~2 次），仍失败才报错；
- 开发/演示时注意别把配额打满。

已记入 [24 号文](24_open_issues.md)。

### 30.8 已知偶发问题（记录备查，未修）

同一晚遇到两次**采集异常**，都发生在板子刚起来不久：

| 现象 | 细节 |
|---|---|
| 采集读到 **0 块** | 45 秒一个字节都没读到 |
| 采集只读到 **46 块** | 45 秒只采到 2.9 秒音频（约 6% 实时率） |

两次都伴随开头的满量程爆音，怀疑与音频通路刚启动时的状态有关。
**不是本节改动引入的**（"0 块"那次出现在改动之前），但值得后续单独查。

### 30.9 提交索引

| 仓库 | 提交 | 内容 |
|---|---|---|
| packages/ai_agent | `da2b5bc` | **自动断句** |
| packages/ai_agent | `df094cd` | **物理按键 PTT + VAD 标定防护** |

### 30.10 待办

1. **429 退避重试**（见 30.7）—— 优先级最高，直接影响可用性；
2. **采集偶发异常**（见 30.8）；
3. §29.10 遗留：ADC 增益对照实验、多人环境声源选择。
