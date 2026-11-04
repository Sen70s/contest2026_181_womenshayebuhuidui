# 24. 遗留问题清单

> 日期：2026-08-22（原 08-21，Round 15 更新）
> 状态：PAN 上网主线已完成并回归通过（见 21 号文）。P0 已在 Round 15 修掉（构建层面
> 已验证，真机掉电回归待做）。这里记录**明确知道、但有意暂不做**的事，含各自的判断
> 依据和动手时的入口。
> 维护约定：解决一项就把它移到对应的复盘文档并在这里标注去向，不要直接删。

## P0 — 已修复（2026-08-22）

### 24.1 XIP 下 NOR 复位窗口未 RAM 驻留 ✅ 已修

> 修复见 `LOG.md` Round 15。本节保留原始症状与更正后的机制，方便回溯。
> **注意**：8-21 版本的机制描述有误（写成「HAL 那几个函数仍在 XIP flash 里」），
> 实际不是——`bf0_hal_mpi.c` / `bf0_hal_mpi_ex.c` 整个 TU 早已在 `.ramfunc`。
> 真正的漏洞只有一处，见下。

**现象**　非正常掉电后，下次开机偶发在
`nx_mount("/dev/config0", "/data", "littlefs")` 里 HardFault，任务 `AppBringUp`，
板子起不来：

```
INFO: NOR MTD registered at /dev/config0 (offset=2464 blocks=1024)
dump_assert_info: Assertion failed panic: at arm_hardfault.c:186 task: AppBringUp
sched_dumpstack: [ 3] [<0x1203ca06>] sf32lb_flash_unlock+0x1/0x20
```

**同一份镜像有时能过有时不能过**，pandbg 与正式镜像表现也不同——不是确定性的代码路径
问题。而且一旦中招，之后每次开机都中（损坏的元数据要写才能修，一写又炸）。

**机制**　`sf32lb_flash_hw_init()`（`vendor/sifli/chips/sf32lb52/sf32lb_flash.c:120`）
检测到自己在 XIP 执行就跳过 `HAL_FLASH_Init`：

```c
pc = (uintptr_t)&sf32lb_flash_hw_init;
if (pc >= FLASH2_BASE_ADDR && pc < FLASH2_BASE_ADDR + SF32LB_NOR_TOTAL_SIZE) {
    g_flash_hw_initialized = false;
    syslog(LOG_WARNING, "WARN: skip HAL_FLASH_Init during XIP bringup, ...");
    return OK;
}
```

于是首次 NOR 写/擦由 `sf32lb_nor_prepare_io()` 懒加载
`sf32lb_flash_preinit_runtime()`（同文件 :225）。该函数自己标了
`SF32LB_FLASH_RAMFUNC`，它调的 HAL 也都在 RAM 里——查 `System.map` 可证：

```
20021970 t sf32lb_flash_preinit_runtime
2001f590 T HAL_FLASH_PreInit          <- .ramfunc，不是 XIP
20020a4e T HAL_FLASH_ISSUE_CMD        <- .ramfunc
2002103c T HAL_FLASH_SET_QUAL_SPI     <- .ramfunc
12010628 W HAL_Delay_us               <- 只有这一个在 XIP flash 里
```

窗口的边界也比原文以为的窄。`HAL_FLASH_PreInit()` 结尾的
`HAL_FLASH_SET_QUAL_SPI(hflash, false)` 只把 AHB 读命令切成 FREAD（单线快读），
片子照样应答，**XIP 仍然可用**；`nor_qspi_switch(hflash, false)` 更是 `en==0` 直接
return，连 QE 位都没动。所以 PreInit 返回之后的 `sf32lb_flash_unlock()` / `syslog()` /
拷 ctable 的 `memcpy()` 虽然都在 XIP，其实都是安全的。

**真正的窗口只有这五行**（原 :232–241）：

```c
HAL_FLASH_ISSUE_CMD(hflash, SPI_FLASH_CMD_RST_EN, 0);  /* 0x66 */
HAL_FLASH_ISSUE_CMD(hflash, SPI_FLASH_CMD_RST, 0);     /* 0x99 片子开始复位 */
HAL_Delay_us(30);                        /* ← 从正在复位的那块 flash 取指 */
hflash->Mode = HAL_FLASH_QMODE;
HAL_FLASH_SET_QUAL_SPI(hflash, true);
```

