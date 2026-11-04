# 27. 音频驱动审查归档（四轮 pattern 审查 + 两份裁判报告）

> 日期：2026-10-08 ~ 2026-10-10 ｜ 被审文件：`app/ai_agent/audio/sf32lb52_audcodec.c`
> 审查方法：`driver-code-reviewer` 技能 —— **双轮独立 subagent（各自获取源码、互不可见）+ 主流程裁判**
> 本文是**成果归档**：完整保留四轮的量化结论与两份裁判报告，供后续复盘与 v3 设计参考。

## 0. 四轮总览

| 轮次 | 审查对象 | Round 1 | Round 2 | **裁判终评** | 设计健康度 | 关键结论 |
|---|---|:--:|:--:|:--:|:--:|---|
| 第 1、2 轮 | 改动前的原始驱动 | — | — | **9 / 100** | 0/10 (C) | 4 个 Critical：F001 drain 握手缺失(UAF) / F002 `aux` 无锁 / F003 `nxtask_delete` 恒 -EACCES / F004 缺 D-cache invalidate |
| 第 3 轮 | v1 = P1+P2 补丁 | 40/100 | 35/100 | **43 / 100** | 2/10 (C) | F004 真修好（ELF 实证 `Al 4→32`）；F001 只修好快速路径；F003 换成"静默死采集"；F002 未动 |
| 第 4 轮 | v2 = 并发模型重构 | 58/100 | 49/100 | **44 / 100** | 4/10 (C) | 声称的 8 项**全部独立核实成立**；但**自身引入 3 个新问题**（drain 握手结构性不可达 / 栈上 tmp 队列 UAF / `-ETIMEDOUT` 致状态分叉），净分持平 |

> **为什么 v1→v2 分数没涨**：L1-1/L1-2 两个维度因 Critical 触底归零，把它们内部的改善吃掉了。
> 真正变化的是 L1-3（0→7）与 L1-7（9→14）。**v2 修好了它声称的，但引入了新缺陷** —— 其中两条的根因在规格书本身
> （V1"失败即返回错误"未核对 core 的状态回滚语义；V5"用栈上局部队列中转"制造了新窗口）。

## 1. 第 3 轮裁判报告（v1 = P1+P2）

---


> 日期：2026-10-10 ｜ 技能：`driver-code-reviewer`（双轮独立 + 裁判）
> 被审文件：`contest2026_181_womenshayebuhuidui/app/ai_agent/audio/sf32lb52_audcodec.c`（827 行）
> 审查范围：未提交改动（P1 D-cache invalidate / P2a STOP drain 握手 / P2b `nxtask_delete` 返回值）
> 上两轮基线：改动前版本得 9/100，4 个 Critical（F001~F004）

---

### 一、最终结论

**REJECT ｜ 43 / 100 ｜ 设计健康度 2/10（C）**

存在 Critical → 一票否决封顶 60（未触及，43 < 60）。

| 维度 | 满分 | 扣分构成 | 得分 |
|---|---:|---|---:|
| L1-1 内存安全 | 20 | DR-F001 Critical −15；DR-F002 Medium −3 | **2** |
| L1-2 并发安全 | 20 | DR-F003 High −8；DR-F004 Medium −3；DR-F005 Medium −3 | **6** |
| L1-3 资源管理 | 10 | DR-F006 High −8；DR-F007 Medium −3 → 下限 | **0** |
| L1-4 错误处理 | 10 | DR-F008 High −8 | **2** |
| L1-5 类型与数值 | 10 | —（DR-B010 置信度 0.50，仅 WARNING） | **10** |
| L1-6 输入与边界 | 15 | DR-F009 Low −1 | **14** |
| L1-7 嵌入式专项 | 15 | DR-F010 Medium −3；DR-F011 Medium −3 | **9** |
| **合计** | 100 | | **43** |

**设计健康度**：DP-05（芯片专用驱动实现在应用层）−4 + DP-06（`priv->aux`/`pendq` 影子状态）−4 = **2/10 → C**

---

### 二、交叉验证（技能 Step 1~3）

