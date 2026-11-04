# 26. 内存账与扩容评估（SRAM / PSRAM / Flash）

> 日期：2026-10-10
> 分析方法：`codesize` 技能（`analyze_map_file.py` 库级/section 级 + `arm-none-eabi-size -t` 目标文件级）
> 数据源：出货固件 `out/nuttx_contest_board_ai_agent`（2026-09-19 22:17 构建）
> 用途：在加 PPG 心率、外接喇叭、云端 TTS 之前判断"内存够不够、要动什么"。
> 维护约定：口径以 `codesize` 为准；本页的手工聚合只用于**相对排序**（见第九节更正记录）。

---

## 一、三块内存的总账

| 区域 | 容量 | 实测现状 | 判断 |
|---|---|---|---|
| **SRAM** | 512 KB | 静态占用 **421.86 KB（82.4%）**；堆从 `g_idle_topstack` 到 `SRAM_END 0x2007FB00` | **紧**，但够用 |
| **PSRAM** | 8 MB | `kumm_addregion(0x60000000, 8MB)`；真机 `free` 报 `Umem` 总 8,444,912 B、空闲 **7,584,472 B（89.8% 空）** | **几乎没用** |
| NOR Flash | 16 MB | 镜像 **6.94 MB**（`0x12010000`，占 75.2%）+ `/data` littlefs 4 MB（44/1024 块） | 余量充足 |

**一句话**：紧的是 SRAM，PSRAM 基本空着。所谓"内存太小"是**分配结构问题**，不是容量问题。

---

## 二、SRAM 静态占用（库级，`codesize` 口径）

RAM 合计 **421.86 KB** = `.data` 14.62 KB + `.bss` 407.23 KB。

| 库 | .bss | 备注 |
|---|---:|---|
| `libapps_ai_agent.a` | **139.87 KB** | AI 框架 + 团队端侧 LM |
| `libmm.a` | 97.33 KB | **NuttX I/O 缓冲池**（`g_iob_buffer` 单符号 96.8 KB） |
| `libzblue.a` | 87.60 KB | 蓝牙协议栈 |
| `libsched.a` | 36.49 KB | 调度器（含内核工作队列栈 32 KB） |
| `liblibbluetooth.a` | 25.71 KB | 蓝牙 SAL |
| `libarch.a` | 7.41 KB | |
| `libnet.a` | 5.76 KB | |
| `libmbedtls.a` | 2.81 KB | |
| `libc.a` | 2.48 KB | |

**分类印象**：平台（I/O 池 + 蓝牙 + 调度 + SAL）≈ **247 KB**，AI ≈ **140 KB**。**平台开销差不多是 AI 的两倍**——"AI 吃内存"是个错觉。

### 单符号 Top（`nm --size-sort`）

| 符号 | 大小 | 归属 |
|---|---:|---|
| `g_iob_buffer` | 96.8 KB | `libmm.a(iob_initialize.c.o)` |
| `_ZL7s_arena` | 64.0 KB | **TFLM Tensor Arena** |
| `sys_work_q_stack` | 32 KB | zblue |
| `g_hp_work_stack` / `g_lp_work_stack` | 16 KB ×2 | 内核工作队列 |
| `s_tls_raw_buf` | 16 KB | `vela_tls.c.o` |
| `s_buf` | 16 KB | `agent_logbuf.c.o` |
| net_buf 池 ×5 | ~45 KB | 蓝牙协议栈 |

---

## 三、`libapps_ai_agent.a` 139.9 KB 明细（目标文件级）

方法：`arm-none-eabi-size -t apps/packages/ai_agent/libapps_ai_agent.a`（归档 bss 合计 **139.9 KB**，与 `codesize` 的 143,225 B 相差为对齐填充）。

| 目标文件 | BSS | 说明 | 可挪 PSRAM？ |
|---|---:|---|---|
| `ai_lm.cxx.o` | **67.8 KB** | 端侧 LM；其中 `_ZL7s_arena` = 64.0 KB，其余状态 < 2 KB | ✅ arena 可挪，**代价是推理变慢** |
| `vela_tls.c.o` | **18.1 KB** | TLS 原始缓冲 | ✅ 冷路径 |
| `agent_logbuf.c.o` | **16.0 KB** | 日志环形缓冲 | ✅ **最佳候选**，几乎无代价 |
| `cron_service.c.o` | **11.0 KB** | 定时任务服务 | ⚠️ 需看访问频率 |
| `lvgl_ui_channel.c.o` | **10.1 KB** | UI 消息通道 | ⚠️ UI 热路径 |
| `ws_server.c.o` | 4.9 KB | WebSocket 服务 | ✅ |
| `message_bus.c.o` | 3.0 KB | 消息总线 | ⚠️ 热路径 |
| `skill_loader.c.o` | 2.0 KB | 技能加载器 | ✅ |
| `llm_router.c.o` | 2.0 KB | LLM 路由 | ⚠️ |
| 其余 66 个 | ~5 KB | | |