收到 0x99 之后片子在 tRST 内不响应任何读，而下一条指令 `HAL_Delay_us` 恰恰要从它
取指。活不活取决于这段代码是否还在 I-cache 里——这就是间歇性的来源。更糟的是
上一行 `up_irq_restore()` 已经把中断放开了，复位窗口里来一个 ISR，向量表和处理函数
同样在 XIP，一样炸。非正常掉电正好让 littlefs 在 mount 时做恢复写入，命中它。

**恢复手段**（修复前需要，修复后一般用不上）　`docs_ble/tools/erase_data.py`：擦 NOR
`0x129A0000:0x400000`（`/data` 的 littlefs 分区），擦完重烧，`sifli_ap.c` 的
`forceformat` 分支会重建文件系统。镜像本体在 `0x12010000..0x125FA000`，与该区间不重叠。
代价是 bond 丢失，但板子发起连接时 SSP 走 user_confirm 自动接受，会自己重新配对
（实测 7 秒内重新拿到 IP），**不需要在手机上「忘记设备」**。

**实际修法**（`vendor/sifli/chips/sf32lb52/sf32lb_flash.c`，无需改 vendor HAL）

1. 把那五行收进 `sf32lb_flash_reset_to_qmode()`，标 `SF32LB_FLASH_RAMFUNC`；
2. `HAL_Delay_us` 换成 `sf32lb_flash_spin_us()`——同样 `.ramfunc`，计数器在栈上，
   按最高主频留足余量（宁可等久一点）；
3. 整个窗口用 `up_irq_save()/up_irq_restore()` 包住，堵掉 ISR 那条路；
4. 顺手把「确保可写」那处裸调的 `HAL_FLASH_CLR_PROTECT()` 也纳入 IRQ 保护，与
   驱动里其它 erase/write 路径的写法对齐。

反汇编验证窗口内已无 XIP 分支：

```
20021aec  bl 20020a4e <HAL_FLASH_ISSUE_CMD>
20021af6  bl 20020a4e <HAL_FLASH_ISSUE_CMD>
20021afc  bl 20021aa8 <sf32lb_flash_spin_us>
20021b0c  bl 2002103c <HAL_FLASH_SET_QUAL_SPI>
```

代价：SRAM 90.48% → 90.51%（+184 B）。

**还需要真机确认**　~~这一步只做到「代码层面窗口内不再碰 XIP，且二进制可证」。~~
**已在 Round 16 验证**：`logs/p0_wdogcut.py` 用 `wdog` 触发看门狗硬复位，同时在窗口里
连续 39 次写 `/data`，保证复位落在 littlefs 的 NOR 写/擦中间。5 轮全过：`assert=0`、
每轮都 `littlefs mounted on /data (persistent)`、一次都没退化到 `forceformat`、
桌面正常起来。日志 `logs/r15_wcut1..5.log`。

---

## P0 — 已修复（2026-10-14）

### 24.10 蓝牙控制器 HCI 超时导致 `bluetoothd` 自杀（✅ 已修）

**症状**：手机蓝牙共享**已打开**，手表却永远连不上；`ps` 里 `bluetoothd` /
`bt_service_*` / `sysworkq` / `BT LW WQ` **全部消失**，只有重启板子能恢复。
（这一点最容易误判：链路层"看起来"没问题，实际是蓝牙守护进程已经死了。）

**根因**（addr2line 符号化 backtrace，两次崩溃同一行）：
zblue 在 `bt_hci_cmd_send_sync()`（`host/hci_core.c:507`）用 `BT_ASSERT_MSG` 处理
"控制器不应答" —— 控制器一旦对某条 HCI 命令超时，就升级成 `z_fatal_error`，
把整个蓝牙进程打死。两次分别由 `bt_conn_enter_sniff_mode()`（SNIFF_MODE / opcode
`0x0803`）与 `bt_br_write_local_name()`（设置设备名）触发 ⇒ **任何**命令都可能踩到。

控制器为何不应答：vendor HCI 层在**合成/吞掉命令完成事件**，主机的"命令↔应答"配对错位 ——
`sf32lb52 bth4: swallowed CC for injected op`、
`OpCode 0x0c24 completed instead of expected 0x0c18`、
`<wrn> opcode 0x0803 status 0x0c`。

