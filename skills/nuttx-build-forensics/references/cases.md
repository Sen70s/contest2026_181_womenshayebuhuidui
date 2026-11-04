# 真实案例（每个都花了数小时才定位）

> 全部来自 openvela / NuttX flat build 的真机调试，SF32LB52 平台。
> 共同点：**源码看起来完全正确，故障出在"编译/链接的产物"上。**
> 所以排查手段不是读代码，而是查产物。

## 目录

1. [弱符号遮蔽强实现 —— 一个功能"从来没工作过"](#1-弱符号遮蔽强实现--一个功能从来没工作过)
2. [第三方库被整库以 -O0 编译](#2-第三方库被整库以--o0-编译)
3. [.config 不是 ninja 的依赖](#3-config-不是-ninja-的依赖)
4. [崩溃符号化：从"一串地址"到"哪一行"](#4-崩溃符号化从一串地址到哪一行)
5. [附：Kconfig 里的非 ASCII 注释会让 CMake 配置失败](#5-附kconfig-里的非-ascii-注释会让-cmake-配置失败)

---

## 1. 弱符号遮蔽强实现 —— 一个功能"从来没工作过"

**症状**：语音采集 100% 失败，但日志只说打开失败，毫无线索。

```
[voice] ASR pre-connected
[audio_cap] media_recorder_open failed, errno=11
[voice] capture open failed
```

**读代码看不出来**：`audio_capture.c` 里 `open_media_recorder_capture()` 写得完全正常。

**决定性证据**（一行命令）：

```
$ arm-none-eabi-nm nuttx | grep media_recorder_
120ed8d0 W media_recorder_close      ← 全部是 W(weak)
120ed8ba W media_recorder_open
120ed8e8 W media_recorder_prepare
120ed934 W media_recorder_read_data
120ed904 W media_recorder_start
120ed91c W media_recorder_stop
```

**根因**：真实现所在的框架（`frameworks/multimedia/media`）压根没被编进构建，
于是某处为"sim 构建"写的那组 **永远返回 NULL 的 `__attribute__((weak))` 桩**
成了唯一实现。

**为什么"加了真实现"还不够**：静态链接器**先**满足于已经拉进来的弱定义，
之后**再也不会**去归档（`libapps_xxx.a`）里捞那个强实现。

**修法**：不是"把真实现加进来"，而是**把桩删掉**。

**验证**：删桩重建后，`nm` 应显示 `T`（强）。

**推广**：任何 `nm <elf> | grep <symbol>` 全是 `W` 的地方，都值得怀疑。
排查时先问："这个符号有没有可能是被桩遮蔽了？"

---

## 2. 第三方库被整库以 -O0 编译

**症状**：Opus 编码 16 kHz 音频只有 **0.68× 实时**（编码比录音还慢）。
功能完全正确，所以没人怀疑它。

**根因**：vendor 的 `CMakeLists.txt` 用 `target_compile_options()` 自己设编译选项，
**绕过了 NuttX 给所有库的统一 FLAGS**，于是 GCC 用它默认的 `-O0`。

**查法**：看 `build.ninja` 里那条规则实际的 `FLAGS`（本技能的 `flagcheck.py`）。

**修法**：在库的 CMakeLists 里补回去：

```cmake
list(APPEND CFLAGS -O2)
```

**效果**（真机实测，16 kHz / 4.096 秒音频）：

| 配置 | 耗时 | 实时率 |
|---|---|---|
| complexity 10, `-O0` | 19 秒 | 0.22× |
| complexity 10, `-O2` | 6 秒 | 0.68× |
| complexity 0, `-O2` | **2 秒** | **2.05×** |

而且固件**还小了 160 KB**（7867100 → 7706508）—— 优化不一定让体积变大。

**推广**：任何"性能不符合预期"的第三方库，第一件事就是查它的实际编译选项。
"库应该已经被优化了"是一个**没有依据的假设**。

---

## 3. .config 不是 ninja 的依赖

**症状**：在 `.config` / defconfig 里打开了 `CONFIG_LIB_OPUS=y`，
跑 `ninja` 却什么都没发生 —— `build.ninja` 里**一条 opus 规则都没有**，
而且**没有任何报错**。

**根因**：NuttX 的构建系统**不把 `.config` 当作 ninja 的依赖**。
改配置后直接 ninja，规则不会重新生成。

**修法**：显式重跑 cmake，然后**验证新规则真的出现了**：

```bash
cmake -S <nuttx源树> -B <builddir>
grep -c 'external/opus' <builddir>/build.ninja   # 从 0 变成 568
```

**判据**：本技能的 `flagcheck.py --config-check` 会比较 `.config` 与
`build.ninja` 的 mtime。

**推广**：**改完配置一定要验证"配置真的生效了"**，而不是假设。
"我改了配置" 和 "配置生效了" 是两件事。

---

## 4. 崩溃符号化：从"一串地址"到"哪一行"

**症状**：一条地址 + 一个任务表。**不看符号化结果就猜根因，几乎一定会猜错。**

**案例 A —— 栈溢出**（`voice_stop` 之后立刻硬 fault）：

```
0x1216bb92  silk_find_pitch_lags_FIX   silk/fixed/find_pitch_lags_FIX.c:70
0x1216b1ea  silk_encode_frame_FIX      silk/fixed/encode_frame_FIX.c:145
0x12167cc6  silk_Encode                silk/enc_API.c:510
dump_task: 42  12  30 FIFO  pthread  Running  ...  16304  ai_agent
                                                    ↑ 栈只有 16KB
```

一眼看出：**`silk_Encode` 跑在一个 16 KB 栈的线程上**。
而 `voice_channel_stop()` 由 vela CLI 线程处理，那个线程栈正好是
`AGENT_CLI_STACK = 16 * 1024`。根因即"编码被放在了错误的线程里"。

**案例 B —— 控制器不回包**：

```
z_fatal_error ← bt_hci_cmd_send_sync(hci_core.c:507)
             ← bt_conn_enter_sniff_mode(conn.c:4671)
             ← bt_br_write_local_name_mc(br.c:1473)
```

一眼看出：不是"连接逻辑有 bug"，而是**控制器永远不回命令完成事件**，
host 侧那个 `BT_ASSERT_MSG` 就自杀了。修法从"改连接逻辑"变成
"把断言改成超时返回"——**完全不同的方向**。

**★ 极重要的注意事项**：符号化结果**只对同一次构建的 ELF 有效**。
代码一改一重建，地址就全部错位，而 `addr2line` **既不报错也不警告**，
只会安安静静地给出**完全错误**的函数名 —— 比不给答案更危险。

> 实测：同一个 `0x1216bb92`，重建前是 `silk_find_pitch_lags_FIX`，
> 重建后变成 `silk_DIV32_varQ`。两次都"看着很像真的"。

**习惯**：每次烧录的固件与 ELF/map 一起留档。

---

## 5. 附：Kconfig 里的非 ASCII 注释会让 CMake 配置失败

**症状**：

```
CMake Error at nuttx/cmake/nuttx_kconfig.cmake:48
  string sub-command REGEX, mode MATCH needs at least 5 arguments total
```

调用栈指向 `nuttx/CMakeLists.txt:236 nuttx_export_kconfig_by_value`。

**根因**：defconfig / Kconfig 里写了**中文注释**，在配置导出路径上被当成参数拆开。

**修法**：defconfig 与 Kconfig 里**只写 ASCII**（中文说明放到 .md 文档里）。

**推广**：这类"工具链不接受非 ASCII"的坑在嵌入式构建里很常见，
出错信息通常完全不提"编码"两个字。