| 最终 ID | 严重度 | R1 编号 | R2 编号 | 置信度 | 处理 |
|---|---|---|---|---|---|
| DR-F001 P2a 超时分支 UAF + 陈旧 post 毒化 | **Critical** | DR-101 | DR-B001 + DR-B002 | **0.95** | 两轮一致，合并（R2 自己建议合并，避免同根因双计） |
| DR-F002 `drain_pending` 丢弃 `aux` 不归还 | Medium | DR-106 | — | 0.80 | 仅 R1，P-05 HIGH |
| DR-F003 `priv->aux` 无锁影子状态（= 旧 F002） | High | DR-102 | DR-B003 | **0.95** | 两轮一致 |
| DR-F004 worker `nxsem_wait` 返回值未检查 | Medium | DR-103 (High) | LOW 观察 | 0.70 | **裁决**：见下 |
| DR-F005 时基用 `CLOCK_REALTIME` | Medium | — | DR-B007 | 0.70 | 仅 R2，触发现实（本产品有 BLE/RTC 对时） |
| DR-F006 ops 表缺 `.freebuffer` | High | DR-105 | 资源对称性表 | 0.80 | 仅 R1，P-21 HIGH |
| DR-F007 `register` 回滚不完整 + 无重入保护 | Medium | DR-107 | DR-B009 | **0.95** | 两轮一致 |
| DR-F008 P2b 恒 `-EACCES` → 静默死采集 | High | DR-104 | DR-B005 | **0.95** | 两轮一致 |
| DR-F009 `GETBUFFERINFO` 不校验 arg | Low | DR-111 | LOW 观察 | 0.80 | 仅 R1 |
| DR-F010 D-cache 协议不完整（缺 pre-DMA clean、诊断读） | Medium | DR-108 | DR-B006 | **0.95** | 两轮一致 |
| DR-F011 `rx_half` 电平 vs `rx_sem` 计数 | Medium | DR-109 | DR-B008 | **0.95** | 两轮一致 |

#### 裁决 1：DR-F004 的严重度（两轮矛盾）

- Round 1 判 **High（−8）**：`CONFIG_CANCELLATION_POINTS=y` 下 `nxsem_wait` 是取消点，可返 `-ECANCELED`/`-EINTR` → 被当成有效唤醒 → 按陈旧 `rx_half` 重复交付。
- Round 2 列为 **LOW 观察（不扣分）**。

**裁判采信：Medium（−3），置信度 0.70。** 理由：API 语义（是取消点、可返负值）**确定成立**，故缺陷为真、不能归零；但**可达性低**——内核线程正常不会被取消，唯一会调 `nxtask_delete` 的正是 DR-F008 那条失效的恢复路径。取两轮之间。

#### 裁决 2：仅 Round 2 命中的三条，按技能规则降级

技能规定"仅 Round 2 + 触发场景不确定 → 置信度 0.50 → **仅 WARNING 不扣分**"。故以下三条**不参与扣分**，但必须记录（触发条件一旦成立即为 High）：

| 编号 | 内容 | 为何触发不确定 |
|---|---|---|
| DR-B004 | `cancelbuffer` 用无成员检查的 `dq_rem` → 对未入队节点会**静默清空整条 pendq**；且不清 `priv->aux` → 同一 apb 二次归还 | **树内无任何 CANCELBUFFER 调用者**（`mic_capture` 不调）。语义确定错误，但当前不可达 |
| DR-B010 | `deliver` 里 `nmaxbytes - curbyte` 为 size_t 无符号减法，下溢 → 越界 memcpy | 需客户端违规（重复入队同一 apb）叠加 DR-F003 才可达 |
| DR-B006(a) | 无运行时行大小校验 | **R2 已用 ELF 实证对齐成立**（新产物 32 对齐 / 旧产物 Al=4），仅剩"若行大小 >32"的理论风险 |

#### 两轮一致确认"确有实效"的部分

