# 踩坑目录

## P1. 「函数存在、能编译、返回成功，但什么都没做」

本仓已发现 **6 个**，其中 3 个直接导致了主要 bug。
**每次接新接口，先按这张表自查。**

| # | 位置 | 症状 | 怎么发现的 |
|---|---|---|---|
| 1 | `pcm_media_player.c` 的 `media_player_prepare()` | 第一行就是 `(void)url;`，**根本不读文件** -> 永远放不出声 | 顺着「没声音」的调用链读到头 |
| 2 | `media_policy_set_stream_volume()` | `(void)stream;(void)volume; return 0;` -> 音量按钮一个寄存器都没动 | 读函数体只有两行 |
| 3 | `sf32lb_lcd_setcontrast()` | `return -ENOSYS` -> 亮度调不了 | grep 到 ENOSYS |
| 4 | `LCD_SetBrightness()`（面板驱动） | 完整实现、注册进 ops 表、**零调用者** | `grep -rn SetBrightness` 只有定义和赋值 |
| 5 | `tx_start()`（音频驱动） | 开头 `if (priv->running) return OK;` -> **静默跳过**，恢复播放后无声 | 查「暂停后恢复没声音」时读到这里 |
| 6 | `vibrate` 工具 | `CONFIG_VIBRATOR` 没开，永远返回 not enabled | 审计 41 个工具的 CONFIG 依赖 |

**自查三问**：
1. 它有调用者吗？（`grep -rn 函数名`）
2. 返回成功是真成功还是被吞了？（看调用方有没有检查返回值）
3. 依赖的 `CONFIG_*` 开了吗？（`grep 名字 out/*/.config`）

---

## P2. 我自己的探测工具也会骗人

`i2ctool` 和自写的 `imu_i2c_probe()` **在这块板子上对任何总线都返回 Transfer failed**。
用它们做的所有「扫不到设备」的结论**全部无效**。

**根因**：SiFli I2C 驱动只支持 `I2C_M_NOSTART` 形式的组合传输（见 hardware.md）。

**教训**：
- 用探测工具前，先拿一个**确定存在**的设备做对照
- 对照也失败 -> **是工具的问题**，不是硬件的问题
- 但对照本身也可能选错：曾经用 MAX30102 做对照，而那条总线的 pinmux 写在血氧 app 内部，
  app 没跑时根本没引脚

**最可靠的判据**：内核级 I2C 的驱动能否注册成功（`/dev/xxx` 节点在不在）。

---

## P3. 验证烧录是否真的发生

**真实踩坑**：烧录命令写成

```bash
python3 recover_flash.py fw.bin 0x12010000 | tail -2
```

管道让退出码变成 `tail` 的 0。脚本其实因为找不到串口立即退出了（**70 毫秒**），
我却报了「烧录成功」，然后拿旧固件的现象当新版本的结果分析了好几轮。

**检查清单**：
- 有新的 `.v3_work/recover_rst_NN.log`（看时间戳）
- 日志结尾是 `100%`
- 退出码是 0（**用 `; echo EXIT=$?` 显式记录，不要接管道**）
- 耗时约 2 分钟（70 ms 一定是失败）

**并且**：job 的 `status: completed` 只代表进程结束，不代表成功。

---

## P4. 「看起来是硬件问题，其实是接线位置」

**真实踩坑**：用户说「插上 MPU6050 屏幕就灭，拔掉就好」。
我坚持认为 PA00/PA01 是空闲引脚，查了三轮代码。**实际 PA01 就是背光控制。**

**教训**：用户能看到板子，我看不到。**他们的物理观察优先级高于我的代码推断。**

---

## P5. 跨线程 / 跨生命周期的句柄

| 案例 | 症状 | 修法 |
|---|---|---|
| `s_cur_player` 由播放任务写、UI 线程读 | 释放后读到悬空指针 -> EBADF + 播放卡死 | **UI 线程永不碰音频句柄**，只改标志 |
| `s_drag` 只在 PRESSED 里清 | 没挂手势的页面永远等不到 -> **所有按钮失效** | 加 800ms **自动过期** |

