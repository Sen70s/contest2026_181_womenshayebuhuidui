# 29. v3 设计：apb 所有权模型（目标：消灭挂死）

> 状态：**设计就绪，未实现** ｜ 日期：2026-10-10
> 前置：[25](25_flash_reliability_and_audio_defects.md)（缺陷记录）、[27](27_audio_driver_review.md)（四轮审查 + 两份裁判报告）、[28](28_audio_mic_verification.md)（真机验证 + 根因）
> 本文是**交接文档**：v3 实现者按此执行即可，不需要重新调研。

---

## 一、v3 的目标（已修正）

| | 目标 | 状态 |
|---|---|---|
| v1/v2 时期的假设 | 修 "94% 常数 PCM" | ❌ **归因错误** —— 真因是缺 `HAL_TURN_ON_PLL()`，与并发无关，**已在 v2.5 修复** |
| **v3 的真实目标** | **消灭约 1/3 概率的整机挂死** | 🔶 **已定性 + 已兜底，未根除**（2026-10-14）—— 位置固定在开机后 1~3s 的**蓝牙 PAN 建链**期；优先级 240 的看门狗线程也一行打不出 ⇒ **整机停摆而非饿死**；10 次冷启动复现 3 次；已加 20s 硬件看门狗自愈。详见 **§九** 与 [30 §25.3](30_status_report.md) |

**音质问题已解决**（[28](28_audio_mic_verification.md) 第八节），当前**唯一拦路虎是挂死**。
连续第 3 次采集必挂（修复 PLL 前后均如此，与 PLL 改动无关）。

> **2026-10-14 更新**：上面"连续第 3 次采集必挂"是当时的复现手段。现在有了更直接的判据 ——
> **冷启动 10 次复现 3 次**，且抓到了现场特征：最后一次日志停在 PAN 建链、
> 连优先级 240 的看门狗线程都不再运行、而 USB 串口**仍然枚举**（控制台是 CH343 桥，
> 与 SoC 无关，不能据此判断板子活着）。取证设施与结论见 **§九**。

---

## 二、挂死的机制（已定位到代码级，非推测）

### 2.1 现场回溯（真机捕获）

```
sched_dumpstack: [13] [<0x120a51d4>] nxmutex_wait+0x83/0xe0
sched_dumpstack: [13] [<0x120a530c>] nxmutex_lock+0xd/0x3a
sched_dumpstack: [13] [<0x120a5458>] apb_free+0xf/0x58
sched_dumpstack: [13] [<0x1209ea14>] sf32lb52_audio_freebuffer+0xd5/0xe2
sched_dumpstack: [13] [<0x120a3cfc>] audio_freebuffer+0x4f/0xe0
sched_dumpstack: [13] [<0x120a41d6>] audio_ioctl+0x169/0x3a0
sched_dumpstack: [13] [<0x1205f602>] file_vioctl+0x65/0x248
sched_dumpstack: [13] [<0x1205f8b8>] ioctl+0x17/0x44
sched_dumpstack: [13] [<0x1209f2c4>] audio_rec_test_main+0x5f3/0x68c
```

### 2.2 `apb_free` 是**引用计数**释放

`nuttx/libs/libc/audio/lib_buffer.c:114`：

```
void apb_free(FAR struct ap_buffer_s *apb)
{
  nxmutex_lock(&apb->lock);          /* <- 对已销毁的 mutex 加锁就卡在这里 */
  refcount = apb->crefs--;
  nxmutex_unlock(&apb->lock);
  if (refcount <= 1)
    {
      nxmutex_destroy(&apb->lock);
      lib_ufree(apb->samp);
      lib_ufree(apb);                /* <- 真释放 */
    }
}
```

`apb_alloc()` 置 `crefs = 1`；`apb_reference()` 递增；`apb_free()` 递减并在降到 0 时释放。

### 2.3 树内 8 个参考驱动的**标准模型**（以 `cs4344` 为例）

| 时机 | 标准驱动 | 本驱动 |
|---|---|---|
| `enqueuebuffer` | `apb_reference(apb)`（`cs4344.c:1065`） | ❌ **从不调用** -> `crefs` 恒为 1 |
| 用完归还 | `apb_free(apb)`（`cs4344.c:760 / 1373`） | — |
| `.freebuffer` | **不实现** —— 由核心 `audio_freebuffer` 代调 `apb_free` 减引用 | ❌ **实现并直接 `apb_free`** |

参考驱动（全部使用 `apb_reference`）：`cs4344` / `cs43l22` / `wm8904` / `wm8776` /
`es8311` / `es8388` / `vs1053` / `audio_dma`。

### 2.4 失效链条

```
crefs 恒为 1
  -> 应用一调 AUDIOIOC_FREEBUFFER, 驱动 sf32lb52_audio_freebuffer 里的 apb_free()
     就把内存【真释放】了
  -> 但 worker 仍持有该 apb 的裸指针 (deliver 的锁外窗口 / drain 的栈上 tmp 队列)
  -> worker 恢复后调 dev.upper(DEQUEUE, apb) -> 核心往已释放内存写
     apb->flags |= AUDIO_APB_DEQUEUED  (audio.c:1529)
  -> 堆元数据/相邻块被破坏
  -> 下一次 apb_free 对已销毁的 mutex 加锁 -> 断言或永久阻塞
```

这与 [27](27_audio_driver_review.md) 四轮审查反复报告的 **apb 所有权 UAF 家族**
（DR-J01 / DR-J03、DR-201 / DR-203）**是同一个根因**。

---

## 三、v3 设计

### 3.1 核心原则

> **驱动只要可能触碰某个 apb，就必须自己持有一个引用；用完必须归还。**
> 这样上层释放只会减引用，不会把内存从 worker 脚下抽走。

### 3.2 四项改动

