# 32. Skill 体系：运行时技能与开发沉淀

> 对应赛题要求：**自定义 Skill 至少沉淀 1 个（硬性）**；AI 硬件产品创新赛道须说明
> **运行时 Skill（`/data/agent/skills/`）的定义与触发场景**。
> 本文把两类 Skill 讲清楚：设备运行时真正跑的那套机制，以及开发过程沉淀下来的方法论。

## 0. 一句话

作品里的 Skill 不是"写几个 Markdown 摆着"，而是一套**可加载、可注入、可热更新、可自创建**的运行时机制：
固件内置 11 个 Skill，设备还支持从 `/data/agent/skills/` 加载文件式 Skill、通过 REST 接口增删、
以及让 Agent 用 `skill-creator` 给自己写新 Skill。开发侧另沉淀了 3 个方法论 Skill。

---

## 1. 运行时 Skill

### 1.1 三层来源

| 层 | 位置 | 说明 |
|---|---|---|
| **内置** | 固件 `src/tools/skill_loader.c` | 11 个，烧录即存在，不依赖文件系统 |
| **文件式** | `AGENT_SKILLS_DIR` = `/data/agent/skills/*.md` | 可下发、可增删，重启后仍存在 |
| **自创建** | Agent 调用 `skill-creator` 写 `SKILL.md` | 设备在运行中扩展自己的能力 |

目录宏定义：

```c
// include/agent_config.h
#define AGENT_DATA_DIR  CONFIG_EXAMPLES_AI_AGENT_VELA_DATA_DIR   // 本板为 /data/agent
#define AGENT_SKILLS_DIR AGENT_DATA_DIR "/skills/"               // → /data/agent/skills/
```

启动时创建设备目录（`src/agent_main.c`）：

```c
mkdir("/data/agent", 0755);
mkdir("/data/agent/config", 0755);
mkdir("/data/agent/memory", 0755);
mkdir("/data/agent/sessions", 0755);
mkdir("/data/agent/skills", 0755);      // ← 技能目录
```

### 1.2 内置 Skill（11 个）

注册表在 `skill_loader.c`，名称与能力如下：

| Skill | 作用 |
|---|---|
| `care-reminder` | **关怀叫醒**：到点前预告、到点主动开口、无响应再催（本作品核心，详见 1.4） |
| `weather` | 天气查询与预报 |
| `daily-briefing` | 个性化每日简报 |
| `skill-creator` | **让 Agent 自己创建新 Skill**（写 `SKILL.md` 到技能目录） |
| `system-health` | 设备状态自检与汇总 |
| `reminder` | 普通定时提醒（静默通知，与 care-reminder 区分） |
| `note-taker` | 快速笔记写入日记文件 |
| `translate` | 多语言翻译 |
| `news-digest` | 按兴趣检索并汇总新闻 |
| `feishu-test` | 飞书机器人端到端自测 |
| `task-manager` | TODO 清单增删查 |

### 1.3 加载与注入链路

```
启动        agent_main.c        创建 /data/agent/skills/
            ↓
加载        skill_loader.c      内置表 + 扫描 AGENT_SKILLS_DIR
            ↓
注入        context_builder.c    skill_loader_build_summary() → 摘要进 LLM 系统提示
            ↓
决策        agent_loop.c        LLM 依据摘要决定调用哪个设备端工具
            ↓
热更新      api_handler.c       GET/POST /api/skills、DELETE /api/skills/{name}
            agent_loop.c        目录变化时自动重载
            ↓
同步        nsh_commands.c      skill_sync：从飞书多维表格拉取技能
```

关键点：**注入给模型的是"技能摘要"，不是全文**。这决定了 Skill 能否被发现——
摘要与工具描述比正文长度更重要（见 1.4 的真实调参过程）。

### 1.4 赛题要求：`care-reminder` 的定义与触发场景

**文件**：`app/ai_agent/skills/care-reminder.md`（随固件内置，烧录后即存在于 `/data/agent/skills/`）

**触发场景**：用户说「X 分钟后提醒我 / 叫我 / 喊我起床」，或指定某个钟点——
凡是希望**用户被手表主动提醒到**的，都触发本 Skill。

**行为定义**：

1. 先算分钟数（给了钟点先用 `get_current_time` 换算）
2. 调 `set_alarm(minutes)`，范围 1–1440
3. 用用户的语言简短确认，并说明设备行为（到点前 1 分钟预告、到点叫醒、没反应再催）
4. 取消时调 `cancel_alarm`
5. 同一次请求只调一次 `set_alarm`