**修法都不是「加锁」，而是从结构上消除这种共享。**
加锁只是把竞态变小，加过期/改所有权才是消除。

---

## P6. 时间基的隐性偏差

| 案例 | 数值 | 后果 |
|---|---|---|
| `usleep(128ms)` 喂 128ms 的音频 | 实际睡 130ms（tick=10ms 向上取整） | 每块慢 2ms，64 块后落后一个缓冲 -> 持续欠载 |
| `usleep(50ms)` 累计暂停时长 | 实际 60-70ms | 暂停 173 秒少算几十秒 -> 恢复瞬间灌完 14 秒音频 |

**教训**：任何"按固定间隔"的循环，**要么用绝对时间（t0 + n*period），要么用墙钟测量**，
不要依赖 `usleep` 的精度。

`CONFIG_USEC_PER_TICK=10000`（1 tick = 10ms）是这里的关键常数。

---

## P7. 假数据混进生产路径

`health/hr_monitor.c` 用 `rand_r()` 随机游走 + 伪造「心率飙升」，
然后调用 `pet_care_hr_report()` **让手表真的弹出心率告警**。
它被 `pet_care_tick()` 每秒调用 —— **真实生产路径**。

**已下线**（v37），文件头加了警示。

**检查方法**：`grep -rn "rand\|simulat\|模拟\|fake" src/`，看有没有在活跃调用链上。

---

## P8. 音频设备级共享导致的跨模块干扰

TTS（24000 Hz）和音乐（16000 Hz）用同一个 `/dev/audio/pcm0p`。
TTS 一 `prepare`，音乐的流就被打断。

**识别方法**：日志里看 `prepared: in=XHz out=YHz` 的采样率跟当前播放器是否一致。

---

## P9. 日志在启动早期会丢

板级初始化（`sifli_ap.c`）在控制台就绪**之前**执行，那里的 `syslog` 输出**看不到**。

**变通**：把值存进全局变量，由用户态命令在启动完成后打印。
（例：`g_imu_pinmux_scl` / `g_imu_pinmux_sda`）

---

## P10. Kconfig 改了但没生效

`kconfig-tweak` 改了 `.config` 之后，**还要重新跑 cmake configure** 才会重新生成
`include/nuttx/config.h`。否则编译用的还是旧的宏。

```bash
kconfig-tweak --file out/.../.config -e XXX
cmake -S nuttx -B out/...            # 必须
grep XXX out/.../include/nuttx/config.h   # 验证
```

**并且**：defconfig 必须是 **ASCII**，写中文注释会出问题。

---

## P11. 烧录「成功」但一个字节都没写

`sftool` 会以 **rc=0** 退出，日志里也会出现 `100%`，但实际上没写进去。

**唯一可靠判据：日志里有没有完整的 0→100% 百分比扫描。**

| 现象 | 真烧录 | 假烧录 |
|---|---|---|
| 耗时 | ~170 秒（8.5 MB） | **3 秒** |
| 日志 | 60+ 行 `0% 1% 3% … 100%` | 只有 `0%` `100%` 三行（24 字节） |
| 芯片状态 | 正常启动 | **停在 ROM loader** |

**不要**用 `EXIT=0`、`grep 100%`、job `status: completed` 判断成功。
真实案例：2026-10-24 14:02 两次「成功」烧录，板子全黑，实际一个字节没写。

```bash
# 正确姿势：看百分比行数
grep -c '%' .v3_work/recover_rst_0N.log    # 真烧录 60+，假烧录 6
```

---

## P12. 黑屏 + 串口 0 字节 ⇒ 先冷上电，别急着怀疑固件

假烧录（P11）会把芯片**留在 ROM loader**：程序根本不跑，
表现就是**全黑 + 串口一个字节都没有**（连 NuttX 早期启动日志都没有）。

这跟「固件有 bug」的表现**完全一样**，但根因完全不同。

**处理顺序**：

