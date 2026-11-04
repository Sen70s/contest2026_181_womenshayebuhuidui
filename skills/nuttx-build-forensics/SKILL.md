---
name: nuttx-build-forensics
description: '当嵌入式固件的行为与源码不符时，用符号表 / 实际编译选项 / 构建依赖去验证编译与链接的产物，而不是继续读源码。针对 NuttX / openvela 这类静态链接、flat build 的工程。Use when 功能在真机上完全失效但代码看着没问题；怀疑符号被 weak 桩遮蔽；第三方库性能异常、怀疑被 -O0 编译；改了 .config/defconfig 却什么都没发生；需要把崩溃日志里的一串地址符号化成文件和行号。Triggers 符号表, nm 弱符号, weak symbol, strong symbol shadowed, stub, 链接问题, 优化级别, -O0, target_compile_options, 构建没生效, stale config, build.ninja, 崩溃定位, backtrace, addr2line, hardfault, 栈溢出。'
---

# NuttX 构建/链接产物取证

## 核心原则

**源码 ≠ 编译结果 ≠ 链接结果 ≠ 运行时行为。**

当行为与源码不符时，绝大多数人会**反复读源码**。
但如果故障发生在"编译/链接"这一层，读源码**永远读不出来** —— 因为源码是对的。

改成逐层验证产物：

| 层 | 问什么 | 用什么查 |
|---|---|---|
| 编译 | 这个文件**实际**用什么选项编的？ | `flagcheck.py`（看 build.ninja 的 FLAGS） |
| 链接 | 这个符号**实际**是强还是弱？实现进来了吗？ | `symcheck.py`（nm 的 T/W/U） |
| 加载 | 配置**真的**生效了吗？规则生成了吗？ | `flagcheck.py --config-check` |
| 运行 | 崩在**哪一行**？那个线程栈多大？ | `symbolize.py`（addr2line + 任务表） |

## 取证流程

### 第 0 步：先判断"这个故障像哪一层"

| 症状 | 先查 |
|---|---|
| 功能 **100% 失效**，日志只有"打开失败/返回 NULL" | **链接层** → `symcheck.py` |
| 功能**正确但慢**，或资源占用反常 | **编译层** → `flagcheck.py` |
| 改了配置/开了开关，**什么变化都没有** | **加载层** → `flagcheck.py --config-check` |
| 硬 fault / 断言 / 重启 | **运行层** → `symbolize.py` |

### 第 1 步：功能失效但代码是对的 → 查符号强弱

```bash
python3 scripts/symcheck.py <elf> '<符号名正则>'
```

**判据**：

- 全是 `W/w` ⇒ **真实现被弱桩遮蔽**。静态链接器先满足了弱定义，
  之后再也不去归档里捞强实现。
  → 修法是**删掉桩**，不是"把真实现加进来"。
- 全是 `U` ⇒ 实现根本没链进来。
- 有 `T` ⇒ 正常。

> 详细案例（含"一个功能从来没工作过"的完整证据链）见
> [references/cases.md](references/cases.md)。

### 第 2 步：性能不对 → 查实际编译选项

```bash
python3 scripts/flagcheck.py <builddir> <源文件路径片段>
```

**判据**：出现 `-O0` 或大批规则没有 `-O` ⇒ 该库被 vendor 的
`target_compile_options()` 覆盖了统一 FLAGS。

> 真机实测：某个编解码库从 `-O0` 改成 `-O2` 后**快 3 倍，
> 固件还小了 160 KB**。

### 第 3 步：配置不生效 → 查构建依赖

```bash
python3 scripts/flagcheck.py <builddir> --config-check
```

**判据**：`.config` 比 `build.ninja` 新 ⇒ 配置很可能没生效。
NuttX 里 `.config` **不是** ninja 的依赖，必须**显式重跑** cmake：

```bash
cmake -S <nuttx源树> -B <builddir>
# 然后验证新规则真的出现了，而不是假设:
grep -c '<新库名>' <builddir>/build.ninja
```

> **"我改了配置" 和 "配置生效了" 是两件事。** 一定要验证。

### 第 4 步：崩溃 → 符号化

```bash
python3 scripts/symbolize.py <elf> <日志文件|地址...>
```

脚本会提取 `sched_dumpstack` / `up_dump_register` 里的地址并符号化，
同时打印任务表（**最后一列是栈大小**）。

**先看崩在哪个任务上，再看那个任务的栈够不够。**
`libopus` / TLS / 递归解析这类代码的栈开销**远大于直觉**
（真机上 16 KB 栈跑 `opus_encode()` 直接溢出硬 fault）。

> ⚠ **符号化结果只对同一次构建的 ELF 有效。** 代码一改一重建，地址就全部错位，
> 而 `addr2line` 既不报错也不警告，只会给出**完全错误**的函数名。
> **每次烧录的固件与 ELF/map 一起留档。**

### 第 5 步：都不是？查工具链的编码/参数假设

构建工具里写非 ASCII 字符（中文注释）可能让配置直接失败，而报错信息
完全不提"编码"两个字。详见 [references/cases.md](references/cases.md)。

## 检查表（提交前自查）

- [ ] 新加的库/源码，`build.ninja` 里**确实有**对应的编译规则
- [ ] 与桩同名的实现：**桩已删除**，`nm` 显示 `T`
- [ ] 性能敏感的库：实际优化级别不是 `-O0`
- [ ] 改了 `.config` 后**重跑过 cmake**，且验证了新规则出现
- [ ] defconfig / Kconfig 里**只有 ASCII**
- [ ] 崩溃定位用的 ELF 与烧录固件是**同一次构建**

## 反模式（这些都是在"假设"而不是"验证"）

| 反模式 | 应该改成 |
|---|---|
| "代码里明明有这个实现" | `nm` 看符号实际是强是弱 |
| "这个库应该已经优化了" | 看 build.ninja 里它的 FLAGS |
| "我改了配置" | 看 build.ninja 里规则有没有变 |
| "崩溃地址大概在 xxx 附近" | addr2line **且确认 ELF 版本一致** |
| "报错说 A，所以是 A 的问题" | 先看调用栈落在**哪一层** |