| # | 位置 | 改动 |
|---|---|---|
| 1 | `sf32lb52_audio_enqueuebuffer` | 在把 apb 挂进 `aux`/`pendq` **之前**调 `apb_reference(apb)`（crefs 1->2） |
| 2 | 所有"用完归还"处 | 调 `apb_free(apb)`（crefs 2->1）。至少覆盖：① `deliver` 中 `upper(DEQUEUE)` **之后**；② drain 归还路径；③ `cancelbuffer` 归还路径 |
| 3 | `sf32lb52_audio_freebuffer` | 保留 aux/pendq 摘除 + 成员预检，末尾 `apb_free(apb)` —— 因驱动持引用，这里只 2->1，**对象仍存活** |
| 4 | `sf32lb52_audio_cancelbuffer` | 与 freebuffer 同款（含成员预检；当前对非成员调 `dq_rem` 会清空整条 pendq） |

### 3.3 设计要点与陷阱

- **必须成对**：每一处 `apb_reference` 都要有且只有一处 `apb_free` 与之配对。
  漏一处 = 内存泄漏（apb 永不释放）；多一处 = crefs 提前归零 = 又回到 UAF。
  **建议在代码里用注释标出每对 reference/free 的对应关系。**
- **`freebuffer` 不能简单删掉**：它还要负责把 apb 从 `aux`/`pendq` 摘除，
  否则 worker 仍会往一只"上层认为已回收"的缓冲里写。
- **`cancelbuffer` 的 `dq_rem` 陷阱**：对**非队列成员**调 `dq_rem` 会把
  `pendq.head/tail` 双双置 NULL，**静默清空整条队列**（`queue.h:229-254`）。
  必须先判成员关系（`freebuffer` 已有此预检，`cancelbuffer` 需同步补）。
- **`aux` 的特殊性**：`enqueuebuffer` 在 `aux == NULL` 时直接把 apb 设为 aux（不入队），
  所以"是否在 pendq 中"不能作为"驱动是否持有引用"的判据。

### 3.4 顺带应关闭的审查发现（原报告见 [27](27_audio_driver_review.md)）

| 编号 | 内容 | 建议 |
|---|---|---|
| DR-J04 | 6 处 `nxmutex_lock` 返回值未检查 | 加返回值检查。**注意**：`-EINTR`/`-ECANCELED` 在 inline 版会重试，**只有其它错误码**会漏出未持锁状态 |
| DR-J05 | drain 失败后 `drain_seq` 残留作用到下一会话 | drain 请求与会话绑定，或启动前拒绝 |
| DR-J13 | `shutdown` 在 core 关中断的自旋锁区内做可阻塞等待 | 改非阻塞（core 在 `spin_lock_irqsave` 内调用，超时依赖的定时器中断已被屏蔽） |
| DR-J07 | `register` 失败不回滚、worker 泄漏 | 集中 errout 逆序回滚 |
| DR-J14 | D-cache 行对齐只在 `DEBUGASSERT` | 发布构建无兜底 |

---

## 四、验证方案

### 4.1 验收标准（必须全部满足）

| # | 标准 | 判据 |
|---|---|---|
| 1 | **连续 30 次 start/stop 不挂死、不断言** | 这是 v3 的核心目标 |
| 2 | 音质不回退 | 不同取值个数 > 100；精确 0 与 −1 合计 < 5% |
| 3 | `nobuf` 保持 0 | 不因改动引入丢缓冲 |
| 4 | 无内存泄漏 | 30 轮前后 `free` 堆量一致 |

### 4.2 测试手段

```
# 烧录（必须用这个配方，--connect-attempts 3 会连续失败十几次）
sftool -c SF32LB52 -p /dev/ttyACM0 -b 1000000 \
    --before no_reset --connect-attempts 127 --after soft_reset \
    write_flash out/audio_test/nuttx.bin@0x12010000
# 需人工按 RESET；循环重试直到成功（实测 round 2~13 不等）

# 板上
audio_setup &
audio_rec_test 1        # 连跑 30 次
hexdump /data/mic_test.pcm
```

**串口要点**（踩过的坑）：
- `serial.Serial()` 必须在 `open()` **之前**设 `rts=False, dtr=False`（RTS 接板子电源）
- 行结束符必须 `\n`，**`\r` 不工作**
- hexdump 抓取要用**紧凑 `in_waiting` 轮询**；基于 `sleep` 的读取会丢约 22% 字节
- NuttX `hexdump` **每 512 字节重启一个块**（`at XXXXXXXX:` 头、偏移归零），重建时必须叠加块基址

### 4.3 分析陷阱（**必须遵守**）

**判断"有没有信号"必须先看"不同取值个数"，不能只看零占比或分析器结论。**

- `pcm-audio` 的 `audio_analyzer.py` **识别不了常量 DC**：喂 2 秒纯 `-1`，它报
  `No abnormal silence segments` / `SNR: 90.3 dB (good)` / `No significant audio quality issues detected`
- 本项目曾因此**误判一次**：把 84.8% 的精确 `-1` 当成"真实数据"

---

## 五、实现前的准备

1. 加载 **`nuttx-driver-development`** 技能（驱动开发流程）
2. 实现后走 **`driver-code-reviewer`** 双轮审查 + 裁判（不要自评了事）
3. 对照参考驱动：`nuttx/drivers/audio/cs4344.c`（`apb_reference` 在 :1065，`apb_free` 在 :760/:1373）

## 六、当前仓库状态（接手时先核对）