1. 拔插 USB 一次（真正的冷上电）；RTS 接板子电源，软件拉 RTS 也行
2. 还黑 → 重新烧录，并用 P11 确认真烧上了
3. 还黑 → 才怀疑固件，用已知 good 基线做对照

**推理技巧**：若挂死发生在 `ui_build()` **之后**的代码，
屏幕早该有画面、串口也早该打出前面的日志。
「0 字节串口 + 黑屏」说明**根本没进到应用层** —— 先查启动链路，别查应用代码。

---

## P13. QSPI manual 模式不恢复 → XIP 取指取到垃圾（最阴的一类崩溃）

`HAL_FLASH_MANUAL_CMD(hflash, ..., cmd_mode=1)` 把 QSPI 控制器切到 **manual command 模式**，
**它不会自己切回来**。在调用 `sf32lb_flash_restore_ahb_read()` 之前，
任何 **XIP 取指**都会从读不出的 flash 拿到垃圾 → HardFault，
崩溃任务名会指向当时正在跑的任何东西（本次是 `AppBringUp`）。

**症状**：开机挂在 `nx_mount("/dev/config0")` 的首次 NOR 访问；
backtrace 指向 `sf32lb_flash_unlock` / `nxmutex_unlock`；
**拔插 USB 也救不回来**，只能重烧。

**已修**（2026-10-24，v48）：`sf32lb_flash_try_global_unlock()` 发完 0x98 后补
`sf32lb_flash_restore_ahb_read()`（同时给该函数加上 `SF32LB_FLASH_RAMFUNC`），
并且在 `up_irq_restore()` **之前**完成恢复。
erase / write 路径早就调了它（`:893/1013/1059/1105`），**唯独这个函数漏了**。

**检查清单**：这个驱动里凡是 `HAL_FLASH_MANUAL_CMD` / `HAL_FLASH_SET_CMD` 之后
**必须**跟 `sf32lb_flash_restore_ahb_read()`；这段窗口里**不能有任何 XIP 调用**
（`syslog`、`nxmutex_*`、`memcpy` 都算）。

**取证方法（重要）**：这类 bug 的 backtrace 会被栈上残留帧带偏，不可信。
用 `syslog(LOG_INFO, "[pin] ...")` 打点——syslog 本身在 XIP，正好也是边界探针，
**一次编译就能定位到具体哪一行**。本次就是靠 `J after clr_protect` 有、
`K after glb_unlock` 没有，一步锁定。

---

## P14. 重跑 `cmake` 会把手工改的 `.config` 全部冲掉

`.config` 是 **defconfig + Kconfig 默认值** 生成的。用 `kconfig-tweak` 手工调的值
（比如 `MPU60X0_I2C_FREQ=50000`、`ACCEL_AFS_SEL=1`）**只要不在 defconfig 里，
下一次 `cmake -S nuttx -B out/...` 就会被打回默认值**。

**本次事故**（2026-10-24）：为了修 `genromfs` 缺工具重跑了一次 cmake，结果
`ACCEL_AFS_SEL` 从 1（±4g）回到默认 2（±8g），静止加速度 `mag` 从 8400 掉到 4210
（**正好一半**），而 `pedometer.c` 的阈值是按裸 LSB 定的 → 计步全部偏一倍。
同时 `MPU60X0_I2C_FREQ` 也回到 400000（本板无外部上拉，必须 50000）。

**规则**：任何**必须长期保持**的配置，一律写进
`contest2026_181_womenshayebuhuidui/board/contest_board/configs/ai_agent/defconfig`，
不要只改 `.config`。

⚠️ **defconfig 必须是纯 ASCII**——写中文注释会出问题（见 P10）。
自检：`LC_ALL=C grep -n "[^ -~]" <defconfig>` 应该无输出。

---

## P15. 被中断的 sftool 会话会把芯片留在半复位状态

`read_flash` / `write_flash` 被 kill 或被超时掐断后，芯片可能停在 bootrom
与 App 的中间态，表现是：

- 系统还在跑（串口有 `[alive]`、`lcd_flush fail=0`），**但屏幕是黑的**
- 串口里混进 bootrom 的同步串：`~ATSF32!~@r...`
- NSH 收到半截垃圾命令，回 `xxx: command not found`

