# 公共仓改动归档（packages_ai_agent）

> 目录用途：按大赛规则，**公共仓的改动不直接放在专属仓里**，而是 fork + PR 到 `dev-ai-contest-2026`。
> 这一份 patch 是那批改动的**完整快照**，目的是让评委在专属仓内就能看到「改了哪些文件、改了什么」，
> 即使 PR 尚未合入也不影响评审。

## 文件

| 文件 | 内容 |
|------|------|
| `packages_ai_agent-2026-09-18.patch` | 公共仓 `packages_ai_agent` 的完整 diff（22 个文件，+1398 / -24 行） |

- **基线提交**：`fe3c9c0`（"snapshot: UI/pet/launcher + local_lm wiring + time_sync + PAN netmgr updates"）
- **改动范围**：`fe3c9c0..36e149b`，共 **10 个提交**
- **本地分支**：`bletest`（仓 `packages/ai_agent`）
- **待办**：fork `open-vela/packages_ai_agent` → 推送该分支 → 发起 PR 到 `dev-ai-contest-2026`

## 这 10 个提交做了什么

| 提交 | 内容 |
|------|------|
| `45e35fb` | 新增关怀调度器 `pet_care`（空闲关怀 / 闹钟状态机 / 静默期 / 冷却） |
| `8ff4528` | 新增模拟心率 `hr_monitor` + 阈值告警 + `hr_set` 命令 |
| `f309df6` | 措辞约束说明（去医疗化表述） |
| `36ae09a` | 上机前加固：首次关怀不被冷却误拦、L2 非阻塞入队、状态互斥、`rand_r` 替换全局随机 |
| `af0ed27` | 心率告警冷却独立为 60 秒（软复位保留 RAM 状态，原 10 分钟会吞掉演示） |
| `5f8c326` | **两个真缺陷**：LVGL 跨线程竞态（加锁投递队列）、1 Hz 调度实际 0.2 Hz（改墙钟门控） |
| `4ac9e47` | 经典蓝牙名 App 侧覆盖（宿主侧生效；空口为平台层限制，见 `docs_ble/24.7`） |
| `d29d2e3` | 新增云端工具 `set_alarm` / `cancel_alarm` + 内置 Skill `care-reminder` |
| `107eb4e` | 让 Skill 在「手表提醒」场景优先于 `cron_add`；空收尾文本不再误报错误 |
| `36e149b` | 配置原子写（修 API key 被整文件覆盖）+ 内置技能内容更新 + 退避窗口 300→60 秒 |

## 为什么必须走公共仓 PR（而不是搬进专属仓）

`packages/ai_agent` 是 openvela 的一个**独立公共仓项目**（`openvela.xml:208`）。专属仓通过 manifest 的
`<linkfile>` 只能把**新增文件**挂进构建树（现有 `ai_lm.cxx` / `tokenizer.c` 即如此），**无法覆盖**该仓已有的跟踪文件。
而本批改动中：

- **新增文件**（可在专属仓留副本）：`src/ui/pet_care.c/.h`、`src/health/*`、`src/tools/tool_alarm.c/.h`
- **修改既有文件**（只能在公共仓改）：`agent_loop.c`、`config_store.c`、`skill_loader.c`、`tool_registry.c`、
  `llm_router.c`、`key_input.c`、`pet_page.c`、`lvgl_ui_channel.c/.h`、`pet_display.c`、`message_bus.c/.h`、`agent_main.c`、`CMakeLists.txt`

因此唯一干净的路径是公共仓 PR（与 `docs_ble/23` 中已有的三项修复同一套做法）。

## 如何应用这份 patch

```bash
# 1) 拉到对应版本的公共仓
repo init -u https://github.com/open-vela/contest2026_181_womenshayebuhuidui \
  -b dev-ai-contest-2026 -m contest2026_181_womenshayebuhuidui.xml
repo sync -c -j8

# 2) 在 packages/ai_agent 里应用（基线应为 fe3c9c0）
cd packages/ai_agent
git log -1 --format=%h          # 确认是 fe3c9c0（若已前进，可能需 git apply -3）
git apply /path/to/packages_ai_agent-2026-09-18.patch

# 3) 编译烧录后按 docs/test_runbook.md 验证
```

> 提示：patch 用 `git apply`；若基线已前进导致上下文偏移，可用 `git apply -3`（三方合并）或直接参考上面的提交清单逐个 cherry-pick。

## 校验

- 密钥复扫：`grep -cE "Er8s[A-Za-z0-9]{40,}|sk-[A-Za-z0-9_-]{20,}|gho_[A-Za-z0-9]{20,}" *.patch` → **0**
- 生成方式：`git -C packages/ai_agent diff fe3c9c0..HEAD`