```
分支        feat/ai-agent-contest（团队仓 contest2026_181_womenshayebuhuidui）
工作区      干净
音频驱动    app/ai_agent/audio/sf32lb52_audcodec.c
            · P1(D-cache) + P2(drain 代次握手) + v2(并发重构) + 启动顺序修正 + HAL_TURN_ON_PLL 修复
            · 四轮审查评分 REJECT 44/100（见 27 号）
出货固件    ✅ 已链接音频子系统（CONFIG_AUDIO=y，2026-10-14 核实并真机录音验证）
            · 2026-10-10 时这里是「# CONFIG_AUDIO is not set」，上述改动都还没进交付镜像；
              现已进入 out/nuttx_contest_board_ai_agent（见 §九 9.2）
备份        /home/aila/backups/vela_contest_2026-10-10/（含出货固件 nuttx.bin）
构建目录    out/audio_test（唯一真正编译该驱动的目标）
```

## 七、遗留的更大问题（v3 之外）

**✅ 已解决（2026-10-14）**：`out/nuttx_contest_board_ai_agent/.config` 现为
`CONFIG_AUDIO=y`，`CONFIG_EXAMPLES_AI_AGENT_VELA=y` 也挂上了音频块；
真机实测 `audio_rec_test 4` → `done, 131072 bytes -> /data/mic_test.pcm`。
详见 **§9.2**。以下为 2026-10-10 的历史记录（保留作证据链）：

> ~~音频子系统尚未进入出货固件。~~ `out/nuttx_contest_board_ai_agent/.config` 中
> ~~`# CONFIG_AUDIO is not set`~~，且团队 CMake 的音频块挂在 `CONFIG_EXAMPLES_AI_AGENT` 上，
> 而板子用的是 `CONFIG_EXAMPLES_AI_AGENT_VELA` —— 这是"驱动从未进入出货固件"的根因。
>
> ~~**即使 v3 修好挂死，麦克风对最终用户仍不可用，除非同时解决构建挂载。**~~

---

## 八、v3 实施状态（2026-10-10 实现轮）

> 状态：**四项改动已实现 + 三轮审查已完成 + 镜像已构建；真机 30 轮验收未完成**（板子 USB 掉线，见 §8.4）

### 8.1 四项改动（§3.2）落地

| # | 设计 | 实现要点 | 位置 |
|---|---|---|---|
| 1 | `enqueuebuffer` 取引用 | `apb_reference()` 在挂进 `aux`/`pendq` **之前**；加锁失败立刻把引用还回去 | `sf32lb52_audio_enqueuebuffer` |
| 2 | 用完归还 `apb_free` | `deliver` 的 `upper(DEQUEUE)` 之后 / `drain_pending` 归还之后 / `cancelbuffer` 归还之后 | 同上 |
| 3 | `freebuffer` 保留摘除 + 成员预检 | 摘除后归还「上层那份」；若 apb 已完全落在驱动手里（`IN_AUX`/`IN_PENDQ`）再归还「驱动那份」，否则永久泄漏 | `sf32lb52_audio_freebuffer` |
| 4 | `cancelbuffer` 同款 | 成员预检 + 交付 + 归还驱动引用（本树 core 没有 `AUDIOIOC_CANCELBUFFER`，属防御性实现） | `sf32lb52_audio_cancelbuffer` |

**比设计文档多出的一点：`inflight` 标记。** 设计 §3.3 把 `aux` 当成「worker 正在填充」，但 `enqueuebuffer` 也会直接设置 `aux` —— 两种所有权语义混在一起，两轮审查分别判为 Critical/High（驱动引用要么永久泄漏、要么被还两次）。实现把「worker 此刻手里有裸指针」显式化（`priv->inflight`，只在每轮交付的取用~归还之间非空），`freebuffer`/`cancelbuffer`/残留清理按四种位置（`NOT_MEMBER`/`IN_AUX`/`IN_WORK`/`IN_PENDQ`）决定引用由谁归还；`deliver` 满缓冲时用「交付权声明」（`inflight == apb` 的原子判定）保证每只 apb 的 `upper(DEQUEUE)` 恰好一次。

### 8.2 §3.4 顺带关闭的审查发现

| 编号 | 处置 |
|---|---|
| DR-J04 | 6 处 `nxmutex_lock` 全部检查返回值；失败即不碰共享状态（drain 也只在真正清空后才回报完成代次） |
| DR-J05 | drain 请求与会话绑定：START 开头作废未确认的 drain，shutdown 里也作废 |
| DR-J13 | shutdown 改为纯非阻塞：停硬件 + 收回交付权 + 作废 drain，不等待、不回调、不 `apb_free` |
| DR-J07 | register errout 逆序回滚，补 `HAL_AUDCODEC_DeInit`/`HAL_RCC_DisableModule`/`HAL_PMU_EnableAudio(0)` 与 worker 有序停机 |
| DR-J14 | D-cache 行对齐改成运行时检查 + 返回 `-EINVAL`（发布构建不再只靠 `DEBUGASSERT`） |

另外顺手修掉：`hw_on` 与 `recording` 解耦（`hw_stop` 幂等，start 中途失败也能回滚硬件，审查 DR-005/DR-B006）、重复 `ENQUEUEBUFFER` 预检（避免 dq 链表自环，DR-B005）、`HAL_AUDCODEC_Config_ADCPath_Volume` 补原型并检查返回值、成功 STOP 后发一次 `AUDIO_CALLBACK_COMPLETE`（让 core 状态从 DRAINING 收敛回 OPEN，DR-B004，同时解掉 nxrecorder 停止流程死等 COMPLETE 的问题）。

### 8.3 三轮审查结论