1. **P1（D-cache）真修好**：调用点正确（worker、线程上下文、读前）、第二参为 end 地址、`aligned(32)` 前提经 **readelf 实证**（新 `.bss.g_audio Al=32` / 旧 `Al=4`），且 `2048%32=0`、`4096%32=0` → 两半都整行对齐，不走 DCCIMVAC 半行分支。
2. **P2a 快速路径正确**：`nxsem_timedwait_uninterruptible` 的 API 映射（= `nxsem_clockwait_uninterruptible(sem, CLOCK_REALTIME, abstime)`，内部 `while(ret==-EINTR)` 重试）与超时判据 **R2 判定为正确**。
3. **P2b 确实消除了双 worker**（不再丢句柄）——但代价见 DR-F008。

---

### 三、本次改动是否修好了它声称修的

| 声称 | 结论 |
|---|---|
| 修 F004（D-cache） | ✅ **真修好**（残留 DR-F010 的两个子项） |
| 修 F001（drain 握手 UAF） | ⚠️ **只修好快速路径**。超时路径把 F001 原样带回（DR-F001），且**失败语义错误**——超时被当成"已停止"并恒返回 OK |
| 修 F003（双 worker） | ⚠️ 双 worker 消除了，但换成**静默死采集**：`-EACCES` 使重建恒不可达，而驱动仍 `recording=true` + 返回 OK（DR-F008） |
| F002（`aux` 竞争） | ❌ **完全未动**（DR-F003），且已确认高概率触发路径：`mic_capture_flush()` 收到消息立即 `mic_enqueue` 归还，落在 worker 的 `upper(DEQUEUE)` 窗口内 |

---

### 四、v2 修复清单（按优先级）

| 优先 | 对应 | 修法 |
|:--:|---|---|
| 1 | **DR-F001** Critical | drain 握手改"**请求-应答 + 代次**"：STOP 递增 `drain_seq`，worker 完成后带 seq，STOP 只接受本次 seq；**删除超时分支对 worker 状态的锁外重置**（`dq_init`/`aux=NULL`）；超时不得当"已停止"，`stop` 应返回错误 |
| 2 | **DR-F008** High | **删掉整段 stall 探测 + 重建**；worker 改为 `quit` 标志 + `nxsem_post` 自行退出、主线程等 `exit_done` 确认；无法恢复时 `start` 返回 `-EIO` 而非 OK |
| 3 | **DR-F003** High | `priv->aux` 的**全部**读改写移入 `pendlock`；去掉 `:219` 的无条件覆盖 |
| 4 | **DR-F006** High | 实现 `.freebuffer`（持 `pendlock` 摘除 `aux`/`pendq` 引用后再 `apb_free`） |
| 5 | DR-F002 Medium | `drain_pending` 先把 `aux` 经 `upper(DEQUEUE)` 归还再清队列 |
| 6 | DR-F004 Medium | 检查 `nxsem_wait` 返回值（改 uninterruptible 变体） |
| 7 | DR-F005 Medium | 改 `nxsem_clockwait_uninterruptible(&sem, CLOCK_MONOTONIC, ...)` + `clock_gettime(CLOCK_MONOTONIC)` |
| 8 | DR-F007 Medium | `register` 加 `static bool inited` 守卫；失败路径逆序回滚 |
| 9 | DR-F010 Medium | DMA 启动前对 `rx_buf` **invalidate**（丢弃 memset 脏零，**不是 clean**）；诊断 dump 前同样 invalidate |
| 10 | DR-F011 Medium | `rx_half` 改**半缓冲序号/位掩码**，worker 按 `last_consumed+1` 计算目标半缓冲 |
| 11 | DR-F009 Low | `GETBUFFERINFO` 加 arg 校验 |
| 12 | 加固 | `aligned(32)` → **`aligned(64)`**（32/64 都覆盖，代价仅结构体填充）+ `DEBUGASSERT` 校验；真机打印一次 `up_get_dcache_linesize()` 结案 |
| 13 | 潜在（WARNING） | DR-B004 `cancelbuffer` 的 `dq_rem`、DR-B010 size_t 下溢 |

---

### 五、对"点击说话"计划的直接影响

点击说话的交互本质是**反复 start/stop 采集**，而 DR-F001 恰好就在 STOP 路径上，DR-F003 的触发点 `mic_capture_flush()` 又是**每次语音提问都会走**的一步。

