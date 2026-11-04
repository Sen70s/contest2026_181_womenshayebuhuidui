# 28. 板载麦克风真机验证记录（2026-10-10）

> 目的：验证 P1（D-cache invalidate）/ P2（drain 握手）的实际效果
> 验收口径：**无人说话时基线不再恒零**
> 结论：**未达成**；并挖出两个前四轮审查未覆盖的缺陷
> 配套：驱动缺陷见 [25](25_flash_reliability_and_audio_defects.md)，审查史见 [27](27_audio_driver_review.md)

---

## 0. 本轮新挖出的两个缺陷（前四轮审查都未覆盖）

### 0.1 `getcaps` 必须返回 `ac_len` 而不是 `OK`（已修，真机验证）

```c
/* 错误 */  return OK;
/* 正确 */  return caps->ac_len;
```

- audio core 把该返回值**直接透传**给 `AUDIOIOC_GETCAPS` 的调用者（`nuttx/audio/audio.c:960`）
- `nxrecorder` 判的是 `ioctl(...) == cap.ac_len`（`apps/system/nxrecorder/nxrecorder.c:202`）
  → 返回 0 会**跳过整个检查块** → `-ENODEV` → `No suitable Audio Device found`
- **树内 8 个参考驱动全部返回 `ac_len`**：`cs4344` / `cs43l22` / `audio_i2s` / `audio_dma` /
  `audio_fake` / `audio_null` / `audio_rpmsg` / `audio_tunnel`
- **真机验证**：修复前 `nxrecorder recordraw` 必报 `No suitable Audio Device found`；修复后不再报

### 0.2 `nxrecorder recordraw` 会整机挂死（未修）

录音过程中板子完全静默（连回车都不回显），`/data/p1.pcm` = **0 字节**。
USB 桥仍在、端口可开，说明是**固件挂死**而非断电/掉枚举。

---


> 目的：验证 P1（D-cache invalidate）/ P2（drain 握手）的实际效果，验收口径 = **无人说话时基线不再恒零**
> 结论：**验收口径未达成**；且发现比预期更严重的现象（标准录音路径挂死 + Critical 的真机复现）

---

### 一、烧录方法与参数（可复用的正解）

| 策略 | 命令 | 实测 |
|---|---|---|
| 交替 + 3 次连接尝试 | `--before default_reset/no_reset_no_sync --connect-attempts 3` | ❌ 5 轮 + 8 轮全败 |
| **长等待** | `--before no_reset --connect-attempts 127 --after soft_reset` | ✅ **1~2 轮即成功**（三次烧录分别在 round 2 / 1 / 11 成功） |

**关键**：`--connect-attempts 127` 让 sftool 长时间挂着等同步，操作者按 RESET 的命中率高得多。
`--connect-attempts 3` 两次调用之间有"空档"，RESET 落在空档里就被浪费。

**串口约定**（必须）：打开前设 `rts=False, dtr=False`（RTS 接板子电源）；行终止符用 `\n`，**`\r` 无效**。

---

### 二、发现并修复的新缺陷（两轮审查都未覆盖）

#### 2.1 `getcaps` 必须返回 `ac_len` 而不是 `OK`

```c
/* 错误 */  return OK;
/* 正确 */  return caps->ac_len;
```

- audio core 把该返回值**直接透传**给 `AUDIOIOC_GETCAPS` 的调用者（`nuttx/audio/audio.c:960`）
- `nxrecorder` 判的是 `ioctl(...) == cap.ac_len`（`app/system/nxrecorder/nxrecorder.c:202`）→ 返回 0 直接跳过整个检查块 → `-ENODEV`
- **树内 8 个参考驱动全部返回 `ac_len`**：`cs4344` / `cs43l22` / `audio_i2s` / `audio_dma` / `audio_fake` / `audio_null` / `audio_rpmsg` / `audio_tunnel`
- **真机验证**：修复前 `nxrecorder recordraw` 必报 `No suitable Audio Device found`；修复后不再报错

> **更正**：`docs_ble/25` 早期把根因写成"`sscanf` 只取文件名"——**错**。
> `nxrecorder_opendevice()` 里 `if (precorder->device[0] != '\0')` 是**唯一路径**（无扫描循环），
> 而真正的失败点是 `ac_len` 返回值。`audio_setup` 只判 `<0` 所以"通过"，把问题掩盖了。

#### 2.2 `nxrecorder` 的正确用法