- 第一轮（Pattern 全面）+ 第二轮（深层逻辑）由两个独立子代理分别执行，共报 2 Critical / 4 High / 5 Medium，全部集中在「会话销毁路径与 worker 交付路径的并发时序」。
- 作者按结论重写了所有权模型后再做第三轮独立验证；第三轮确认前两轮的问题已修，但发现 3 个**修复引入**的新问题（N1 残留清理放在 START 会吃掉新会话已入队的缓冲、N2 抢走 `inflight` 后引用永不归零、N3 早退路径粘住 `inflight`），已分别改为：清理挪到 `reserve()`/`configure()`（一定早于新会话第一次 enqueue）、交付声明失败时不清 `aux`、早退前交还 `inflight`。
- **如实记录的残留**：shutdown 与 worker 之间没有「静默协议」。若 worker 恰好被抢占在「交付权声明成功」与「调用 `upper()`」之间（几十条指令的窗口），或恰在 drain 的回调途中，仍可能在上层释放 `upper->status` 之后回调。窗口已从旧实现的「整段 memcpy + 整轮循环」缩到几十条指令；而旧实现在同一场景是 100% 死锁（关中断下等 worker 永远等不到）。彻底修好需要 core 侧提供静默语义（`audio_close` 在 `kumm_free(upper->status)` 前保证 lower half 已静止），属框架级改动，不在本次范围。

### 8.4 真机验收（未完成，阻塞在硬件）

- v3 镜像已构建：`out/audio_test/nuttx.bin`（2026-10-10 14:17，`CONFIG_EXAMPLES_AUDIO_REC_TEST=y` 仍在）
- **本机复位方式（实测更正）**：RTS/DTR **都不能**复位板子（拉低 3s 板子照样停在 `nsh>`），但 `/dev/watchdog0` 是硬件看门狗 —— 先让 NSH 执行 `wdog -t 500`（**必须在 sftool 打开串口之前发**，否则 NSH 的行缓冲会被 sftool 的同步帧污染成乱码命令而不执行），再立刻起 sftool（`--before no_reset --connect-attempts 127`）；芯片约 2.5s 后被看门狗复位，启动序列里出现 `SFBL`（bootrom），窗口约 2s，正好覆盖 sftool 的重试期。脚本：`.v3_work/flash_wdog2.py`
- **阻塞点**：多次失败尝试后，板子的 USB 桥（`1a86:55d3` CH343）从 USB 总线消失，`/dev/ttyACM0` 不再存在，软件无法恢复（root hub 设备节点无权限、RTS/DTR 均不控制电源）——**需要人工拔插一次 USB**。
- 插回后一条命令跑完验收：`.v3_work/accept_all.sh`（烧录 → `audio_setup` → 30×`audio_rec_test 1` → `/proc/meminfo` 前后对比 → `hexdump` 抓 PCM → 统计「不同取值个数 / 精确 0 与 −1 占比」，全部按 §4.2 的串口三坑处理）。
- **2026-10-11 更新：已在冷上电窗口烧录并跑完 30 轮，结果见 §8.5；同时更正了断言的根因，见 §8.6。**

### 8.5 真机 30 轮验收结果（2026-10-11）

镜像：`out/audio_test/nuttx.bin` 构建于 2026-10-11 11:02（串口 `uname` 回显 `Oct 11 2026 11:02:41`）。

| 验收项（§4.1） | 结果 |
|---|---|
| 1. 连续 30 次 start/stop 不挂死、不断言 | ✅ **30/30 轮 `rec_test: done, 32768 bytes`，零断言、零 panic、零 dumpstack**（另加 10 轮复测同样干净） |
| 2. 音质不回退 | ⚠️ 数据真实但幅度极低，见下 |
| 3. `nobuf` 保持 0 | ✅ 30 轮每轮 `[mic-dbg] … nobuf=0` |
| 4. 无内存泄漏 | ✅ **30 轮前后 `/proc/meminfo` 完全一致**（`used=37584`、`free=8783656`、`maxused=84888`、`nused=107`、`nfree=9`）；另有 10 轮复测同样一致 |

最终一轮原始证据（`.v3_work/accept_raw.log`，30 轮逐轮）：`rec_test: done, 32768 bytes` × 30、`nobuf=0` × 30（非 0 计 0 次）、断言/panic/dumpstack 计 0 次、每轮都是 `irq=18 cplt=18`（18 个半缓冲 = 36864 B 进、32768 B 交付，与设计一致）。

**音质（第 2 项）结果：当前 16 kHz capture 数据不满足设计阈值；是否为麦克风硬件无响应，仍待模拟输入实测**：