**结论：在 DR-F001 / DR-F003 / DR-F008 修好之前，"点击说话"不能作为交付功能上线。** 原计划"P1+P2 作为前置"应扩展为"P1+P2+v2 清单中的 1~4 项"。

---

### 六、需人工确认（不猜）

1. **该芯片 D-cache 实际行大小**（CCSIDR 实测）——离线证据（`sifli_uart.c:59` 的 32、CMSIS 的 32）倾向 32，但 CMSIS 那条针对 **Cortex-M7**，而本芯片是 **Cortex-M33**（`register.h:232 __CM33_REV`），**旁证不同核**。建议 `aligned(64)` + 真机打印。
2. **STOP 200ms 超时在本 app 的真实触发频度**——决定 DR-F001 的实际暴露面（机制已由源码证据确凿）。可在超时分支计数，真机跑语音问答循环后统计。
3. DR-F008 的"调用者必为应用任务"需按最终集成方式确认（是否可能由内核线程发起 `AUDIOIOC_START`）。
4. DR-B007 中 `abstime` 已过期时 `wd_start_realtime`/`clock_abstime2ticks` 的确切行为未逐行追到底。

---

### 七、方法学备注

- 两轮 subagent **各自独立获取源码、互不可见**（技能默认模式），本轮 prompt 未传递任何一轮的结论。
- 裁判在主流程执行，交叉验证表见第二节。
- 本轮**未修改任何被审代码**（技能硬规定）；P1/P2 的实现改动由独立实现 subagent 完成，与本审查完全分离。
- 构建产物证据：`out/audio_test/nuttx`（2026-10-10 11:57）、`.o` 与 `out/sifli_sf32lb52_devkit_lcd_nsh` 的旧产物（2026-08-17）对比。


---

> **⚠️ 后记（2026-10-10 晚，四轮审查之后）**：本归档结束时，v2 的核心争议是
> "94% 常数 PCM 是否由交付路径的并发缺陷造成"，v3 的目标被定为修这个音质问题。
> **后续实测推翻了该归因**：`nobuf = 0` 证明缓冲从未丢过，真因是驱动**漏调
> `HAL_TURN_ON_PLL()`**（ADC 无参考电压），属模拟前端缺失调用，与并发模型无关。
> 补上后真实数据 19% → 99%。**四轮审查之所以都没找到，是因为缺失的调用不在
> "被审代码"范围内，且树内无厂商参考序列可供对照。**
> 音质修复后，**挂死**成为唯一拦路虎 —— 而那正是本归档反复指出的 apb 所有权
> UAF 家族，**v3 的目标因此从"修音质"改为"消灭挂死"**。
> 详见 **[28](28_audio_mic_verification.md)** 第七、八节。

---

## 2. 第 4 轮裁判报告（v2 = 并发模型重构）

---


> 日期：2026-10-10 ｜ 技能：`driver-code-reviewer`（双轮独立 + 裁判）
> 被审：`app/ai_agent/audio/sf32lb52_audcodec.c`（979 行，diff +315/−118）
> 上轮基线：v1（P1/P2）**REJECT 43/100**，设计健康度 2/10（C）

---

### 一、最终结论

**REJECT ｜ 44 / 100 ｜ 设计健康度 4/10（C）**

Critical 存在 → 封顶 60（未触及）。

| 维度 | 满分 | 扣分构成 | 得分 | 上轮 |
|---|---:|---|---:|---:|
| L1-1 内存安全 | 20 | **DR-J01 Critical −15**；DR-J02 High −8 → 下限 | **0** | 2 |
| L1-2 并发安全 | 20 | **DR-J03 Critical −15**；DR-J04/05/06/13 Medium 各 −3 → 下限 | **0** | 6 |
| L1-3 资源管理 | 10 | DR-J07 Medium −3 | **7** | 0 |
| L1-4 错误处理 | 10 | DR-J08 High −8；DR-J09 Low −1 | **1** | 2 |
| L1-5 类型与数值 | 10 | DR-J10 Low −1 | **9** | 10 |
| L1-6 输入与边界 | 15 | DR-J11/Low −1；DR-J12 Low −1 | **13** | 14 |
| L1-7 嵌入式专项 | 15 | DR-J14 Low −1 | **14** | 9 |
| **合计** | 100 | | **44** | 43 |

