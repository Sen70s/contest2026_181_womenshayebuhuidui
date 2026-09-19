# 23. 公共仓贡献索引（评审可见入口）

> 日期：2026-08-21
> 用途：本作品的一部分改动**按大赛规则不能放在团队仓**，而是以 PR 提交到 openvela
> 公共仓。这篇是给评委和后来人的索引：改了哪个仓、哪几行、修的是什么、对应 PR 在哪。

## 为什么团队仓里看不到这些代码

《参赛代码提交指南》规定：

> 「若需改动 nuttx 等公共仓库，则不在专属仓内直接 push，而是 fork 对应公共仓、以 PR
> 形式提交到 `dev-ai-contest-2026` 分支，由组委会 review 后合入。」

大赛的官方文档**没有**提供把公共仓改动同步回团队仓的机制（没有 patch 目录约定、没有
manifest revision 覆盖、也没有要求团队仓记录上游 PR）。团队仓能承载的只有文档，所以
就有了这一篇——**公共仓的代码在 PR 里，团队仓里的这份索引负责让它可被找到**。

同时也说明：团队仓里**不放**这些文件的副本。曾经放过（`fw_patches/`），是误判，已撤销，
原因见 `fw_patches/README.md`。

## 本作品在公共仓的改动

这三项都属于平台底层，是 PAN 上网能跑起来的前提条件。技术背景与完整证据链见
`21_pan_breakthrough_authoritative.md`，可复用规则见 `22_pan_engineering_guide.md`。

### 1. `open-vela/external_zblue` — net_buf 池注册

| | |
|---|---|
| 文件 | `port/sections/defines.c`（+28 行） |
| 本地 commit | `d9fb8207cc1` |
| 分支 | `pan/netbuf-pool-registration` |
| PR | **https://github.com/open-vela/external_zblue/pull/231** |

zblue 的 NuttX port 用手写数组 `_net_buf_pool_list[]` 代替 Zephyr 的 linker section 收集
net_buf 池。`pool_id()` 遍历该数组反查指针，**找不到时静默返回 0**（`__ASSERT` 在
release 下被编掉）。任何未注册的池，其 buffer 会拿到 `_net_buf_pool_list[0]` 的
`max_alloc_size` **和它的 `data_pool` 基址**——尺寸不对，且写进别的池的存储区。

本次注册了 `pan_tx_pool`、`rfcomm_tx_pool`、`bt_avrcp_tx_pool`（追加在数组末尾，zblue
内部池下标不变）。其中 SPP 的 `rfcomm_tx_pool` 是既有缺陷，与本作品无关也一并修了。

> 这是三项里最值得单独 review 的：28 行，收益明确，且对**任何**在该 port 上自定义
> net_buf 池的项目都成立。

### 2. `open-vela/vendor_sifli` — 邮箱 ring 写入、ACL 长度上限、堆布局

| | |
|---|---|
| 文件 | `chips/sf32lb52/sf32lb52_bt_adapter.c/.h`、`sf32lb52_bth4.c`、`sifli_allocateheap.c`（4 文件，+116/−105） |
| 本地 commit | `434ffbe` |
| 分支 | `bletest` |
| PR | **https://github.com/open-vela/vendor_sifli/pull/29** |

三件事必须同时生效才自洽，所以在一个 commit 里：

- **邮箱 ring 必须整帧一次写入。** `struct circular_buf` 的 `read_idx_mirror`（LCPU 写）
  与 `write_idx_mirror`（HCPU 写）在同一条 D-cache line，`ring_write()` 结尾的
  `up_clean_dcache()` 会把 HCPU 缓存里那份陈旧 read_idx 一起写回，**回退 LCPU 的读指针**
  → H4 解析失同步 → `Hardware error, hardware code: 0` → 停回 NoCP → ACL 信用耗尽 →
  TX 死锁。旧代码先单独写 1 字节 H4 type 再触发中断，精确打开了这个窗口。
- **合成的 `HCI_Read_Buffer_Size` 把 ACL 长度压到 487** = ring 可用 492 − H4 type 1 −
  HCI ACL 头 4，保证任何 ACL 包都能一次写完。更长的 L2CAP PDU 由 zblue 正常分片。
- **堆上界钉在 0x2007FB00。** 0x2007FB00 以上是 HCPU custom config + 两个
  HCPU2LCPU 邮箱 buffer；`up_allocate_heap()` 原来一直开到 0x20080000，而本工程
  `.bss` ~455 KB、堆只剩 ~32 KB，**确实会分配到邮箱上去**，LCPU 写 HCI 字节等于改写
  malloc 出来的对象。

> 后两项对所有基于 SF32LB52 的板子都成立，不只本作品。第三项还解释了本项目历史上
> 一系列「换个无关配置崩点就转移」的怪现象。

### 3. `open-vela/frameworks_bluetooth` — PAN TX 池与开机自动连接

| | |
|---|---|
| 文件 | `service/stacks/zephyr/sal_pan_interface.c`、`service/profiles/pan/panu_service.c`（+121/−34） |
| 本地 commit | `215d2cd8` |
| 分支 | `bletest`（相对上游共 36 个 commit，是 PAN/BNEP 整条线 + 两个 style 修正） |
| PR | **https://github.com/open-vela/frameworks_bluetooth/pull/591** |

- BNEP TX 池按 1691 MTU 正确取到尺寸后，去掉调试期的诊断脚手架，改为单次分配 +
  100 ms 等待，并加 `tx_nobuf` 计数以区分「池耗尽」和「池坏了」。
