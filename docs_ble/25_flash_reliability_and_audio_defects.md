# 25. 烧录可靠性复盘 + 音频驱动两处缺陷

> 日期：2026-10-10 ｜ 板卡：SF32LB52-DevKit-LCD
> 场景：为验证板载麦克风采集而重烧 `audio_test` 精简镜像，验证完成后要把正式固件刷回，
> 期间把烧录失败的坑踩满了一轮；音频驱动另有两处缺陷。
> 阅读约定：第 1 节是**可操作的排查表**，建议并入 [dev_skill_bt_pan.md](../docs/dev_skill_bt_pan.md) 规则 4；
> 第 2 节是缺陷记录，修完请移到 [24_open_issues.md](24_open_issues.md) 对应章节并标注去向。

---

## 一、烧录：失败签名 → 先查什么

### 1.1 机制（官方文档口径）

[SF32 系列串口自动下载设计](https://wiki.sifli.com/hardware/SF32-auto-download.html)：

> **52 系列没有预留出 RST/EN 引脚**，因此复位需要关断芯片电源（VCC/PVDD），
> 一般需要单独外接一个负载开关用于控制芯片电源。
> 52 系列在启动的时候会**停留在 bootrom 中大约 2000ms**，在这段时间内可以使用 uart debug 进入下载模式。
> sftool 在下载的时候会通过 **RTS** 进行复位，因此设计时只需要将 RTS 引脚连接到负载开关的使能引脚上即可。

推论：**烧录成功的充要条件是「sftool 的同步帧落在芯片上电后的 ~2 s 窗口内」**。
本机 CH343 桥的 RTS 不能可靠控制电源（见 `dev_skill_bt_pan.md` 规则 4），
所以「靠 RTS 自动复位」这条路成功率天然不稳。

### 1.2 失败签名对照表（本次实测）

| 报错 | 真实含义 | 判定依据 |
|------|---------|---------|
| `Failed to download stub: Io(Custom { kind: TimedOut })` | **没进 ROM 窗口** | 串口里能看到 NSH 把协议帧当命令：`nsh: ~ATSF32!~@r...: command not found` |
| `Failed to download stub: Timeout("waiting for shell prompt")` | 同上；sftool 在等 stub 的 shell 提示符 | 同上 |
| `timeout while waiting for RAM command response` | sync 貌似建立（实为 NuttX 控制台回显），RAM 命令无响应 | 仍未真正进入 bootrom |
| `Failed to set baud rate to NNNN ... timeout while waiting for shell prompt` | stub 已写入 RAM 但未启动（`BKP0R=0xA640` / `PA21` 点火序列未生效） | 降速 + `--compat true` 时出现 |

> **确定性证据**：串口里出现 `nsh: ~ATSF32!...: command not found` 就说明
> sftool 正在跟**运行中的 NuttX 控制台**对话，而不是 ROM bootloader —— 这时不用再试，直接去制造复位。

### 1.3 三种策略的实测结果

| 策略 | 命令要点 | 本次结果 |
|------|---------|---------|
| RTS 自动复位 | `--before default_reset --connect-attempts 40` | ❌ 连续 10+ 次失败（历史上有过 3 次成功，属概率事件） |
| 不复位直接同步 | `--before no_reset_no_sync`（含 `460800 + --compat true`、`--no-compress`） | ❌ 稳定报 RAM command timeout |
| **循环重试 + 人工按 RESET** | 循环交替 `default_reset`/`no_reset_no_sync`（各 3 次连接尝试），操作者每 2–3 s 按一次 RESET | ✅ **本次刷回成功** |
| 仓库既有流程 | `docs_ble/tools/flash_rts.py`（后台 sftool + 手工 RTS 断电 500 ms 再上电） | 未复跑，但机制上正是 1.1 节所述正解 |

### 1.4 恢复流程（推荐）

```bash
# 0) 确认端口空闲（有输出说明被别的进程占着）
fuser /dev/ttyACM0

# 1) 确认板子是否还活着：打开串口必须 rts=False, dtr=False（否则等于给板子断电）
#    发一个换行，能看到 "nsh>" 就说明固件完好，只是没进 bootrom

# 2) 挂循环重试（两种 before 模式交替），同时人工按 RESET
#    成功标志：进度条走到 100% 且输出中没有 Error / panicked
```

补充经验：

- **烧录失败通常不会破坏已烧固件**。只要进度条还没进入写入阶段，失败后板子照样能启动到 `nsh>`；
  本次失败十余次后固件仍完好，最后也成功覆盖。
- 不要连续盲试。先按 1.2 节判读报错属于哪一类，再决定「继续重试」还是「去制造复位」。
- RESET 按钮**有效**：按下后串口立即输出完整启动序列
  （`SFBL` → ADC 校准 → `bluetoothd` → `ai_agent` → `NuttShell`），可用于制造 bootrom 窗口。
  注意本板 RESET 是软复位（RAM 与 `CLOCK_MONOTONIC` 保留），
  **测需要冷启动状态的功能仍要用烧录后的全新上电**（与规则 5 一致）。

---

## 二、音频驱动缺陷

### 25.1 STOP 的 drain 完成握手缺失 → apb 释放后使用（UAF）（P0）

> 状态：**已定位机制，修法待真机回归**。采集主路径不受影响（数据完整落盘）。

**现象**　录音结束后的释放循环触发断言：

```
dump_assert_info: Assertion failed : at file: /include/nuttx/semaphore.h:523 task: audio_rec_test
sched_dumpstack: [12] [<0x1209ef00>] audio_rec_test_main+0x5f3/0x68c   ← FREEBUFFER 循环
up_dump_register: R4: de820000 R8: dea69f00                          ← 0xde… = NuttX 释放内存毒化模式
```

**机制**　`semaphore.h:523` 正是 `nxmutex_wait()` 里的 `DEBUGASSERT(NXSEM_IS_MUTEX(sem))`。
而 `apb_free()`（`nuttx/libs/libc/audio/lib_buffer.c:114`）的写法是：

```c
void apb_free(FAR struct ap_buffer_s *apb)
{
  nxmutex_lock(&apb->lock);      /* ← 第二次释放时锁已被 destroy，断言在这里炸 */
  int refcount = apb->crefs--;
  nxmutex_unlock(&apb->lock);

  if (refcount <= 1)
    {
      nxmutex_destroy(&apb->lock);
      lib_ufree(apb->samp);
      lib_ufree(apb);
    }
}
```

**更正（2026-10-10，经 `driver-code-reviewer` 技能双轮独立审查）**　本节初版把机制定性为
"同一个 apb 被 `apb_free()` 了两次"。两轮审查（Round 1 `DR-001` / Round 2 `DR-B001`）交叉验证后确认：
**驱动从不调用 `apb_reference()`**（全文件 grep 0 命中），apb 的 `crefs` 恒为 1、归上层所有，
因此**不存在驱动侧的二次释放**。真实机制是**释放后使用（UAF）**：

`sf32lb52_audio_stop_capture()` 用 `priv->drain` 标志与 worker 握手（`:431-437`），
但 worker **先清标志、后干活**（`:262-263`）：

```c
if (priv->drain)
  {
    priv->drain = false;                  /* 262: 先清标志 */
    sf32lb52_audio_drain_pending(priv);   /* 263: 后才真正归还 */
  }
```

于是 STOP 的轮询循环（`:434-437`）在 worker 刚开始归还时就满足退出条件 →
**STOP 在 drain 完成前返回** → 上层随即 `FREEBUFFER` + `close()`，而 worker 仍在
`upper(AUDIO_CALLBACK_DEQUEUE)` 里写 `apb->flags |= AUDIO_APB_DEQUEUED`（`audio.c:1529`）
与 `upper->status->tail++`（`audio.c:1504`）——两者都可能落在已释放对象上。

`apb_free()` 的 `nxmutex_lock(&apb->lock)`（`lib_buffer.c:118`）之所以在
`semaphore.h:523` 触发 `DEBUGASSERT(NXSEM_IS_MUTEX)`，正是因为该 apb 已被释放、
其 `lock` 已被 `nxmutex_destroy()` 过，同一块内存上再做 `nxmutex_lock` 必然失败。

**同一根因的其它触发窗口**（Round 2 `DR-B002`）：drain 超时分支（`:439-448`）假定
"worker 已死"并直接 `dq_init(&priv->pendq); priv->aux = NULL;`，但 worker 的
`apb` 是**栈上局部变量**（`:181/208`），队列被清不影响它；worker 恢复后仍会
`memcpy` 进已被上层释放的缓冲。

**结构性成因（读码可证，三处叠加）**

1. 驱动 ops 表（`app/ai_agent/audio/sf32lb52_audcodec.c:121-133`）**没有 `.freebuffer` 成员**。
   于是核心 `nuttx/audio/audio.c:741-751` 直接走 else 分支 `apb_free(bufdesc->u.buffer)`——
   驱动**没有机会**在释放前把 `pendq`/`aux` 里的引用摘掉。
2. `sf32lb52_audio_drain_pending()`（同文件 `:234-247`）停止采集时把 `pendq` 里的 apb 逐个经
   `AUDIO_CALLBACK_DEQUEUE` 归还上层，但结尾 `priv->aux = NULL;`（`:247`）
   **把"正在填充"的那一只直接丢弃、没有归还**。此时驱动与上层对这只 apb 的归属认知已经分叉。
3. 停止采集是**异步**的（worker 线程）。上层收到 `AUDIOIOC_STOP` 返回就立刻 `FREEBUFFER` 全部缓冲，
   而 worker 可能仍在 `dev.upper(...)` 里触碰同一批 apb。

**建议修法（未应用，待真机回归后再合）**

```c
/* 新增: 释放前先从驱动内部队列摘除, 避免悬垂引用 */
static int sf32lb52_audio_freebuffer(FAR struct audio_lowerhalf_s *dev,
                                     FAR struct audio_buf_desc_s *bufdesc)
{
  FAR struct sf32lb52_audio_s *priv = (FAR struct sf32lb52_audio_s *)dev;
  FAR struct ap_buffer_s *apb = bufdesc->u.buffer;

  nxmutex_lock(&priv->pendlock);
  if (priv->aux == apb)
    {
      priv->aux = NULL;
    }
  else if (apb->dq_entry.flink != NULL)
    {
      dq_rem(&apb->dq_entry, &priv->pendq);
    }
  nxmutex_unlock(&priv->pendlock);

  apb_free(apb);
  return sizeof(struct audio_buf_desc_s);
}
```

ops 表补一行 `.freebuffer = sf32lb52_audio_freebuffer,`；
同时让 `drain_pending()` 先归还 `aux` 再清空：

```c
  if (priv->aux != NULL)
    {
      priv->dev.upper(priv->dev.priv, AUDIO_CALLBACK_DEQUEUE, priv->aux, OK);
      priv->aux = NULL;
    }
```

**应用侧缓解**（不改驱动也能让一次性测试干净退出）：
`AUDIOIOC_STOP` 后**先把 mq 取空**、再 `AUDIOIOC_RELEASE`，最后才 `FREEBUFFER`；
或释放前先短暂 `sleep` 等 worker 停稳。

**影响面**　仅限「停止采集 + 释放缓冲」的收尾路径。**采集数据本身完整**：
实测 2 s 录到 65536 B，全部落盘可读。

### 25.2 `getcaps` 采样率字段截断（P2，编译告警）

**现象**　编译期告警（每次构建都出现）：

```
sf32lb52_audcodec.c:522:37: warning: unsigned conversion from 'int' to 'uint8_t'
    changes value from '16000' to '128' [-Woverflow]
sf32lb52_audcodec.c:523:40: warning: ... changes value from '48000' to '128'
```

**机制**　`ac_controls` 是联合体（`nuttx/include/nuttx/audio/audio.h:722-728`）：
`uint8_t b[4]` / `uint16_t hw[2]` / `uint32_t w`。
NuttX 的约定是**采样率放 `hw[0]`**（高位再补 `b[3]`）、**位深放 `b[2]`**——
`nxrecorder` 的 CONFIGURE 就是这么填的（`apps/system/nxrecorder/nxrecorder.c:1189-1191`）：

```c
cap_desc.caps.ac_controls.hw[0] = samprate ? samprate : 48000;
cap_desc.caps.ac_controls.b[3]  = samprate >> 16;
cap_desc.caps.ac_controls.b[2]  = bpsamp ? bpsamp : 16;
```

驱动在 `AUDIO_TYPE_INPUT + AUDIO_FMT_PCM` 分支（`:522-523`）往 `b[0]`/`b[1]` 写 16000/48000，
三重问题：**字段用错**（落进低字节）、**与 `b[2]` 的位深语义冲突**、**数值被截断成 128**。

**修法**（已随本次提交应用）

```c
            case AUDIO_FMT_PCM:
              /* 采样率范围: NuttX 约定放 hw[0]/hw[1] (16 bit) */
              caps->ac_controls.hw[0] = SF32LB52_AUDIO_DEFAULT_RATE;
              caps->ac_controls.hw[1] = 48000;
              break;
```

**影响面**　树内**没有消费者**读这个分支：`grep -rn 'AUDIO_FMT_PCM:' nuttx/audio/` 只命中本驱动；
`nxrecorder` 走的是 `ac_format.hw` 位图 + `FMT_OTHER` 路径（`nxrecorder.c:204-225`），
因此这是**潜伏缺陷**——修它主要是消除每次构建的告警，并在将来接入
`nxplayer`/`nxrecorder` 变体或第三方工具时不踩。

### 25.3 三处隐式声明告警（P2，顺带发现）

> 状态：**仅记录，未修**。三处都不影响当前功能，但都是「编译器不知道原型」的隐患。

修掉 25.2 后重编该文件，剩下的告警只有三条（实测 `ninja` 全量日志 `grep -c overflow` = 0）：

```
sf32lb52_audcodec.c:306:3: warning: implicit declaration of function 'HAL_AUDCODEC_Config_ADCPath_Volume'
sf32lb52_audcodec.c:318:3: warning: implicit declaration of function 'up_enable_irq'
sf32lb52_audcodec.c:381:3: warning: implicit declaration of function 'up_disable_irq'
```

| 位置 | 成因 | 修法 |
|------|------|------|
| `:318` / `:381` | `up_enable_irq()`/`up_disable_irq()` 声明在 `nuttx/include/nuttx/arch.h:1848`，驱动只 include 了 `nuttx/irq.h` | 补 `#include <nuttx/arch.h>` |
| `:306` | `HAL_AUDCODEC_Config_ADCPath_Volume()` 在 HAL 里**确实存在**（`vendor/sifli/chips/drivers/hal/bf0_hal_audcodec_m.c:1023`），但 `drivers/Include/` 下**任何头文件都没有它的原型** | 在 `app/ai_agent/audio/sf32lb52_audcodec.h` 里补一条 `extern HAL_StatusTypeDef` 原型 |

**风险**　隐式声明让编译器按 `int f()` + 默认实参提升来处理调用。AAPCS 下这几处恰好能工作，
但属于 UB；一旦原型与调用约定不符（例如将来 HAL 改签名）就会静默出错而不是编译报错。

### 25.4 DMA 采集缓冲缺 D-cache invalidate（P0，可能导致采到陈旧数据）

> 状态：**已定位，未修**。这条最可能解释真机「基线全零」的现象，优先级高于 25.1。

**现象**　真机录音的前段是 ADC 使能瞬态（`ff ff …`），**之后整段是 `00 00 …`**。
团队开发记录 13 第 7 条把"基线全零"当作正常底噪，但它也可能是**读到陈旧 cache 行**。

**证据链（三条独立事实）**

1. **D-cache 是开着的** —— 三个构建的 `.config`（含出货固件 `out/nuttx_contest_board_ai_agent/.config`）全部有：
   `CONFIG_ARMV8M_DCACHE=y` + `CONFIG_ARCH_DCACHE=y`。
2. **驱动一处 cache 维护都没有** —— `grep -c 'invalidate_dcache\|clean_dcache\|up_flush_dcache'` 对本驱动 = **0**。
3. **同芯片其它 DMA 读路径全都做了** —— `sf32lb52_uart.c:464/836`、`sf32lb52_bt_adapter.c:165/336/474/613`
   都在读 DMA 缓冲前 `up_invalidate_dcache()`。

**机制**　`rx_buf`（`sf32lb52_audcodec.c:71`）是静态结构体成员，`register` 时被
`memset(priv, 0, sizeof(*priv))` 写过 → 对应 cache 行**驻留且脏（内容是 0）**。
DMA 直写 RAM 绕过 D-cache，worker 随后 `memcpy` 读 `rx_buf` 时命中**陈旧的全零行**，
而不是 DMA 写入的采样。脏行被驱逐时还会反向覆盖 DMA 刚写的新数据。

**修法**　worker 在读取每个半缓冲前 invalidate 对应区间（与 uart/bt 驱动同款）：

```c
up_invalidate_dcache((uintptr_t)half, (uintptr_t)half + SF32LB52_RX_HALF);
/*                                  ^ 第二参是 **end 地址**，不是长度 */
```

同时把 `rx_buf` 的对齐从 `aligned(4)` 提到 **`aligned(32)`**（必须是**编译期常量**——
静态数组的 `aligned` 属性不能用运行时的 `up_get_dcache_linesize()`。32 的旁证：同芯片
`vendor/sifli/chips/sf32lb52/sifli_uart.c:59` 定义 `SIFLI_UART_DMA_CACHE_ALIGN 32`、
CMSIS `cachel1_armv7.h:46` 亦硬编码 32）。

> **2026-10-10 复核修正**：本节初版的机理描述有两处错误，已改。① 说"会连带丢弃同一行内的
> `rx_half/dbg_*` 等脏标志"——错的，见下；② 建议对齐到 `up_get_dcache_linesize()`——静态数组做不到。

**为什么必须对齐（修正后的机理）**
`nuttx/arch/arm/src/armv8-m/arm_cache.c` 的 `up_invalidate_dcache()` 对**非对齐起点向下取整**、
对**非对齐终点向上取整**，且**这两条边界残行用的是 `DCCIMVAC`（clean + invalidate）**，
只有中间整行才用 `DCIMVAC`（纯 invalidate）：

```c
if ((start & (ssize - 1)) != 0)
  { start &= ~(ssize - 1); putreg32(start, NVIC_DCCIMVAC); start += ssize; }  /* 边界: clean+invalidate */
while (start + ssize <= end)
  { putreg32(start, NVIC_DCIMVAC); start += ssize; }                          /* 内部: 纯 invalidate */
if (start < end)
  { putreg32(start, NVIC_DCCIMVAC); }                                         /* 尾部: clean+invalidate */
```

所以危害**不是**"丢弃脏标志"（初版写错：`DCCIMVAC` 会**先写回**），而是**边界残行被 clean**，
把 CPU 侧遗留的陈旧副本（`register` 时 `memset` 的 0）**写回** DMA 刚覆盖的采样，
并溢出到请求的半缓冲之外（第二半的起点回退还会碰到第一半尾部）。

**`aligned(32)` 之后两个半缓冲都整行对齐 → 两条边界分支都不触发 → 只剩纯 `DCIMVAC`，危害消失。**
（`rx_half/dbg_*` 在 `rx_buf` **之后**，起点向下取整碰不到它们——初版这句也是错的。）

参照 `nuttx/libs/libc/audio/lib_buffer.c:72` 的处理（那里用 `MAX(up_get_dcache_linesize(), 1)`
是因为走**运行时堆分配**，与静态数组场景不同）。

**验证口径**　修完后重录：无人说话时基线应**不是恒零**（真实底噪是几个 LSB 的随机抖动）。
若仍是恒零，说明还叠加了 MIC 增益/通路问题（`HAL_AUDCODEC_Config_ADCPath_Volume` 的 12 是经验值）。

---

## 三、复现与验证入口

- 麦克风验证镜像：`out/audio_test`（defconfig 见 `app/ai_agent/tools/audio_test_defconfig`，
  与 `vendor/sifli/boards/sf32lb52/sf32lb52_devkit_lcd/configs/audio_test/defconfig` 逐字节一致）
- 参考实现（已验证的完整 AUDIOIOC 序列）：
  `app/ai_agent/speech/mic_capture.c` ——
  `open(O_RDWR) → RESERVE → CONFIGURE(16k/16bit/1ch) → mq_open(O_RDWR) → REGISTERMQ
   → GETBUFFERINFO → ALLOCBUFFER×8 → ENQUEUEBUFFER×8 → START`，
  之后靠 `mq_receive(AUDIO_MSG_DEQUEUE)` 取 `msg.u.ptr` 并归还
- **`nxrecorder` 的正确用法**（2026-10-10 真机更正，见 [28](28_audio_mic_verification.md)）：
  ```
  nxrecorder                     # 交互模式（命令行参数模式会直接掉进交互，args 不生效）
  device /dev/audio/audio0       # ← 必须先指定设备
  recordraw /data/x.pcm 1 16 16000
  stop
  q
  ```
  `nxrecorder_opendevice()` 里 `if (precorder->device[0] != '\0')` 是**唯一路径**（无扫描循环），
  不先 `device` 必报 `-ENODEV`。
- **实测结论（2026-10-10 更正）**：16 kHz/16 bit/单声道 PCM **能采到数据，但交付路径仍有缺陷**。
  初版这里写"随后基线归零"，是把**读到陈旧 cache 行**当成了正常底噪——经 `pcm-audio` 分析器判定，
  实际是 **52.7% 精确 0 + 41.4% 精确 −1 + 仅 5.9% 变化数据**（≈一个 60 ms DMA 半缓冲），
  另有 500 ms 全零静音段、25 个/秒爆破音、SNR 33.5 dB、122 Hz 周期性失真。
  `sec 0 peak=32767` 是 ADC 使能瞬态（与开发记录 13 第 7 条一致）。
  完整证据与"验收口径未达成"的判定见 **[28](28_audio_mic_verification.md)**

## 四、待办

- [ ] **25.4 的 `up_invalidate_dcache` + cache line 对齐：优先级最高**，很可能解释真机基线全零
- [ ] 25.1 的 drain 完成握手（改为可等待事件）+ 归还 `aux` + 删除 `nxtask_delete` 复活逻辑
- [ ] 25.1 附带：`enqueuebuffer` 加 `apb_reference()`，或实现 `.freebuffer`（注意是 UAF 不是双重释放）
- [x] 25.2 的 `hw[0]/hw[1]` 修改：已随本页提交应用（重编验证：`-Woverflow` 归零）
- [ ] 25.3 的三处隐式声明：补 `<nuttx/arch.h>` 与 `HAL_AUDCODEC_Config_ADCPath_Volume` 原型
- [ ] 把 1.2/1.4 节并入 `docs/dev_skill_bt_pan.md` 规则 4 与 `docs_ble/22_pan_engineering_guide.md`