```
nxrecorder                     # 进入交互模式（命令行参数模式会直接掉进交互，args 不生效）
device /dev/audio/audio0       # ← 必须先指定设备，否则必 -ENODEV
recordraw /data/x.pcm 1 16 16000
stop
q
```

---

### 三、真机现象（按发生顺序）

| # | 操作 | 结果 |
|---|---|---|
| 1 | `audio_setup &` | ✅ `device caps OK (input=3 output=0)`、`/dev/audio/audio0 ready`；二次执行返回 `-17`（幂等） |
| 2 | `nxrecorder` → `device` → `recordraw` | ⚠️ 不再报错，但**录音过程中整机挂死**，`/data/p1.pcm` = **0 字节** |
| 3 | `audio_rec_test 3` | ✅ 完成，98304 字节；但清理阶段触发 `semaphore.h:523` 断言（**带完整回溯**） |
| 4 | `audio_rec_test 1` | ✅ 完成，32768 字节 |
| 5 | `audio_rec_test 1`（重跑） | ❌ **整机挂死** |

**结论：失效是不确定的**——同一路径可能"完成"、"断言"或"挂死"。

#### DR-F001 的真机回溯（现象 3）

```
sched_dumpstack: apb_free+0xf/0x58
                 audio_freebuffer+0x7b/0xe0
                 audio_ioctl+0x169/0x3a0
                 file_vioctl / nx_vioctl / ioctl
                 audio_rec_test_main+0x5f3/0x68c
```
即"上层 `AUDIOIOC_FREEBUFFER` 释放 apb 时，驱动仍持有该 apb 的引用"——
与审查 **DR-F001（Critical）** 完全对应，**这条不再是纸面推理**。

---

### 四、PCM 数据质量（`pcm-audio` 技能分析器）

素材：`audio_rec_test 1` 的 32768 字节（16 kHz/16 bit/单声道，16384 样本）。
取得方式：`hexdump` 经串口导出后按块偏移重建，**100% 完整**（32768/32768）。

> 导出要点：NuttX `hexdump` **每 512 字节重开一块**（每块 `at XXXXXXXX:` 头 + 块内偏移从 0 起），
> 必须按块基址重建。串口**紧凑读取**（`in_waiting` + 无 sleep）才不丢字节；
> 带 sleep 的读法只抓到 78% 且有截断行。

#### 结构：只有 3 段

| 段 | 样本 | 时间 | 占比 | 内容 |
|---|---|---|---|---|
| ZERO | 0–8631 | 0–0.540 s | **52.7%** | 全精确 0 |
| DATA | 8632–9596 | 0.540–0.600 s | **5.9%** | 真实变化数据（≈**一个 60 ms 半缓冲**） |
| FFFF | 9597–16383 | 0.600–1.024 s | **41.4%** | 全精确 −1 |

#### 分析器输出

| 项 | 结果 |
|---|---|
| 削波 | 0.04%（正常） |
| **静音段** | 0.00–0.50 s，500 ms，**零值 100%** |
| **爆破音** | **25 个（24.4/秒）** |
| 底噪 SNR | 33.5 dB（偏低）；RMS 691.26；colored noise |
| **周期性失真** | **8.19 ms / 122.1 Hz**（"疑似定时器中断"） |

---

### 五、对 P1/P2 的判定

| 项 | 判定 |
|---|---|
| **P1（D-cache invalidate）** | **必要但不充分**。真实 DMA 数据**首次出现在交付缓冲里**（此前整段近零），说明 invalidate 确实在起作用；但交付缓冲 **94.1% 仍是常数**（0 与 −1），**验收口径"基线不恒零"未达成** |
| **P2a（drain 握手）** | 未能阻止 DR-F001 复现——**真机断言与回溯已证明**该不变量仍被破坏（与审查 DR-F001 的判断一致） |
| **P2b（`nxtask_delete`）** | 本次未触及该路径 |

**最可能的根因（与审查预测一致，尚需 v2 修复后复测确认）**：
交付路径的 **DR-F003（`priv->aux` 无锁竞争 → 缓冲丢失）** 与 **DR-F011（`rx_half` 电平 vs 计数 → 半缓冲重复/丢失）**，
使多数 apb 未被正确填充，客户端写入的仍是未填充内容。

---

### 六、待办

- [ ] 把板子刷回出货固件（当前跑的是带测试程序的 audio_test 镜像）
- [ ] v2 修复（DR-F001/F003/F008/DR-F011 + `.freebuffer`）后**重跑本节全部验收项**
- [ ] `docs_ble/25` 更正 2.1 节的错误根因，并补 `nxrecorder` 正确用法
- [ ] 把 `getcaps` 返回值修复纳入正式固件（它同时影响框架的 `audio_capture` 路径）
- [ ] 测试程序 `audio_rec_test` 与 `CONFIG_EXAMPLES_AUDIO_REC_TEST` 验证完即移除（或保留为回归工具）