**设计健康度**：DP-F001（直写 HAL 内部状态）−2 + DP-F002（`recording` 影子状态）−2 + DP-F004（SDK 时钟表复制）−2 = **4/10 → C**（DP-F003 为 Low，按规则不扣分）

---

### 二、⚠️ 必须先说清楚的结论：v2 分数**没有改善**（43 → 44）

两轮都称 v2 是"实质性进步"，但按体系的量化规则，**总分几乎没动**。原因是真实的，不是计分口径问题：

| 变化 | 维度 | 说明 |
|---|---|---|
| ✅ 4 个维度改善 | L1-3 **0→7**、L1-7 **9→14**、L1-1 的 aux 无锁消失、drain 陈旧 post 关闭 | v2 确实修好了它声称的东西 |
| ❌ **v2 自己引入了新缺陷** | L1-2 **6→0**、L1-4 **2→1** | 见下 |

**v2 引入的三个新问题（均为其自身设计所致）**：

1. **DR-J03（新 Critical）**：drain 的完成依赖 `dev.upper()` → `audio_sendmsg()` → `file_mq_send()`，而该回调在 mq 满时**无界阻塞**，唯一消费者正是阻塞在 STOP ioctl 里的应用线程 → **握手在结构上不可达，必然超时**。这是"设计成死锁"，不是竞态。
2. **DR-J01（新 UAF 窗口）**：v2 的 V5 把 apb 搬到 **worker 栈上的局部队列** `tmp` 再放锁回调 → 那段时间 apb 既不在 `pendq` 也不在 `aux`，`freebuffer` **摘不到** → 释放后 worker 仍从 `tmp` 取用。
3. **DR-J08（有害的错误传播）**：v2 的 V1 让 `stop` 返回 `-ETIMEDOUT`，而 core 收到非 OK 会把 `status->state` **恢复为 RUNNING**（`audio.c:698-702`），驱动侧却已 `recording=false` + DMA 停 → 下一次 START 在 RUNNING 态**不调用 `ops->start`** 直接返回 OK → **上层以为在录、实际永久静默**。

> **这三条里，DR-J03 与 DR-J08 的根因在我在规格书里写的 V1 与 V5 设计**（"失败就返回错误"、"用局部队列中转"）。子代理照做了，审查者发现了。**规格是我写的，责任在我。**

---

### 三、交叉验证（技能 Step 1~3）