**修复**：`external/zblue` 提交 `78ba2004999` —— 改成 `LOG_ERR` + 归还 net_buf +
`return -ETIMEDOUT`。★ 返回前**必须**先把 `cmd(buf)->sync` 置 NULL，否则迟到的完成事件会
`k_sem_give` 一个**栈上**信号量（use-after-free）。
真机验证：单次启动、`dhcp_ok ip=192.168.44.140`、`netmgr CONNECTED`、0 次 Assertion。

**仍未修**：vendor HCI 层本身（`sf32lb52 bth4`）—— 现在是"**不再致命**"，不是"修好了"。

### 24.11 自建硬件看门狗在开机阶段误判（✅ 已修）

刷完固件后出现**约 20 秒一次的启动循环**（三次启动 `00:54:20 → 00:54:43 → 00:55:03`，
且**没有任何崩溃 dump** ⇒ 不是 assert，是看门狗自己干的）。
原因：`stall` 判据的基准时刻取自**看门狗线程启动**时，而开机阶段 UI 心跳还没开始跳，
超过 8s 就判卡死 → 停止喂狗 → 20s 后把**正在正常启动**的板子复位。
修复：`stall = (ui_seen != 0) && (ui_stuck_ms >= HW_STALL_MS)`
（`packages/ai_agent` 提交 `79505a6`）。详见 [30 §26.5](30_status_report.md)。

---

## P1 — 影响产品完整度

### 24.12 语音上行带宽 —— ✅ 已解决（2026-10-14，改用 Ogg Opus）

**原问题**：实测 BT PAN 上行 TLS 上传只有 **4.6~4.9 KB/s**（131369 字节要 50~57 秒），
而 16 kHz/16 bit 原始 PCM 是 **32 KB/s**；叠加 `AGENT_VOICE_PCM_BUF_SIZE = 128 KB`
（= 4 秒）硬上限（`read_pcm_file()` 会把更长的文件以 `File too large` 挡掉）。

**解决**：上传前先编成 **Opus 16 kbps（2 KB/s）并包成 Ogg**：
真机同一文件 131072 → **8471 字节（15.5×）**，整轮（含编码）**57 秒 → 8 秒**。
- 为什么必须带容器：MiniMax **拒绝无容器的裸 Opus 码流**（稳定 HTTP 400
  `Invalid data found when processing input`，换 `.ogg` 后缀也一样）—— 实测结论。
- 编码器：树内 `external/opus`；**必须补 `-O2`**，此前全库是 `-O0`（见 24.17）。
- 完整过程与证据：见 [30 §27](30_status_report.md)。

**仍未解决的部分**：上行带宽本身没修好（→ 24.15）；识别仍是"录完 → 编码 → 整段上传"，
不是流式，且 4 秒上限还在（→ 24.16）。

### 24.15 蓝牙上行吞吐的结构性上限（P1，未解决）

`~12 KB/s` 是结构性的：ACL 在飞窗口 × 分片 × RTT（`4×487B / RTT159ms ≈ 12 KB/s`，
§22 根因1 实测 11.6 KB/s）。实测 TLS 上传只有 4.6~4.9 KB/s，差距来自
**上行 TX 排队 → 占满共享 IOB 池 → RX 方向的 TCP ACK 拿不到 IOB 被丢**（缓冲膨胀反馈，
`rx_tun_write_fail ... errno=11`）。

**试过并已推翻的做法**：在 RX 侧对 EAGAIN 做有界退避重试 —— 实测无效
（每条丢包日志都是 `tries=20`，3 ms 内 20 次全部 EAGAIN ⇒ 池是**持续**告罄而非瞬时），
且每次白等 3 ms，已 revert（`8a706af1`）。**不要在 RX 侧重试，要从 TX 队列或池容量下手。**
注意池容量受限：IOB 池在 **SRAM**（96.8 KB / 64 个），而 SRAM 静态占用已 82%；
8 MB PSRAM 虽空着 89%，但 `CONFIG_IOB_SECTION` 需要链接脚本里的静态段，
而本板 PSRAM 是运行时 `umm_addregion` 进去的 —— 想做要改链接脚本。