**为什么需要它**：内置的 `reminder` / `cron` 只会往消息历史里静默塞一行文本，叫不醒任何人；
`set_alarm` 才会让设备端主动开口。Skill 的价值正是把这条差别显式告诉模型。

**一次真实的调参过程**（说明"可发现性"比"正文长度"重要）：
第一次演示时模型选了 `cron_add`，且没有真正读取 Skill 文件——它只凭系统提示里的一行摘要和工具描述做决定。
改三处后立刻生效：①摘要行写明优先级；②工具描述加 `PREFER THIS over cron_add`；③内置 reminder 指向本 Skill。

---

## 2. 开发过程沉淀的 Skill

| Skill | 位置 | 解决什么问题 |
|---|---|---|
| **SF32LB52 蓝牙 PAN 调试** | `docs/dev_skill_bt_pan.md` | 从十几天真机调试提炼：4 条平台硬规则 +「症状 → 先查什么」对照表 + 工具入口，把"看起来哪里都可能有问题"压成几条先验规则 |
| **NuttX 构建/链接产物取证** | `skills/nuttx-build-forensics/` | 固件行为与源码不符时，用符号表 / 实际编译选项 / 构建依赖去验证产物，而不是继续读源码（弱符号遮蔽、`-O0`、config 没生效、崩溃符号化） |
| **小云手表功能开发纪律** | 工作区 `.claude/skills/vela-contest-feature/` | 5 条硬规则：先取证再改代码、「编译通过」≠「功能生效」、一次只改一层、烧录必须验证真的写进去、用户说"没效果"时先怀疑自己的假设；附本板硬件坑清单 |

这三个 Skill 的共同点：**每一条都是从真实踩坑里长出来的**，不是通用模板。
例如 PAN 调试里的「一条 H4 帧必须一次写进邮箱 ring」，是排查了数天、换过无数无关配置之后才定位的。

---

## 3. 与评审要求对照

| 赛题要求 | 满足方式 | 证据 |
|---|---|---|
| 自定义 Skill ≥ 1 个（硬性） | 运行时 11 个（含 care-reminder）+ 开发沉淀 3 个 | `src/tools/skill_loader.c`、`app/ai_agent/skills/`、`docs/dev_skill_bt_pan.md`、`skills/nuttx-build-forensics/` |
| 说明运行时 Skill 的定义与触发场景 | 见 1.4 | `app/ai_agent/skills/care-reminder.md` |
| 运行时路径 `/data/agent/skills/` | `AGENT_SKILLS_DIR` 宏、启动建目录 | `include/agent_config.h`、`src/agent_main.c` |
| Skill 真正参与决策 | 摘要注入系统提示 + 工具调用 | `src/core/context_builder.c`、`src/core/agent_loop.c` |
| 可扩展性 | 文件式加载 + REST 增删 + 自创建 | `src/infra/api_handler.c`、`skill-creator` |

## 4. 如何新增一个 Skill

**运行时（设备侧）**，三种方式任选：

1. **文件式**：把 `SKILL.md` 放到 `/data/agent/skills/`，重启或等热重载
2. **接口式**：`POST /api/skills`（带 name + content），即时生效
3. **自创建**：对设备说「帮我创建一个翻译技能」，Agent 会用 `skill-creator` 写文件并注册

**开发侧（方法论）**：在 `.claude/skills/<name>/SKILL.md` 写 `description` + 触发词 + 规则，
Claude Code / AIoT-IDE 会在匹配场景自动加载。

## 5. 证据索引

| 内容 | 文件 |
|---|---|
| 内置技能定义与注册表 | `packages/ai_agent/src/tools/skill_loader.c` |
| 技能目录与数据目录宏 | `packages/ai_agent/include/agent_config.h` |
| 启动建目录 | `packages/ai_agent/src/agent_main.c` |
| 摘要注入 | `packages/ai_agent/src/core/context_builder.c` |
| 热重载 | `packages/ai_agent/src/core/agent_loop.c` |
| REST 接口 | `packages/ai_agent/src/infra/api_handler.c` |
| 飞书同步 | `packages/ai_agent/src/channels/nsh_commands.c` |
| care-reminder 定义 | `app/ai_agent/skills/care-reminder.md` |
| 开发沉淀 | `docs/dev_skill_bt_pan.md`、`skills/nuttx-build-forensics/` |