| 最终 ID | 严重度 | R1 | R2 | 置信度 | 处理 |
|---|---|---|---|---|---|
| DR-J01 worker 裸指针跨锁外窗口 → freebuffer 后 UAF（锁外 memcpy + 栈上 tmp） | **Critical** | DR-004(High) + DR-001(Crit) | DR-201(Crit) + DR-203(High) | **0.95** | 合并、取更严重级别 |
| DR-J02 `cancelbuffer` 无成员检查的 `dq_rem` → 静默清空整条 pendq | High | DR-007(Med) | DR-205(High) | **0.95** | 合并、取更严重级别（树内无调用者，属潜伏） |
| DR-J03 drain 握手因 `upper()` 可无界阻塞而**结构性不可达** | **Critical** | DR-008 提及同机制 | DR-202(Crit) | **0.95** | R2 深化 |
| DR-J04 `nxmutex_lock` 返回值未检查 | Medium | DR-003(High) | — | 0.70 | **裁判裁决：降级**，见下 |
| DR-J05 drain 失败后 `drain_seq` 残留影响下一会话 | Medium | — | DR-206 | 0.70 | 仅 R2，触发现实 |
| DR-J06 `rx_ready` 位掩码丢事件计数（落后时合并、静默丢 64 ms） | Medium | — | DR-207 | 0.70 | 仅 R2 |
| DR-J07 `register` 回滚不完整 + worker 泄漏 | Medium | DR-006(Med) | DR-210(Low) | **0.95** | 合并 |
| DR-J08 `-ETIMEDOUT` 使 core 状态分叉 → 下次 START 静默 no-op | High | DR-002(Med) | DR-204(High) | **0.95** | 合并、取更严重 |
| DR-J09 `start` 无 worker 存活证据即置 `recording` | Low | DR-005 | — | 0.60 | 仅 R1 |
| DR-J10 HAL 音量函数隐式声明 | Low | DR-010 | — | 0.60 | 仅 R1 |
| DR-J11 `enqueuebuffer` 不校验重复归属 | Low | DR-011 | — | 0.60 | 仅 R1 |
| DR-J12 `getcaps` 声称 OUTPUT 但未实现 | Low | DR-012 | — | 0.80 | 仅 R1 |
| DR-J13 `shutdown` 在 core 关中断自旋锁区内阻塞 | Medium | DR-008(Med) | DR-208(High) | **0.95** | 合并、取 Medium（R2 的 High 依据是"不可达"论证，但 R1 与 R2 都判可达性依赖客户端） |
| DR-J14 对齐保证仅 `DEBUGASSERT`（发布构建编译掉） | Low | DR-009 | DR-209 | **0.95** | 合并 |
| DR-211 `freebuffer` 无条件信任 `bufdesc->u.buffer` | WARNING | — | DR-211(Low) | 0.50 | 仅 R2 且树内不可达 → 按规则**不扣分** |

#### 裁判裁决：DR-J04 降级 High → Medium（R1 的机制描述有误）

R1 称"`nxmutex_lock` 可因线程被 cancel 返回 `-ECANCELED`/`-EINTR` 且**不持有互斥量**"。**我查源码，这个机制不成立**：

```c
/* nuttx/include/nuttx/mutex.h:412-431（inline 版本） */
for (; ; )
  {
    ret = nxmutex_wait(&mutex->sem);
    if (ret >= 0) { nxmutex_add_backtrace(mutex); break; }
    else if (ret != -EINTR && ret != -ECANCELED) { break; }   /* ← 这两个会重试 */
  }
return ret;
```

且两个构建的 `# CONFIG_LIBC_SEM_MUTEX_NOINLINE is not set`（我核实过）→ **走的就是这个 inline 版本**。所以 `-EINTR`/`-ECANCELED` **不会**漏出未持锁状态。

**但缺陷类别仍然成立**：其它非 `EINTR/ECANCELED` 的错误会 `break` 跳出并**在未持锁的情况下返回负值**（如 `-EINVAL`），代码不检查就进临界区。故降级为 **Medium（−3）**，置信度 0.70。

（另：R1 说"5 处"，实际是 **6 处**——它漏了 deliver 填满分枝里的 :256。）

#### 两轮在"94.1% 常数 PCM 成因"上**结论矛盾**（需裁决）

- **R1**：`{0, −1}` 并存"恰是 ADC 静默时 ±1 LSB 量化噪声的典型形态"，提示不一定是 cache 问题
- **R2**：明确"我不认为位掩码能解释 94.1% 常数"，但也不认同 R1 的量化噪声说，主张做真机 A/B

**裁判裁决（基于我实际持有的 PCM 数据）**：**两者都不完全成立**。
实测是 **52.7% 精确 0 + 41.4% 精确 −1 + 5.9% 真实数据（≈一个 60 ms 半缓冲）**，且那 5.9% 段的值域是 ±32768。
- "纯陈旧 cache 零行"解释不了 41.4% 的 −1
- "±1 LSB 量化噪声"也解释不了 5.9% 段里的满幅值域，以及它恰好等于**一个 DMA 半缓冲长度**

**故本条仍为未决**，需真机 A/B（R2 建议的三变量隔离是对的：`O_NONBLOCK` mq / STOP 后先取空再 FREEBUFFER / 关闭 DCACHE）。置信度 MEDIUM。

---

### 四、轮次分歧汇总