---

## 七、ADC 路径判别实验（2026-10-10 下午）

> 目的：在 v2 重构后，用**已内置的 `nobuf` 计数器**判定"94% 常数"的病因是否在交付路径（并发模型）。
> 结论：**不在。v3 的并发/所有权重构对本体征无效** —— 该实验省下了一次大型重构。

### 7.1 判别结果：`nobuf = 0`

```
[mic-dbg] irq=20 cplt=20 wake=20 nobuf=0 max16=32768 big=408   (第 1 次)
[mic-dbg] irq=52 cplt=52 wake=53 nobuf=0 max16=1     big=0     (第 2 次)
[mic-dbg] irq=18 cplt=18 wake=18 nobuf=0 max16=1     big=0     (顺序修正后)
```

`nobuf` = "半缓冲到达时无 apb 可填而丢弃"的次数。**全部为 0**，且 `cplt == wake`
（每个 DMA 事件都被消费）→ **缓冲一只都没丢，`priv->aux` 竞争不是本体征的病因**。

### 7.2 已排除的嫌疑点

| 嫌疑 | 结论 | 依据 |
|---|---|---|
| `aux` 竞争丢缓冲 | ❌ 排除 | `nobuf = 0` |
| `HAL_Delay` 是空实现（MICBIAS/PLL 稳定等待丢失） | ❌ 排除 | `bf0_hal.c:468` 是真实忙等 `while(Delay){HAL_Delay_us(1000); Delay--;}` |
| `Config_ADCPath_Volume(0, 12)` 增益非法 | ❌ 排除 | 真实原型 `(AUDCODEC_HandleTypeDef*, int channel, int volume)`；合法范围 **−60~30**（非 58X），12 有效 |
| 隐式声明导致参数错位 | ❌ 排除 | 原型与调用 `(&codec, 0, 12)` 的 AAPCS 布局一致 |

### 7.3 确认的缺陷：DMA 在 ADC 使能之前启动（latent，非病因）

HAL 自己在 `HAL_AUDCODEC_Receive_DMA()` 里把全局使能**注释掉了**：

```c
/* Check if the AUDCODEC is already enabled */
//__HAL_AUDCODEC_ENABLE(hacodec);      ← 被注释
```

而驱动原顺序是 `Receive_DMA → Config_Analog_ADCPath → ADC_ENABLE`，
即**让 DMA 先跑、ADC 后开** —— 那段时间 DMA 搬的是未转换的 ADC 寄存器空闲值，
**真机 PCM 里成片的 `0x0000`/`0xFFFF` 常数即由此而来**。

**已改为**"先配置并使能 ADC → 等 MICBIAS/MEMS 麦稳定(600 ms) → 再启动 DMA"（`SF32LB52_AUDIO_MIC_SETTLE_US`）。

**但真机复测该修正未消除常数**（第 7.1 节后两行仍是 `max16=1`）→ 顺序是真实缺陷但**不是本体征主因**，仍保留该修正。

### 7.4 未能定位的部分（如实记录）

- **ADC 输出非确定性**：同一次上电内连续采集结果不同（第 1 次有真实数据段、第 2 次全程 ±1）；约 **1/3 的运行会让整机挂死**
- **`max16=1` 与 `peak=32767` 并存**：`max16` 只统计 `rx_buf` **前半的前 512 样本**，而应用侧每秒 peak 达满幅 → 大值可能在**另一半**，但按半缓冲拆分的验证因板子挂死未能完成
- **时钟表无法交叉验证**：驱动注释引用的 `codec_adc_clk_config_pll` **在本树内不存在**（表是从树外 SDK 抄的）。该表有前科（早前漏写 `samplerate` 首字段导致全体左移、ADC 时钟快一个量级），**是最值得继续查的嫌疑点**

### 7.5 工具局限（重要）

**`pcm-audio` 分析器无法识别常量 DC。** 对一段**全部 −1** 的数据，它输出：

```
[2] Silence Insertion  — No abnormal silence segments detected
[4] Noise Floor        — SNR: 90.3 dB (good)
[5] Periodic Distortion— none
Summary: No significant audio quality issues detected
```

常量 −1 既不算削波、也不算零值静音，五项检测全部漏掉。
**判断"有没有信号"必须先看"不同取值个数"**，不能只看零占比或分析器结论。
（本轮据此更正了一次自己的误判：曾把 84.8% 的精确 −1 误读为"真实数据"。）