### 24.16 语音编码仍是"录完再编"（P1，可优化）

当前：说完 4 秒 → 编码 2 秒 → 上传/识别 6 秒 ≈ **12 秒**出文字。
`complexity=0` 实测 **2.05× 实时**，有 2 倍余量 ⇒ 可以**边录边编码**省掉那 2 秒；
再配合**分段上传**（官方推荐的 "client-side VAD for pseudo-streaming"）可再省 3~4 秒，
有望进到 **5~6 秒**。见 [30 §27.8](30_status_report.md)。

### 24.17 `external/opus` 曾被整库以 `-O0` 编译（✅ 已修，但值得记住）

vendor 的 `CMakeLists.txt` 用 `target_compile_options()` 自己设 flags，**绕过 NuttX 给
其他库的统一优化标志**，GCC 默认 `-O0`：编码 16 kHz 音频只有 0.68× 实时。
补 `list(APPEND CFLAGS -O2)` 后 **快 3 倍**（c10: 19s→6s；c0: 6s→2s = 2.05× 实时），
而且**固件还小了 160 KB**。教训：**新增第三方库后要检查它到底以什么优化级别编译**
（`grep -A3 '<target>.*\.c\.o' build.ninja | grep FLAGS`）。

### 24.18 实时语音采集从未工作过（✅ 已修，2026-10-14）

**症状**：`voice_start` 必然失败

```
[voice] ASR pre-connected
[audio_cap] media_recorder_open failed, errno=11
[voice] capture open failed
```

**根因（看符号表，不是看代码）**：

```
$ arm-none-eabi-nm nuttx | grep media_recorder_
120ed8d0 W media_recorder_close      ← 全部是 W(weak)
120ed8ba W media_recorder_open
...
```

`frameworks/multimedia/media` 里的真实现**没编进固件**，`src/stubs.c` 里那组
"永远返回 NULL"的桩成了唯一实现。另一条 direct ALSA 路径的开关
`CONFIG_AI_AGENT_AUDIO_ALSA_DIRECT` **没开**，且它依赖的
`aw-alsa-lib/pcm.h` 全树都不存在（那是 Allwinner R528 的头）。

**影响**：实时语音采集**从未工作**。此前所有"语音能用"的演示走的都是离线路径
（`audio_rec_test` 录成文件 → `voice_test_asr` 识别），**离线路径能出结果
掩盖了实时路径 100% 失败**。

**修法**：新增 `src/voice/pcm_media_recorder.c`，用**强符号**直驱
`/dev/audio/pcm0c`（序列照抄已被验证的 `audio_rec_test_main.c`），
并**删掉 stubs.c 里的弱桩** —— 只加强实现不够，弱符号会先被链接器选中，
归档里的强实现就永远没机会被拉进来（真机验证过：留着桩时 `nm` 仍是 `W`）。

**★ 教训（写进检查表）**：
1. 新增实现的符号如果与某处的桩同名，**必须把桩删掉**；判断方法
   `nm <elf> | grep <symbol>` 看 `T` 还是 `W`。
2. **"能出结果" ≠ "链路是通的"**。演示/验收脚本要覆盖**真实入口**，
   不能只覆盖等价的离线路径。

### 24.22 HTTP 429 没有退避重试（P1，影响可用性）

2026-10-14 晚反复测试把 MiniMax 的配额打满，服务端返回 **429 Too Many Requests**
（TTS 同样 429）。当前实现把它当普通失败直接放弃，用户看到的就是"识别失败"。
**应当**：识别到 429 时按 `Retry-After` 退避重试 1~2 次，仍失败才报错。
改法入口：`src/voice/minimax_tts.c` 的 `minimax_https_post()` 返回处，
以及 `src/voice/minimax_asr.c` 的 `mm_asr_post_payload()`。

### 24.23 采集偶发异常：0 块 / 极低实时率（P1，未定位）

同一晚遇到两次，都发生在板子刚起来不久：

| 现象 | 细节 |
|---|---|
| 采集读到 **0 块** | 45 秒一个字节都没读到 |
| 采集只读到 **46 块** | 45 秒只采到 2.9 秒音频（约 **6% 实时率**） |