**唯一可靠恢复：拔插 USB（真断电）**。软件复位（RTS / `wdog`）不够。

**预防**：`run_code` 的执行上限是 120 秒，长操作（读 8MB flash 约 70 秒）
必须用 `run_in_background` 起后台任务，不要塞进前台命令里等着被掐。

---

## P16. sftool 烧录失败后禁止盲目重试（会越搅越浑）

`--before default_reset` 在板子非干净状态（之前烧录失败过 / UART 有残留）时
**几乎必失败**，且**每次失败都会往 UART 留 bootrom 脏字节**（`~ATSF32!~@r...`）。
继续重试 = 新会话被旧脏字节搅糊，错误码随机漂（0x41/0x40/0x7E/0x79/0x02/0x10…），
看起来像"硬件不稳"，其实是**自己把总线搅浑了**。

**证据**（2026-10-25）：连败 5 次后，直接向串口发 `uname`，回显是
`~ATSF32!~@r...uname: command not found` —— 芯片在正常跑 app，
脏字节挂在总线上。期间连 `wdog` 命令都被脏数据包住而失效。

**正确烧录方法（已验证，setup_guide §3.2 / flash_rts.py 两步法）**：

```bash
# 拔插 USB 清一次状态后：
python3 contest2026_181_womenshayebuhuidui/docs_ble/tools/flash_rts.py \
        out/nuttx_contest_board_ai_agent/nuttx.bin /dev/ttyACM0
```

原理：后台先起 `sftool --before no_reset` 等着，pyserial 把 RTS 拉高 500ms
再放开（RTS 接板子电源），芯片上电瞬间落入 ROM bootloader，sftool 秒接。
本次拔插后用这个方法**一次成功**（此前 default_reset 连败 5 次）。

**规则**：
1. 烧录一律优先 flash_rts.py 两步法，不要裸跑 default_reset
2. 失败 1 次可以重试 1 次；**连败 2 次必须停**，先拔插 USB（物理清 UART）
3. 判断"板子到底在不在跑"：直接发 `uname\n` 看回显 —— 有 `command not found`
   或正常日志 = app 活着；只有垃圾字节 = 卡在半复位态

---

## P17. 触摸 I2C 锁死 + 息屏 = 设备永久黑屏（reset 救不了）

**症状**（2026-10-25 16:00 实测）：
- 系统完全健康（alive 连续、lcd_flush fail=0、网络通、CLI 应答）
- 屏幕黑（面板在刷新，背光灭）
- **触摸彻底死**：`dd if=/dev/input0` 永久阻塞，一个字节都不吐
- 按 reset 无效；**普通插拔无效**（第一次可能好一阵，之后连插拔都不行）

**机制**：FT6146 触摸芯片的 I2C 被锁死（从设备把总线拉死或状态机卡住）。
SoC 软复位（reset 键）不会重置 I2C 从设备；普通插拔的断电时间不够
（板上电容维持供电）。同时息屏后的唯一唤醒路径是触摸 —— 触摸死 =
黑屏死锁，用户无从恢复。

**恢复**：**长断电** —— 拔 USB 后等 30 秒以上（电容放光、芯片完全掉电）
再插。实测 up=61s 全新启动 + 触摸恢复数据。

**预防（已排入待办）**：
1. KEY1 物理按键也能唤醒屏幕（触摸死锁时的逃生口）—— 现 KEY1 只跳页面
2. 息屏超时自动亮屏兜底（如 5 分钟无响应自动点亮，防"息屏+触摸死"死锁）
3. 排查息屏路径：LCDDEVIO_SETPOWER(0) 是否与触摸供电有关联（本次黑屏
   可能正是息屏动作触发了触摸掉电后未恢复）—— 待复现验证

---

## P18. 在运行态直接写 NOR（XIP）会把芯片写死，且卡死在看门狗之前