- 池从 4 个 buffer 降到 2 个：每个约 1.7 KB `.bss`，而堆必须给邮箱让出空间（见第 2 项）。
  TAP 读循环一次只发一帧、buffer 在 ACL 完成时释放，深度只影响流水。
- 开机自动连接补两处：`last_nap` 缺失时从 bond list 回退取最近配对的 BR/EDR 设备；
  连接成功后回写 `last_nap`。此前旧固件配过对的表冷启动永远不会自动上网。

> 注意 PR 粒度：`bletest` 相对上游 34 个 commit，包含整条 PAN/BNEP 从零实现的调试历史
> （含若干后来被替代的中间方案）。发 PR 时可选择整条线一起提（体现完整工作量），
> 或另开分支只提 `ec9ad5c5` 这类最终结论（便于 review）。

## 提交状态

三个 PR 均已提交至 `open-vela/*` 的 `dev-ai-contest-2026` 分支，等待组委会 review：

| PR | commit 数 | CI | 可合并性 |
|----|-----------|-----|----------|
| [external_zblue#231](https://github.com/open-vela/external_zblue/pull/231) | 4 | **全绿**（10 pass / 1 skip） | MERGEABLE |
| [vendor_sifli#29](https://github.com/open-vela/vendor_sifli/pull/29) | 15 | **全绿**（10 pass / 1 skip） | MERGEABLE |
| [frameworks_bluetooth#591](https://github.com/open-vela/frameworks_bluetooth/pull/591) | 36 | **全绿**（11 pass / 1 skip） | MERGEABLE |

绿的项目包括 checkpatch、clang-format、CLA，以及 `ci_dev` 的五个平台构建矩阵
（aurix / flagchip / goldfish / qemu / sil）。跳过的是 `ci_trunk`（只对 trunk 分支跑）。

## 第二批：`open-vela/packages_ai_agent` — 关怀调度 / 云端工具 / 自定义 Skill（PR 待发）

第一批三项是「手表能上网」的前提；第二批是本作品**主动能力与端云协同**的实现。它们落在
`packages_ai_agent` 这个公共仓项目里（openvela 的 ai_agent 应用本体就在该仓）。

| | |
|---|---|
| 改动范围 | `fe3c9c0..36e149b`，**10 个提交**，22 个文件（+1398 / −24） |
| 本地分支 | `bletest`（工作区 `packages/ai_agent`） |
| 完整 patch | `docs/upstream_patches/packages_ai_agent-2026-09-18.patch`（附说明见同目录 `README.md`） |
| PR 状态 | **待发**：需 fork `open-vela/packages_ai_agent` 后推送该分支并发起 PR 到 `dev-ai-contest-2026` |
| 专属仓可审阅副本 | `app/ai_agent/pet_care.c/.h`、`app/ai_agent/health/*`、`app/ai_agent/skills/care-reminder.md` |

**包含内容**：

- **新增**：关怀调度器 `src/ui/pet_care.c/.h`（空闲关怀 / 闹钟状态机 / 静默期 / 冷却）、
  模拟心率 `src/health/hr_monitor.c/.h` + `hr_cmd.c`、云端工具 `src/tools/tool_alarm.c/.h`、
  内置 Skill `care-reminder`（`src/tools/skill_loader.c`）
- **修改既有文件**：`core/agent_loop.c`、`core/message_bus.c/.h`、`infra/config_store.c`、
  `llm/llm_router.c`、`tools/skill_loader.c`、`tools/tool_registry.c`、`ui/key_input.c`、
  `ui/lvgl_ui_channel.c/.h`、`ui/pet_display.c`、`ui/pet_page.c`、`agent_main.c`、`CMakeLists.txt`
- **两个真缺陷的修复**：LVGL 跨线程竞态（加锁投递队列）、1 Hz 调度实际 0.2 Hz（改墙钟门控），
  另有配置原子写、内置技能内容更新、退避窗口收敛等加固

**为什么不能搬进专属仓**：`packages_ai_agent` 是独立公共仓项目；专属仓的 manifest
`<linkfile>` 只能把**新增文件**挂进构建树（现有 `ai_lm.cxx` / `tokenizer.c` 即此机制），
**无法覆盖该仓已有的跟踪文件**。因此「修改既有文件」这一步只能在公共仓完成，按大赛规则
走 fork + PR。改动内容已用 patch 形式归档进专属仓，保证评审可见（见上表）。

### 提交过程中修掉的三类门禁问题

记下来供后来人参考，这三条都不是功能缺陷，但会直接卡住 PR：

1. **CLA 认不出假身份。** 早期若干 commit 的作者是 `zcode <zcode@local>`（工具默认
   身份），CLA 机器人无法把它对应到任何 GitHub 账号，报
   `CLA required for 1/3 contributor(s)`。用 `git filter-branch --env-filter` 把这些
   commit 的作者改写为真实贡献者，树内容零变化。**新仓开工前先把 `user.name` /
   `user.email` 设成真实身份**，比事后改写省事得多。
2. **commit message 与源码注释都不许有中文。** checkpatch 里有一道
   `chinese-detector`，commit message 和源文件分开检查。本次一条 commit message
   （`"PIN错误"`）和三处源码注释被拦下。
3. **clang-format 是硬门禁。** 本仓 `.clang-format` 是 `BasedOnStyle: WebKit`：
   `if (x) { body; }` 这种一行式、以及 Allman 风格的 `if (x)\n{`，都会被判违规——
   要么单语句不带花括号，要么左花括号跟在 `if` 同一行、body 独占一行。

本地 patch 备份与命令留在工作区根 `upstream_patches/`（该目录故意不在团队仓内，避免
又变成代码副本）。