两次都伴随开头的**满量程爆音**（`chunk#1: peak=32768`，正常应是 7000 上下），
怀疑与音频通路刚启动时的状态有关。**不是 VAD/按键改动引入的**（"0 块"那次
出现在改动之前）。排查入口：`src/voice/pcm_media_recorder.c` 的
`media_recorder_read_data()`（超时返回 `-EAGAIN` 的路径）与音频驱动
`AUDIOIOC_DEQUEUE` 的投递情况。**现象是"静默的"** —— 没有报错，
只在 `chunk#` 计数上看得出来，所以要先加一条"实时率异常"的告警。

### 24.20 能量型 VAD 在多人环境会挑"最响的声源"（P1，固有局限）

真机遇到过一次：房间里有别人在聊天（底噪标定值 **7015**，是安静时的 3 倍），
VAD 放行的是**环境对话**，说话人对表说的那句因为不够响反而没被放行。
这不是实现 bug，是**能量型 VAD 的固有局限** —— 它只认能量，不认声源。
可选方向：降低倍数阈值让 ASR 自己扛噪声（上传量回升）；或产品层面要求
"靠近手表说"（本次验证就是这么做的，效果很好）。本板无双麦，做不了波束成形。

### 24.21 ADC 增益要不要回调：**先别急着降**（P1，需对照实验）

pcm-audio 技能测出 SNR 29.2 dB（阈值 40dB），技能建议"检查麦克风增益是否过高"；
但同一段音频的**削波率是 0.00%** —— 说明增益并没有过载。
SNR 低更可能是**环境噪声（声学）**而不是电路噪声。
要判断必须做对照：**同一环境下降 ADC 增益再测底噪** ——
- 底噪跟着**等比下降** ⇒ 噪声在 PGA **之前**（声学/麦克风自噪），**降增益并不能改善 SNR**；
- 底噪**基本不变** ⇒ 噪声来自 PGA/ADC 之后，这时降增益才有用。

**不要在没做这个对照前就调增益** —— 之前为了修"麦克风无响应"把增益一路加到
+30dB（量程上限），贸然降回去可能又把它弄哑。

### 24.19 语音交互仍靠手动 start/stop（P1，待产品化）

目前靠 CLI 的 `voice_start` / `voice_stop` 手动控制录制边界。产品化需要：
- **静音检测（VAD）自动收尾**：说完了自动停，不用按键；
- 或至少一个物理按键的按下/松开语义。
另外官方文档明确建议的做法就是 "**client-side VAD for pseudo-streaming**"
（MiniMax **不支持音频侧流式输入**），所以 VAD 也是分段上传方案的前置件。

### 24.13 心率数值准确度：接受前稳定时间不足（P1）

`hr_measure` 现在的规则是"第一批被连续确认两次的值就接受"，于是会锁定**接触瞬间的暂态**：
真机连测三次得 143 / 136 / 85 bpm，而算法侧的 `pk5s=6 rr_med=700ms` 明明指向 85。
**改法**：要求跑满 15~20s 且确认窗口拉长后才接受 —— 代价是出数变慢，换来数值可信。
需真机复测。见 [30 §26.7](30_status_report.md)。

### 24.14 长录音会卡住音频采集，并把 nsh 一起堵死（P1）

`audio_rec_test 25` 在第 16 秒停住：不再打印 `sec N`、不出 `done`、
连 `nsh>` 提示符也不再出现；`ps` 显示一个任务卡在 `WAIT_MQNOTEMPTY`，采集 DMA 停止。
恢复只能重刷固件。`4`~`10` 秒的短录音正常（`done, 131072/323584 bytes`）。
**影响**：任何"长按说话"的交互都会踩到；也说明 `audio_rec_test` 不适合做长稳测试。
**注意**：阻塞型 `audio_rec_test` 会把 nsh 的 stdin 一起占住 —— 这反而可以当作
"把命令送给 `vela>` CLI"的手段（见 [30 §26.3](30_status_report.md)）。

手表对外显示 `cd:ab:78:56:34:12`，设备名 `Agent-Watch-cd:ab:78:56:34:12`，bt-pan 网卡
MAC 也是它。多台设备同时在场会撞地址；而 `pan_get_device_id()` 用
`SHA256(BD_ADDR)[0:16]` 生成 Device-Id/chip_id，假地址意味着**所有设备的 Device-Id
相同**，一旦接入需要设备标识的云侧服务就会出问题。