- 30 轮那次 PCM：`不同取值个数 = 98`、`精确 0 = 2.92%`、`精确 −1 = 3.22%`（合计 6.14%）；3 秒采集 98304 B：`99 / 3.01% / 3.08%`（合计 6.09%）——未达 §4.1 的字面阈值（>100、<5%）。
- 起初归因为「环境无人说话」。随后做了**决定性实验**：15 秒与 20 秒两次采集，明确要求「从第 3 秒起连续大声拍手/敲桌 5 下，再说一句话」，结果 **逐秒 RMS 恒为 12.8~13.1、peak 43~62**，敲击与说话**没有引起任何变化**（原始逐秒数据：`.v3_work/mic_speech_run.log`、`.v3_work/mic_knock_run.log`）。
- 对照 28 号文档 §8.3「已修复」基线：`max16 = 153/342`、`[mic-raw] ≈ ±100~240`、`peak/avg = 265/49`；本轮 `max16 = 33~40`、`[mic-raw] ≈ ±20~30`、`peak/avg = 46~62/10`，**低 5~8 倍**。
- 结论（严格限定为这次能支持的范围）：**已采到的是小幅、近乎平稳的模拟输入波形**，但这两次受控采集里它**没有显示出与拍手/说话同步的响应**；这还不能单凭 PCM 证明麦克风坏、模拟输入浮空、MICBIAS 丢失或 H1 通道错。`pcm-audio` 对 20 秒 PCM 的结果：0% 削波、无异常静音段、0 爆音、Noise RMS 12.94、SNR 68.1 dB；报 158.4 Hz/6.31 ms 周期（RMS 谱估算：45–65 Hz 带 RMS 2.26、145–165 Hz 带 RMS 1.52），是待解释的低幅度分量，**不足以据此断言 50 Hz 市电拾取或高阻浮空**。
- **H2 的“寄存器 enable 位未置位”版本已被活动快照反证**：活动采集中 `xd 0x50088070 0x50` 的 COMMON_CFG 后一个 word（`BG_CFG0`，寄存器偏移 `+0x74`）为 `0x00001931`（EN=1、VREF_SEL=0xc、MIC_VREF_SEL=4、EN_RCFLT=1、EN_AMP=1），`REFGEN_CFG`（偏移 `+0x80`）为 `0x00000005`（REFGEN_EN=1）；`xd 0x500880ac 0x18` 读得 `ADC_ANA_CFG=0x2e`（MICBIAS_EN bit=1）、`ADC1_CFG2=0x0e`、`ADC2_CFG2=0x0e`（两者 EN/RSTB=1）；`xd 0x50088020 0x08` 读得 `ADC_CH0_CFG=0x00010c9d`（CH0 enable、DMA_EN=1）和 `ADC_CH1_CFG=0x00000a00`。这些寄存器地址按 `AUDCODEC_TypeDef` 的字段顺序计算。更正先前草稿的偏移/解码错误：`BG_CFG0` 是 `AUDCODEC_BASE+0x74`（不是 `+0x84`），`ADC_ANA_CFG` 是 `+0xac`；本次仅保留上述重复活动采集的读数。注意：读数只证明寄存器状态，不能证明芯片内部参考/偏置电压在电气上达到规格，更不能证明声音信号已到达 ADC。
- **H1 数字 FIFO 选择已确认，模拟映射仍未知**：驱动使用数字 AUDCODEC CH0；HAL `Receive_DMA` 将 CH0 DMA 源选为 `ADC_CH0_ENTRY`。不应写作“CH0 对应模拟 ADC1”。信号增益码也不能证明路由；旧版 `0x4=4 dB / 0x1e=30 dB` 数值注释不可靠（独立 PAC 描述分别为 +18 dB / +20 dB，详见 docs.rs）；`ADC1_CFG1/ADC2_CFG1` 与数字 `ADC_CHx_CFG/ENTRY` 是分开的寄存器组，现有代码没有证明它们如何接通。
- **排除 H3（PDM）**：官方 DevKit-LCD V1.2.0 原理图确认板载 M0100 为 WMM7037ATEN0 模拟输出 MEMS；其 pin4 OUTPUT 经 `MIC_ADC_IN` 接到模组 pin37，`MIC_BIAS` 到 pin36。开发板不是 PDM 麦；原理图没有显示从模组 pin37 到 SoC 内部 ADC1/ADC2 的细节。
- **不是 v3 引入的**：v3 只改所有权与生命周期；这次没有显示生命周期回归。先前 15/20 秒的“无响应”结论也应限定为：**当前这份固件/当前摆放和距离下，记录到的拍手/语音窗口没有可辨认的响应**，不是证明模拟输入端完全没有拾音。00_README 已将功能状态降为「半通」。

### 8.7 复核结果与下一步（模拟信号来源仍未闭环）

