---
name: vela-contest-feature
description: >
  在 SF32LB52 小云手表（Team 181 / openvela 参赛仓）上**加功能或修 bug** 的工作流纪律。
  覆盖：改动前先取证、验证「真的生效」而不是「编译通过」、烧录与回滚、
  「函数存在但什么都没做」陷阱清单、以及本板已知的硬件坑
  （PA01 是背光、I2C 弱上拉必须降速、音频设备级共享、MPU6050 字节序）。
  Trigger: 小云手表、vela_contest、ai_agent 包、SF32LB52、
  给手表加功能、修手表 bug、改 UI 页面、烧录验证、固件回滚、
  「改了没效果」「屏幕不亮」「没声音」「按钮不灵」等本仓具体故障。
metadata:
  version: "1.0"
---

# 小云手表功能开发纪律

这个仓（Team 181 参赛仓 + openvela vendor 树）有几个反复踩的坑，
**它们看起来都像正常代码**。这份技能把「怎么不踩」写成可执行的规则。

## 何时使用

- 给 `packages/ai_agent` 加页面 / 工具 / 驱动对接
- 修「用户说没效果」的 bug
- 烧录并要判断这次改动到底成没成
- 需要回滚到某个基线

配件细节看 `references/hardware.md`；
历史踩坑目录看 `references/pitfalls.md`；
这个项目主的期望与我的历史错误看 `references/user-style.md`。

---

## 五条硬规则

### 1. 先取证，再改代码

故障说的和代码写的经常不是一回事。**先拿到至少一条硬证据**：

| 证据类型 | 例子 |
|---|---|
| 日志里的具体数字 | `underruns=562` vs `halves=240` |
| 任务状态 | `ps` 显示 `pcm_play Waiting Semaphore` |
| 符号是否存在 | `arm-none-eabi-nm nuttx \| grep 函数名` |
| 设备节点是否存在 | `ls /dev` 里 `/dev/mpu0` 在不在 |
| 寄存器返回值 | `HAL_PIN_Set(...) == 0` 才算成功 |

**没有证据就别改。**「我觉得应该是……」在这个仓已经错过很多次。

### 2. 「编译通过」不等于「功能生效」

本仓已发现 **6 个**函数：存在于源码、能编译、被注册、**返回成功，但什么也没做**。
每次接一个新接口，先问三个问题：

1. 它有调用者吗？（`grep` 一遍）
2. 它的返回成功是真的成功，还是被吞掉了？
3. 它依赖的 `CONFIG_*` 打开了吗？（`grep out/*/.config`）

完整清单见 `references/pitfalls.md` 的 P1。

### 3. 一次只改一层，每层都验证

**反例（真实发生过三次）**：

```
改 A → 编译通过 → 直接改 B → 烧录 → 坏了 → 不知道是 A 还是 B
```

**正确做法**：

```
改一层 → 编译 → 烧录 → 验证三件事 → 存基线 → 下一层
```

**每次烧录后必须验证三项**：

1. 启动没有崩溃（`grep -c 'Assertion failed'` 为 0）
2. **上一个功能没退化**（举一个具体动作去试）
3. 这次的新功能真的生效（有可观测的判据）

### 4. 烧录必须验证「真的写进去了」

**踩过的坑**：命令加了 `| tail -2`，退出码变成 `tail` 的 0，
脚本其实因为没串口直接退出了，我却报「烧录成功」。

```bash
# 错：管道吃掉退出码
python3 recover_flash.py fw.bin 0x12010000 | tail

# 对：显式记录退出码 + 检查日志时间戳
python3 recover_flash.py fw.bin 0x12010000 > /tmp/f.log 2>&1
echo "EXIT=$?"
ls -lt .v3_work/recover_rst_*.log | head -1   # 必须是刚刚的
grep 100% $(ls -t .v3_work/recover_rst_*.log | head -1)
```

**而且：job 的 `status: completed` 只代表进程结束，不代表成功。**

### 5. 用户说「没效果」时，先怀疑自己的假设

真实例子：用户说「插上 MPU6050 屏幕就灭」，我坚持认为 PA00/PA01 是空闲引脚，
查了三轮代码才承认 **PA01 是背光控制**。

**用户能看到板子，我看不到。** 他们的物理观察优先级高于我的代码推断。

---

## 验证协议（可复制的命令）

### 编译 + 烧录 + 验证

```bash
# 1. 编译
cd out/nuttx_contest_board_ai_agent
ninja nuttx.bin 2>&1 | grep -iE 'error:|FAILED' ; ls -l nuttx.bin

# 2. 停桥、烧录（记录退出码）
pkill -9 -f serial_bridge.py
timeout 300 python3 .v3_work/recover_flash.py out/.../nuttx.bin 0x12010000 \
  > /tmp/flash.txt 2>&1 ; echo EXIT=$?
grep -E 'rc=0|100%' /tmp/flash.txt

# 3. 重启桥、等启动
python3 .v3_work/serial_bridge.py --fresh &
for i in $(seq 1 30); do sleep 5; grep -q 'NSH CLI started' /tmp/serial_ts.log && break; done

# 4. 三项验证
grep -c 'Assertion failed' /tmp/serial_ts.log        # 必须是 0
grep -c 'lcd_flush' /tmp/serial_ts.log               # 屏幕在刷（约 2 次/秒）
grep 'alive. up=' /tmp/serial_ts.log | tail -1       # 看 tasks= 有没有异常增长
```

### 给板子发 NSH 命令

```bash
printf 'ls /dev\n' > /tmp/serial_cmd
sleep 4
grep -a 'mpu0' /tmp/serial_ts.log | tail -3
```

### 存基线

```bash
.v3_work/fw.sh save vNN_描述性标签
```

命名用 `vNN_做了什么`，不要用 `vNN_test`。回滚点另存 `vNN_pre_xxx_backup`。

---

## 加一个新功能的标准流程

1. **写之前**：确认依赖的硬件/配置真的可用（`grep` 掉 `CONFIG_*`、`ls /dev`）
2. **写**：新文件加进 `packages/ai_agent/CMakeLists.txt`
3. **编译**：只看 `error:`，警告要读（本仓 `-Wno-error`，警告可能藏着真问题）
4. **烧录 + 三项验证**
5. **如果有数据**：先在日志里打印原始值，确认物理量合理（比如加速度模长 ≈ 1g）
6. **存基线**，并在 `meta.txt` 填「验证结论」

**第 5 步最容易跳过，也最值得做。** 本仓多次出现「代码全对但数据是垃圾」。

---

## 明确不要做的事

- ❌ 一次改多个不相关的文件再统一烧录
- ❌ 用 `| tail` 之类会吃掉退出码的管道包装烧录命令
- ❌ 在自己写的探测工具返回失败时，就下「硬件不存在」的结论
  （先验证工具本身：拿一个**确定存在**的设备做对照）
- ❌ 把 `HAL_PIN_Set` 之类配置函数当成一定生效 —— 检查返回值
- ❌ 忽略 `#ifdef CONFIG_XXX` 包裹的代码（配置没开时它根本不编译）

---

## 参考

- `references/hardware.md` —— 引脚、总线、音频链路、屏幕，含 PA01 禁用法则
- `references/pitfalls.md` —— 「假实现」清单 + 历史 bug 的根因与证据
- `references/user-style.md` —— 项目主的期望、我被纠正过的具体点