入口：LCPU 的 NVDS 区（`sf32lb52_bt_adapter.c` 里
`SF32LB52_BT_NVDS_BUF_START 0x2040FE00`、`SF32LB52_BT_NVDS_PATTERN`）。产品化做法是
每台设备烧片时写入唯一 BD_ADDR，或从芯片 UID 派生。

不阻塞当前功能，配对与 PAN 都正常。

### 24.3 吞吐没有实测数字（本轮放弃）

镜像里没有 iperf，也没有 wget / webclient，`ping` 只能测延迟不能测带宽。要出数字需往
defconfig 加 `NETUTILS_IPERF` 或一个 HTTP 下载客户端；当前 SRAM 占用 90.48%、余量约
37 KB，加进去要重新核 SRAM 预算（见 19 号文）。

**已知的间接指标**：协商 L2CAP MTU 1691，接口 MTU 1500，1500 字节包能连续跑，60 分钟
长稳里大包丢包约 4.4%、小包 0.33%，RTT 均值 159 ms（120 次统计）。

注意 22 号文规则 3 的约束：**靠加大 ACL 包长提吞吐这条路已经没有余量**（487 已是邮箱
ring 上限），要提只能做分片流水与链路参数（sniff / QoS）。

---

## P2 — 工程卫生

### 24.4 `vendor/sifli` 里有被跟踪的构建产物

```
boards/sf32lb52/drivers/cmake_install.cmake
boards/sf32lb52/drivers/input/cmake_install.cmake
boards/sf32lb52/drivers/lcd/cmake_install.cmake
```

这三个文件被 `ninja` 改写，内容是**上次构建目录的绝对路径**（`CMAKE_INSTALL_PREFIX`
指向 `out/xxx/staging`），因此每次换构建目录它们就变「脏」。本来不该入库。

处理方式：向 `open-vela/vendor_sifli` 提一个 `git rm --cached` + `.gitignore` 的清理 PR。
**给上游仓提 PR 前记得别把这三个文件的本地改动带上。**

### 24.5 团队仓 `README.md` 仍是组委会模板

团队仓 README 第六节要求提交前替换成自己的作品说明（作品名、赛道、运行方式、简介）。
作品尚未完成，暂不写。

另外 `TASK_STATUS.md` 关于 PAN 的记录已过期（还写着「PAN vs SLIP 待定」「官方未实现
PAN」），改 README 时一并更新。

### 24.6 大赛要求的其余交付物尚未准备

按《大赛总览》，除代码与 AI 日志外还需要：介绍文档（.docx/.pdf/.pptx）、5 分钟以内的
演示视频；若使用 AI Coding 则**至少提供一个可复用的 Skill**。这些都还没做。

三个公共仓的 PR 已提交（zblue#231、sifli#29、bluetooth#591，CI 全绿、MERGEABLE，等组委会
review），索引见 23 号文。

### 24.7 蓝牙设备名：宿主侧可改，空口上报改不动（P2）

**现象**（2026-09-16 实测）：手机扫描列表里显示 `Agent-Watch-cd:ab:78:56:34:12`，
即使在开机后无竞争地重写名字也一样：

```
nsh> bttool
bttool> get name
[bttool] Local Name:小云手表           <- 宿主侧已是产品名
bttool> set name 小云手表
[bttool] Local Name:小云手表 set success
# 手机重新扫描「可用设备」-> 仍是 Agent-Watch-cd:ab:78:56:34:12
```

**已核实的机制**：zblue 开了 `CONFIG_BT_DEVICE_NAME_DYNAMIC=y`，
`bt_set_name()` 会 `bt_settings_store_name()` 落盘 + `bt_br_write_local_name_mc()`
下发 HCI；SAL 侧 `bt_sal_set_name()` → `bt_set_name()` 也在（`sal_adapter_interface.c:819`）。
**PAN 服务在适配器上电时设的 `<前缀>-<MAC>` 确实上了空口**（手机能看到它），
说明这条路本身有效；但 App 侧后续的覆盖没有反映到空口。