**注**：`ai_lm.cxx.o` 的 67.8 KB 里 **97% 是 TFLM arena**（逐符号验证：`_ZL7s_arena` 64.0 KB + `s_ag_devs` 0.8 + `s_resolver` 0.4 + `s_reply_scratch` 0.4 + `interpreter` 0.2 + 其余 < 0.1）。所以"挪 arena = 回收 64 KB"这条成立。

---

## 四、Flash 占用分布（`codesize` 口径）

Flash 合计 **6.94 MB**。

| 库 | 占比 | 大小 |
|---|---:|---:|
| `libapps_ai_agent.a` | **64.4%** | 4.47 MB（其中 `ai_lm.cxx.o` 单符号 **2.86 MB** = 模型权重） |
| `libmbedtls.a` | 5.1% | 361 KB |
| `liblvgl.a` | 4.6% | 330 KB |
| `libzblue.a` | 3.6% | 255 KB |
| `libarch.a` | 2.5% | 181 KB |
| `liblibbluetooth.a` | 2.5% | 177 KB |

**Flash 不是瓶颈**：16 MB NOR 只用了 7.19 MB，加 TTS 权重/提示音都有地方放。

---

## 五、扩容评估：加什么要多少 SRAM

| 目标 | SRAM 增量 | 依据 |
|---|---|---|
| 启用麦克风采集（`CONFIG_AUDIO` + 驱动） | **~5 KB** | 驱动静态结构 + `rx_buf[4096]`；**apb 缓冲走 `lib_umalloc` → PSRAM，SRAM 零成本** |
| 加 PPG 心率传感器 | **几 KB** | I2C 驱动寄存器状态 + uORB 主题发布 |
| 驱动 DAC 播放路径 | **~5 KB** | 静态结构 + `tx_buf`；播放 apb 同样走 PSRAM |
| 外接喇叭（硬件） | **0** | 纯模拟电路，功放使能 `PA10` 已有 |
| 云端 TTS | **0（新模型）** | 见第七节：代码已链接，**不需要端侧 TTS 模型** |
| ~~端侧 TTS~~ | ~~几十~几百 KB arena~~ | **不必要**，见第七节 |

**结论：加心率 + 扬声器合计 < 10 KB SRAM。**

### 为什么音频缓冲不占 SRAM（证据链）

1. `apb_alloc()` 用 **user heap**：`lib_umalloc(`…`)` + `lib_umemalign(`…`)`（`nuttx/libs/libc/audio/lib_buffer.c:68-69`）
2. `arm_addregion()` 把 PSRAM 加进堆：`kumm_addregion((void *)PSRAM_START, PSRAM_SIZE)`（`vendor/sifli/chips/sf32lb52/sifli_allocateheap.c:203`）
3. 真机 `free`：`Umem` 总 8,444,912 B —— 即 PSRAM 那块

---

## 六、可挪移清单（按性价比排序）

| 优先 | 目标 | 回收 | 性能代价 | 风险 |
|:--:|---|---:|---|---|
| 1 | `agent_logbuf` 16 KB | 16 KB | 极低 | 日志路径非实时 |
| 2 | `vela_tls` 18.1 KB | 18.1 KB | 低（TLS 非热循环） | 无 |
| 3 | TFLM arena 64 KB | 64 KB | **中高——arena 是每层激活值，高频访问，PSRAM 走 MPI1 QSPI 延迟远高于 SRAM** | 推理明显变慢 |
| 4 | `g_iob_buffer` 96.8 KB | 需先缩容再谈 | — | **网络/蓝牙热路径，改动风险最高** |

**注意**：栈（`sys_work_q_stack` / `*_work_stack`）**不应挪 PSRAM**。策略是"热路径留 SRAM、冷/大块放 PSRAM"。

---

## 七、重要发现：云端语音与心率链路**已经现成**

这一节推翻了早期的两个结论（"TTS 要新模型"、"问心率要重训模型"）。

### 7.1 云端 LLM 已有 `get_heartrate` 工具（端侧 LM 没有）

| 环节 | 位置 | 状态 |
|---|---|---|
| 工具注册 | `packages/ai_agent/src/tools/tool_registry.c:372` | ✅ `"get_heartrate"` → `tool_get_heartrate_execute` |
| 关键词路由 | `packages/ai_agent/src/core/agent_loop.c:351` | ✅ `{ kw_heartrate, "get_heartrate", "{}", 256 }` |
| 执行器实现 | `packages/ai_agent/src/tools/tool_health.c` | ❌ **空壳** |

执行器受编译期开关限制：

```c
#if defined(CONFIG_UORB) && defined(CONFIG_FITNESS_ALGO)
    orb_read_once(ORB_ID(algo_heartrate), &hr);   /* 读 uORB 心率主题 */
#else
    "{\"error\":\"fitness topics not available\"}"   /* ← 出货固件走这里 */
#endif
```

出货固件现状：`CONFIG_FITNESS_ALGO` **未设**、`nuttx/include/fitness/` **目录不存在**、`algo_heartrate.h` **找不到**。