本次先做只读核验，未改代码/烧录：
1. `xd` 活动录音时读取寄存器，观察到 MICBIAS/REFGEN/ADC2 enable/CH0 enable 位如 §8.5 列出；参考/偏置的电气电压仍需要高阻万用表在**可接触测试点**验证，不要触碰或短接 codec 引脚。
2. 下载并检查厂商 [DevKit-LCD V1.2.0 原理图 ZIP](https://downloads.sifli.com/hardware/files/documentation/SF32LB52-DevKit-LCD_V1.2.0.zip?)：`SF32LB52-DevKit-LCD-1-SCH_V1.2.0.txt` 中 M0100 是模拟输出 MEMS，`MIC_ADC_IN` → 模组 U0100-A pin37，`MIC_BIAS` → pin36；线路在模组边界停止，无法从该图推出内部 ADC 实例。模块图同样不能证明 ADC1/ADC2 对应关系。
3. HAL `Config_Analog_ADCPath()` 同时设置 ADC1 与 ADC2 analog blocks；HAL `Receive_DMA(CH0)` 则只选 `ADC_CH0_ENTRY`，两个命名空间之间没有找到映射。测试 CH1 前必须补齐它的 DMA request/instance/IRQ 和 teardown：本板 `dma_config.h` 定义 ADC0/ADC1 request 39/40，但只给 ADC0 配了 DMAC1_CH4 instance/IRQ；HAL 对任何非 CH0 的 ADC 通道都走 CH1 entry。因此**不能把现有 CH0 DMA handle 直接换成 CH1 就烧录试验**，否则结果不可解释且可能误配置 DMA。
4. 下一步优先做不改固件的外部核查：高阻万用表量模组 pin36/MIC_BIAS 相对 GND 是否有偏置（避免探测短路），以及在可接触 pin37/MIC_ADC_IN 上用高输入阻抗示波器看拍手/语音相关波形；若这两个测试点不可接触，应向 SiFli 索取模组内部 MIC_ADC_IN→ADC channel 资料。然后在单独、正确配置 ADC1 DMA 的试验镜像里做 CH0/CH1 A/B，并以采集窗口内事件相关 RMS/峰值作判断。

| 假设 | 当前状态 |
|---|---|
| 板载麦是 PDM | **排除**：原理图证实是模拟输出 MEMS |
| 驱动误选数字 CH0/CH1 | **未知**：CH0 使用事实确定，CH0/CH1→模拟 ADC 映射无证据 |
| MICBIAS/REFGEN enable/config 位为 0 | **与活动快照不符**：对应位均为 1；实际偏置/参考电压及其电气功能仍待外测 |
| 物理麦克风/模组连接、实际偏置、模拟源映射问题 | **待查**：需要外部测试点测量或厂商映射资料 |

### 8.8 1 kHz 播放音复核（2026-10-11）

按 15 秒采集窗口测试手机播放 1 kHz：采集 483,328 B / 241,664 样本，`nobuf=0`、`irq=cplt=238`、`max16=36`；`distinct=110`、0/−1 合计 6.14%、全局 peak=60。每秒 RMS 在 12.77~13.10、peak 47~60，没有与计划音调窗口相伴的明显整体电平跳升。

频域复核也**没有看到清晰的 1 kHz 窄带音调**：Welch 970–1030 Hz 最大峰在安静前段约 990 Hz / PSD 0.054，预期播放段约 986 Hz / 0.040，安静后段约 977 Hz / 0.040；970–1030 Hz 积分 RMS 在三段均约 0.71~0.75。`pcm-audio` 结果仍是 0% clipping、无异常静音、0 pops、noise RMS 12.92 / SNR 68.1 dB，并报 155.3 Hz 周期成分。

**结论仅限本次数据**：这份 PCM 中没有可确认的 1 kHz 音调响应，支持“本次播放/摆位未在录音中形成明显可辨识响应”；但没有独立测量手机音源在麦克风位置的声压或记录确定的播放起止标记，因此仍不能据此证明麦克风、MICBIAS 或 ADC 路径硬件故障。下一步先用第二台设备/声压计确认播放音调实际到达板载麦克风位置，并同步明确起止时间再重测；之后才进行测试点电气检查。原始文件：`.v3_work/mic_tone_run.log`、`.v3_work/mic_tone.pcm`。
**验收脚本自身的两个坑（本轮踩到并修好，记录备查）**：
1. 判断驱动是否已注册不能用 `b"audio0" in probe`——命令回显里也有 `audio0`，会假阳性 → 判定「没注册」应看 `stat failed`/`No such`；
2. 提示符正则**不能带前导 `\n`**：串口分块会把 `"\n"` 和 `"nsh> "` 切进两次 read，第二次 read 从行中间开始就永远匹配不到 → 每轮白等 12 s 并被误判为 `NOPROMPT`（本轮 30 轮曾因此全部误报，改用 `rb"nsh> "` 后每轮 2.5 s，`ok=30/30`）。

### 8.9 开始/停止口令同步的复测（2026-10-11）

用户在收到“录音已经开始”后立即发出“开始”，随后约 15 秒录音期间发出“停止”。此次确有用户侧口令同步，但聊天时间戳精度无法与采集样本逐帧对应；串口日志仍显示完整 15 秒任务，不能据口令把 3–9 秒标为确定的音调播放区间。

采集结果：`483,328 B / 241,664 样本 / 15.10 s`，`nobuf=0`，`irq=cplt=238`，`max16=33`，distinct=112，0/−1 合计 6.25%，整体 RMS=12.83、peak=65。半秒窗 RMS 在 12.54–13.17；1 kHz lock-in RMS 在 0.037–0.336，970–1030 Hz 带 RMS 在 0.87–1.52，均未出现能明确归属于音调的稳定时间段增强。`pcm-audio`：0% clipping、无异常静音、0 pops、noise RMS 12.83、SNR 68.1 dB、周期项 156.9 Hz。原始数据：`.v3_work/mic_tone_timed_run.log`、`.v3_work/mic_tone_timed.pcm`；半秒统计：`.v3_work/tone_timed_profile.py` 输出。

**本次仍不能判定麦克风无响应**：播放起止与串口样本时间没有硬件同步标记，也没有独立确认手机实际声压到达麦克风；所以这些数字只支持“这份录音中未观察到明确的 1 kHz 窄带响应”，不能证明测试音已正确播放到位，也不能定位麦克风、偏置或 ADC 映射故障。后续仍按 §8.7：先以第二设备/声压计确认音源，再同步重测；无法做声学确认时再量 pin36 bias 和 pin37 MIC_ADC_IN。
### 8.10 与官方 SDK 文档对照：驱动的麦克风配置是对的（2026-10-11）

思澈官方《Mic增益调试》文档（[docs.sifli.com](http://docs.sifli.com/projects/sdk/latest/sf32lb52x/audio/mic.html)）给出了厂商侧的麦克风配置口径：

| 官方口径 | 本驱动实测 |
|---|---|
| 麦克风走**数字通道 CH0**：`ADC_CH0_CFG`（`ENABLE`/`HPF_COEF=0x7`/`DMA_EN`/`DATA_FORMAT=1`=16bit） | 活动录音读回 `ADC_CH0_CFG=0x00010c9d`，逐位吻合 |
| 数字增益用 `ADC_CH0_CFG_ROUGH_VOL`：0xa=0 dB，步长 6 dB（0xb=+6 … 0xf=+30），`FINE_VOL` 0~0xc 为 0~6 dB | 读回 `ROUGH_VOL=0xc`（+12 dB）、`FINE_VOL=0`，与官方示例一致 |
| 模拟增益只在数字增益仍饱和时微调 `ADC1_CFG1_GC` | 本 HAL 设 `ADC1_CFG1.GC=0x4`；`ADC1_CFG2=0x0e`、`ADC2_CFG2=0x0e`（两个模拟核 EN/RSTB 均为 1） |
| `MICBIAS_EN`、`REFGEN_EN` 必须为 1 | 读回 `ADC_ANA_CFG=0x2e`（MICBIAS_EN=1）、`REFGEN_CFG=0x5`（REFGEN_EN=1）、`BG_CFG0=0x1931`（VREF_SEL=0xc、MIC_VREF_SEL=4） |

**由此可收敛的判断**：官方文档把麦克风与 **CH0** 绑定，并明确 52x 平台用 `bf0_hal_audcodec_m.c` 的 `ADC_CH0_CFG` 调麦克风增益；本驱动的数字通道选择、数字增益、16bit 格式、MICBIAS/REFGEN 配置**与官方口径逐条一致**。
于是 §8.7 的 H1（“驱动选错通道”）**基本可以排除**，剩下的是**电气/器件侧**：MICBIAS 是否真的加到麦上（需万用表量模组 pin36 对 GND）、MEMS 输出是否随声音变化（需高阻示波器看 pin37），以及模组内部 MIC_ADC_IN→模拟 ADC 的走线。
这也解释了为什么三轮代码审查 + 本次配置对照都找不到软件缺陷：**问题不在驱动配置**。

原始读数与解码依据：`.v3_work/mic_tone_timed_run.log`；寄存器偏移按 `vendor/sifli/chips/drivers/cmsis/sf32lb52x/audcodec.h` 的 `AUDCODEC_TypeDef` 字段顺序。
### 8.11 通道映射已证实：CH0 = ADC1 = 麦克风（H1 排除）

厂商开源 SDK 与官方手册把最后一处不确定也钉死了：

| 证据 | 内容 | 出处 |
|---|---|---|
| SDK 音频服务注释 | `stream = ... (1 << HAL_AUDCODEC_ADC_CH0) << 8; // ADC1`、`... ADC_CH1 ... // ADC2`；单声道录音默认 `AUDIO_MIC0_ONLY` → **CH0** | `middleware/audio/audio_manager/audio_server.c` L1229-1241（[SiFli-SDK](https://github.com/OpenSiFli/SiFli-SDK)） |
| 模组规格书 | pin36 `MIC_BIAS` / **pin37 `MIC_ADC_IN` = 模拟音频输入**，pinout 图芯片侧网络名 **ADC1P** | DS5203《SF32LB52-MOD-1 技术规格书 V0.3》 |
| 芯片规格书 | QFN68L **pin41 `ADCP` = 音频 ADC 输入**；全表只有一个音频 ADC 输入脚；「本芯片共集成了一路 24-bit ADC」 | DS5201 V2.5.3 表5-3 / §3.2.5 |
| HW App Note | `AU_ADC1P → ADCP → Single-ended analog MIC input`；MEMS_MIC_ADC_IN 接 **ADCP** | SF32LB52B-E-G-J-HW-Application 表4-15 |
| 用户手册寄存器表 | `0xB0 adc1_cfg1` GC[20:18]（0x4 = 18 dB）、`0xB8 adc2_cfg1` GC[22:18]、`0xAC adc_ana_cfg` MICBIAS_EN | UM5201 V0.8.8 §11.4 |
| 官方 mic 增益文档 | 麦克风数字增益用 `ADC_CH0_CFG_ROUGH_VOL`；仍饱和才动**模拟 `ADC1_CFG1_GC`** | [Mic增益调试](http://docs.sifli.com/projects/sdk/latest/sf32lb52x/audio/mic.html) |

**结论**：麦克风 = `MIC_ADC_IN` → `ADCP/AU_ADC1P` → 模拟 ADC1；官方录音默认走数字 `ADC_CH0`；本驱动全程使用 CH0、`ROUGH_VOL=0xc`、模拟 ADC1 GC=0x4（=18 dB，与 52x HAL 一致）——**与官方单声道录音路径逐项相同**。
因此 8.7 的 **H1（选错通道）正式排除**，8.10 的判断得到手册级证据支持：**软件侧没有可改的东西了，剩下的只能是模拟/器件（MICBIAS 实际电压、MEMS 本体、模组到 ADCP 的连接）**。

（附：ADC2 在 52x 上究竟接什么，官方文档自相矛盾——芯片 DS 说一路 ADC，寄存器却有 ADC1/ADC2 两套；HAL 两颗都配。这不影响本驱动：我们只用官方认可的单声道路径。）
### 8.6 根因更正：那次 `apb_free` 断言来自测试程序的越界释放（不是驱动 UAF）

真机加了引用计数轨迹（`enqueue/deliver/drain/freebuffer/shutdown` 全打印 apb 指针 + crefs）后，8 只 apb 的计数**逐条配平**（enqueue 2 → deliver/drain 后 1 → freebuffer 到 0），但断言仍在第 1 轮出现，现场多出一行：

```
[mdbg-free] apb=0x200170b8 where=0 crefs=1     ← 8 只真 apb, 计数全对
... (共 8 行)
[mdbg-free] apb=0x10 where=0 crefs=0           ← 第 9 次调用, 野指针
dump_assert_info: Assertion failed : at file: /include/nuttx/semaphore.h:523
```

**0x10 = 16 正好是 `g_nbufs` 的值**。`nm` 显示 `g_bufs` 在 `0x200121c0`、`g_nbufs` 在 `0x200121e0` —— 正是 `g_bufs[8]` 的位置；而 `audio_rec_test_main.c` 的释放循环是 `for (i = 0; i < g_nbufs; i++)`。

链条：NuttX 平铺构建下内置应用**每次启动不重新初始化静态/全局变量**（`nuttx/libs/libc/sched/task_startup.c:58-67` 只调用 entrypoint）→ `audio_rec_test` 第二次运行时 `g_nbufs` 从 8 变 16 → 释放循环走到 `i = 8`，越界读到紧邻其后的 `g_nbufs` 本身 → 把 8/16/24… 当成 `ap_buffer_s *` 交给 `AUDIOIOC_FREEBUFFER` → `apb_free` 对无效 mutex 加锁 → 断言（回溯恰为 `apb_free ← sf32lb52_audio_freebuffer ← audio_freebuffer ← audio_ioctl ← FREEBUFFER`）。

**结论**：25 号 §2.1 / 29 号 §2.4 把这个现场归因于「驱动不持引用的 UAF」是**误判** —— 现场一直是测试程序自己越界释放。修复：`audio_rec_test_main.c` 在 `main()` 开头清零 `g_nbufs`/`g_bufs`，循环上界加 `&& i < NBUFS` 双保险。

这不否定 v3 的价值：驱动「可能触碰 apb 就必须自持引用」消除的是**另一类真实缺陷**（上层 FREEBUFFER 与 worker 在飞交付之间的提前释放），§3.4 各项与 `hw_on` 解耦、shutdown 非阻塞也都已落地。但它说明：**测试程序自身的静态状态同样是真机挂死/断言的候选根因**，排查时不能只看驱动。

---

## 九、状态更新（2026-10-14）

> 本节只记录**与本设计目标直接相关**的变化；完整证据链见 [30 号文](30_status_report.md) §25–§26。

### 9.1 真实目标（消灭整机挂死）：已定性 + 已兜底，**未根除**

| | 2026-10-10 时 | **2026-10-14** |
|---|---|---|
| 复现率 | 「连续第 3 次采集必挂」 | **冷启动 10 次复现 3 次**（≈30%） |
| 位置 | 不明 | **固定在开机后 1~3s 的蓝牙 PAN 建链期间** |
| 性质 | 不明 | **整机停摆**：优先级 **240** 的看门狗线程也一行打不出 ⇒ 不是优先级饿死；`CONFIG_DEBUG_ALERT=y` 却**无 fault dump** ⇒ 不是硬 fault；USB 串口**仍然枚举**（控制台是 CH343 桥，与 SoC 无关，**不能**据此判断板子活着） |
| 兜底 | 无（只能拔插 USB 复位） | **20s 硬件看门狗自愈**（实测 3/3 成功）；`hangwatch` 提供 `[alive]` / `[stall]` 与全量任务 dump |
| 根因 | 未定位 | **仍未定位**。相关性最强的是 vendor HCI 的 `Unexpected L2CAP continuation`，但该错误**每次**启动都出现 ⇒ 必要不充分 |

**§4.1 的验收标准因此仍未达成**，但取证条件比 10-10 好得多：

- `[alive]` 每秒一行（`up= ui= stuck= tasks=` + 任务状态直方图），可判定"最后一次心跳停在哪"
- `[stall]` 触发时用 `nxsched_foreach` dump 全部任务（pid/state/prio/stack/name）
- **addr2line 符号化能力**：只要拿到 backtrace 地址，就能直接对
  `out/nuttx_contest_board_ai_agent/nuttx` 定位到**文件:行号**。2026-10-14 就是靠它把
  "手机蓝牙共享开着也连不上"破成一条完整调用链（[30 §26.4](30_status_report.md)）——
  **下次抓到挂死的 backtrace 时，第一件事就是符号化**
- 排查陷阱（**必须遵守**）：`gate=` 列不可信；`[hr_measure] last bpm=` 可能带上一轮旧值；
  `ps` 里 BT 任务集体消失 = **bluetoothd 已经死了**，不是"没连上"

### 9.2 §七「音频子系统未进入出货固件」：✅ 已解决

`.config` 现为 `CONFIG_AUDIO=y`，`CONFIG_EXAMPLES_AI_AGENT_VELA=y` 也挂上了音频块；
真机实测 `audio_rec_test 4` → `done, 131072 bytes -> /data/mic_test.pcm`。

### 9.3 麦克风「对声音无响应」：✅ 已解决（是增益，不是硬件）

§8.5–8.11 的结论是"未见可辨识响应"。**真因是 ADC 路径增益只给了 +12 dB**：
`sf32lb52_audcodec.c` 改成 `HAL_AUDCODEC_Config_ADCPath_Volume(&priv->codec, 0, 30)`
（量程 −60..30）之后：

| | 静音 | 喊话 |
|---|---|---|
| peak | ≈1500 | **19036** |
| avg | ≈300 | **933** |

10× 响应 ⇒ 前端是活的。随后把录音发给 **MiniMax** 识别，真机返回了中文文本，
"听到 → 理解 → 说话"闭环打通（[30 §26.3](30_status_report.md)）。
**注意口径**：这只证明"麦克风能采到可用语音"，**不等于**"识别准确率已量化"——
后者需要一句已知的话落在 ≤4s 窗口内，仍待做。

### 9.4 同一类缺陷再次出现：文件级 static 跨次运行不重置

§8.6 已经指出这一类根因（当时是 `audio_rec_test` 的 `g_nbufs`）。
**2026-10-14 又踩到同一个坑**：`max30102.c` 的 `g_hr_pub`（文件级 static）在第二轮测量时
不为 −1，于是 `orb_advertise` **整段被跳过**、publish 打到已失效的 fd ——
真机表现就是"只有开机后第一次测心率能出数"。修法与 §8.6 同源：在 `main()` 的
`!inited && !running` 守卫里显式复位。

> **教训（应写进检查表）**：本树任何"只做一次"的初始化，只要落在文件级 static 上，
> 都必须在入口显式复位 —— 因为 NuttX 平铺构建**不会**在应用再次运行时重置静态/全局变量
> （`nuttx/libs/libc/sched/task_startup.c:58-67` 只调用 entrypoint）。

### 9.5 本轮新增的两处根因修复（与 v3 目标无关，但影响可演示性）

| 问题 | 根因 | 提交 |
|---|---|---|
| 手机蓝牙共享开着也连不上 | zblue 把"控制器 HCI 命令超时"用 `BT_ASSERT_MSG` 升级成致命错误 → `z_fatal_error` → **bluetoothd 整个进程死掉** | `external/zblue` `78ba2004999` |
| 刷完固件约 20s 一次的启动循环 | 自建看门狗的 `stall` 基准时刻取自线程启动时，开机阶段 UI 心跳还没开始跳 ⇒ 误判卡死 ⇒ 停止喂狗 ⇒ 把正在正常启动的板子复位 | `packages/ai_agent` `79505a6` |