**判断**：控制器（LCPU）侧的名字存储/生效时机与宿主侧不同步，属平台层行为，
应用层无法保证。未继续深挖（设备名不属大赛硬性要求）。

**根治路径（按代价排序）**：
1. 改框架 PAN 服务的前缀并去掉 MAC 后缀（`frameworks/connectivity/bluetooth/service/
   profiles/pan/panu_service.c` 的 `BT_NAME_PREFIX` 与 `pan_set_local_name_with_mac()`）
   ——这是唯一已验证能影响空口的路径，属公共仓改动，须走 fork + PR（同 23 号文的三项修复）。
2. 查 LCPU NVDS 里是否有名字字段（`vendor/sifli/chips/sf32lb52/sf32lb52_bt_adapter.c`
   的 NVDS 默认表），若能写入则在 BT 初始化前设名字——需供应商文档支持。

### 24.9 免打扰窗口按 UTC 判定（Round 30 实测）

> 与 24.7 的设备名问题无关，是 Round 30 调「空闲关怀」演示时新发现的。

`pet_care` 的静默期判定走 `care_local_hour()` → `localtime_r()`，但本构建
**未开 `CONFIG_LIBC_LOCALTIME`**，`localtime_r` 原样返回 RTC，而 RTC 是 SNTP
写入的 UTC。实测：北京时间 23:44 时设备 `date` 显示 `Sat, Sep 19 15:44:36 2026`。

**后果**：`CONFIG_AI_AGENT_CARE_QUIET_START=22` / `..._QUIET_END=7` 实际压制的是
**北京时间的 06:00–15:00**，与用户作息错位 8 小时。

**两层含义**：
- 演示口径（今晚）：UTC 15 点不在静默区，北京夜里拍「空闲关怀」不受影响。
  **不要为了「修正」它而在拍摄前改时区**——一改，这幕立刻被静默掉。
- 根治方向：给 `pet_care` 一个显式时区偏移（Kconfig 常量，或按 `time()` 语义
  在 `care_local_hour()` 里加 8 小时）。注意修完静默区就回到 22:00–07:00 北京，
  夜间拍摄必须把 `CARE_QUIET_START/END` 一并调整。

**当前处理（已决策 2026-09-16）**：走选项 A —— **接受现状**。
保留 App 侧覆盖（宿主侧名字正确、便于本地排查），交付文档中说明「手机显示名可能仍是
框架默认名」；不为此新增公共仓 PR（三项平台修复的 PR 链路已经够长）。
若赛后产品化，按上面路径 1 改框架前缀即可。

### 24.8 云端 LLM 配置会被清空（P1，已定位到写入路径但未根治）

**现象**（2026-09-16 两次）：`/data/ai_agent/config/config.json` 只剩网络组件写的两项，
云端 backend 与 API key 消失：

```
{"net.dns_primary":"223.5.5.5","net.dns_secondary":"8.8.8.8"}
```

表现为 `router_status` 的 `backend_count: 0`，对话静默回退端侧模型（`backend=-1`）。

**已排除**：
- 已加**原子写**（写 `config.json.tmp` 再 `rename`）后仍然复发 → 不是「写一半留下残缺文件」。
- 全仓只有 `config_store.c` 会打开 `AGENT_CONFIG_FILE` 写入；网络组件用的是
  合并安全的 `claw_config_set()`（加锁 + 读-改-写）。

**判断**：落在 `claw_config_set()` 的读-改-写里——**读取时拿到空对象**（文件缺失/为空/
解析失败），随后合并保存就只写回本次的键（DNS 恰是网络组件在启动/联网时写的）。
`config_store.c` 的 `load_json()` 已改为解析失败时打 `LOG_ERR`（此前静默），
所以下一步只需**抓一次启动日志**看是否出现该报错，即可区分「解析失败」与「文件当时为空」。

**规避（演示口径）**：重启后先读一次 `router_status`；若 `backend_count: 0`，重新执行：

```
nsh> ping -c 10 223.5.5.5        # 占住 NSH，让 vela> CLI 收到输入
set_llm https://api.stepfun.com/v1/chat/completions step-3.7-flash <key>
```

**根治方向**：`claw_config_set()` 在 load 失败时不要用空对象覆盖——应当**先备份坏文件再重建**，
或改为「只改不动其它键」的追加式写入。