**结论**：走**云端**路径加心率，**模型一行都不用改**——只需提供 uORB `algo_heartrate` 主题（补 fitness algo + 头文件，把 PPG 读数发布上去）。
**只有"离线用端侧 LM 问心率"才需要重训**（端侧 LM 是 9 个硬编码工具、工具名由模型生成 token，无法凭空生成新 call）。

### 7.2 云端 TTS 与音频播放**已链接进固件**

| 对象 | text | 作用 |
|---|---:|---|
| `volc_tts.c.o` / `volc_tts_ws.c.o` | 3.2K / 5.6K | 火山引擎云端 TTS |
| `volc_asr.c.o` | 9.2K | 云端 ASR |
| `voice_channel.c.o` | 8.3K | 语音通道编排 |
| `audio_capture.c.o` / `audio_playback.c.o` | 1.4K / 1.0K | 采集 / 播放 |
| `voice_tts.c.o` / `voice_asr.c.o` | 0.8K / 1.0K | 封装 |

均在 `packages/ai_agent/CMakeLists.txt:63-70` 编译，且在 `nuttx.map` 中**确认已链接**（引用次数 149~548）。

播放走 NuttX **media_player** 框架：

```c
void* player = media_player_open(MEDIA_STREAM_MUSIC);
media_player_prepare(player, NULL, "format=s16le:sample_rate=16000:ch_layout=mono");
```

**但被三道闸挡住**：

| 闸 | 现状 | 要做什么 |
|---|---|---|
| `CONFIG_MEDIA` | **`# CONFIG_MEDIA is not set`** → `media_player` 不可用 | 开启 `CONFIG_MEDIA` |
| 驱动 DAC OUTPUT | 团队标注 `/* 播放能力: P1 未实现 */` | 写 DAC 播放路径（HAL API 齐全） |
| 火山引擎凭证 | `volc_tts.c` 读 `AGENT_CFG_KEY_VOLC_API_KEY` | 配置 TTS 凭证 |

**结论**："让设备说话"**不需要端侧 TTS 模型**，因此我早期担心的"TTS 要吃几十~几百 KB arena"**不存在**。

---

## 八、分析方法与已知局限

**用的技能**：`codesize`（`analyze_map_file.py` 出库级/section 级 + JSON；`arm-none-eabi-size -t` 出目标文件级）。

**`memdump` 技能无法落地**（已核实，非遗漏）：

| 检查 | 结果 |
|---|---|
| `apps/system/memdump/` | **不存在** |
| `grep -rn 'MEMMAP\|memdump' apps/*/Kconfig` | 无命中 |
| 唯一相关的 `apps/system/lsan` | `depends on SIM_ASAN` → **仅模拟器**，真机不可用 |

因此**运行期堆/泄漏分析缺失**。可行替代：设备连上后用 `free` 做"事件前后差异对比"（`memdump` 技能场景二的降级版），但这**达不到符号级/backtrace 级**，不应冒充。

**工具局限**（引用数字时注意）：
- `codesize` 的 Overview 把 `.rodata` 报成 0 B，但 By Library 表里各库 `.rodata` 有值 → **Overview 的 rodata 行不可信**
- By Library 的 "Total Size" 列（如 `libarch.a 75.05 MB`）含未链接/调试段，**只有它分列的 `.text/.rodata/.data/.bss` 可用**

---

## 九、更正记录

| 早期结论 | 更正 |
|---|---|
| "AI 相关 SRAM 104.4 KB" | ❌ 错。我的 map 正则只匹配 `.bss.<sym>` 形式，漏了非该前缀的段。**正确值 139.9 KB**（归档）/143,225 B（section） |
| "第一个该挪的是 64 KB arena" | 部分对：`ai_lm.cxx.o` 的 67.8 KB 里 97% 是 arena，但**整库 139.9 KB 里还有 logbuf 16 / tls 18.1 / cron 11 / ui_channel 10.1 等更易挪的** |
| "加 TTS 要端侧新模型，arena 要吃内存" | ❌ 错。云端 TTS 代码**已链接**，被 `CONFIG_MEDIA` 挡住；**零新模型** |
| "问心率要重训端侧模型" | ⚠️ 只对端侧路径成立。**云端已有 `get_heartrate` 工具 + 关键词路由**，只缺 uORB 主题 |
| "心率/扬声器要动端侧模型" | ✅ 正确结论：**基本不用动** |

---

## 十、待办

- [ ] 运行期堆前后对比采样（需设备连接 + 同意真的调用 AI 功能）
- [ ] 若 SRAM 告急：先挪 `agent_logbuf` 16 KB 与 `vela_tls` 18.1 KB（低代价），arena 64 KB 作为后手
- [ ] 心率：补 uORB `algo_heartrate` 主题（fitness algo + 头文件），验证云端 `get_heartrate` 返回真值
- [ ] 语音：开 `CONFIG_MEDIA` + 写驱动 DAC 播放路径 + 配火山引擎凭证
- [ ] 麦克风：修 D-cache（见 [25](25_flash_reliability_and_audio_defects.md) 25.4）后把 `CONFIG_AUDIO` 编入正式固件
