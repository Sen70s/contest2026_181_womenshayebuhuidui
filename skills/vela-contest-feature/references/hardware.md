# 硬件速查（SF32LB52-DevKit-LCD / 小云手表）

## 引脚占用（2026-10 实测）

| 引脚 | 用途 | 能不能借 | 依据 |
|---|---|---|---|
| PA00 | `#LCD_RESETB`（`#` = 未连接） | 空闲但按策略不用 | `bsp_pinmux.c:156` |
| **PA01** | **背光控制** `GPTIM1_CH4` | **绝对禁止** | 见下方「PA01 禁用法则」 |
| PA02-PA08 | LCDC QSPI | 否 | `CONFIG_BSP_LCDC_USING_QADSPI=y` |
| PA10 | AUDIO_PA_CTRL（功放使能） | 否 | `bsp_pinmux.c:157` |
| PA12-PA17 | PSRAM / MPI | 否 | |
| PA18 | UART1 调试串口 | 否 | |
| PA30 / PA33 | **I2C1** 触摸 FT6146（0x38） | 否 | `bsp_pinmux.c:234-235` |
| PA31 | 触摸中断 | 否 | `CONFIG_TOUCH_IRQ_PIN=31` |
| PA32 | LED | 否 | |
| PA35 / PA36 | USB | 否 | |
| PA37 / PA38 | GPIO（未用） | 可以 | |
| **PA39 / PA40** | **I2C3 -> MPU6050** | 已占用 | 见下 |
| **PA41 / PA42** | **I2C2** MAX30102（0x57） | 否，**已焊死** | `max30102.c:205-206` 运行时 pinmux |
| PA43 | GPIO（未用） | 可以 | |
| PA44 | VBUS 检测 | 否 | |

### PA39/PA40 为什么可用

`bsp_pinmux.c` 里把它们写成 `LCDC1_8080_DIO3/4` 的那几行，在
`#elif defined(BSP_LCDC_USING_DBI)` 分支里。本板用 QADSPI，**那几行根本不编译**。

而且官方 I2C 应用笔记的示例用的就是这一对。

### PA01 禁用法则

```c
/* bsp_pinmux.c:242 */
HAL_PIN_Set(PAD_PA01, GPTIM1_CH4, PIN_NOPULL, 1);   // LCD backlight PWM
```

- `CONFIG_BSP_USING_PWM2=y` -> PWM 驱动被注册
- **但没有任何代码 `pwm_open()` 它** -> 定时器未启动 -> **PA01 停在静态低电平**
- **背光是低电平使能**

**后果**：任何把 PA01 拉高的东西（I2C 通信、传感器的内部上拉）都会**关掉背光**。

**实测**：往 PA01 发 I2C 后，`lcd_flush` 从约 2.4 次/秒 掉到几十分钟一次；撤销后立即恢复。

---

## I2C 总线

| 设备节点 | 实例 | 引脚 | 已知设备 |
|---|---|---|---|
| `/dev/i2c0` | I2C1 | PA30/PA33（开机由 `BSP_PIN_Touch` 配置） | FT6146 触摸 **0x38** |
| `/dev/i2c1` | I2C2 | PA41/PA42（**由 `max30102.c` 运行时配置**） | MAX30102 **0x57** |
| `/dev/i2c2` | I2C3 | **PA39/PA40**（本仓 `sifli_ap.c` 配置） | MPU6050/6500 **0x68** |

### 两条硬约束

**一、每条 I2C 实例只有一个 `xxx_PINR` 寄存器，只选一对 pad。**
抢别人的实例 = 把那个设备踢下线。要给新外设分配空闲实例。

**二、内部上拉是弱上拉 —— 只有内部上拉时速度必须小于等于 100 kHz。**

> 官方 SiFli I2C 应用笔记：
> 「内部上拉属于弱上拉，当 I2C 只使用内部上拉时，工作速度建议保持在 100k」
> 400Kbps 推荐 2.2k；100k/200k 可用 4.7k~10k

本板 IMU 总线**没有外部上拉电阻**，所以 `CONFIG_MPU60X0_I2C_FREQ=50000`。
设 400 kHz 时**完全扫不到设备**。

### SiFli I2C 驱动只支持一种组合传输

`vendor/sifli/chips/sf32lb52/sifli_i2c.c:492-519`：**只在第二条消息带 `I2C_M_NOSTART` 时**
才把寄存器地址发出去。NuttX 规范的组合读（msg0=NOSTOP + msg1=READ）会**丢掉寄存器地址**。

=> 读寄存器要用**分两次传输**：先只写寄存器地址（带 STOP），再单独发读。

**推论**：`i2ctool` 和任何用组合传输的用户态探测代码在这块板子上都**不可靠**。

---

## 音频链路

```
pcm_file_player / pcm_mem_play
  -> media_player_write_data  (96KB 环形缓冲，满了丢最旧)
  -> pcm_media_player 的 worker 线程
  -> /dev/audio/pcm0p
  -> sf32lb52_audio_tx.c (DMA + AUDCODEC + tx_pa(1))
```

### 关键约束

| 项 | 值 |
|---|---|
| 唯一可用格式 | **16 kHz / 单声道 / s16le** |
| MP3 | **无解码器**，放不了 |
| `CONFIG_MEDIA` | 关闭 -> `frameworks/multimedia/media` 不编译 |
| `media_player_*` | 由 `packages/ai_agent/src/voice/pcm_media_player.c` 提供（兼容层） |
| 音量单位 | 0.5 dB，范围 -36..54，**100% 必须是 0 dB**（54 是 +27 dB 削波） |

### 音频上半部是设备级共享

`audio.c` 只有一个 `upper->lock` 和一个状态机。**第二个客户端 prepare 会打断正在播的流。**

实测症状：音乐播放中途冒出 `[pcm_media] prepared: in=24000Hz out=16000Hz`（TTS 的采样率），
然后音乐断掉。修法是**音乐播放时不产生主动关怀**。

### 暂停不要动设备

`audio.c` 处理 `AUDIOIOC_STOP` 后**无条件**把 `priv->state` 置 OPEN；
驱动的 `tx_start()` 开头是 `if (priv->running) return OK;` —— **静默跳过**。

=> 停设备再启动，**数据照灌但 DMA 不跑**，表现为「恢复后没声音」。

**正确做法：暂停时喂静音块，全程不改设备状态。**

---

## 屏幕

| 项 | 值 |
|---|---|
| 面板 | CO5300，QSPI 接口 |
| 分辨率 | 390 x 450 |
| 亮度 | DCS `0x51`（`LCD_SetBrightness`，0-100 百分比） |
| NuttX 侧 | `ioctl(/dev/lcd0, LCDDEVIO_SETCONTRAST, 0..63)` |
| 息屏 | `LCDDEVIO_SETPOWER` 0/1 |

LVGL 配置：**`LV_USE_BAR/SLIDER/ARC/CHART` 全部关闭**，只有 OBJ/LABEL/IMAGE/BUTTON/FLEX/GRID。

---

## 文件系统

| 挂载点 | 介质 | 大小 |
|---|---|---|
| `/data` | littlefs on NOR | 4 MB |
| `/mnt/tf` | TF 卡（vfat） | 视卡而定 |

**littlefs 上读大文件可能失败**：960KB 的 PCM 读到 2.4 秒处 `read()` 返回 -1。
=> 演示曲改成从固件 const 数组直接播（`pcm_mem_play`）。