### 7.6 下一步（按性价比）

1. **按半缓冲拆分看大值落在哪一半** —— 判定是否半缓冲映射错位（`rx_ready` 位与 ISR 回调的对应关系）
2. **查 `adc_clk_16k` 时钟表** —— 从树外 SDK 或芯片手册核对 `{16000,1,10,1,1,0,5,2}` 各字段
3. **单次上电内跑固定 2 次采集**并记录 `max16`，量化非确定性（当前只能定性）
4. 先解决**挂死**（约 1/3 概率）—— 否则每次实验都要人工复位，迭代成本过高



---

## 八、★ 根因确认与修复：缺失 `HAL_TURN_ON_PLL()`（2026-10-10 晚）

> 这是本项目的**转折点**。第七节把病因从"交付路径"缩小到"ADC 模拟/时钟"，
> 本节给出确定的根因、完整证据链与真机验证结果。

### 8.1 结论

驱动使用 `bf0_enable_pll()` 初始化 PLL，**但该函数不配置模拟前端的参考电压**。
必须补调 `HAL_TURN_ON_PLL()` —— 它做的是 `bf0_enable_pll()` **完全不碰**的那一半：

| 模拟前端配置 | `bf0_enable_pll()`（驱动原先唯一调用） | `HAL_TURN_ON_PLL()`（缺失） |
|---|:--:|:--:|
| `BG_CFG0.EN`（bandgap 使能） | ✅ 仅 RMW 这两位 | ✅ 整体赋值 |
| `BG_CFG0.EN_SMPL` | ✅ | ✅ |
| **`BG_CFG0.VREF_SEL = 0xc`（3.3V，ADC 参考）** | ❌ **不设** | ✅ |
| **`BG_CFG0.MIC_VREF_SEL = 4`（麦克风参考）** | ❌ **不设** | ✅ |
| **`BG_CFG0.EN_RCFLT / EN_AMP`** | ❌ **不设** | ✅ |
| **`HAL_AUCODEC_Refgen_Init()`（REFGEN 参考发生器）** | ❌ **不调用** | ✅ |
| PLL 校准 | ✅（含 VCO 自动校准） | ✅（固定配置） |

**没有参考电压，ADC 就转换不出有效值**，只能输出 `0x0000` 或 `0xFFFF` ——
正是实测 PCM 里那两种**精确常量**。

### 8.2 证据链（每一步都可独立复核）