| 项 | R1 | R2 | 裁判 |
|---|---|---|---|
| 总分 | 58/100 | 49/100 | **44/100** |
| 设计健康度 | 3/10 (C) | 6/10 (B) | **4/10 (C)** |
| DR-J01 严重度 | High（锁外 memcpy）/ Critical（tmp） | Critical（两者） | **Critical**（取更严重） |
| DR-J13 `shutdown` | Medium | High | **Medium** |
| `nxmutex_lock` | High | 未报 | **Medium**（R1 机制有误） |
| 94.1% 常数成因 | ±1 LSB 量化噪声 | 需 A/B，位掩码解释不了 | **均不完全成立，未决** |
| v2 总体评价 | 实质性进步（43→58） | 只修好 2 项、新增 2 Critical | **修好所声称的；但引入 3 个新问题；净分持平** |

---

### 五、v2 到底修好了什么（两轮一致确认）

| 声称 | R1 | R2 | 结论 |
|---|---|---|---|
| drain 代次绑定、陈旧 post 路径关闭 | ✅ | ✅ 基本修好 | **成立** |
| 删除 stall 探测 / `nxtask_delete` | ✅ | ✅（但是"删掉恢复机制"而非"修好恢复能力"） | **成立，附条件** |
| `priv->aux` 全部移入 `pendlock` | ✅ | ✅（但**不解决生命周期**） | **成立，附条件** |
| 实现 `.freebuffer`（签名/返回值/core 语义） | ✅ | ✅ | **成立** |
| `rx_half` → 就绪位掩码 | ✅ | ✅ 原子性 OK（但丢计数） | **成立，附条件** |
| D-cache 协议（start 前 invalidate + 诊断读 invalidate + `aligned(64)`） | ✅ 未经真机 | ✅ | **代码成立，真机待验** |
| `CLOCK_MONOTONIC` + uninterruptible | ✅ | ✅ | **成立** |
| `getcaps` 返回 `ac_len` | ✅ | ✅ | **成立** |

**顺带修复**（两轮均注意到的副作用）：`up_enable_irq`/`up_disable_irq` 的隐式声明随 `#include <nuttx/spinlock.h>` 消失 —— 我构建实测：**告警 5 → 3**，`docs_ble/25 §25.3` 的三处只剩一处。

---

### 六、v3 修复方向（两轮共识）

**根因是一件事**：驱动从不 `apb_reference()`（`crefs` 恒 1），却让 worker 在多处**跨锁外窗口持有裸 apb 指针**。在不加引用的前提下继续打补丁只会不断搬家窗口——R2 说得对。

1. **所有权模型**：`enqueuebuffer` 时 `apb_reference()`，`freebuffer`/`DEQUEUE 后`apb_free()` 配对；或驱动侧维护 **in-flight 集合**，`freebuffer` 对在飞 apb **拒绝/延迟**释放
2. **drain 不能在回调链上等**：把 drain 的"完成"定义改为 **worker 自己置位**（不依赖 `upper()` 返回），或 `upper()` 移出 drain 路径
3. **`stop` 的错误语义重定**：硬件已停后应 best-effort 返回 OK（R1 的论证成立），"未确认 drain"降级为诊断计数；或在 START 里显式重同步
4. **`drain_pending` 不要用栈上局部队列**（改挂回 `priv` 且受锁保护）
5. `cancelbuffer` 用与 `freebuffer` 同款的成员预检（或从 ops 表移除）
6. `shutdown` 路径不做阻塞等待（core 在关中断下调用）
7. `nxmutex_lock` 检查返回值；`rx_ready` 改**计数**而非位掩码；`register` 加 errout 逆序回滚

---

### 七、方法学声明

- 两轮 subagent **各自独立获取源码、互不可见**；本轮 prompt 未传递任何一轮的结论
- 裁判在主流程执行；对被审代码**未做任何修改**
- 裁判独立复核并**纠正了 R1 的一处机制错误**（`nxmutex_lock` 的 `-EINTR`/`-ECANCELED` 重试语义）与**一处计数错误**（5 处 → 实际 6 处）
- 构建产物证据：`out/audio_test`（2026-10-10 12:4x，0 error，本驱动告警 3 条）