**症状**（2026-10-25 20:40–21:00 实测）：
- 连续十余次烧录失败，报错签名是
  `Failed to download stub: Protocol("invalid response code: 0x41")`
  —— `0x41` 就是字符 `'A'`，即 sftool 把**运行中控制台刷屏的日志**
  当成了协议响应（详见 25 号文 1.2 的判定表）。
- 某次"进度条到 100%"的尝试之后，**板子彻底没输出了**：
  串口能打开，但发 `\n`/`uname` 零回应；USB 仍枚举（CH343 在），
  SoC 却不打印任何东西 —— 连 `SFBL` 都没有。

**机制**：本板是 **XIP** 执行（应用从 NOR `0x12010000` 原地取指）。
应用还在跑的时候让 sftool 写同一片 NOR，会改写正在执行的代码 ->
当场取指错乱/死机；而此时 ai_agent 的硬件看门狗（hangwatch 启动后
才 arm）**还没起来**，所以没有任何机制能把芯片救回来 —— 表现为永久静默。

**恢复（唯一有效）**：**真断电 + 冷上电瞬间抢 ROM bootrom 窗口**。
- 拔 USB，**等 10 秒以上**（板上电容放电，只等 1-2 秒不够）
- 插上瞬间芯片会停在 ROM bootloader 约 2000ms（25 号文 1.1）
- 用 `.v3_work/flash_on_plug.py`：50ms 轮询 `/dev/ttyACM*`，**节点一出现
  立刻** `sftool --before no_reset --connect-attempts 40`
- 实测一次成功：`rc=0 / 100% / 无 panicked`

**教训 / 规矩**：
1. **只在 bootrom 窗口烧**（冷上电或按 RESET 后 2 秒内），
   **绝不在应用运行态写 NOR**。
2. RTS 自动复位在这块板上**不可靠**（`--before default_reset` 概率成功），
   不要靠它；要复位就真人按 RESET 或真断电。
3. 烧录前先确认板子状态：串口有 `[alive]` 说明 app 在跑（**不能直接烧**）；
   静默则要按 25 号文 1.2 判读是"卡在 bootrom"还是"已写死"。
4. 失败次数 >3 就停下来换方法，别连续盲试 —— 盲试正是把板子写死的路径。

## P19. `/data` 写入会阻塞到让 UI 心跳停 → IWDT 复位（2026-11-04 实测）

**症状**：黑屏 + 频繁复位，串口最后一行是 `[RSET] WSR=00000020`（IWDT），
没有 Assertion、没有 `[stall]`，看起来像"看门狗自己发疯"。

**证据链（对照实验）**：

| 版本 | `/data` 写入 | 结果 |
|---|---|---|
| v142 | 开 | TTS 结束后 `NOR write seq=960/1024/1088` → UI 心跳停 → 23s 后 IWDT 复位 |
| v142b | 关（`g_nor_data_ro=true`，MTD 写/擦除直接 `-EROFS`） | **up=516s，6×TTS + 3×LLM，零复位** |
| v132 | 开 | 6 分钟内 3 次 IWDT 复位 |

**机制**：写/擦除 NOR 时系统被长时间阻塞，UI 心跳线程停摆；看门狗不是原因，
只是最后"收尸"的那个。BT buffer 风暴是诱因/放大器，不是唯一根因。

**规矩**：
1. **只读隔离法**：怀疑存储/外设写路径时，先把写关掉（本板让 MTD 写/擦除
   直接返回 `-EROFS`），跑**同样的负载**看故障是否消失。这是最快的二分法，
   比读代码快一个数量级。
2. **`[RSET]`/IWDT 复位是后果，不是原因**：先回答"谁让心跳停了"，再谈看门狗。
   把超时值调大只会把死机变成更长的死机。
3. **写路径必须可降级**：所有写失败要被上层容忍成内存态，禁止 assert/卡死。
4. **演示/验证期默认 `/data` 只读**；持久化是可选能力，不是默认能力。
5. **恢复顺序**（确认根因后）：config/alarm → health/steps（低频）→ chat history（最后），
   每一步都要 30 分钟 soak，不能一次全开。