| # | 证据 | 位置 |
|---|---|---|
| 1 | `nobuf = 0` → **不是** `aux` 竞争丢缓冲 | 第 7.1 节 |
| 2 | `HAL_Delay` 是真实忙等 → **不是**稳定等待丢失 | `bf0_hal.c:468` |
| 3 | `Config_ADCPath_Volume(0,12)` 合法（范围 −60~30） | `bf0_hal_audcodec_m.c:1023` |
| 4 | **官方文档的 RX 标准序列含 `HAL_AUCODEC_Refgen_Init()`** | [SiFli AUDCODEC HAL 文档](http://docs.sifli.com/projects/sdk/latest/en/sf32lb52x/hal/audcodec.html) |
| 5 | `HAL_TURN_ON_PLL()` 以 `HAL_AUCODEC_Refgen_Init()` 结尾 | `bf0_hal_audcodec_m.c:429-485` |
| 6 | `HAL_AUCODEC_Refgen_Init()` 置 `REFGEN_CFG \|= EN` | `bf0_hal_audcodec_m.c:512-534` |
| 7 | `bf0_enable_pll()` 只 RMW `BG_CFG0` 两位、无 REFGEN | `bf0_hal_audcodec_m.c:125-215` |
| 8 | **驱动中 `Refgen` / `HAL_TURN_ON_PLL` 调用次数 = 0** | `grep` 全驱动 |

### 8.3 真机验证结果（同一板子、同一测试程序）

| 指标 | 修复前 | **修复后** |
|---|---:|---:|
| **不同取值个数** | **1 ~ 2 个** | **430 / 841 个** |
| 精确零占比 | 80.9% | **0.7% / 0.4%** |
| 精确 −1 占比 | 41.4% | **0.6% / 0.4%** |
| **真实变化数据** | 19.1%（且非确定） | **98.8% / 99.2%** |
| `[mic-dbg] max16` | 1（偶发 32768） | **153 / 342** |
| `[mic-raw]` 前 8 字 | `ffff ffff ffff ffff…` | **`ff91 ffa7 ff74 ff8d ffba ffa5 ffc3 ffc9`** |
| `peak` / `avg` | 32767 / 7265（饱和） | **265/49、615/91** |
| 两次采集一致性 | 非确定 | **两次均正常** ✅ |

`[mic-raw]` 由全 `ffff` 变为小幅度抖动值，是**麦克风底噪**的真实形态。
**验收口径"无人说话时基线不恒为常数"—— 达成。**

### 8.4 为什么四轮代码审查都没找到

**这是一个"缺失调用"，不是一段错误代码。**

四轮审查（`driver-code-reviewer` 双轮 + 裁判）逐行读了 979 行驱动、交叉核对了
`audio.c`/`queue.h`/`cache.h`/`lib_buffer.c`/`nxrecorder.c` 等树内参考，
发现并量化了 30+ 个缺陷 —— 但**没有任何一轮能够发现"少调了一个函数"**，因为：

- 审查对象是**已存在的代码**；缺失的东西不在阅读范围内
- `HAL_TURN_ON_PLL()` 虽在 `bf0_hal_audcodec.h:544` 公开声明，但
  **全树没有任何地方调用它**（厂商 RT-Thread 驱动 `drv_audcodec.c` 不在本树）
  → 审查者没有"正确用法"的参照物
- 驱动注释里写着"参考 SiFli SDK `codec_adc_clk_config_pll[3]`"，
  而**那张表本树也不存在** —— 同样无法核对

**教训：从厂商 SDK 移植的驱动，审查必须拿到 SDK 的"参考调用序列"，
而不只是被审代码本身。** 否则所有审查只能验证"代码自身是否自洽"，
无法验证"是否漏了必要步骤"。

### 8.5 方法论：树内缺参考资料时，去厂商文档/社区找

**本次破局来自用户的提议："上网搜索相关的资料，说不定有呢？"**

找到的关键资料：

| 资料 | 价值 |
|---|---|
| [SiFli AUDCODEC HAL 文档](http://docs.sifli.com/projects/sdk/latest/en/sf32lb52x/hal/audcodec.html) | **给出 RX 标准序列，含 `Refgen_Init()`** ← 破局点 |
| [Audprc 示例](http://docs.sifli.com/projects/sdk/v2.4/sf32lb52x/example/rt_device/audprc/README.html) | 完整录音/播放/Loopback 用法与串口日志 |
| [Mic 增益调试](https://docs.sifli.com/projects/sdk/latest/sf32lb57x/audio/mic.html) | `g_adc_volume`、`ROUGH_VOL` 步长 6dB（默认 0xc）、饱和处理 |

**这条经验应回补到 `nuttx-driver-development` 技能**：现有指引是
"优先用 Linux/Zephyr 同芯片驱动交叉验证"，应扩展为
**"从厂商 HAL 移植的驱动，必须拿到厂商的参考调用序列（SDK 文档/示例/GitHub），
树内找不到就上网找"**。

### 8.6 修复内容

```c
/* start_capture: 在 bf0_enable_pll() 之后补 */
bf0_enable_pll(priv->samprate, 1);
HAL_TURN_ON_PLL();          /* ← 补上 VREF/MIC_VREF/EN_AMP/REFGEN */

/* stop_capture: 对称关闭 (放在 DMA/中断都停之后) */
HAL_AUDCODEC_DMAStop(&priv->codec, HAL_AUDCODEC_ADC_CH0);
HAL_AUDCODEC_Close_Analog_ADCPath();
HAL_TURN_OFF_PLL();         /* ← 与 HAL_TURN_ON_PLL 对称 */
```

`AVDD_V18_ENABLE` 在本树未定义 → `VREF_SEL` 走 `#else` 分支取 `0xc`（3.3V），与板子供电一致。

### 8.7 仍然存在的问题：约 1/3 概率挂死

音质问题解决后，**挂死成为唯一拦路虎**：连续第 3 次采集必挂（修复前后均如此，
与 PLL 改动无关，是既有问题）。

**这恰好闭合了推理链**：

- 第 7 节的判别实验说"**v3 的并发重构对音质无效**" → **正确**（`nobuf=0`）
- 音质修好后，剩下的**挂死**正是四轮审查反复指出的 **apb 所有权 UAF 家族**
  （DR-J01 / DR-J03、DR-201 / DR-203）

**所以 v3 现在真的需要了 —— 但目标是消灭挂死，不是修音质。**
这是四轮审查结论第一次获得**实测证实的、明确的落地目标**。

