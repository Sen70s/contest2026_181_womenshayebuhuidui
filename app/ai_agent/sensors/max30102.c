/****************************************************************************
 * max30102.c - MAX30102 心率传感器 (用户态驱动 + 心率算法)
 *
 * 为什么放用户态 (而不是 nuttx/drivers/sensors 内核驱动):
 *   - ai_agent 读的是"算法话题" algo_heartrate (tool_health.c 走 uORB),
 *     而全树没有发布者; 心率算法本来就该在应用层做
 *     (.agents/skills/nuttx-driver-development/references/sensor_uorb_pattern.md);
 *   - 内核驱动 + SENSOR_TYPE_PPGD + sensortest 是更"正统"的后续形态,
 *     但当前时间预算下, 用户态实现同样能打通 tool_health/pet_care 链路。
 *
 * 硬件: MAX30102 @ I2C 0x57 (无地址脚), 接在**独立总线 I2C2** 上:
 *       PA41=SCL / PA42=SDA -> /dev/i2c1 (板级已注册)。
 *       为什么不用触摸那条 I2C1(PA30/PA33): 触摸驱动会持锁, 共用可能互相干扰;
 *       PA41/PA42 在本板空闲(LCD 走 QADSPI 不占这两脚; 触摸 IRQ=PA31, 音频 PA=PA10)。
 *
 * 时序 (Linux/SparkFun/树内 Zephyr 三源一致的结论):
 *   0x09 = 0x02        仅 RED (心率)
 *   0x0A = 0x2F        4096nA + SR=400Hz + 411us(18bit)
 *   0x08 = 0x5F        4 平均 + ROLLOVER + A_FULL=15
 *                      -> 400Hz/4 = 100Hz 输出 (注意: SR=100Hz+4平均 只有 25Hz)
 *   0x0C = 0x3F        LED1 (RED) = 12.6mA (Table 8; 0x24=7.2mA 太暗, 0x7F=25.4mA 更亮)
 *   0x02 = 0x00        轮询, 不开中断 (本板无空闲中断脚)
 *   样本: 3 字节大端 18bit  ((b0<<16)|(b1<<8)|b2) & 0x3FFFF
 *   轮询: HR_POLL_MS=50ms 一轮(**不是** 10ms) —— 轮询频率不必等于采样率: 100sps 下
 *         50ms 最多积 5 个样本, FIFO 深 32, 安全; 降下来直接按比例(1/5)减总线负载。
 *
 * 读路径: 必须走"写寄存器地址+STOP, 再单独发起读"(见下面 HR_USE_SPLIT_READ) ——
 *         本板下层驱动不支持 NuttX 规范的组合读(NOSTOP+READ), 真机 selftest 已一锤定音:
 *         A 路径 PART_ID 命中 0/8, B 路径 8/8=0x15。写路径不受影响。
 *
 * FIFO 读法 (2026-10 第四次改: **停止每轮清指针** + 每轮排空; 实现见 hr_drain()):
 *   真机证据(本轮诊断, 已钉死): OVF(0x05)恒非 0 -> 旧代码每轮都进溢出分支 -> 每轮把
 *   0x04/0x05/0x06 写 0 -> 器件 FIFO 指针被反复拉回起点 -> 每轮读到同一批槽 ->
 *   dup=97~100%/byeq=100%/d1=2302.8 或 0.0/acdc=73.66%(生理应 0.1~2%)/dc≈202204 近满量程
 *   = **读到的样本 97~100% 是重复的**, 波形根本不存在, 算法(正确地)gate=warm 拒绝出值。
 *   这是**读侧**缺陷; 算法侧(上一提交 8c6798f)一行未动。
 *   现在的读法三条:
 *     (1) 门控: 每轮先读 0x00(INT_STATUS1)/0x01(INT_STATUS2), **读到非 0 就认为可能有
 *         数据**。数据手册/参考实现里的 bit6 = PPG_RDY 是"新样本已进 FIFO"位(逐位定义与
 *         三个来源见 REG_INT_STATUS1 上方的注释块); 放宽到"任一非 0"是本板"读回值不可信"
 *         历史的保守侧(详见该注释块的"保守处理"段)。读该寄存器即清状态位, 不需要额外写。
 *     (2) 数量: 每轮最多读 HR_FIFO_SAMPLES_PER_POLL 个(**默认 5 -> 16**), 且
 *         HR_FIFO_DRAIN_ALL=1 时按"读到 FIFO 空为止"排空 —— 从第 HR_FIFO_MIN_SAMPLES 个
 *         之后, 一旦读到与流中前一拍**完全相同**的样本, 就判为"空 FIFO 返回的陈旧槽":
 *         丢掉它并结束本轮(两个宏的取值依据见各自定义处)。0x04/0x06 指针仍然**只读来
 *         打印**(stat 的 wr=/rd=), 不参与任何控制流 —— 本板读回的指针不可信/不前进。
 *         每个样本仍单独读(HR_FIFO_BYTE_WISE=1: 三次 1 字节读; 置 0 则一次 3 字节读),
 *         两种粒度保持一行可切换。
 *     (3) 0x05: **只记账 + 限流日志, 不清零、不复位算法、不触发恢复**。真机 0x05 恒非 0
 *         极可能正是旧读法"每轮把写指针拉回起点"的**后果**(写指针倒退 -> 器件认为 FIFO
 *         被覆盖 -> 溢出计数只增不减), 拿它当控制流就会每 2s 清一次 = 又回到本 bug。
 *   采样热路径上唯一允许写 0x04/0x06 的出口(计次 + 限流): 连续 HR_STALE_CLEAR_ROUNDS 轮
 *   fresh=0(读侧一个新鲜样本都没有)且距上次 >= HR_STALE_CLEAR_MIN_MS 时清一次, 见 hr_drain
 *   的"极端陈旧出口"。start/init 与 hr_recover 里的清零不受影响(前者只发生在 hr_start,
 *   后者由 ioctl 失败限流触发)。
 *   依据(两家参考实现都不拿指针当控制流, 且**稳定态都不写** 0x04/0x06):
 *     - Zephyr max30101: i2c_burst_read(0x07, num_channels*3) —— **每次只读一个样本**,
 *       由中断/trigger 触发。external/zblue/zblue/drivers/sensor/maxim/max30101/
 *       max30101.c:26-32; 其头文件 max30101.h:34 定义 MAX30101_INT_PPG_MASK = (1 << 6)。
 *     - Linux IIO: 用 INT_STATUS 判有无数据(max30102_fifo_count), 不读 0x04/0x05:
 *       drivers/iio/health/max30102.c:43-48 (#define MAX30102_REG_INT_STATUS 0x00 /
 *       _PPG_RDY BIT(6) / _FIFO_RDY BIT(7)), :240-254 (max30102_fifo_count)。
 *       https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/tree/drivers/iio/health/max30102.c
 *     - 反例(旧读法的出处)SparkFun MAX3010x: 先读 0x06/0x04 指针算 numberOfSamples, 再
 *       beginTransmission+write(0x07)+endTransmission() **+STOP** 后 requestFrom()
 *       (= 本板的路径B), 分块大小强制取 (activeLEDs*3) 的整数倍:
 *       src/MAX30105.cpp:592-699 (MAX30105::check) —— 本板指针不可信, 这条**不能**再用。
 *       注意同一份代码里 0x04/0x05/0x06 的写 0 **只出现在 begin()**(:319-322), 采样循环
 *       里一次都不写 —— 本次改正是回到这个用法。
 *   兜底: INT_STATUS 连续 HR_NOINT_STREAK 轮全 0 时自动改成每轮盲读(见 hr_drain ①),
 *   避免"位定义/使能位理解错"把采样彻底饿死。
 *
 * 总线恢复 (2026-10 加, 见 hr_recover()): 任何读/写失败都记账, 连续 HR_RECOVER_FAILS 轮
 *   失败且距上次恢复 >= HR_RECOVER_MIN_MS 才做一次恢复。
 *   现在只有一条失败入口: 任一 ioctl 失败(含**极端陈旧出口的清零写失败** —— 旧代码虽然
 *   也调 hr_fail(), 但紧接着 return 掉整轮, 真机上表现为 wr/rd 永远 ??)。
 *   第四次改**去掉**了原来的第②条入口("0x05 溢出卡死" -> hr_fail -> 恢复): 真机 0x05
 *   恒非 0 是旧读法把写指针拉回起点造成的假阳性, 拿它触发恢复 = 每 2s 清一次 FIFO 指针
 *   并复位滤波器, 与本次要修的 bug 同源(恢复里的清零对"真卡死"才有意义)。读侧真卡死
 *   改用 fresh=0 判定, 并且只清指针(见 hr_drain 的极端陈旧出口), 不连累总线恢复。
 *   恢复动作: 先试 I2CIOC_RESET(该 ioctl 曾
 *   因 CONFIG_I2C_RESET 选不上而必然 -ENOTTY; 现已打开, 见 hr_recover 注释), 再
 *   close+open 设备 fd, 探活成功才重配 6 个关键寄存器。stat 行新增 rec= 计数。
 *
 * 诊断(真机排查用, 全部限流):
 *   采样线程每 2s 两行 —— `hr_sensor: stat ...` 与 `hr_sensor: ops ...`:
 *   第一行 samples/n_poll/fifo_rd/ovf/wr/rd/int1/int2/red/min/max/bpm/hb/rec/
 *          lastfail/firstfail/state;
 *   n_poll   本轮实际读到的样本数/本轮**上限**(= HR_FIFO_SAMPLES_PER_POLL) ——
 *            第四次改后默认 HR_FIFO_DRAIN_ALL=1: 读到 FIFO 空为止, 所以
 *            "n_poll < N 且 fresh == n_poll"是**正常**的(有多少取多少), 不再是"N 没读满";
 *   fresh    本轮 n_poll 个样本里与**上一轮最后一个样本**不同的个数 (stat: fresh=x/N) ——
 *            本次改新增的"不再重复"直接证据: 稳态应 fresh == n_poll(接近 N),
 *            旧 bug 下是 fresh=0/N;
 *   rdup     本轮样本里与**流中前一拍**相同的个数(含跨轮边界) —— fresh 的补充: 它把
 *            "轮内也在重复"也照出来; stclr= 极端陈旧出口(清指针)触发次数, 稳态应为 0;
 *   int1/int2 本轮读到的 0x00/0x01 原始值(?? = 这轮没读到) —— 门控判据的现场证据,
 *            用来验证"bit6 = PPG_RDY"这个位定义在本板成不成立;
 *   wr/rd    0x04/0x06 指针(**现在只是诊断值**, 不参与控制流; ?? = 这轮没读到);
 *   state=no-fifo-data 表示 FIFO 没数据, state=data-no-bpm 表示有数据但算法没检出心率,
 *   state=bpm-ok 表示已出 bpm;
 *   lastfail  = 最近一次失败的操作;
 *   firstfail = 最近一轮里**第一个**失败的操作(rd_int/rd_ovf/wr_clr/rd_ptr/rd_fifo,
 *               none=没失败)—— 它就是"总线是被哪一步搞坏的"的入口操作
 *               (见 hr_drain 的 ①②③④ 注释);
 *   第二行 ops 是五种操作各自的 尝试/失败(最后失败的寄存器,errno)。
 *   逐操作失败日志: 每种操作**首次**失败立即打印一条(含 errno/寄存器/第几次),
 *   之后按 HR_OP_LOG_EVERY 限流, 成功不打印(采样热路径绝不逐拍 printf)。
 *   FIFO 读失败/持续为空/整包全 0/读数恒为常数时, 首次立即打一条 diag 行, 并现场
 *   用**路径B** 回读 0x04/0x05/0x06; 之后按 HR_DIAG_EVERY 限流。
 *
 * 算法链路诊断 (2026-10 第三次改新增): 采样线程每 2s **再加一行**
 *   `hr_sensor: algo fs=.. fed=.. rst=.. ovfs=.. dc=.. ac_pp=.. ac_rms=.. bp_pp=..
 *    bp_rms=.. acdc=..% d1=.. dup=..% byeq=..%/..% pk2s=.. pk5s=.. rr_med=..ms rej=..
 *    q=.. gate=.. bpm=.. cand=.. stab=.. last10=..`
 *   它回答"到底卡在采样率、信号幅度、峰值检测, 还是输入根本不是脉搏波" —— 每个字段的
 *   含义与预期数值见 hr_algo_diag() 上方的注释块。
 * 本轮算法侧三处修改(**不碰 I2C 读写路径/恢复逻辑/限流/stat 既有字段**):
 *   ① 旧阈值 thresh = 1.6 × EMA(|y|) 对正弦 = 1.02 × 信号峰值, **数学上就检不到峰** ->
 *      改成 0.6 × 包络(最近数秒 AC 幅度), 依据见 hr_feed (3) 的注释;
 *   ② 溢出分支里每轮调 hr_reset_algo() 在真机"OVF 读数卡死"(见 commit efcca48)时等于
 *      每 50ms 把滤波器/包络/峰位置清一次 -> bpm 不可能出来。改成 hr_reset_filters()
 *      (只清与"样本流连续性"绑定的滤波器状态, 保留 RR 历史);
 *   ③ 质量门限(幅度/5s 峰数/RR 离散度/AC-DC 比)+ 连续确认 + RR 中位数: 信号不合格时
 *      宁可保持上次值, 单次异常峰不改 bpm(分钟级稳定)。
 *
 * 本次改(第五次, 2026-10-13): **只改算法参数与峰值归并, 不碰读侧** —— 读侧已确证正常
 *   (PART_ID=0x15、零 I/O 失败、fresh=8/8、acdc 收敛到 1.38~1.55%), 但静息 25s 的 bpm
 *   序列是 142/136/122/116/95/106/107(波动 47), 而同一行 rr_med=559~561ms(=> 107bpm)
 *   却输出 95 —— 输出与 RR 中位数不一致。逐条根因与修法(调研结论编号):
 *   (A) 主因 #1 杂峰归并: 旧代码在 rr < HR_RR_MIN_MS 时"丢间隔、留峰", 互补的那段长
 *       间隔永远补不回来 -> 窗口只剩被切短的 RR -> 中位数偏短 -> bpm 偏高。改为
 *       "丢峰、留间隔"(不推进 last_peak_ms), 让下一拍与上一个真峰算 RR; 新增 rej= 计数。
 *       构造性复现(真 RR=561, 杂峰延后 d): d=422/441/492/517 -> 142/136/122/116bpm,
 *       与真机四个观测值逐位吻合;
 *   (B) #5 HR_MAX_ACDC_PCT 40 -> 5: 40 是生理上限(2%)的 20 倍, 等于没有门限 —— 真机
 *       t=3s 的 acdc=7.6% 被判"合格", 垃圾期就把错值锁进 bpm; 5% 顺带成为"信号就绪"
 *       判据(t=1s 246% / t=3s 7.6% 直接不合格, t=5s 1.55% 才放行);
 *   (C) #3/#4 容差收紧: HR_RR_AGREE_PCT 25 -> 20(25% 恰好放行双检对: hr_close(422,561,25)
 *       = 24.78% < 25%); bpm 一致性容差 12% -> max(3bpm, 5%)(12% 太松: 真机 7 次跳变里
 *       6 次被判"一致", 最大放行 11.6%);
 *   (D) #2 中位数硬悬崖: RR_HIST 8 -> 12(窗口 4.2s -> 6.3s@107bpm)。n=8 时中位数取
 *       tmp[4], 一个双检峰就能从 561 翻到 422(=106 -> 142bpm 的 34bpm 悬崖);
 *   (E) 输出限速: 每次更新最多变 3bpm(hr_slew_bpm), 把残余跳变摊成爬升; 首次锁定不限。
 *   **刻意不做**: ①自适应不应期(clamp(0.5×RR_prev,300,600) 在 >=100bpm 时是空操作:
 *   0.5×561=280 钳回 300, 与现状完全一样); ②ADC 增益 ADC1_CFG1.GC(PAC 手册: 3bit,
 *   0x4 已是最大 18dB, 改它会破坏相邻字段); ③峰时刻的墙钟 now_ms()(非主因, 仿真只造成
 *   spread <=2bpm)。
 *
 * 本次改(第四次)只在**读侧**, 算法侧(上面 ①②③ 与 hr_feed 的全部参数)一行未动:
 *   ① 停止每轮清 0x04/0x05/0x06(核心): 清指针只保留在 start(init) 与两个"限流出口"
 *      (hr_recover 的重同步、连续 fresh=0 的极端出口) —— 让器件自己推进自己的 FIFO 指针;
 *   ② 每轮读满/排空: HR_FIFO_SAMPLES_PER_POLL 5 -> 16, 新增 HR_FIFO_DRAIN_ALL=1
 *      (读到 FIFO 空为止, 最少 HR_FIFO_MIN_SAMPLES=5 个);
 *   ③ 新增取证字段: stat 行的 fresh=x/N(本轮 N 个样本里与**上一轮最后一个**不同的个数)、
 *      rdup=(与**流中前一拍**相同的个数, 含跨轮边界)、stclr=(极端出口触发次数, 稳态应为 0)。
 *
 * 用法: hr_sensor start | stop | status | raw | info | selftest
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include <nuttx/i2c/i2c_master.h>
#include <nuttx/mutex.h>

/* PA41/PA42 -> I2C2 的 pinmux。本板 LCD 走 QADSPI(CO5300), 不使用这两脚
 * (bsp_pinmux.c 里把它们配成 LCDC1_8080_DIO5/DIO6 的代码在 BSP_LCDC_USING_DBI
 *  分支内, 本板未编译); 触摸在 I2C1 (sifli_ap.c:480-481 实际配的是 PA37=SCL/PA33=SDA,
 *  与 README 写的 PA30 不一致 —— 本板既有的历史问题, 与本传感器无关)。把传感器
 *  放在独立的 I2C2 上可以完全避开与触摸驱动争总线。I2C2 实例已由板级代码注册为
 *  /dev/i2c1, 但它的引脚默认只在 CONFIG_SENSORS_LSM6DSL 打开时才配置, 所以这里补上。
 * 注意: 板级 LSM6DSL 分支与这里写的是同一个 I2C2_PINR, 两者互斥(先写者被覆盖)。
 * 想改回与触摸共用 I2C1: 把 I2C_DEV_PATH 改成 /dev/i2c0 并删除本函数调用。 */
#include "bf0_hal_pinmux.h"

static void max30102_pinmux(void)
{
  /* 两个引脚都开 SoC 内部弱上拉 (无仪器场景的"余量", 不能替代外部上拉):
   *   PIN_PULLUP = HPSYS_PINMUX_PAD_PA00_PE | HPSYS_PINMUX_PAD_PA00_PS
   *   (vendor/sifli/chips/drivers/Include/bf0_hal_pinmux.h:32-37; PE=1/PS=1 即上拉,
   *    PE=1/PS=0 是下拉, 0 是不拉 —— 只有这三态, 没有更细的阻值可选);
   *   HAL_PIN_Set() 内部先把 flags 掩到 PE|PS 两位, 再 OR 进该 pad 的 pinmux 寄存器
   *   (vendor/sifli/chips/drivers/hal/bf0_hal_pinmux.c:1237-1247), 所以第三个参数直接
   *   传 PIN_PULLUP, 这里没有额外的 *_MASK 参数要传。
   *   它是 SoC 片内弱上拉(几十 kΩ 量级, 随工艺/电压变化, 不是数据手册里的 4.7k),
   *   只给"SCL 是拼接的、模块侧又没外部上拉"的现状兜一点底; 真正解决问题仍然是
   *   重做 SCL 接头 + SDA/SCL 各加 2.2~4.7k 外部上拉到 3V3。
   *   本板没有别的代码会覆盖这两脚: 唯一会重配 PA41/PA42 的是 bsp_pinmux.c:278-279
   *   的 LCDC1_8080_DIO5/6(PIN_NOPULL), 但它在 BSP_LCDC_USING_DBI 分支内, 本板未编译。
   * 注意: 不要用返回值判失败 —— HAL_PIN_Set 返回的是选中的功能号,
   * 实测(真机 PART_ID=0x15)复用成功后返回值也不是 PIN_FUNC_UNDEF 之外
   * 的"成功码", 用它判断会误报。 */
  HAL_PIN_Set(PAD_PA41, I2C2_SCL, PIN_PULLUP, 1);
  HAL_PIN_Set(PAD_PA42, I2C2_SDA, PIN_PULLUP, 1);
}

#ifdef CONFIG_UORB
#  include <uORB/uORB.h>
#endif
#if defined(CONFIG_UORB) && defined(CONFIG_FITNESS_ALGO)
#  include <fitness/algo_heartrate.h>
#  define HAVE_HR_TOPIC 1
#endif

/****************************************************************************
 * Definitions
 ****************************************************************************/

#define MAX30102_ADDR     0x57
#define I2C_DEV_PATH      "/dev/i2c1"   /* I2C2: PA41=SCL / PA42=SDA (独立总线) */
/* 总线速率: 50kHz —— 拼接 SCL 时低速更稳(降一档换电气余量)。
 *  总线修好后可以(也建议)改回 100000, 甚至模块自带 4.7k 上拉且线短时用 400000。
 * 依据:
 *  (1) 速率是"逐条消息"重做的: SiFli HAL 每发一条 message 都重新 HAL_I2C_Init
 *      (sifli_i2c.c:509/516 -> sifli_i2c_configure():348), 所以改这个宏 = 整条总线改速率;
 *  (2) 本 SoC 的可达速率有硬下限: SCL = 48MHz/(2*FLV + dnf + 7), i2c_clk 固定 48MHz,
 *      dnf=0, FLV 字段只有 9bit(最大 511)
 *      (vendor/sifli/chips/drivers/hal/bf0_hal_i2c.c:270-294; cmsis/sf32lb52x/i2c.h:200-202)
 *      -> 400kHz=FLV57, 100kHz=FLV237, 50kHz=FLV477(<=511, 可用),
 *         25kHz 需要 FLV=957 > 511 -> HAL_ASSERT 变成 while(1) 把任务挂死(本板未定义
 *         USE_FULL_ASSERT), 下限约 48MHz/(2*511+7)=46.6kHz。这就是"25kHz 读不通"的根因。
 *  (3) 真机现状: 400k/100k 下 PART_ID(0xff) 连读常为 0x00、偶发 0x15/乱码。
 *      降到 50kHz 属于"软件侧补救", 具体是电气还是读路径问题, 用
 *      `hr_sensor selftest` 一次分清(见文件末尾自检段)。
 * 注: 50kHz 与板内 `i2c` 工具默认的 100kHz 不同, 交叉验证时记得给 i2c 工具改成 50k。 */
#define I2C_FREQ          50000
#define PART_ID_REG       0xFF
#define PART_ID_VAL       0x15

/* 读寄存器走哪条路 (主读路径开关, 只影响读; 写路径 i2c_write_reg 不动):
 *   1 = 路径B —— 分两次传输: 传输1 "只写寄存器地址"并以 STOP 结束, 传输2 单独发起读。
 *       等价 `i2c get -s`; 本板 MAX30102 必须走这条。
 *   0 = 路径A —— NuttX 规范的组合读: msg0={addr,I2C_M_NOSTOP,&reg,1} +
 *       msg1={addr,I2C_M_READ,buf,n}, 一次 ioctl 交 2 条消息
 *       (nuttx/drivers/i2c/i2c_writeread.c:73-95; 也是 i2c 工具的默认路径)。
 *
 * 为什么默认 1 (真机 selftest 结论, 2026-10):
 *   A 路径 PART_ID(0x15) 连读 8 次全错(00 00 00 00 00 00 0f ff), 命中 0/8;
 *   B 路径 8/8 = 0x15, 且写 0x0c=0x3f 后回读一致 —— 电气通路是好的, 问题在软件读路径。
 * 根因(在下层驱动, 不在本文件): vendor/sifli/chips/sf32lb52/sifli_i2c.c:492-519 只在
 *   第二条消息带 I2C_M_NOSTART 时才发寄存器地址, 否则把 msg0 丢进 nostop_msg 后丢弃
 *   (:556-558) -> 组合读读到的是"器件当前指针", 不是目标寄存器。
 * 什么时候可以改回 0: 下层驱动修好"组合读会丢寄存器地址消息"之后。改回前先跑
 *   `hr_sensor selftest`, 确认 A 路径命中 8/8 再切。 */
#define HR_USE_SPLIT_READ 1

#if HR_USE_SPLIT_READ
#  define HR_READ_PATH_NAME "B(写地址+STOP 再单独读)"
#else
#  define HR_READ_PATH_NAME "A(组合读 NOSTOP+READ)"
#endif

#define REG_INT_STATUS1   0x00
#define REG_INT_STATUS2   0x01

/* INT_STATUS1(0x00) 的位定义 —— 本文件只用"有没有新样本"这一个判据。
 *   来源一, Linux IIO(drivers/iio/health/max30102.c, 逐位一致):
 *     #define MAX30102_REG_INT_STATUS           0x00
 *     #define MAX30102_REG_INT_STATUS_PWR_RDY   BIT(0)
 *     #define MAX30102_REG_INT_STATUS_ALC_OVF   BIT(5)
 *     #define MAX30102_REG_INT_STATUS_PPG_RDY   BIT(6)   <- 新样本就绪
 *     #define MAX30102_REG_INT_STATUS_FIFO_RDY  BIT(7)   <- Linux 内部命名, 对应数据手册 A_FULL
 *   来源二, 树内 Zephyr max30101(与 MAX30102 同一套寄存器映射):
 *     external/zblue/zblue/drivers/sensor/maxim/max30101/max30101.h:11,34
 *     #define MAX30101_REG_INT_STS1 0x00
 *     #define MAX30101_INT_PPG_MASK (1 << 6)
 *   即: bit6 = PPG_RDY = 新样本已进 FIFO(**每个样本置一次**, 本文件的**主判据**);
 *       bit7 = A_FULL = FIFO 几乎满(本配置 0x08=0x5F 的 A_FULL=15 -> 积到 17 个样本才置位,
 *              Linux 拿它做批量排空触发, 不适合做 50ms 级的"有没有数据"判据);
 *       bit5 = ALC_OVF, bit0 = PWR_RDY。
 *   INT_STATUS2(0x01) 只有 DIE_TEMP_RDY 之类的次要标志, 不参与判据, 读它是为了清标志 +
 *   打印现场(int2=)。MAX30102 的语义是**读该寄存器即清其中的状态位**, 不需要额外写。
 *   保守处理: 本板此前有过"读回值不可信"(路径A 读任何寄存器都返回常数)的历史, 所以
 *   hr_drain 的门控**放宽为 0x00/0x01 任一非 0 就当"可能有数据"** —— 多读一轮的代价只是
 *   3*N 字节 + FIFO 空时返回的旧样本, 漏读的代价是整轮没有波形。 */
#define HR_INT1_PPG_RDY   0x40      /* 0x00 bit6: 新样本就绪(主判据; 门控放宽见上) */
#define REG_INT_ENABLE1   0x02
#define REG_INT_ENABLE2   0x03
#define REG_FIFO_WR_PTR   0x04
#define REG_FIFO_OVF      0x05
#define REG_FIFO_RD_PTR   0x06
#define REG_FIFO_DATA     0x07
#define REG_FIFO_CONFIG   0x08
#define REG_MODE_CONFIG   0x09
#define REG_SPO2_CONFIG   0x0A
#define REG_LED1_PA       0x0C
#define REG_LED2_PA       0x0D      /* IR LED 驱动电流 —— 旧代码定义了但**从未写入** */

/* ── 传感器归属仲裁 ─────────────────────────────────────────────────────
 *
 * 为什么需要（2026-10-19 对抗审查确认的真 bug）：hr 和 spo2 共用同一颗
 * MAX30102，却**没有任何互斥**。血氧入口只查 g_hr.running，自己从不置
 * running；而 hr_start 的守卫是 !g_hr.inited 才初始化。于是：
 *   - 血氧测量中点测心率 → inited==true → 跳过初始化 → 在 MODE_SPO2 的
 *     6 字节/样本流上按 3 字节解析 → 全是垃圾，两边还抢同一个 FIFO 指针；
 *   - 反向更狠：心率线程退出写 SHDN，直接把血氧的 LED 灭掉。
 *
 * 为什么锁必须放在本文件、而不是 app 层的按钮回调：hr_sensor start / spo2
 * 本身还是 NSH 内建命令，命令行会绕过任何只写在按钮回调里的检查。只有把
 * 锁放在**唯一真正碰硬件的地方**（这里）才能覆盖全部入口。
 *
 * 语义：同一 owner 可重入（hr_start 重复调用不算冲突），不同 owner 返回忙。 */

typedef enum
{
  SENSOR_OWNER_NONE = 0,
  SENSOR_OWNER_HR,        /* hr_start 起、采样线程退出止 */
  SENSOR_OWNER_SPO2,      /* spo2_measure 全程 */
} sensor_owner_t;

static sensor_owner_t g_sensor_owner = SENSOR_OWNER_NONE;
static mutex_t        g_sensor_lock  = NXMUTEX_INITIALIZER;

static bool sensor_claim(sensor_owner_t who)
{
  bool ok = false;

  nxmutex_lock(&g_sensor_lock);
  if (g_sensor_owner == SENSOR_OWNER_NONE || g_sensor_owner == who)
    {
      g_sensor_owner = who;
      ok = true;
    }
  nxmutex_unlock(&g_sensor_lock);
  return ok;
}

static void sensor_release(sensor_owner_t who)
{
  nxmutex_lock(&g_sensor_lock);
  if (g_sensor_owner == who)
    {
      g_sensor_owner = SENSOR_OWNER_NONE;
    }
  nxmutex_unlock(&g_sensor_lock);
}

/* ── 给上层 hr_measure 用的两个查询/控制入口 ────────────────────────────
 *
 * 背景：hr_measure 是在**另一个任务组**里通过 task_spawn 起 hr_sensor 的，
 * 它看不到 g_hr 这些 static 状态（flat build 下内存共享，但没有对外符号）。
 * start 超时后它需要两件事：
 *   max30102_hr_abort()   —— 让那个可能还在 init 里的进程别再去起采样线程；
 *   max30102_hr_running() —— 轮询确认采集真的停了，而不是"发完 stop 就算"。
 */
static volatile bool g_hr_abort;

#define MODE_HR           0x02      /* RED only */
#define MODE_SPO2         0x03      /* RED + IR —— 血氧必须要双通道 */
#define MODE_SHDN         0x80      /* bit7 = SHDN, 关掉 LED 与内部时钟 */
#define SPO2_CFG          0x2F      /* 4096nA | 400Hz | 411us */
#define FIFO_CFG          0x5F      /* 4-avg | rollover | A_FULL=15 */
/* LED1(RED) 驱动电流, 数据手册 Table 8: 0x24=7.2mA / 0x3F=12.6mA / 0x7F=25.4mA。
 * 取 12.6mA: 本配置 400Hz/411us 的 LED 占空比只有约 16%, 0x24 平均电流仅 ~1.2mA,
 * 信号幅度小到肉眼几乎看不见; 演示想要更醒目可用 0x7F(25.4mA), 代价是功耗与温升。
 * 改这里时请同步修改 init 成功那行的 "LED=xx mA" 文案。 */
#define LED_PA            0x2F      /* 9.4 mA —— 真机 dc≈207659(近满量程)且 ac_pp=0(恒定), 判为饱和; 按数据手册 Table 8 从 12.6mA 降到 6.2mA 换回动态范围 */

#define REG_WR_TRIES      3         /* 关键寄存器"写+回读"校验的尝试次数 */
#define REG_WR_RETRY_MS   15        /* 两次尝试之间的间隔 (ms) */

/* 主循环轮询周期 (ms)。轮询频率不必等于采样率:
 *   输出样本率是 100 sps(0x0A=0x2F 的 400Hz 采样经 0x08=0x5F 的 4 平均), 而读 FIFO
 *   本来就是"攒几个样本一次取走"的设计 —— 50ms 一轮最多积 5 个样本, FIFO 深 32,
 *   离溢出(32 个 = 320ms)差得很远, 安全(见 HR_FIFO_SAMPLES_PER_POLL 的换算)。
 * 取 50 而不是 10 的理由: 一轮采样 = 读 0x00/0x01 + 读 0x05 + 读 0x04/0x06(仅诊断) + 读样本,
 *   10ms 一轮 = 把这套动作压在 100Hz 上重复, 总线负载是 50ms 一轮的 5 倍; 而本板总线
 *   是"拼接 SCL + 只有 SoC 内部弱上拉"的边际状态(见文件顶部), 降负载直接换电气余量。
 *   (第四次改后**每轮的溢出清零 x3 没有了** —— 那是本次修的 bug 的根因, 单轮消息数不增反减。)
 *   采样率/算法不受影响: 样本还是按 100 sps 进 FIFO, 只是我们攒 50ms 再取。
 * 取值范围 20~50: 下限 20ms 仍比采样周期(10ms)慢, 不会把负载抬回去。
 * 改这里只需同步看 HR_RECOVER_FAILS / HR_DIAG_STREAK / HR_OVF_STUCK_ROUNDS 的换算。 */
#define HR_POLL_MS        50

/* 诊断打印限流(硬要求: 采样循环里绝不逐拍打印):
 *   HR_STAT_MS      stat 行固定周期 —— 每 2s 一条
 *   HR_DIAG_STREAK  "FIFO 空"判定: 连续这么多轮 wr==rd(100 轮 @HR_POLL_MS=50 -> 约 5s)
 *                   才算真没数据, 否则单拍读到空属正常, 会误报
 *   HR_CONST_STREAK 连续相同 raw 样本数(按样本数算, 与轮询周期无关: 约 3s @100sps)
 *                   判"读数恒为常数"
 *   HR_DIAG_EVERY   diag 限流: 每种原因首次立即打印, 之后每这么多次补一条
 *                   (按轮数算: 600 轮 @50ms -> 约 30s) */
#define HR_STAT_MS        2000
#define HR_DIAG_STREAK    100
#define HR_CONST_STREAK   300
#define HR_DIAG_EVERY     600
/* FIFO 读粒度 (单轮处理上限 + 读法回退开关, 见文件顶部"FIFO 读法"):
 *   0 = 一个样本一次 3 字节读 (Zephyr 的做法);
 *   1 = 一个样本三次 1 字节读 (最保守形态: 只走本板 100% 验证过的 length=1)。
 * 真机依据(2026-10, 已确证): 采样期 3 字节的单次读失败 —— 同一轮里 0x05(OVF)
 *   能读到非 0, 而 0x04/0x06 读失败(stat 行 wr=?? rd=??, ovf 每秒 +100 = 每轮
 *   都走溢出分支); 单字节读在 init 期稳定全绿。故取 1。
 * 若将来总线余量恢复(重做 SCL 接头 / 加外部上拉 / 换整条线), 可切回 0 拿回吞吐
 *   —— 一行可单独回退。 */
#define HR_FIFO_BYTE_WISE  0
/* 每轮读取样本数的**上限** —— **不用 0x04/0x06 指针算数量**(真机已确证本板读回的指针
 * 不可信/不前进: wr=1 rd=0 恒定, 拿它算 n=(wr-rd)&0x1F 会永远只读同一个 FIFO 槽)。
 * 取值 5 -> 16 (第四次改)的依据:
 *   - 产出速率: 100 sps × HR_POLL_MS=50ms = 每轮 5 个。取 5 是"刚好跟上" —— 任何一轮
 *     被拖慢(printf/总线恢复/调度/门控兜底)就**永久落后**, FIFO 常满、溢出计数常涨;
 *   - 16 ≈ 3.2 倍余量: 单轮被拖到 160ms 也能在同一轮把积压取走; FIFO 深 32 = 320ms,
 *     按 16/轮 的名义排空速率是产出速率的 3.2 倍, 稳态不可能溢出;
 *   - 上限 32 = MAX30102 FIFO 深度(器件物理上不可能一次给出更多), 由下面的 #if 兜住;
 *   - 总线代价: 每样本 3 次单字节读(HR_FIFO_BYTE_WISE=1), 16 个 = 48 次 ioctl + 5 次
 *     寄存器读 ≈ 53 次/轮, 50kHz 下仍在 50ms 预算内(真机 N=5 时 20 次/轮、零失败);
 *     且 HR_FIFO_DRAIN_ALL=1 时这个值只是上限 —— 真按 16 读只在"真有积压"时发生。
 * 改这里请同步看 HR_POLL_MS(50ms)、HR_FIFO_MIN_SAMPLES 与 FIFO 深度(32) 的换算。 */
#define HR_FIFO_SAMPLES_PER_POLL  16

#if HR_FIFO_SAMPLES_PER_POLL < 1 || HR_FIFO_SAMPLES_PER_POLL > 32
#  error "HR_FIFO_SAMPLES_PER_POLL 必须落在 1..32 (MAX30102 FIFO 深度 = 32)"
#endif

/* 排空模式(第四次改新增, 一行可切回固定 N):
 *   1 = 每轮"读到 FIFO 空为止": 从第 HR_FIFO_MIN_SAMPLES 个之后, 一旦读到与**流中前一拍
 *       完全相同**的样本, 就判为"空 FIFO 返回的陈旧槽" —— 丢掉该样本并结束本轮。于是
 *       "有多少新鲜样本就取多少", 不会落后于器件写指针, 也不会把陈旧样本喂进算法;
 *   0 = 固定读 HR_FIFO_SAMPLES_PER_POLL 个(不判陈旧; 空 FIFO 时多读的样本会进算法)。
 * 为什么"相邻样本完全相同"可以当空 FIFO 判据: MAX30102 在 FIFO 空时读 0x07 返回的是读
 *   指针所在的那个旧槽(SparkFun/Linux 都用指针计数来避免多读, 正是因为这个行为); 而
 *   18bit 原始值在 100sps 下相邻两拍完全相同的概率极低(静息时 AC 也有几十 counts)。
 *   真机上 dup=97~100%/byeq=100% 就是"反复读到同一个槽"的指纹。
 * 代价(如实标注): 偶发的一对真实相同样本会被当成空标记丢掉 1 个 —— FIFO 不清, 下一轮
 *   自然会补上, 不丢数据, 只是波形上少一个点(算法对单点缺口不敏感)。 */
#define HR_FIFO_DRAIN_ALL  1

/* 排空模式的**最少**读取数: 5 = 100 sps × HR_POLL_MS=50ms, 即"一轮的产出量"。
 * 低于它就不做"重复即停"判断 —— 否则开局空 FIFO 时一轮只读 1 个样本, 算法会被饿。 */
#define HR_FIFO_MIN_SAMPLES  5

#if HR_FIFO_MIN_SAMPLES < 1 || HR_FIFO_MIN_SAMPLES > HR_FIFO_SAMPLES_PER_POLL
#  error "HR_FIFO_MIN_SAMPLES 必须落在 1..HR_FIFO_SAMPLES_PER_POLL(否则排空早停永不触发)"
#endif

/* FIFO 整块读开关(降级路径, 第四次改新增; 默认 0 = 逐样本读):
 *   0 = 逐样本读(本板唯一 100% 验证过的形态, 见 HR_FIFO_BYTE_WISE);
 *   1 = 每轮先试一次 i2c_read_regs(0x07, buf, 3*N) 把整块读回来(= 一次性排空 96 字节),
 *       **失败一次即永久降级**为逐样本读(本板 length>1 的读有前科: 采样期 3 字节读失败
 *       而单字节读全绿, 见 HR_FIFO_BYTE_WISE 的定义), 且一次失败要等下层
 *       I2C_XFER_TIMEOUT=5000ms, 所以绝不每轮重试; 降级事实会打一条限流日志并计入
 *       rd_fifo 失败。整块读没有逐样本的早停机会, 尾部的陈旧重复样本在读完后的统一
 *       裁剪里丢掉(见 hr_drain ⑤)。
 * 与 HR_FIFO_BYTE_WISE=1 互斥(逐字节读没有"整块"一说), 编译期挡住。 */
#define HR_FIFO_BULK_READ  1

#if HR_FIFO_BULK_READ && HR_FIFO_BYTE_WISE
#  error "HR_FIFO_BULK_READ=1 需要 HR_FIFO_BYTE_WISE=0(逐字节读与整块读互斥)"
#endif

/* FIFO 指针被清零(hr_fifo_clear / hr_recover)之后是否跳过本轮采样:
 *   1 = 跳过 —— 刚被清空的 FIFO 读出来是陈旧/空数据, 会把"red 恒定"的假象带回来;
 *   0 = 照读(默认) —— 采样不被清零分支饿死(efcca48 的真机结论: 清零后那轮照样要读)。
 * 第四次改后 fifo_clear 只发生在"极端陈旧出口"(连续 fresh=0), 所以这个开关的影响面
 * 比旧代码小得多; 保留它是为了与 HR_FIFO_BYTE_WISE/HR_FIFO_DRAIN_ALL 同一风格回退。 */
#define HR_SKIP_SAMPLE_AFTER_CLEAR  0

/* INT_STATUS 门控的兜底(见 hr_drain ①): 连续这么多轮 0x00/0x01 都读到 0 时, 认为
 * "PPG_RDY 位定义/使能位"的假设在本板不成立, 自动改成每轮盲读 —— 宁可多花 3*N 字节的
 * 总线时间, 也不能因为一个位理解错就把采样饿死。20 轮 @HR_POLL_MS=50ms = 1s。
 * 自愈: 哪一轮重新读到非 0 状态就立刻回到门控。 */
#define HR_NOINT_STREAK  20

/* 故障恢复限流 (两条都要满足才真做一次恢复):
 *   HR_RECOVER_FAILS  连续失败轮数(一轮采样最多记一次失败, 50ms 一轮 -> 约 1s)
 *   HR_RECOVER_MIN_MS 两次恢复之间的最小时间间隔 */
#define HR_RECOVER_FAILS  20
#define HR_RECOVER_MIN_MS 2000

/* "0x05 连续非 0"的**诊断**门槛(第四次改后不再作为控制流、不再触发恢复):
 * 连续这么多轮 OVF != 0 时打**一条**提示(只打一次, 到 HR_OVF_STUCK_ROUNDS 那一轮),
 * 说明"现在只记账"; 真机 0x05 恒非 0 是旧读法把写指针拉回起点的假阳性 —— 判据请看
 * stat 行的 fresh=/rdup= (读侧) 与 algo 行的 dup=/d1= (信号侧)。HR_POLL_MS=50 时 20 轮 = 1s。 */
#define HR_OVF_STUCK_ROUNDS 20

/* ★ 极端陈旧出口(第四次改新增): 采样热路径上**唯一**允许写 0x04/0x06 的地方。
 *   触发条件(两个都要): 连续 HR_STALE_CLEAR_ROUNDS 轮 fresh=0(本轮读到的样本全都与
 *   上一轮最后一个相同 —— 读侧一个新鲜样本都没有), 且距上次清零 >= HR_STALE_CLEAR_MIN_MS。
 * 依据: 100 sps 下 50ms 一轮必有约 5 个新样本, 连续 2s 一个都没有, 只能解释为"读侧真的
 *   卡在同一个槽"(或器件停采) —— 这时器件自己的指针已不可信, 需要主机重同步一次。
 *   40 轮 @50ms = 2s; 10s 的时间限流保证退化态下最多每 10s 清一次(代价 <= 20 轮陈旧
 *   样本), 而不是旧代码的**每 50ms 清一次**(那正是本次要修的 bug)。
 * 判据刻意用 fresh 而**不是** 0x05(OVF): 真机 0x05 恒非 0 是假阳性, 拿它触发的效果就是
 *   "每 2s 清一次指针" —— 还没修好。 */
#define HR_STALE_CLEAR_ROUNDS   40
#define HR_STALE_CLEAR_MIN_MS   10000

#define HR_RING           512       /* 原始样本环形缓冲 (约 5s @100Hz) */
#define RR_HIST           12        /* RR 中位数窗口 (2026-10-13 第五次改: 8 -> 12)。
                                     * 窗口从 4.2s 拉到 6.3s@107bpm。理由: 偶数窗口中位数取
                                     * tmp[n/2], n=8 时是"第 4 个/共 8 个" —— 一个双检峰
                                     * 就能把中位数从 561 推到 422(106 -> 142bpm 的硬悬崖);
                                     * n=12 时要 6/12 才推得动, 悬崖概率显著下降 */
#define BPM_MIN           40
#define BPM_MAX           200

/* ---- 心率算法参数(2026-10 第三次改: 让 bpm 真的出值; 每条都写依据) ----
 * 生理依据: 静息心率 45~150bpm = 0.75~2.5Hz。带通取 **0.5~3.5Hz(30~210bpm)**:
 *   低频端留到 0.5Hz 是为了运动/恢复期与呼吸性窦性心律不齐(HRV)的低频成分,
 *   高频端留到 3.5Hz 是为了运动上限; 同时把 4 平均后残余的高频噪声压掉。
 *   (比 0.75~2.5 宽一档; 又比 0.1~10 窄 —— 后者会把运动伪影与工频谐波放进峰值检测。) */
#define HR_PI              3.14159265f
#define HR_FS_NOMINAL      100.0f   /* 标称输出样本率: 0x0A=0x2F(400Hz)/0x08=0x5F(4平均) */
#define HR_FS_MIN          25.0f    /* 有效采样率下限: 低于它峰值检测的时间分辨率不够 */
#define HR_BP_LP_HZ        3.5f     /* 带通上边带 */
#define HR_BP_HP_HZ        0.5f     /* 带通下边带 */
#define HR_DC_HP_HZ        0.1f     /* 去 DC 一阶高通(比 0.5Hz 带边低 5 倍 -> 带内增益 0.98) */
#define HR_ENV_ATTACK      0.25f    /* 包络上升限速: 单拍尖峰最多把包络抬 25% */
#define HR_ENV_HALFLIFE_MS 2000.0f  /* 包络衰减半衰期(心率/幅度真变了时 2s 内跟上) */
#define HR_ENV_BLANK_SAMPLES 500    /* ★启动瞬态屏蔽窗(第六次改): 复位后 5s 内包络用快
                                     * 衰减, 把 LED 上电/DC 建立产生的大 |y| 忘掉。真机取证:
                                     * 手指一开始就按住时 env 被抬到 23399, 阈值 0.6×env
                                     * 要 ~20s 才降到真实脉搏峰以下(nth==nmax, 一个峰都过不
                                     * 去) —— 这正是「15s 窗口必然 timeout」的根因。 */
#define HR_ENV_FAST_DECAY  0.97f    /* 屏蔽窗内的快衰减(每样本); @100Hz 半衰期 0.23s */
#define HR_PEAK_THRESH_K   0.6f     /* 自适应阈值 = 0.6 × 包络(最近数秒 AC 幅度) */
#define HR_PEAK_REFRACT_MS 300      /* 不应期: 对应 200bpm 上限, 防一次心跳被检成两拍 */
#define HR_SAMPLE_MS       10       /* 一个样本的名义时间(100sps), 峰时刻回退用 */
#define HR_WARMUP_SAMPLES  80       /* 复位后先喂 0.8s(滤波器/DC/包络建立)再开始检峰 */
#define HR_RR_MIN_MS       333      /* RR 下限: 180bpm。比不应期(300ms=200bpm)更严,
                                     * 免得把"贴住不应期的双检"当成 200bpm 输出 */
#define HR_RR_MAX_MS       1500     /* RR 上限: 40bpm */
#define HR_RR_HIST_MIN     3        /* 至少 3 个 RR(4 个峰)才谈中位数 */
#define HR_RR_AGREE_PCT    20       /* RR 与中位数的"一致"容差(%); 门限见 hr_feed (5):
                                     * 至少一半 RR 落在容差内才认这个节律 —— 用**一致性
                                     * 计数**而不是极差, 单个漏检/双检不该把门限判死。
                                     * 2026-10-13 第五次改: 25 -> 20 —— 25% 恰好放行"双检
                                     * 对": hr_close(422,561,25) = 24.78% < 25% 判"一致";
                                     * 20% 才把它挡在门外(561 的 20% = 112ms < 139ms) */
#define HR_BPM_ACCEPT_MIN  40       /* 接受窗口(比 BPM_MIN/MAX 更严: 静息 45~150 留余量) */
#define HR_BPM_ACCEPT_MAX  180
#define HR_BPM_STABLE_N    3        /* 候选值连续一致几次才允许更新 bpm(分钟级稳定的落点) */
#define HR_BPM_TOL_PCT     5        /* "一致"的容差(%): 覆盖 HRV。2026-10-13 第五次改:
                                     * 12 -> 5 —— 真机 7 次观测跳变里 6 次被 12% 判"一致"
                                     * (最大放行 11.6%), 于是 95<->142 的漂移在 stab 计数上
                                     * 一路"稳定"。5% @100bpm = ±5bpm */
#define HR_BPM_ABS_TOL     3        /* bpm "一致"的**绝对**下限(bpm): 低心率下 5% 只有
                                     * ±2bpm, 而 cand = 60000/med 是整数截断 —— ±1 的量化
                                     * 不该被判"不一致"。最终判据 = max(3bpm, 5%) */
#define HR_BPM_SLEW        3        /* 输出限速(bpm/次更新), 见 hr_slew_bpm() */
#define HR_MIN_AC_AMP      10.0f    /* 带通后 AC 幅度(包络)下限, 18bit counts; 对应峰峰值
                                     * ~40 counts。低于它的"波形"是噪声/量化; 真机若 bp_pp
                                     * 只有个位数, 说明信号太弱(手指压力/LED/环境光) ——
                                     * 那是硬件问题, **不要**降这个门限去凑 bpm。 */
#define HR_MAX_ACDC_PCT    5        /* AC/DC 上限(%): 生理 PPG 的 AC/DC 只有 0.1%~2%。
                                     * 2026-10-13 第五次改: 40 -> 5 —— 40 是生理上限的 20 倍,
                                     * 等于没有门限: 真机 t=3s 的 acdc=7.6% 被判"合格", 垃圾期
                                     * 就把错值锁进 bpm。5% 顺带就是"信号就绪"判据:
                                     * t=1s 246% / t=3s 7.6% 直接不合格, t=5s 1.55% 才放行 */
#define HR_PK5S_MIN        3        /* 5s 内峰数下限(36/min) */
#define HR_PK5S_MAX        20       /* 5s 内峰数上限(240/min) */
#define HR_PK_WIN_MS       5000     /* 峰数质量窗口(ms) */
#define HR_PEAK_RING       16       /* 峰时刻环深度(>= HR_PK5S_MAX, 保证 5s 窗口装得下) */
#define HR_LAST_N          10       /* 诊断打印的原始值快照长度(2 轮 @5 样本/轮) */

/****************************************************************************
 * Private Types
 ****************************************************************************/

/* 采样热路径上的每一个 ioctl 步骤(逐操作记账, 见 hr_op_result())。
 * 枚举顺序 = 一轮 hr_drain() 里的**实际执行顺序**: 真机上"每轮第一个失败的"就是按它比的。
 *   rd_int  ① 读 0x00/0x01 中断状态 —— 本轮"有没有数据"的**判据**(读清)
 *   rd_ovf  ② 读 0x05 FIFO 溢出计数
 *   wr_clr  ③ FIFO 指针清零写 0x04/0x05/0x06(逐条下发, 任一条失败即停, 见 hr_fifo_clear)
 *              —— 第四次改后**只在极端陈旧出口/recover 才发生**, 稳态 n=0
 *   rd_ptr  ④ 读 0x04/0x06 写/读指针 —— **现在只作诊断**, 不参与控制流
 *   rd_fifo ⑤ 读 0x07 FIFO 样本(HR_FIFO_BYTE_WISE=1 时每个样本 3 次单字节读)
 * HR_OP_NONE=0 是"没有失败"哨兵 —— g_hr 整体 memset 清零后天然就是"没失败过",
 * 不会把 0 误显示成某个真实操作。 */
enum hr_op_e
{
  HR_OP_NONE = 0,
  HR_OP_RD_INT,
  HR_OP_RD_OVF,
  HR_OP_WR_CLR,
  HR_OP_RD_PTR,
  HR_OP_RD_FIFO,
  HR_OP_N
};

struct hr_op_stat_s
{
  uint32_t n;              /* 累计尝试次数 */
  uint32_t fails;          /* 其中失败次数 */
  uint8_t  last_reg;       /* 最近一次失败所在的寄存器 */
  uint8_t  last_sub;       /* 样本读的第几个字节(1..3); 0 = 不适用 */
  int      last_errno;     /* 最近一次失败时的 errno */
};

struct hr_state_s
{
  int      fd;                 /* /dev/i2c1 (I2C2: PA41=SCL/PA42=SDA) */
  bool     running;
  bool     inited;
  uint32_t samples;            /* 累计样本数 */
  uint32_t overflows;          /* FIFO 溢出次数 */
  uint32_t last_bpm;
  uint32_t last_bpm_ts;        /* ms */
  bool     have_bpm;
  uint32_t raw_last;           /* 最近一个原始值 */
  uint32_t raw_min, raw_max;

  /* 诊断/统计(只由采样线程写, 不参与算法):
   *   stat 行每 HR_STAT_MS 一条; 异常持续时首次 + 限流打印 diag 行 */
  uint32_t fifo_last;          /* 本轮 FIFO 实际读到的样本数 (stat 的 n_poll/fifo_rd) */
  int      fifo_wr;            /* 最近一次读到的 0x04 写指针 (-1=这轮没读到) —— 仅诊断 */
  int      fifo_rd;            /* 最近一次读到的 0x06 读指针 (-1=这轮没读到) —— 仅诊断 */
  int      int1;               /* 本轮读到的 0x00 INT_STATUS1 (-1=这轮没读到) */
  int      int2;               /* 本轮读到的 0x01 INT_STATUS2 (-1=这轮没读到) */
  uint32_t noint_streak;       /* 连续"INT_STATUS 全 0"轮数(门控兜底, 见 hr_drain ①) */
  uint32_t empty_streak;       /* 连续"本轮没读到任何样本"轮数(空 FIFO 判定用) */
  uint32_t same_streak;        /* 连续相同 raw 值的样本数(常数检测) */
  uint32_t diag_empty;         /* 诊断计数(限流用): FIFO 空 */
  uint32_t diag_err;           /*   读失败 */
  uint32_t diag_zero;          /*   整包全 0 */
  uint32_t diag_const;         /*   样本恒为常数 */
  uint32_t diag_rec;           /*   总线恢复(限流打印用) */

  /* 故障记账与恢复(见 hr_fail()/hr_recover(), stat 行 rec=) */
  uint32_t fails;              /* 累计失败轮数 */
  uint32_t fail_streak;        /* 连续失败轮数(任一轮读/写成功即清零) */
  bool     fail_counted;       /* 本轮是否已记过一次(一轮内多处失败只算一次) */
  uint32_t recover_count;      /* 已发起的恢复次数(= stat 的 rec=) */
  uint32_t recover_ok;         /* 其中"探活+重配"走完的次数 */
  uint32_t last_recover_ms;    /* 上次恢复时刻(时间限流) */
  int      reset_ioctl_ret;    /* 最近一次 I2CIOC_RESET 的 errno(0=成功) */

  /* 逐操作记账(见 hr_op_result()): 每种操作的"尝试/失败"次数 + stat 行的两个标记。
   *   last_fail_op     最近一次失败的操作(stat: lastfail=)
   *   round_fail_first 本轮第一个失败的操作(stat: firstfail=) —— 真机指纹就是它
   *   ovf_streak       连续"0x05 非 0"的轮数(**仅诊断**: algo 行的 ovfs=; 第四次改后
   *                    不再据此清零/触发恢复, 见 hr_drain 的溢出分支) */
  struct hr_op_stat_s op[HR_OP_N];
  uint8_t  last_fail_op;
  uint8_t  round_fail_first;
  uint32_t ovf_streak;
  uint32_t diag_ovf;           /* 诊断计数(限流用): 0x05 非 0(只记账) */
  uint32_t diag_stale;         /* 诊断计数(限流用): 极端陈旧出口触发 */

  /* 读侧新鲜度取证(第四次改新增, 直接回答"读到的样本还重不重复"):
   *   round_n      本轮实际读到的样本数(= fifo_last)
   *   round_fresh  其中与**上一轮最后一个样本**不同的个数(stat: fresh=x/N)
   *   round_dup    其中与**流中前一拍**相同的个数(含跨轮边界; stat: rdup=)
   *   stale_streak 连续"fresh=0"的轮数(极端陈旧出口的判据)
   *   stale_clear  极端出口已执行次数(stat: stclr=; 稳态应恒为 0)
   *   last_stale_clear_ms 上次极端出口时刻(HR_STALE_CLEAR_MIN_MS 限流用)
   *   bulk_failed  HR_FIFO_BULK_READ=1 时整块读失败过的粘滞标记(失败一次永久降级) */
  uint32_t round_n;
  uint32_t round_fresh;
  uint32_t round_dup;
  uint32_t stale_streak;
  uint32_t stale_clear;
  uint32_t last_stale_clear_ms;
  bool     bulk_failed;

  /* 算法状态(信号链: 去DC -> 带通 -> 包络/阈值 -> 峰 -> RR中位数 -> 稳定输出) */
  float    dc;                 /* DC 估计(一阶高通状态; 真机量级 6.8万~14万) */
  float    lp, hp;             /* 带通的两级状态(低通 3.5Hz / 高通 0.5Hz) */
  float    prev;               /* 上一个滤波输出 */
  float    prev2;
  float    env;                /* **带通后** AC 幅度包络(自适应阈值的基准) */
  float    env_raw;            /* **去 DC 后(带通前)** AC 幅度包络 —— AC/DC 合理性门限
                                * 用它而不是 env: 白噪声/错位数据是宽带信号, 窄带带通
                                * 会把它的幅度压掉一个量级, 用 env 判会漏(实测: 6.8万~14万
                                * 随机跳的数据用 env 判能通过门限并输出 133bpm 的错值) */
  float    env_decay;          /* 包络每样本衰减系数(按实测 fs 换算, 半衰期 2s) */
  uint32_t last_peak_ms;
  uint32_t rr[RR_HIST];
  int      rr_count;
  int      rr_idx;
  uint32_t rr_med;             /* 最近 RR 中位数(ms) —— 诊断打印用 */
  uint32_t reject_cnt;         /* 杂峰归并次数(2026-10-13 第五次改新增, 见 hr_feed (4)):
                                * "新峰与上一个**真峰**太近(< HR_RR_MIN_MS)"被丢掉的次数。
                                * 稳态不该为 0(双检/舒张期切迹杂峰总会有), 但**短间隔
                                * 422/441/492/517 不该再出现在 rr[] 里**; 若它恒为 0 且
                                * rr_med 仍偏短, 说明杂峰落在不应期(300~333ms)之内, 走的
                                * 是"整拍丢弃"那条路, 不是归并这条路 */
  uint32_t warm;               /* 复位后已喂样本数(峰值检测的 warm-up 门槛) */
  uint32_t win_nmax;           /* 诊断: 本窗口"3点局部极大"次数(与 warm/阈值无关) */
  uint32_t win_nth;            /* 诊断: 其中被自适应阈值挡掉的次数 */
  float    last_thr;           /* 诊断: 最近一次的自适应阈值(= 0.6 × env) */
  uint32_t peak_ring[HR_PEAK_RING];  /* 最近若干次检峰的墙钟时刻(ms, 算 pk5s 用) */
  int      peak_idx;
  int      peak_cnt;
  uint32_t cand;               /* 最近一次候选 bpm(= 60000/RR 中位数) */
  uint32_t last_cand;          /* 上一个候选(连续一致性判定用) */
  uint32_t stable_cnt;         /* 候选值连续一致的次数 */
  uint32_t last_jump_cand;     /* "与当前 bpm 差得远"的那一串候选 */
  uint32_t jump_cnt;           /* 上面那一串连续了几次 */
  int      q;                  /* 最近一次检峰时的置信度 0..100 */
  const char *gate;            /* 最近一次检峰时第一个不过的门限(字符串常量, 只读) */
  float    fs_used;            /* 算法当前用的采样率(实测, 限幅在 HR_FS_MIN..200Hz) */

  /* 算法诊断窗口(每 HR_STAT_MS 由 hr_algo_diag() 取走并清零; 只由采样线程读写) */
  uint32_t fed;                /* 累计入算法样本数(= hr_feed 调用次数) */
  uint32_t win_n;              /* 本窗口入算法样本数 */
  uint32_t win_dup;            /* 本窗口内 raw == 上一个 raw 的个数(陈旧/重复样本指纹) */
  uint32_t win_dsum;           /* 本窗口 |Δraw| 累计(波形平滑度) */
  uint32_t win_b12eq;          /* 本窗口内样本 b1==b2 的个数(逐字节读错位指纹) */
  uint32_t win_b012eq;         /* 本窗口内 b0==b1==b2 的个数(三次读回同一字节) */
  uint32_t win_start_ms;       /* 本窗口起点(算实测 fs 用) */
  float    win_ac_min, win_ac_max, win_ac_sq;   /* 去 DC 后 AC 的极值与平方和 */
  float    win_bp_min, win_bp_max, win_bp_sq;   /* 带通后信号的极值与平方和 */
  uint32_t algo_rst;           /* 滤波器复位次数(每轮都涨 = 溢出分支在反复清算法) */
  uint32_t algo_rst_ms;        /* 最近一次滤波器复位时刻(ms) */
  uint32_t last_n[HR_LAST_N];  /* 最近 HR_LAST_N 个原始值(读取顺序) */
  int      last_n_idx;
  int      last_n_cnt;
  uint32_t raw_prev;           /* 上一个样本(差分/重复诊断) */
  bool     have_raw_prev;
};

static struct hr_state_s g_hr;

/****************************************************************************
 * I2C helpers (/dev/i2cN + I2C_TRANSFER ioctl, 与 i2c 工具同路径)
 ****************************************************************************/

/* 运行期总线速率: 默认取编译期 I2C_FREQ, 只有 selftest 在诊断期间临时改(结束后恢复)。
 * 本树没有 I2CIOC_SET_FREQUENCY 之类的 ioctl —— nuttx/include/nuttx/i2c/i2c_master.h
 * 只定义了 I2CIOC_TRANSFER(0x0001)/I2CIOC_RESET(0x0002); 但速率是逐条消息生效的:
 * sifli_i2c.c:509/516 对每条消息都调用 sifli_i2c_configure() -> HAL_I2C_Init()(:348)
 * 重算分频, 所以改 msg.frequency 就能在运行期切频, 不用改宏重编。 */
static uint32_t g_i2c_freq = I2C_FREQ;

static int i2c_write_reg(int fd, uint8_t reg, uint8_t val)
{
  struct i2c_msg_s msg;
  struct i2c_transfer_s xfer;
  uint8_t buf[2] = { reg, val };

  msg.frequency = g_i2c_freq;
  msg.addr      = MAX30102_ADDR;
  msg.flags     = 0;
  msg.buffer    = buf;
  msg.length    = 2;

  /* 走 I2C 字符设备: I2CIOC_TRANSFER + struct i2c_transfer_s
   * (nuttx/include/nuttx/i2c/i2c_master.h:121; I2C_TRANSFER 那个宏是
   *  master ops 用的, 不是 ioctl 号) */
  xfer.msgv = &msg;
  xfer.msgc = 1;
  return ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer);
}

/* 读失败日志(限流): 读路径在采样热路径(每 HR_POLL_MS 一轮, 每轮多次读)上被调用,
 * 总线真断时不做限流会刷屏。
 * 前 HR_ERR_LOG_FIRST 次全打, 之后每 HR_ERR_LOG_EVERY 次打一条 —— 留证据但不刷屏。
 * 成功不打日志(否则采样循环会把控制台淹掉), 与现有"只在失败时 printf"的风格一致。
 * 注: 采样循环另有更细的逐操作日志(hr_op_result, 见"逐操作记账"段), 这里保留的是
 *     "路径B 的第 1 条还是第 2 条消息失败"这一层细节。 */
#define HR_ERR_LOG_FIRST  3
#define HR_ERR_LOG_EVERY  256

static void i2c_read_err_log(const char *stage, uint8_t reg, int ret)
{
  static uint32_t n;

  n++;
  if (n <= HR_ERR_LOG_FIRST || (n % HR_ERR_LOG_EVERY) == 0)
    {
      printf("hr_sensor: %s 失败(reg=0x%02x): ioctl=%d errno=%d(%s), 第 %u 次\n",
             stage, reg, ret, errno, strerror(errno), (unsigned)n);
    }
}

/* 路径A: NuttX 规范的组合读(msg0=NOSTOP 写地址 + msg1=READ, 一次 ioctl 2 条消息)。
 * 本板下层驱动不支持它(详见文件顶部 HR_USE_SPLIT_READ 与下面路径B 的注释), 主读路径
 * 已不用; 保留在这里给 selftest 做对照/回归证据 —— 下层驱动修好后这里应能命中。 */
static int i2c_read_regs_combined(int fd, uint8_t reg, uint8_t *buf, int n)
{
  struct i2c_msg_s msg[2];
  int ret;

  msg[0].frequency = g_i2c_freq;
  msg[0].addr      = MAX30102_ADDR;
  msg[0].flags     = I2C_M_NOSTOP;
  msg[0].buffer    = &reg;
  msg[0].length    = 1;

  msg[1].frequency = g_i2c_freq;
  msg[1].addr      = MAX30102_ADDR;
  /* ★ 必须带 I2C_M_READ: 少了它第二条消息会被当成"写", 数据读不回来
   *   (症状: PART_ID 恒为 0x00 而 i2c 工具读得出 0x15)。
   *   用法与 apps/system/i2c/i2c_get.c:176-182 完全一致。 */
  msg[1].flags     = I2C_M_READ;
  msg[1].buffer    = buf;
  msg[1].length    = n;

  {
    struct i2c_transfer_s xfer;

    xfer.msgv = msg;
    xfer.msgc = 2;
    ret = ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer);
  }

  if (ret < 0)
    {
      /* 退回两条独立传输 (有些总线实现不支持 NOSTOP/NOSTART 组合) */
      struct i2c_msg_s single;
      struct i2c_transfer_s xfer;

      single.frequency = g_i2c_freq;
      single.addr      = MAX30102_ADDR;
      single.flags     = 0;
      single.buffer    = &reg;
      single.length    = 1;
      xfer.msgv = &single;
      xfer.msgc = 1;

      ret = ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer);
      if (ret < 0)
        {
          return ret;
        }

      single.flags  = I2C_M_READ;
      single.buffer = buf;
      single.length = n;
      ret = ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer);
    }

  return ret;
}

/* 路径B(分两次传输, 既是**主读路径**(HR_USE_SPLIT_READ=1), 也是 selftest 对照里的 B):
 * 先"只写寄存器地址"(带 STOP)把器件内部地址指针定位到目标寄存器, 再单独发一次读。依据:
 *   (1) MAX30102 的寄存器地址指针在 STOP 后保留, SparkFun/多数 Arduino 库就是
 *       beginTransmission+write(reg)+endTransmission() 再 requestFrom() 这么读的;
 *   (2) 等价于 `i2c get -s ...` 的走法 (apps/system/i2c/i2c_get.c:195-206 两次独立 transfer)。
 * 为什么 selftest 要单列这条: 本板下层驱动只在"第二条消息带 I2C_M_NOSTART"时才把寄存器
 * 地址发出去 (vendor/sifli/chips/sf32lb52/sifli_i2c.c:492-519); 而 NuttX 规范的组合读
 * (msg0=NOSTOP 写地址 + msg1=READ, 见 nuttx/drivers/i2c/i2c_writeread.c:73-95, 也是
 * i2c 工具的默认路径) 在这里会把 msg0 存进 nostop_msg 后直接丢弃(:556-558) ->
 * 读回的是"器件当前指针", 不是目标寄存器。A/B 两条路一起跑, 就能把"电气问题"和
 * "这条软件路径问题"分开, 不至于让电气排查背错锅。 */
static int i2c_read_regs_split(int fd, uint8_t reg, uint8_t *buf, int n)
{
  struct i2c_msg_s msg;
  struct i2c_transfer_s xfer;
  int ret;

  msg.frequency = g_i2c_freq;
  msg.addr      = MAX30102_ADDR;
  msg.flags     = 0;                 /* 写: 只发寄存器地址, 带 STOP */
  msg.buffer    = &reg;
  msg.length    = 1;

  xfer.msgv = &msg;
  xfer.msgc = 1;
  ret = ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer);
  if (ret < 0)
    {
      i2c_read_err_log("路径B 传输1(只写寄存器地址+STOP)", reg, ret);
      return ret;
    }

  msg.flags  = I2C_M_READ;
  msg.buffer = buf;
  msg.length = n;
  ret = ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer);
  if (ret < 0)
    {
      i2c_read_err_log("路径B 传输2(单独发起读)", reg, ret);
    }

  return ret;
}

/* 主读路径分发: 所有"读寄存器"都从这里走(含 init 的 PART_ID/回读校验、采样 FIFO 读取)。
 * 写路径 i2c_write_reg 不经过这里, 保持原样。 */
static int i2c_read_regs(int fd, uint8_t reg, uint8_t *buf, int n)
{
#if HR_USE_SPLIT_READ
  return i2c_read_regs_split(fd, reg, buf, n);
#else
  return i2c_read_regs_combined(fd, reg, buf, n);
#endif
}

static int i2c_read_reg(int fd, uint8_t reg, uint8_t *val)
{
  return i2c_read_regs(fd, reg, val, 1);
}

/* 写寄存器 + 回读校验, 最多 REG_WR_TRIES 次, 每次间隔 REG_WR_RETRY_MS。
 * 失败时打印 寄存器名/期望值/实际值/第几次尝试。
 * *back (可为 NULL) 带回最后一次回读值 —— 读不到时为 0, 便于调用方在失败信息里
 * 展示"器件里真实是什么", 而不是回显写进去的值。
 * 返回 0 = 校验通过; -EIO = 尝试用尽仍未生效。 */
static int i2c_write_verify(int fd, uint8_t reg, uint8_t val,
                            const char *name, uint8_t *back)
{
  uint8_t got = 0;
  int t;

  for (t = 0; t < REG_WR_TRIES; t++)
    {
      int ret;

      got = 0;
      ret = i2c_write_reg(fd, reg, val);

      if (ret < 0)
        {
          printf("hr_sensor: %s(0x%02x) 写入失败: ioctl=%d errno=%d(%s), 第 %d/%d 次\n",
                 name, reg, ret, errno, strerror(errno), t + 1, REG_WR_TRIES);
        }
      else if (i2c_read_reg(fd, reg, &got) < 0)
        {
          printf("hr_sensor: %s(0x%02x) 回读失败: errno=%d(%s), 第 %d/%d 次\n",
                 name, reg, errno, strerror(errno), t + 1, REG_WR_TRIES);
        }
      else if (got == val)
        {
          if (back != NULL)
            {
              *back = got;
            }

          return 0;
        }
      else
        {
          printf("hr_sensor: %s(0x%02x) 回读校验失败: 期望 0x%02x, 实际 0x%02x, 第 %d/%d 次\n",
                 name, reg, val, got, t + 1, REG_WR_TRIES);
        }

      if (back != NULL)
        {
          *back = got;
        }

      usleep(REG_WR_RETRY_MS * 1000);
    }

  if (back != NULL)
    {
      *back = got;
    }

  return -EIO;
}

/****************************************************************************
 * Device init
 ****************************************************************************/

static int max30102_reset(int fd)
{
  uint8_t v;
  int i;

  if (i2c_write_reg(fd, REG_MODE_CONFIG, 0x40) < 0)     /* RESET */
    {
      return -EIO;
    }

  for (i = 0; i < 20; i++)                              /* 最多 200ms */
    {
      usleep(10 * 1000);
      if (i2c_read_reg(fd, REG_MODE_CONFIG, &v) < 0)
        {
          return -EIO;
        }

      if ((v & 0x40) == 0)
        {
          return 0;
        }
    }

  return -ETIMEDOUT;
}

static int max30102_init(int fd)
{
  uint8_t id = 0;
  uint8_t v;
  uint8_t rb_fifo = 0;          /* 以下 4 个都是"回读"到的真实寄存器值 */
  uint8_t rb_spo2 = 0;
  uint8_t rb_led  = 0;
  uint8_t rb_mode = 0;
  int fails;
  int tries;

  /* 读 10 次: 本板传感器走排针跳线、只靠内部上拉, 实测前几次常被干扰
   * (读到 0x11/0x0d/0x04 等), 连续读几次后会稳定在 0x15 —— 所以这里多试几次,
   * 命中 0x15 即认为器件在位。根治要在 SDA/SCL 上外加 2.2~4.7k 上拉到 3V3。 */
  for (tries = 0; tries < 50; tries++)
    {
      uint8_t got = 0;

      if (i2c_read_reg(fd, PART_ID_REG, &got) == 0 && got == PART_ID_VAL)
        {
          id = got;
          break;
        }
      id = got;
      usleep(20000);
    }

  printf("hr_sensor: PART_ID=0x%02x (期望 0x15, 第 %d 次读到)\n", id, tries + 1);
  if (id != PART_ID_VAL)
    {
      return -ENODEV;
    }

  if (max30102_reset(fd) < 0)
    {
      return -EIO;
    }

  /* 清 FIFO 指针与溢出计数 —— ★ 这是"start 时清一次"的那一次 (第四次改的契约:
   * 采样热路径 hr_drain 绝不再清; 见文件顶部"FIFO 读法")。
   * 此处只写不校验: 0x04 会被器件自身推进, 回读可能刚好撞上写指针 +1 而误报。 */
  i2c_write_reg(fd, REG_FIFO_WR_PTR, 0x00);
  i2c_write_reg(fd, REG_FIFO_OVF, 0x00);
  i2c_write_reg(fd, REG_FIFO_RD_PTR, 0x00);

  /* 关键配置逐条"写 + 回读校验 + 有限重试"。根因: 旧代码对这些写完全不看返回值,
   * I2C 写失败(跳线/上拉/总线错误)时寄存器还是默认值(0x0C=0x00 -> LED 不亮),
   * 函数却照样打印"初始化完成"。
   * 顺序: 先配 LED 电流(0x0C), 最后才进 MODE_CONFIG(0x09) —— 进模式前把所有
   * 配置摆好, 避免器件用默认 LED 电流先跑起来。 */
  rb_fifo = 0;
  rb_spo2 = 0;
  rb_led  = 0;
  rb_mode = 0;
  fails   = 0;

  fails += i2c_write_verify(fd, REG_FIFO_CONFIG, FIFO_CFG,
                            "FIFO_CONFIG", &rb_fifo) < 0;
  fails += i2c_write_verify(fd, REG_SPO2_CONFIG, SPO2_CFG,
                            "SPO2_CONFIG", &rb_spo2) < 0;
  fails += i2c_write_verify(fd, REG_LED1_PA, LED_PA,
                            "LED1_PA", &rb_led) < 0;
  /* INT_ENABLE1(0x02) = 0xC0: 使能 A_FULL_EN(bit7) + PPG_RDY_EN(bit6)。
   * 为什么现在要开(旧值是 0x00 "轮询, 不开中断"):
   *   2026-10 的新读法用 INT_STATUS(0x00) 判"有没有数据", 而**中断使能位决定对应的状态位
   *   会不会被置起来**。Linux IIO 也是这个次序 —— 先 update_bits(INT_ENABLE, MASK, FIFO_EN)
   *   再读 INT_STATUS 判数据(drivers/iio/health/max30102.c:414-417 与 :240-254);
   *   树内 Zephyr max30101 同样先写 INT_EN1 再用 INT_PPG_MASK 判(max30101.h:34)。
   *   不开的话状态位可能恒为 0, 门控会把采样饿死(本文件另有 HR_NOINT_STREAK 兜底)。
   * 为什么开两个: bit6=PPG_RDY 每来一个样本置一次, 是**主判据**; bit7=A_FULL(本配置
   *   0x08=0x5F 的 A_FULL=15 -> FIFO 积到 17 个才置位)是"我们落后了"的备份信号, 也正是
   *   Linux 用的那一个。门控对两者一视同仁(任一非 0 即可能有数据)。
   * INT 引脚在本板上没有接线(见文件顶部: 无空闲中断脚), 所以"使能中断"只影响状态位,
   *   不会真的去打断任何人。 */
  fails += i2c_write_verify(fd, REG_INT_ENABLE1, 0xC0,
                            "INT_ENABLE1", NULL) < 0;   /* A_FULL_EN | PPG_RDY_EN */
  fails += i2c_write_verify(fd, REG_INT_ENABLE2, 0x00,
                            "INT_ENABLE2", NULL) < 0;
  fails += i2c_write_verify(fd, REG_MODE_CONFIG, MODE_HR,
                            "MODE_CONFIG", &rb_mode) < 0;   /* 最后进模式 */

  if (fails > 0)
    {
      printf("hr_sensor: 初始化失败: %d 个关键寄存器写入未生效 (上表逐条列出)。"
             "0x08=%02x 0x0a=%02x 0x0c=%02x 0x09=%02x (期望 0x%02x/0x%02x/0x%02x/0x%02x)"
             " -> 回读值不等于写入值说明 I2C 写路径没生效, 属电气/时序问题"
             "(排针跳线上拉、SCL/SDA 走线、总线争用), 不是配置值写错了\n",
             fails, rb_fifo, rb_spo2, rb_led, rb_mode,
             FIFO_CFG, SPO2_CFG, LED_PA, MODE_HR);
      return -EIO;
    }

  /* 进入 HR 模式后再清一次 FIFO 指针: 模式切换会复位 FIFO 状态机, 不清的话
   * 第一包样本可能带着切换前的残留 (同样只写不校验, 理由见上)。
   * ★ 这仍然是 start 路径内的一次性动作(与上面那次一起), 不是"每轮清"; 采样期清零
   * 只剩两个限流出口: hr_recover 的重同步与 hr_drain 的极端陈旧出口。 */
  i2c_write_reg(fd, REG_FIFO_WR_PTR, 0x00);
  i2c_write_reg(fd, REG_FIFO_OVF, 0x00);
  i2c_write_reg(fd, REG_FIFO_RD_PTR, 0x00);

  i2c_read_reg(fd, REG_INT_STATUS1, &v);                /* 读清状态 */
  i2c_read_reg(fd, REG_INT_STATUS2, &v);

  /* 一行完整状态: 全部取"回读"值, 真机一眼可判断配置是否真的落到器件里 */
  printf("hr_sensor: init OK 0x08=%02x 0x0a=%02x 0x0c=%02x 0x09=%02x "
         "(RED-only, 400Hz/411us, LED=12.6mA, i2c=%dkHz)\n",
         rb_fifo, rb_spo2, rb_led, rb_mode, I2C_FREQ / 1000);
  return 0;
}

/****************************************************************************
 * 心率算法: 去 DC -> 带通 -> 自适应峰值检测 -> RR 中位数
 ****************************************************************************/

static uint32_t now_ms(void);

/* 只复位"滤波器/包络/峰位置"这类与**样本流连续性**绑定的状态。
 * 调用点: 溢出清 FIFO 之后、总线恢复之后(样本流确实断过一次)。
 * **故意不动** rr[]/输出稳定计数: 它们是已经测到的心率结果 —— 真机上"OVF 读数卡死"
 * 时每一轮都走溢出分支, 若连 RR 历史一起清, RR 永远攒不满(HR_RR_HIST_MIN), bpm
 * 就永远出不来(这正是第三次改之前 bpm=0 的第二个直接原因, 第一个是阈值)。
 * warm=0 表示"下一拍用当拍样本给 DC 打初值", 见 hr_feed (1)。 */
static void hr_reset_filters(void)
{
  g_hr.dc  = 0.0f;
  g_hr.lp  = 0.0f;
  g_hr.hp  = 0.0f;
  g_hr.prev = 0.0f;
  g_hr.prev2 = 0.0f;
  g_hr.env = 0.0f;
  g_hr.env_raw = 0.0f;
  g_hr.warm = 0;
  /* 刻意**不清** g_hr.last_peak_ms / peak_ring / rr[] / 稳定计数:
   *   - last_peak_ms 是"上一次心跳的时刻", 不是滤波器状态。保留它, 复位后的第一个峰
   *     就能给出一个真实 RR(期间心脏照跳; 若缺口超过 HR_RR_MAX_MS 会被 RR 合法性滤掉);
   *   - peak_ring 是最近 5s 的**测量**(pk5s 质量门限用), 清掉会让 pk_ok 假性失败几秒;
   *   - rr[]/稳定计数是已经测到的心率结果, 一次 FIFO 断点不该把它们作废。 */
  g_hr.algo_rst++;             /* 诊断: 复位次数(只由 hr_recover 触发, 见那里的 2s 限流) */
  g_hr.algo_rst_ms = now_ms();
}

/* 全量复位(只在 hr_sensor start 时调): 滤波器 + RR 历史 + 输出稳定计数。
 * 与上面的区别就是"要不要把已经测到的心率历史一起作废" —— 新一轮开始才作废。
 * 注意: 两者都**不动** have_bpm/last_bpm(保持"最近一次测到的 bpm"语义)。 */
static void hr_reset_algo(void)
{
  hr_reset_filters();
  g_hr.rr_count = 0;
  g_hr.rr_idx = 0;
  g_hr.rr_med = 0;
  g_hr.reject_cnt = 0;         /* 归并计数: 新一轮从 0 重新计(只做诊断, 不影响控制流) */
  g_hr.cand = 0;
  g_hr.last_cand = 0;
  g_hr.stable_cnt = 0;
  g_hr.last_jump_cand = 0;
  g_hr.jump_cnt = 0;
}

static uint32_t now_ms(void)
{
  struct timespec ts;

  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

/* 两个心率值/候选值是否"足够接近": |a-b| <= max(a,b)*tol%
 * 全整数运算(不引 64 位除法), 值域 40..200 不可能溢出。 */
static bool hr_close(uint32_t a, uint32_t b, uint32_t tol_pct)
{
  uint32_t hi = (a > b) ? a : b;
  uint32_t d  = (a > b) ? (a - b) : (b - a);

  return ((uint64_t)d * 100u <= (uint64_t)hi * (uint64_t)tol_pct);
}

/* bpm 值的"一致"判据(2026-10-13 第五次改新增): |a-b| <= max(HR_BPM_ABS_TOL, 5%)
 * 为什么要有绝对下限: cand = 60000/med 是**整数截断**, 低心率下 5% 只有 ±2bpm,
 *   同一条节律的相邻两次候选(如 106 与 107)会被判"不一致" -> stab 计数被反复打断,
 *   bpm 永远锁不上。3bpm 的绝对下限把 ±1~2 的量化吸收掉, 相对项仍负责高心率侧。
 * 值域 40..200: 3bpm 相对占比 7.5%(@40) ~ 1.5%(@200), 不会把真实心率变化放过去
 *   (真实变化 > 5bpm 才会被 3bpm 挡住, 而那正是 HR_BPM_STABLE_N 连续确认要的)。 */
static bool hr_close_bpm(uint32_t a, uint32_t b)
{
  uint32_t d = (a > b) ? (a - b) : (b - a);
  uint32_t hi = (a > b) ? a : b;

  return (d <= HR_BPM_ABS_TOL) ||
         ((uint64_t)d * 100u <= (uint64_t)hi * (uint64_t)HR_BPM_TOL_PCT);
}

/* (E) 输出限速(2026-10-13 第五次改): 每次更新最多变 HR_BPM_SLEW(3)bpm。
 * 依据: 门限+连续确认已经把"单次异常峰"挡在外面, 但 RR 中位数仍可能因为一个漏检/
 *   多检而在**两次更新之间**整体平移(例如 12 点窗口里 6 个 561 + 6 个 422 的中位数
 *   会翻到 422) —— 那一下就是 30bpm 的跳变。限速把这类残余跳变摊成"每 2s 走 3bpm"
 *   的爬升: 真心率在 3s 内变化不会超过 3bpm(静息), 所以正常跟踪几乎无感; 而
 *   "锁不住/来回跳"的现象被压成单调的小步走。首次锁定(have_bpm=false)不设限 ——
 *   否则第一次要爬 30 多次才到位。 */
static uint32_t hr_slew_bpm(uint32_t cand)
{
  if (!g_hr.have_bpm || g_hr.last_bpm == 0)
    {
      return cand;
    }

  if (cand > g_hr.last_bpm + HR_BPM_SLEW)
    {
      return g_hr.last_bpm + HR_BPM_SLEW;
    }

  if (cand + HR_BPM_SLEW < g_hr.last_bpm)
    {
      return g_hr.last_bpm - HR_BPM_SLEW;
    }

  return cand;
}

/* 最近 win_ms 毫秒内检出的峰数(读 g_hr.peak_ring)。
 * 用途: 质量门限"最近 5 秒峰数合理" + 诊断打印 pk2s=/pk5s=。 */
static uint32_t hr_peaks_last_ms(uint32_t now, uint32_t win_ms)
{
  uint32_t n = 0;
  int i;

  for (i = 0; i < g_hr.peak_cnt; i++)
    {
      uint32_t idx = (uint32_t)((g_hr.peak_idx + HR_PEAK_RING - 1 - i) % HR_PEAK_RING);

      if ((uint32_t)(now - g_hr.peak_ring[idx]) <= win_ms)
        {
          n++;
        }
    }

  return n;
}

/* 返回 1 表示本次产生了一个新的 bpm(调用方再做 BPM_MIN/MAX 校验与 uORB 发布)。
 *
 * 信号链: 原始 18bit -> 去 DC(一阶高通 0.1Hz) -> 带通 0.5~3.5Hz(两级一阶)
 *         -> 包络自适应阈值(0.6 × 包络) + 不应期 300ms -> RR 中位数
 *         -> 质量门限 + 连续确认 -> bpm
 *
 * 为什么**不**用"1s 滑动均值"去 DC: N 点滑动均值的残余响应在 f = k·fs/N 处有零点,
 *   N=100 @100Hz 的零点正好落在 1/2/3Hz = 60/120/180bpm —— 等于把心率基波一起吃掉,
 *   这是把"去 DC"写成梳状滤波器的经典坑。一阶高通在带内平坦(0.5Hz 处增益 0.98),
 *   代价只有一次乘加。
 * 为什么阈值用"包络"而不是"平均绝对值": 旧代码 thresh = 1.6 × EMA(|y|), 对正弦
 *   EMA(|y|) = 2A/π = 0.637A -> 阈值 = 1.02A, **比信号峰值还高**, 峰值检测几乎不触发。
 */
static int hr_feed(uint32_t raw, uint32_t *bpm_out)
{
  float fs = (g_hr.fs_used >= HR_FS_MIN) ? g_hr.fs_used : HR_FS_NOMINAL;
  float x  = (float)raw;
  float ac;
  float y;
  float thresh;
  bool  first_win;
  int   is_peak = 0;
  int   produced = 0;

  g_hr.fed++;
  g_hr.warm++;

  /* 波形快照(最近 HR_LAST_N 个样本, 读取顺序): 真机判"样本流是否连续/有没有恒定尾" */
  g_hr.last_n[g_hr.last_n_idx] = raw;
  g_hr.last_n_idx = (g_hr.last_n_idx + 1) % HR_LAST_N;
  if (g_hr.last_n_cnt < HR_LAST_N)
    {
      g_hr.last_n_cnt++;
    }

  /* 诊断窗口: 样本间差分(平滑度)与"与上一拍完全相同"的计数 */
  first_win = (g_hr.win_n == 0);
  g_hr.win_n++;
  if (g_hr.have_raw_prev)
    {
      uint32_t d = (raw > g_hr.raw_prev) ? (raw - g_hr.raw_prev)
                                         : (g_hr.raw_prev - raw);

      g_hr.win_dsum += d;
      if (d == 0)
        {
          g_hr.win_dup++;
        }
    }

  g_hr.raw_prev = raw;
  g_hr.have_raw_prev = true;

  /* (1) 去 DC: 一阶高通 f_c = HR_DC_HP_HZ(0.1Hz), 远低于 0.5Hz 带边 -> 带内平坦。
   *     真机红光 DC 有 6.8万~14万, 不先去掉, AC 完全淹在基线里。 */
  if (g_hr.warm == 1)
    {
      g_hr.dc = x;               /* 初值取当拍样本: 免得 dc 从 0 起步产生大幅瞬态 */
    }

  g_hr.dc += (x - g_hr.dc) * (2.0f * HR_PI * HR_DC_HP_HZ / fs);
  ac = x - g_hr.dc;

  /* (2) 带通 0.5~3.5Hz: 两级一阶(低通 3.5Hz 再高通 0.5Hz), 系数 α ≈ 2π·fc/fs
   *     (fc<<fs 时该近似够用: 与精确值 1-exp(-2π·fc/fs) 差约 13%, 只影响时间常数,
   *      不移动通带位置; 换来不用 expf)。 */
  g_hr.lp += (ac - g_hr.lp) * (2.0f * HR_PI * HR_BP_LP_HZ / fs);
  g_hr.hp += (g_hr.lp - g_hr.hp) * (2.0f * HR_PI * HR_BP_HP_HZ / fs);
  y = g_hr.lp - g_hr.hp;

  /* 诊断窗口累计(每 HR_STAT_MS 由 hr_algo_diag() 取走并清零): 只做加减乘, 不打印。 */
  if (first_win)
    {
      g_hr.win_ac_min = g_hr.win_ac_max = ac;
      g_hr.win_bp_min = g_hr.win_bp_max = y;
    }
  else
    {
      if (ac < g_hr.win_ac_min) g_hr.win_ac_min = ac;
      if (ac > g_hr.win_ac_max) g_hr.win_ac_max = ac;
      if (y  < g_hr.win_bp_min) g_hr.win_bp_min = y;
      if (y  > g_hr.win_bp_max) g_hr.win_bp_max = y;
    }

  g_hr.win_ac_sq += ac * ac;
  g_hr.win_bp_sq += y * y;

  /* (3) 自适应阈值 = 0.6 × 包络。包络 = "限速上升 + 半衰期 2s 衰减"的 |y| 上包络:
   *     上升限速(每拍最多补 25%)让单个错位字节/运动尖峰拉不动阈值;
   *     衰减保证心率/幅度真的变了时, 阈值在 2s 量级跟上。 */
  {
    float ay = fabsf(y);
    float aa = fabsf(ac);

    /* ★启动瞬态屏蔽(2026-10-13 第六次改, 真机取证): 复位后 HR_ENV_BLANK_SAMPLES 个
     * 样本内, 包络上升仍限速(0.25), 但下降改用 HR_ENV_FAST_DECAY(半衰期 0.23s)。
     * 于是上电瞬态在 ~1s 内被忘掉, 阈值迅速回到真实信号幅度; 屏蔽窗之后恢复
     * 2s 半衰期的正常跟随(心率/幅度真变了时仍能跟上)。 */
    if (g_hr.warm < HR_ENV_BLANK_SAMPLES)
      {
        if (ay > g_hr.env)
          {
            g_hr.env += (ay - g_hr.env) * HR_ENV_ATTACK;
          }
        else
          {
            g_hr.env *= HR_ENV_FAST_DECAY;
          }

        if (aa > g_hr.env_raw)
          {
            g_hr.env_raw += (aa - g_hr.env_raw) * HR_ENV_ATTACK;
          }
        else
          {
            g_hr.env_raw *= HR_ENV_FAST_DECAY;
          }
      }
    else
      {
        if (ay > g_hr.env)
          {
            g_hr.env += (ay - g_hr.env) * HR_ENV_ATTACK;
          }
        else
          {
            g_hr.env *= g_hr.env_decay;
          }

        /* 同步维护"去 DC 后(带通前)"的包络: 只给 AC/DC 合理性门限用, 见 (5) junk_ok */
        if (aa > g_hr.env_raw)
          {
            g_hr.env_raw += (aa - g_hr.env_raw) * HR_ENV_ATTACK;
          }
        else
          {
            g_hr.env_raw *= g_hr.env_decay;
          }
      }

    thresh = g_hr.env * HR_PEAK_THRESH_K;
    g_hr.last_thr = thresh;
  }

  /* (4) 峰值检测: 局部极大 + 超阈值 + 不应期 HR_PEAK_REFRACT_MS(300ms)。
   *     用 prev(上一拍)当峰: 本拍 y 已经开始下降, 说明峰顶在上一拍 —— 峰时刻取
   *     "当前时刻 - 一个采样周期"(误差 10ms), 换来不需要未来数据、不阻塞采样线程。
   *     warm 门槛: 复位后先喂 HR_WARMUP_SAMPLES 个样本(滤波器/DC/包络建立)再检峰。 */
  /* 诊断(2026-10-13): 把"没有局部极大"与"极大被阈值挡掉"分开计数。
   * 真机"手指按住却 pk5s=0"只有两种可能, 修法完全不同:
   *   nmax=0             -> 带通输出压根没形成峰(滤波/信号问题)
   *   nmax>0 且 nth≈nmax -> 自适应阈值高于真实脉搏峰(该调 K 或让 env 更快衰减)
   * 这两个计数与 warm 无关, 纯观察, 不参与控制流。 */
  if (g_hr.prev > g_hr.prev2 && g_hr.prev >= y)
    {
      g_hr.win_nmax++;
      if (!(g_hr.prev > thresh))
        {
          g_hr.win_nth++;
        }
    }

  if (g_hr.warm >= HR_WARMUP_SAMPLES &&
      g_hr.prev > g_hr.prev2 && g_hr.prev >= y && g_hr.prev > thresh)
    {
      uint32_t t = now_ms();
      uint32_t t_peak = (t > HR_SAMPLE_MS) ? (t - HR_SAMPLE_MS) : t;

      if (g_hr.last_peak_ms == 0 ||
          (uint32_t)(t_peak - g_hr.last_peak_ms) >= HR_PEAK_REFRACT_MS)
        {
          uint32_t rr = (g_hr.last_peak_ms != 0) ?
                        (t_peak - g_hr.last_peak_ms) : 0;

          /* (A) 杂峰归并(2026-10-13 第五次改, 主因 #1): 与上一个**真峰**太近的峰
           *     不是"一次心跳", 而是同一次心跳的第二个检出点(双检)或舒张期切迹。
           *     旧代码在这里做的是"**丢间隔、留峰**": rr < HR_RR_MIN_MS 时只跳过
           *     rr[] 的写入, 却照样把 last_peak_ms 推到杂峰上 —— 于是**互补的那段
           *     长间隔永远补不回来**(杂峰把真峰顶掉了), 窗口里只剩下被切短的 RR,
           *     中位数系统性偏短 -> bpm 偏高。真机 25s 序列就是它的指纹:
           *       真 RR=561ms、杂峰延后 d ms 时, 互补间隔 (561-d) 被丢, 窗口只剩
           *       d: d=422 -> 142bpm, d=441 -> 136, d=492 -> 122, d=517 -> 116,
           *       四个观测值逐位吻合。
           *     现在改成"**丢峰、留间隔**": 不写 rr[], 也**不回退/不推进
           *     last_peak_ms** —— 保持上一个真峰不动, 让**下一拍与上一个真峰**算间隔,
           *     拿回那个完整的 ~561ms。代价是这一拍被合并掉(不产生 RR), 换来的是
           *     不再有"半拍"污染中位数。
           *     注: 真正的双检(间隔落在不应期 300~333ms 之内)在本函数的入口就被
           *     不应期挡掉了, 那条路是"整拍丢弃", 不计入 reject_cnt; reject_cnt 只数
           *     这条"过了不应期但仍短于生理下限"的归并。 */
          if (g_hr.last_peak_ms != 0 && rr < HR_RR_MIN_MS)
            {
              g_hr.reject_cnt++;
            }
          else
            {
              is_peak = 1;

              if (g_hr.last_peak_ms != 0 && rr <= HR_RR_MAX_MS)
                {
                  g_hr.rr[g_hr.rr_idx] = rr;
                  g_hr.rr_idx = (g_hr.rr_idx + 1) % RR_HIST;
                  if (g_hr.rr_count < RR_HIST)
                    {
                      g_hr.rr_count++;
                    }
                }

              g_hr.last_peak_ms = t_peak;
              g_hr.peak_ring[g_hr.peak_idx] = t_peak;
              g_hr.peak_idx = (g_hr.peak_idx + 1) % HR_PEAK_RING;
              if (g_hr.peak_cnt < HR_PEAK_RING)
                {
                  g_hr.peak_cnt++;
                }
            }
        }
    }

  g_hr.prev2 = g_hr.prev;
  g_hr.prev  = y;

  /* (5) RR 中位数 -> 候选 bpm -> 质量门限 -> 连续确认。
   *     "宁可不更新也不输出错值": 幅度 / AC-DC 比 / 5s 峰数 / RR 一致性 四个门限全过,
   *     且候选值连续 HR_BPM_STABLE_N 次一致, 才允许更新 bpm。 */
  if (is_peak && g_hr.rr_count >= HR_RR_HIST_MIN)
    {
      uint32_t tmp[RR_HIST];
      uint32_t med;
      uint32_t cand;
      uint32_t pk5;
      uint32_t agree = 0;
      bool amp_ok;
      bool junk_ok;
      bool pk_ok;
      bool rr_ok;
      bool range_ok;
      int  n = g_hr.rr_count;
      int  i, j;

      memcpy(tmp, g_hr.rr, sizeof(uint32_t) * (size_t)n);

      for (i = 1; i < n; i++)                  /* 插入排序: n<=8, 一次几微秒 */
        {
          uint32_t key = tmp[i];

          for (j = i - 1; j >= 0 && tmp[j] > key; j--)
            {
              tmp[j + 1] = tmp[j];
            }

          tmp[j + 1] = key;
        }

      med = tmp[n / 2];

      for (i = 0; i < n; i++)                  /* 有多少个 RR 与中位数一致(±25%) */
        {
          if (hr_close(g_hr.rr[i], med, HR_RR_AGREE_PCT))
            {
              agree++;
            }
        }

      cand = (med > 0) ? (60000u / med) : 0;
      pk5  = hr_peaks_last_ms(now_ms(), HR_PK_WIN_MS);

      amp_ok   = (g_hr.env >= HR_MIN_AC_AMP);
      /* AC/DC 合理性: 用**去 DC 后(带通前)**的包络算, 不用带通后的 env ——
       * 白噪声/错位样本是宽带信号, 0.5~3.5Hz 带通会把幅度压掉约一个量级, 拿 env 判
       * 会放它过关(host 仿真: 6.8万~14万随机跳的数据会输出 133bpm 的错值)。 */
      junk_ok  = !(g_hr.dc > 1.0f &&
                   (g_hr.env_raw * 200.0f) > (g_hr.dc * (float)HR_MAX_ACDC_PCT));
      pk_ok    = (pk5 >= HR_PK5S_MIN && pk5 <= HR_PK5S_MAX);
      rr_ok    = (med > 0) && ((agree * 2u) >= (uint32_t)n);   /* 至少一半 RR 一致 */
      range_ok = (cand >= HR_BPM_ACCEPT_MIN && cand <= HR_BPM_ACCEPT_MAX);

      g_hr.rr_med = med;
      g_hr.cand   = cand;
      g_hr.q      = (amp_ok ? 40 : 0) + (junk_ok ? 15 : 0) +
                    (pk_ok ? 25 : 0) + (rr_ok ? 20 : 0);
      g_hr.gate   = !amp_ok   ? "amp"   :
                    !junk_ok  ? "junk"  :
                    !pk_ok    ? "pk"    :
                    !rr_ok    ? "rr"    :
                    !range_ok ? "range" : "ok";

      if (amp_ok && junk_ok && pk_ok && rr_ok && range_ok)
        {
          /* 连续确认: 候选与上一个候选接近则计数 +1, 否则从 1 重新计。
           * 单次异常峰(哪怕混进中位数)只会让计数回到 1, 推不动 bpm。 */
          if (g_hr.last_cand != 0 && hr_close_bpm(cand, g_hr.last_cand))
            {
              g_hr.stable_cnt++;
            }
          else
            {
              g_hr.stable_cnt = 1;
            }

          g_hr.last_cand = cand;

          if (g_hr.stable_cnt >= HR_BPM_STABLE_N)
            {
              if (!g_hr.have_bpm || hr_close_bpm(cand, g_hr.last_bpm))
                {
                  /* 首次锁定不设限速, 之后每次最多走 HR_BPM_SLEW bpm(见 hr_slew_bpm) */
                  *bpm_out = hr_slew_bpm(cand);   /* 首次锁定, 或与当前值一致(缓慢跟踪) */
                  produced = 1;
                  g_hr.jump_cnt = 0;
                  g_hr.last_jump_cand = 0;
                }
              else
                {
                  /* 与当前 bpm 差得远: 需要"持续偏离"的独立确认(>= HR_BPM_STABLE_N 次),
                   * 否则保持上次值 —— 这就是"分钟级稳定、不因单次异常峰跳变"的落点;
                   * 心率真实变化会在几次心跳内被连续确认, 然后一次性跟上。 */
                  if (g_hr.last_jump_cand != 0 &&
                      hr_close_bpm(cand, g_hr.last_jump_cand))
                    {
                      g_hr.jump_cnt++;
                    }
                  else
                    {
                      g_hr.jump_cnt = 1;
                    }

                  g_hr.last_jump_cand = cand;

                  if (g_hr.jump_cnt >= HR_BPM_STABLE_N)
                    {
                      *bpm_out = hr_slew_bpm(cand);   /* 限速同上(见 hr_slew_bpm) */
                      produced = 1;
                      g_hr.jump_cnt = 0;
                      g_hr.last_jump_cand = 0;
                    }
                }
            }
        }
    }

  return produced;
}

/* ---- 算法链路诊断(每 HR_STAT_MS 一行, 由 hr_thread 调用) ----
 * 一行回答四个问题:
 *   (a) 有效采样率: fs= 本窗口"入算法样本数/窗口时长", fed= 累计入算法样本数。
 *       fs 明显低于 25Hz 说明样本流是"陈旧尾/重复样本"堆出来的(samples 涨得快也没用);
 *   (b) 信号幅度: dc= 去 DC 后的直流(原始量级), ac_pp/ac_rms= 去 DC 后的峰峰值/均方根,
 *       bp_pp/bp_rms= **带通后**的峰峰值/均方根, acdc= ac_pp/dc 的百分比。
 *       生理 PPG 的 AC/DC 只有 0.1%~2%, 几十个百分点说明拿到的根本不是脉搏波
 *       (字节错位/陈旧样本/运动) —— 这时**不该**去调阈值, 该查读法/电极/接触;
 *   (c) 峰值检出: pk2s/pk5s= 最近 2s/5s 检出的峰数, rr_med= RR 中位数(ms),
 *       rej= 杂峰归并次数(第五次改新增, 累计值): 与上一个真峰间隔 < HR_RR_MIN_MS(333ms)
 *       的峰被**丢掉而不是切短 RR** 的次数。它回答"bpm 偏高是不是杂峰造成的" ——
 *       旧代码正是把这些峰留下、把 RR 切短(真机 422/441/492/517 的来历);
 *       验收: 修好后 rr[] 里不该再出现 <560ms 的静息 RR, 而 rej= 应当在涨;
 *   (d) 质量与输出: q= 置信度 0..100, gate= 最近一次检峰时**第一个不过的门限**
 *       (ok/amp/junk/pk/rr/range; warm= 还在 warm-up), cand= 当前候选 bpm,
 *       stab= 候选连续一致的次数(>= HR_BPM_STABLE_N 才允许更新 bpm)。
 * 另有三个"数据是不是读坏了"的现场量:
 *   dup=  与上一个样本**完全相同**的比例(陈旧/重复样本; 真波形在 100sps 下应接近 0);
 *   fresh= 最近一轮"与上一轮最后一个样本不同"的样本数/本轮样本数(与 stat 行同源, 第四次改
 *         新增) —— dup= 是窗口级的陈旧比例, fresh= 是轮级的"新鲜度", 两者一起看:
 *         修好前 dup=97~100% 且 fresh=0/N; 修好后 dup≈0 且 fresh≈N;
 *   d1=   相邻样本 |Δ| 的均值(平滑度: 真 PPG 只有个位数~几十 counts, 字节错位的
 *         假波形会到上千 —— 与 bp_pp 一起看, 一眼分清"小信号"和"假信号");
 *   byeq= b1==b2 的比例 / b0==b1==b2 的比例(逐字节读 FIFO 的**错位指纹**: 随机波形下
 *         前者约 1/256=0.4%, 后者约 1/65536; 明显偏高 = 三次读回的是同一个字节或
 *         同一个字节位置, 18bit 样本是拼出来的假值 -> 该翻 HR_FIFO_BYTE_WISE 了);
 *   rst=  滤波器复位次数(只由 hr_recover 触发, 受 HR_RECOVER_MIN_MS=2s 限流;
 *         rst 每 2s 涨 1 = 总线恢复在反复跑 —— 恢复本身会清 FIFO/重配器件, 心率出值
 *         会被拖慢, 这时先看 rec=), ovfs= 当前连续溢出轮数(真机 OVF 读数卡死的现场)。
 * 末尾 last10= 最近 10 个原始值(读取顺序, 约 1 轮 @16 样本/轮; 第四次改前是 2 轮 @5): 样本流是否连续、是否
 *   "每轮的固定槽都是同一个常数"一眼可见 —— 同时它也就是 stat 行 red= 的来源
 *   (red= 打印的 g_hr.raw_last 在 hr_drain 里逐样本更新, 见那里的注释)。
 * 打印频率与 stat 相同; 采样热路径里只累计, 不打印。 */
static void hr_algo_diag(void)
{
  uint32_t now = now_ms();
  uint32_t el  = now - g_hr.win_start_ms;
  uint32_t n   = g_hr.win_n;
  uint32_t pk5 = hr_peaks_last_ms(now, HR_PK_WIN_MS);
  uint32_t pk2 = hr_peaks_last_ms(now, HR_STAT_MS);
  float    fs_meas = 0.0f;
  float    ac_pp = 0.0f;
  float    ac_rms = 0.0f;
  float    bp_pp = 0.0f;
  float    bp_rms = 0.0f;
  float    d1 = 0.0f;
  float    acdc = 0.0f;
  unsigned dup_pct = 0;
  unsigned b12_pct = 0;
  unsigned b012_pct = 0;
  const char *gate;
  int      i;

  if (n > 0)
    {
      fs_meas = (float)n * 1000.0f / (float)((el > 0) ? el : 1);

      if (fs_meas >= HR_FS_MIN && fs_meas <= 200.0f)
        {
          g_hr.fs_used = fs_meas;
          /* 包络衰减系数跟着实测 fs 走(半衰期 HR_ENV_HALFLIFE_MS): 一阶近似
           * 1 - ln2*1000/(T_half*fs) —— 100Hz 时 0.99653, 与精确值 0.99654 差 1e-5 */
          g_hr.env_decay = 1.0f - 0.6931472f * 1000.0f /
                                 (HR_ENV_HALFLIFE_MS * fs_meas);
        }

      ac_pp  = g_hr.win_ac_max - g_hr.win_ac_min;
      bp_pp  = g_hr.win_bp_max - g_hr.win_bp_min;
      ac_rms = sqrtf(g_hr.win_ac_sq / (float)n);
      bp_rms = sqrtf(g_hr.win_bp_sq / (float)n);
      d1     = (float)g_hr.win_dsum / (float)n;

      dup_pct  = (unsigned)((uint64_t)g_hr.win_dup   * 100u / n);
      b12_pct  = (unsigned)((uint64_t)g_hr.win_b12eq * 100u / n);
      b012_pct = (unsigned)((uint64_t)g_hr.win_b012eq * 100u / n);

      if (g_hr.dc > 1.0f)
        {
          acdc = ac_pp * 100.0f / g_hr.dc;
        }
    }

  gate = (g_hr.warm < HR_WARMUP_SAMPLES) ? "warm" : g_hr.gate;
  if (gate == NULL)
    {
      gate = "warm";
    }

  printf("hr_sensor: algo fs=%.1fHz fed=%u rst=%u ovfs=%u dc=%.0f ac_pp=%.0f "
         "ac_rms=%.0f bp_pp=%.0f bp_rms=%.0f acdc=%.2f%% d1=%.1f dup=%u%% "
         "fresh=%u/%u byeq=%u%%/%u%% pk2s=%u pk5s=%u rr_med=%ums rej=%u q=%d "
         "warm=%u env=%.0f thr=%.0f nmax=%u nth=%u "
         "gate=%s bpm=%u cand=%u stab=%u last%u=",
         (double)fs_meas, (unsigned)g_hr.fed, (unsigned)g_hr.algo_rst,
         (unsigned)g_hr.ovf_streak, (double)g_hr.dc, (double)ac_pp, (double)ac_rms,
         (double)bp_pp, (double)bp_rms, (double)acdc, (double)d1, dup_pct,
         (unsigned)g_hr.round_fresh, (unsigned)g_hr.round_n,
         b12_pct, b012_pct, (unsigned)pk2, (unsigned)pk5, (unsigned)g_hr.rr_med,
         (unsigned)g_hr.reject_cnt, g_hr.q,
         (unsigned)g_hr.warm, (double)g_hr.env, (double)g_hr.last_thr,
         (unsigned)g_hr.win_nmax, (unsigned)g_hr.win_nth,
         gate, (unsigned)g_hr.last_bpm,
         (unsigned)g_hr.cand,
         (unsigned)g_hr.stable_cnt, (unsigned)HR_LAST_N);

  for (i = 0; i < g_hr.last_n_cnt; i++)
    {
      /* last_n_idx 在 [0,HR_LAST_N), last_n_cnt <= HR_LAST_N -> idx >= -HR_LAST_N,
       * 所以下面一次 +HR_LAST_N 就够(不是循环) */
      int idx = g_hr.last_n_idx - g_hr.last_n_cnt + i;

      if (idx < 0)
        {
          idx += HR_LAST_N;
        }

      printf("%u%s", (unsigned)g_hr.last_n[idx],
             (i + 1 < g_hr.last_n_cnt) ? "," : "");
    }

  printf("\n");

  /* 取走窗口: 下一次打印从"空窗口"重新累计 */
  g_hr.win_n = 0;
  g_hr.win_dup = 0;
  g_hr.win_dsum = 0;
  g_hr.win_b12eq = 0;
  g_hr.win_b012eq = 0;
  g_hr.win_ac_sq = 0.0f;
  g_hr.win_bp_sq = 0.0f;
  g_hr.win_ac_min = g_hr.win_ac_max = 0.0f;
  g_hr.win_bp_min = g_hr.win_bp_max = 0.0f;
  g_hr.win_start_ms = now;
}

/* 算法诊断窗口复位(hr_start 调; 与 stat/diag 计数的清零风格一致)。
 * 顺序说明: 先 hr_reset_algo()(内部会 ++algo_rst), 再本函数把 algo_rst 归零 ——
 * 于是 stat/algo 行里的 rst= 就是"本轮运行中算法被复位了几次", 一眼看出"每轮都涨"。 */
static void hr_algo_diag_reset(void)
{
  g_hr.fed = 0;
  g_hr.win_n = 0;
  g_hr.win_dup = 0;
  g_hr.win_dsum = 0;
  g_hr.win_b12eq = 0;
  g_hr.win_b012eq = 0;
  g_hr.win_ac_sq = 0.0f;
  g_hr.win_bp_sq = 0.0f;
  g_hr.win_ac_min = g_hr.win_ac_max = 0.0f;
  g_hr.win_bp_min = g_hr.win_bp_max = 0.0f;
  g_hr.win_start_ms = now_ms();
  g_hr.last_n_idx = 0;
  g_hr.last_n_cnt = 0;
  g_hr.raw_prev = 0;
  g_hr.have_raw_prev = false;
  g_hr.q = 0;
  g_hr.win_nmax = 0;
  g_hr.win_nth = 0;
  g_hr.gate = "warm";
  g_hr.algo_rst = 0;
  g_hr.algo_rst_ms = 0;
  g_hr.fs_used = HR_FS_NOMINAL;
  g_hr.env_decay = 1.0f - 0.6931472f * 1000.0f /
                         (HR_ENV_HALFLIFE_MS * HR_FS_NOMINAL);
  memset(g_hr.last_n, 0, sizeof(g_hr.last_n));
}

/****************************************************************************
 * 诊断打印 (stat 行 + FIFO 异常诊断): 让真机串口一眼分清
 *   ① FIFO 没数据        -> stat 行 samples/fifo_rd 长期为 0, 且有一条 diag 原因
 *   ② 有数据但算法没出 bpm -> samples/fifo_rd 在涨、red/min/max 在变, 但 bpm=0 hb=0
 *   ③ 读到的根本不是波形   -> diag 直接点出"整包全 0"或"恒为常数"
 * 限流: stat 固定 HR_STAT_MS 一条; 每种 diag 原因首次立即打印, 之后每 HR_DIAG_EVERY 次
 *       补一条 —— 采样循环里绝不逐拍 printf。
 ****************************************************************************/

/****************************************************************************
 * 逐操作记账 (采样热路径): 每种 ioctl 步骤的 尝试/失败 次数 + 限流日志 + stat 标记
 *
 * 为什么要有它: 旧代码在采样循环里只看"整轮成不成功", 出问题时只能看到
 *   stat 行 samples=0 / wr=?? rd=?? —— 分不清是"哪一步先失败"还是"根本没失败"。
 * 这里把一轮里的每个 ioctl 都记一笔, 于是真机上可以一眼看出:
 *   (1) 每轮第一个失败的操作(stat: firstfail=) → 就是"总线是怎么坏的"的入口操作;
 *   (2) 错误码(stat 第二行与限流日志里的 errno) → EIO/ENOTTY/ETIMEDOUT/EREMOTEIO 分得清;
 *   (3) 若 firstfail=none 而 samples 仍不涨 → 没有任何 ioctl 失败, 故障在器件状态
 *       或整轮控制流上(旧代码的"溢出分支提前 return"就是这一类)。
 * 打印策略(硬约束: 采样热路径绝不逐拍 printf): 每种操作**首次**失败立即打印一条,
 *   之后每 HR_OP_LOG_EVERY 次失败补一条; 成功不打印。
 ****************************************************************************/

#define HR_OP_LOG_EVERY  256    /* 每种操作: 首次失败立即打印, 之后每这么多次失败补一条 */

/* 操作的短标记: stat 行的 lastfail=/firstfail= 与限流日志共用同一套名字,
 * 现场对着串口念就能对上(不要改成中文, 免得每次都要翻译一遍)。 */
static const char *hr_op_name(uint8_t op)
{
  switch (op)
    {
      case HR_OP_RD_INT:  return "rd_int";
      case HR_OP_RD_OVF:  return "rd_ovf";
      case HR_OP_WR_CLR:  return "wr_clr";
      case HR_OP_RD_PTR:  return "rd_ptr";
      case HR_OP_RD_FIFO: return "rd_fifo";
      default:            return "none";
    }
}

/* 记一次操作结果。ret >= 0 = 成功(只计数, 不打印); ret < 0 = 失败:
 *   累计到该操作的 fails, 记下寄存器/子步/errno, 更新 lastfail/firstfail 两个标记,
 *   并按 HR_OP_LOG_EVERY 限流打印(含 errno 与 strerror, 真机可分辨错误码)。
 * 只由采样线程调用(与 hr_drain 同一上下文), 无并发写。 */
static void hr_op_result(uint8_t op, uint8_t reg, uint8_t sub_step, int ret)
{
  struct hr_op_stat_s *s = &g_hr.op[op];
  char sub[16];

  s->n++;
  if (ret >= 0)
    {
      return;
    }

  s->fails++;
  s->last_reg   = reg;
  s->last_sub   = sub_step;
  s->last_errno = errno;
  g_hr.last_fail_op = op;
  if (g_hr.round_fail_first == HR_OP_NONE)
    {
      g_hr.round_fail_first = op;   /* 本轮第一个失败的操作: 每轮只记第一处 */
    }

  if (s->fails == 1 || (s->fails % HR_OP_LOG_EVERY) == 0)
    {
      /* 子步只对逐字节样本读(HR_FIFO_BYTE_WISE=1)有意义: 它标出失败的是样本的
       * 第几个字节 —— 第 2/3 字节失败说明一个样本读到一半被打断(有错位风险)。 */
      if (sub_step != 0)
        {
          snprintf(sub, sizeof(sub), " sub=%u", (unsigned)sub_step);
        }
      else
        {
          sub[0] = 0;              /* 空串: 子步不适用时不打这个字段 */
        }

      printf("hr_sensor: op=%s reg=0x%02x%s ioctl=%d errno=%d(%s)"
             " —— 第 %u 次失败/%u 次尝试\n",
             hr_op_name(op), reg, sub, ret, errno, strerror(errno),
             (unsigned)s->fails, (unsigned)s->n);
    }
}

/* 打印一个寄存器的现场回读值。直接调 i2c_read_regs_split() = **路径B**(写地址+STOP
 * 再单独读), 不经过 i2c_read_regs() 分发器: 诊断值本身必须可信, 不能随主读路径开关
 * HR_USE_SPLIT_READ 漂移(本板路径A 读任何寄存器都返回常数, 结论不可信)。 */
static void hr_diag_reg(uint8_t reg, const char *name)
{
  uint8_t v = 0;
  int ret = i2c_read_regs_split(g_hr.fd, reg, &v, 1);

  if (ret < 0)
    {
      printf(" 0x%02x(%s)=读失败(errno=%d)", reg, name, errno);
    }
  else
    {
      printf(" 0x%02x(%s)=0x%02x", reg, name, v);
    }
}

/* FIFO 异常诊断(限流): *cnt 为该原因累计出现次数, 第 1 次与每 HR_DIAG_EVERY 次各打一条。
 * why 是"原因"文字, 后面紧接着用路径B 现场回读 0x04/0x05/0x06 —— 指针是否在动、
 * 是否溢出, 一眼可判。 */
static void hr_diag_fifo(uint32_t *cnt, const char *why)
{
  (*cnt)++;
  if (*cnt != 1 && (*cnt % HR_DIAG_EVERY) != 0)
    {
      return;
    }

  printf("hr_sensor: FIFO 诊断 #%u: %s\n", (unsigned)*cnt, why);
  printf("hr_sensor:   路径B 现场回读:");
  hr_diag_reg(REG_FIFO_WR_PTR, "WR_PTR");
  hr_diag_reg(REG_FIFO_OVF,    "OVF");
  hr_diag_reg(REG_FIFO_RD_PTR, "RD_PTR");
  printf("\n");
}

/* stat 行的一句话结论(三段式, 与真机排查的三种情况一一对应) */
static const char *hr_stat_state(void)
{
  if (g_hr.have_bpm)
    {
      return "bpm-ok";
    }

  return (g_hr.samples > 0) ? "data-no-bpm" : "no-fifo-data";
}

/* 打印一种操作的"尝试/失败"计数; 失败过就带上**最后失败的寄存器/子步/errno**,
 * 例: wr_clr=1200/1200(0x04,errno=5) —— 一眼看出是哪一步、错在哪个寄存器、什么码。 */
static void hr_print_op(const char *name, uint8_t op)
{
  const struct hr_op_stat_s *s = &g_hr.op[op];

  if (s->fails == 0)
    {
      printf(" %s=%u/0", name, (unsigned)s->n);
    }
  else if (s->last_sub != 0)
    {
      printf(" %s=%u/%u(0x%02x/b%u,errno=%d)", name, (unsigned)s->n,
             (unsigned)s->fails, s->last_reg, (unsigned)s->last_sub,
             s->last_errno);
    }
  else
    {
      printf(" %s=%u/%u(0x%02x,errno=%d)", name, (unsigned)s->n,
             (unsigned)s->fails, s->last_reg, s->last_errno);
    }
}

/* 周期性 stat 行: 固定每 HR_STAT_MS(2s) 一条, 与采样循环解耦。
 * 第一行字段(按排查顺序):
 *   samples  累计有效样本数(算法真正吃到的)
 *   n_poll   本轮实际读到的样本数/本轮**上限**(= HR_FIFO_SAMPLES_PER_POLL, 默认 16)。
 *            第四次改后默认 HR_FIFO_DRAIN_ALL=1(读到 FIFO 空为止), 所以
 *            "n_poll < 上限且 fresh == n_poll"是**正常**的(有多少新鲜样本取多少);
 *            分子 = 0 才是"这一轮没有数据"(门控/器件), 分子 < HR_FIFO_MIN_SAMPLES(5)
 *            才说明产出不足或中途读失败被 break。
 *   fresh    本轮 n_poll 个样本里与**上一轮最后一个样本**不同的个数 (fresh=x/N) ——
 *            第四次改新增的"不再重复"直接证据, 也是本轮验收的核心:
 *              修好前: fresh=0/N(或个位数), 配合 dup=97~100%/acdc=73% 就是重复样本;
 *              修好后: fresh == N(接近上限), 波形是逐拍变化的真实样本。
 *   rdup     本轮样本里与**流中前一拍**相同(含跨轮边界)的个数 —— fresh 的补充, 把
 *            "轮内也在重复"照出来; 稳态 0, 恒等于 N 说明读侧又卡在同一个槽了。
 *   stclr    极端陈旧出口(连续 HR_STALE_CLEAR_ROUNDS 轮 fresh=0 才清一次指针)的触发
 *            次数 —— 稳态应恒为 0; 它涨 = 读侧重同步发生过(配合 fresh= 看有没有救回来)。
 *   fifo_rd  与 n_poll 同源(最近一次实际读到的样本数, 括号=字节数), 保留给老脚本/习惯
 *   ovf      累计 FIFO 溢出次数; 一直涨=我们没跟上产出速率(或 OVF 读数是假的)
 *   wr/rd    0x04/0x06 指针(?? = 这轮没读到) —— **现在只是诊断值, 不参与控制流**。
 *            本板已知这两个数不可信/不前进(wr=1 rd=0 恒定), 留着是为了和 int1/int2 对照:
 *            如果 wr/rd 不动、red 却在逐轮变化, 就证实"指针不可信但样本是真的"。
 *   int1/int2 本轮读到的 INT_STATUS1(0x00)/INT_STATUS2(0x01) 原始值(?? = 没读到)。
 *            门控判据就是"任一非 0"; int1 的 bit6 = PPG_RDY 是数据手册定义的"新样本就绪"
 *            (逐位定义与来源见 REG_INT_STATUS1 上方的注释块)。真机验证位定义就看这一列:
 *            有波形时 int1 应频繁带 0x40(或至少非 0)。
 *   red      最近一个 RED 原始值 = g_hr.raw_last(18bit), 在 hr_drain 的逐样本循环里
 *            **每解析出一个样本就覆盖一次** —— 所以它确实就是"最近一次成功读到的样本值"
 *            (已核对: 不是固定槽、不是未更新字段)。ir=n/a 是因为 0x09=0x02 为 RED-only。
 *            **真机验收的核心**: 它必须逐轮变化; 若它恒为同一个数而 min/max 在动, 那是
 *            "每轮读到的最后一个槽确实是那个常数"(FIFO 读法/清零点位的后果), 不是打印
 *            字段的问题 —— 用 algo 行的 last10=/dup= 可直接看出有没有恒定尾。
 *   min/max  本次运行的原始值范围(有真实波形则两端会明显分开)
 *   bpm/hb   算法最近一次 bpm / 是否已产出过 bpm
 *   rec      累计总线恢复次数(见 hr_recover()); 一直涨=总线反复挂, 停在 0=没触发过
 *   lastfail 最近一次失败的操作(rd_int/rd_ovf/wr_clr/rd_ptr/rd_fifo; none=从没失败过)
 *   firstfail 最近一轮里**第一个**失败的操作 —— 这就是"总线是被哪一步搞坏的"的入口:
 *            例如 firstfail=rd_int 表示这一轮连 0x00 都没读回来;
 *            firstfail=wr_clr 表示该轮 0x00/0x05 读成功、紧接着的清零写失败;
 *            而 firstfail=none 却 samples 不涨, 说明没有任何 ioctl 失败, 故障在
 *            器件状态或整轮控制流上(不是总线错误), 两种情况的修法完全不同。
 *   state    no-fifo-data(FIFO 没数据) | data-no-bpm(有数据但算法没检出) | bpm-ok
 * 第二行(hr_print_op): 五种操作各自的 尝试/失败(最后失败寄存器,errno) —— 不用翻
 *   限流日志就能看出是哪一步在失败、错误码是什么(EIO/ENOTTY/ETIMEDOUT/EREMOTEIO)。 */
static void hr_print_stat(void)
{
  char ptr[32];
  char ints[40];

  if (g_hr.fifo_wr >= 0 && g_hr.fifo_rd >= 0)
    {
      snprintf(ptr, sizeof(ptr), "wr=%d rd=%d", g_hr.fifo_wr, g_hr.fifo_rd);
    }
  else
    {
      snprintf(ptr, sizeof(ptr), "wr=?? rd=??");
    }

  /* 状态位: 读到就打十六进制原值, 没读到(或读失败)打 ?? —— 门控判据的现场证据 */
  if (g_hr.int1 < 0)
    {
      snprintf(ints, sizeof(ints), "int1=?? int2=??");
    }
  else if (g_hr.int2 < 0)
    {
      snprintf(ints, sizeof(ints), "int1=0x%02x int2=??", g_hr.int1);
    }
  else
    {
      snprintf(ints, sizeof(ints), "int1=0x%02x int2=0x%02x", g_hr.int1, g_hr.int2);
    }

  printf("hr_sensor: stat samples=%u n_poll=%u/%u fresh=%u/%u rdup=%u stclr=%u "
         "fifo_rd=%u(%uB) ovf=%u %s %s "
         "red=%u ir=n/a(RED-only) min=%u max=%u bpm=%u hb=%d rec=%u "
         "lastfail=%s firstfail=%s state=%s\n",
         (unsigned)g_hr.samples,
         (unsigned)g_hr.fifo_last, (unsigned)HR_FIFO_SAMPLES_PER_POLL,
         (unsigned)g_hr.round_fresh, (unsigned)g_hr.round_n,
         (unsigned)g_hr.round_dup, (unsigned)g_hr.stale_clear,
         (unsigned)g_hr.fifo_last,
         (unsigned)(g_hr.fifo_last * 3), (unsigned)g_hr.overflows, ptr, ints,
         (unsigned)g_hr.raw_last, (unsigned)g_hr.raw_min,
         (unsigned)g_hr.raw_max, (unsigned)g_hr.last_bpm,
         g_hr.have_bpm ? 1 : 0, (unsigned)g_hr.recover_count,
         hr_op_name(g_hr.last_fail_op), hr_op_name(g_hr.round_fail_first),
         hr_stat_state());

  printf("hr_sensor: ops(尝试/失败)");
  hr_print_op("rd_int",  HR_OP_RD_INT);
  hr_print_op("rd_ovf",  HR_OP_RD_OVF);
  hr_print_op("wr_clr",  HR_OP_WR_CLR);
  hr_print_op("rd_ptr",  HR_OP_RD_PTR);
  hr_print_op("rd_fifo", HR_OP_RD_FIFO);
  printf("\n");
}

/****************************************************************************
 * 故障恢复 (读/写失败 -> 限流 -> I2CIOC_RESET / close+open / 探活后重配)
 ****************************************************************************/

/* 算一轮采样开始: 清"本轮已记过失败"标志。语义上"一轮采样 = 一次失败机会",
 * 否则一轮里 0x05+0x04+0x06 三条读失败会被算成 3 轮, 把 HR_RECOVER_FAILS 的意思搅乱。 */
static void hr_round_begin(void)
{
  g_hr.fail_counted = false;
  g_hr.round_fail_first = HR_OP_NONE;   /* 逐操作: 重新记"本轮第一个失败的操作" */
}

/* 一轮采样里读/写成功时调这里: 打断"连续失败"。
 * 本轮只要已经记过失败(fail_counted)就不清 —— 否则"先成功一条、再失败一条"的轮次
 * 会把 fail_streak 反复清零, HR_RECOVER_FAILS 永远到不了, 恢复就成了死代码。 */
static void hr_ok(void)
{
  if (g_hr.fail_counted)
    {
      return;
    }

  g_hr.fail_streak = 0;
}

static void hr_recover(void);

/* 一轮采样里任何读/写失败都调这里(一轮内只记一次), 顺带跑限流后的恢复判定。 */
static void hr_fail(void)
{
  uint32_t now;

  if (g_hr.fail_counted)
    {
      return;
    }

  g_hr.fail_counted = true;
  g_hr.fails++;
  g_hr.fail_streak++;

  if (g_hr.fail_streak < HR_RECOVER_FAILS)
    {
      return;
    }

  now = now_ms();
  if (g_hr.recover_count > 0 &&
      (now - g_hr.last_recover_ms) < HR_RECOVER_MIN_MS)
    {
      return;                     /* 时间限流: 恢复动作本身可能很贵 */
    }

  hr_recover();
}

/* 总线/器件恢复。**顺序与边界都是刻意的**:
 *   (1) 先试下层驱动给的 I2CIOC_RESET;
 *   (2) 再 close+open 重开设备 fd (软恢复);
 *   (3) 只发**一次**单字节读探活 —— 失败立刻收手, 不再往下发配置写。
 *
 * 为什么 (3) 必须在前: 总线卡着时, 下层每一条 message 都会被拖满
 *   I2C_XFER_TIMEOUT = 5000ms (vendor/sifli/chips/sf32lb52/sifli_i2c.c:41,
 *   等待处 :536 nxsem_tickwait_uninterruptible(MSEC2TICK(I2C_XFER_TIMEOUT)))。
 *   若不加探活就重配 6 个寄存器, 一次恢复最坏会堆到 30s+。加了探活, 最坏只有
 *   这 1 次读会被拖住 —— 而"已经在飞的那一次传输"应用层拦不住
 *   (同一结论见本文件 selftest 的 ST_BUDGET_MS 注释)。app 侧自己**不 sleep**,
 *   恢复路径固定 <= 1 次探活 + 9 条短写 + 2 条短读。
 *
 * I2CIOC_RESET 的可达性 (2026-10 更新): 它**曾经是死的** ——
 *   - nuttx/include/nuttx/i2c/i2c_master.h:123-130: I2CIOC_RESET, Argument: None,
 *     Dependencies: CONFIG_I2C_DRIVER && CONFIG_I2C_RESET;
 *   - nuttx/drivers/i2c/i2c_driver.c:284-301: 该 case 在 #ifdef CONFIG_I2C_RESET 内,
 *     没开就落到 default: -ENOTTY;
 *   - nuttx/drivers/i2c/Kconfig:62-65: config I2C_RESET 依赖 ARCH_HAVE_I2CRESET;
 *   - nuttx/arch/arm/src/chip/Kconfig:14-36: config ARCH_CHIP_SF32LB52 当时只 select 了
 *     FPU/MPU/FETCHADD/HEAPCHECK/PROGMEM/SPI_BITORDER, **没有** ARCH_HAVE_I2CRESET
 *     -> CONFIG_I2C_RESET 选不上, 出固件的 .config 里也确实没有它。
 * 现已补齐(git log 里 i2c: 开头那条, 两处):
 *   - chips/sf32lb52/Kconfig 的 ARCH_CHIP_SF32LB52 下补 select ARCH_HAVE_I2CRESET;
 *   - 板级 defconfig 补 CONFIG_I2C_RESET=y -> 本函数现在真能下发到 HAL_I2C_Reset()。
 * 下层实现(读源码后的如实描述): vendor/sifli/chips/sf32lb52/sifli_i2c.c:583-593
 *   sifli_i2c_reset() 持锁调 HAL_I2C_Reset() ->
 *   vendor/sifli/chips/drivers/hal/bf0_hal_i2c.c:4287-4317, 依次做三件事:
 *   (a) 把 LCR.SLV 写满(0x1FF) —— HAL 注释说标准模式下发总线复位要用最低频率, 并规避
 *       A0 LCPU ROM 把 SLV 清 0 的 bug(该字段的准确定义树内没有寄存器手册, 按注释转述);
 *   (b) 脉冲 CR.UR(bit31) 两次, 每次保持 >=1us(HAL 在该处的注释是 Delay at least
 *       9 cycle);
 *   (c) 中间置 CR.RSTREQ(bit30) 并自旋等硬件自清(cmsis/sf32lb52x/i2c.h:68-70)
 *       —— 这一步才是所谓的总线复位。
 * 与 NuttX 参考实现的差别(不要夸大): stm32h7_i2c.c:2501-2625 / am335x_i2c.c:1447 /
 *   rp2040_i2c.c:623 / kinetis_i2c.c:1172 那一套是"把 SCL/SDA 改成 GPIO, 手动敲 SDA
 *   高 + 最多 10 组 SCL 低/高, 再补 START+STOP"的位翻转恢复; SF32 走的是控制器硬件
 *   复位: RSTREQ 由 IP 自己按 I2C 规范发总线复位(树内无手册逐位确认)。对"从机把 SDA
 *   拉低"这类死锁, 只要 RSTREQ 真发出 9 个时钟就能解开; 若它只复位控制器内部状态,
 *   线仍被从机 hold 住 —— 本改动是"把下层已经写好的能力接上", 不保证痊愈。
 * 真机判据: stat 行 samples 是否增长、rec= 是否触发。 */
static void hr_recover(void)
{
  int ret;
  uint8_t id = 0;
  uint8_t st = 0;

  g_hr.last_recover_ms = now_ms();
  g_hr.recover_count++;

  /* (1) 下层驱动提供的 ioctl(CONFIG_I2C_RESET=y 后已可达; 见上面注释) */
  errno = 0;
  ret = ioctl(g_hr.fd, I2CIOC_RESET);
  g_hr.reset_ioctl_ret = (ret < 0) ? errno : 0;

  g_hr.diag_rec++;
  if (g_hr.diag_rec == 1 || (g_hr.diag_rec % HR_DIAG_EVERY) == 0)
    {
      printf("hr_sensor: 总线恢复 #%u (连续失败 %u 轮): I2CIOC_RESET -> %s",
             (unsigned)g_hr.recover_count, (unsigned)g_hr.fail_streak,
             (ret < 0) ? strerror(g_hr.reset_ioctl_ret) : "OK");
      if (ret < 0)
        {
          printf(" (本板 CONFIG_I2C_RESET 未开/不可选, 见 hr_recover 注释)");
        }

      printf("\n");
    }

  /* (2) 软恢复: 重开设备。代价与局限 —— 这一步只复位"字符设备/驱动侧"的软件状态,
   * 并把后续读的失败从"卡 5s 超时"变成"立即 -EBADF"; 它**不会**把被从机拉低的 SDA
   * 放掉, 真正的 9 时钟总线恢复要靠 (1) 的 ioctl(内核侧)或断电重上电。 */
  if (g_hr.fd >= 0)
    {
      close(g_hr.fd);
      g_hr.fd = -1;
    }

  g_hr.fd = open(I2C_DEV_PATH, O_RDWR);
  if (g_hr.fd < 0)
    {
      return;                     /* 设备都开不回来: hr_drain 会直接跳过, 不再碰总线 */
    }

  /* 引脚复用重新确认一遍(两次寄存器写, 代价 ~0): 恢复动作若要改成 GPIO 位翻转,
   * 这里就是"把引脚交还给 I2C2"的落点; 现在它只是幂等兜底。 */
  max30102_pinmux();

  /* (3) 探活: 只发这一条。单字节读是本板唯一 100% 验证过的长度
   * (见文件顶部 HR_USE_SPLIT_READ / selftest 的 A/B 对照)。 */
  if (i2c_read_reg(g_hr.fd, PART_ID_REG, &id) < 0 || id != PART_ID_VAL)
    {
      return;                     /* 总线还没活: 交回限流器, 本轮不再发任何 ioctl */
    }

  /* (4) 活了: 重配 6 个关键寄存器 + 清 FIFO 指针/溢出/中断状态。
   * 刻意**不**调 max30102_init(): 它的 PART_ID 最多 50 次重试(每次 sleep 20ms)和
   * max30102_reset() 的 200ms 轮询, 都远超"恢复不得阻塞 200ms"的预算; 这里只发一次
   * 不校验的写(总线上活了之后每条 ~1ms)。配置值与原 init 完全一致, 参数不受影响。 */
  i2c_write_reg(g_hr.fd, REG_FIFO_CONFIG, FIFO_CFG);
  i2c_write_reg(g_hr.fd, REG_SPO2_CONFIG, SPO2_CFG);
  i2c_write_reg(g_hr.fd, REG_LED1_PA, LED_PA);
  i2c_write_reg(g_hr.fd, REG_INT_ENABLE1, 0xC0);   /* 同 init: A_FULL_EN|PPG_RDY_EN */
  i2c_write_reg(g_hr.fd, REG_INT_ENABLE2, 0x00);
  i2c_write_reg(g_hr.fd, REG_MODE_CONFIG, MODE_HR);
  /* ★ 采样期仅有的两个"写 0x04/0x06"出口之一(另一个是 hr_drain 的极端陈旧出口):
   * 这里是总线恢复后的彻底重同步, 且 hr_recover 自带 >=HR_RECOVER_MIN_MS=2s 限流;
   * 每次采样轮(hr_drain)里**没有**任何清指针动作 —— 见文件顶部"FIFO 读法"。 */
  i2c_write_reg(g_hr.fd, REG_FIFO_WR_PTR, 0x00);
  i2c_write_reg(g_hr.fd, REG_FIFO_OVF, 0x00);
  i2c_write_reg(g_hr.fd, REG_FIFO_RD_PTR, 0x00);
  i2c_read_reg(g_hr.fd, REG_INT_STATUS1, &st);   /* 读清状态 */
  i2c_read_reg(g_hr.fd, REG_INT_STATUS2, &st);

  /* 样本流断过一次, 滤波器状态已失效 -> 复位滤波器(不动 have_bpm/last_bpm,
   * 也不动作废 RR 历史 —— 一次总线恢复不代表"刚测到的心率"失效; 真机上若 rec= 每 2s
   * 涨一次, 全量复位会让 RR 永远攒不满, 与溢出分支同一考虑)。 */
  hr_reset_filters();

  g_hr.recover_ok++;
  g_hr.fail_streak = 0;
}

/* FIFO 指针清零: 依 SparkFun 的顺序写 0x04(FIFO_WR_PTR)/0x05(OVF)/0x06(FIFO_RD_PTR) = 0。
 * ★ 第四次改后**只有两个调用点**(都在限流出口上, 稳态一次都不发生):
 *   (1) hr_drain 的"极端陈旧出口": 连续 HR_STALE_CLEAR_ROUNDS 轮 fresh=0 且距上次
 *       >= HR_STALE_CLEAR_MIN_MS —— 读侧真的卡在同一个槽时才重同步;
 *   (2) hr_recover(): 总线恢复后按原 init 的顺序重配(它自带 >=2s 限流)。
 * **绝不在每轮的溢出分支里调它** —— 那正是本次修的 bug: 每轮把写指针拉回起点 ->
 * 每轮读到同一批槽 -> 样本 97~100% 重复(真机 dup=97~100%, 见文件顶部)。
 * 只写不校验 —— 与 init 同一理由: 0x04 会被器件自身推进, 回读可能刚好撞上写指针 +1。
 * **逐条下发, 任一条失败立刻停手**(与旧代码 `A || B || C` 的短路语义完全一致, 不是
 * 新增重试): 总线可能已经挂了, 每多试一条就要多等一次下层 I2C_XFER_TIMEOUT=5000ms
 * (vendor/sifli/chips/sf32lb52/sifli_i2c.c:41/:536)。
 * 每一步都记账(hr_op_result), 所以失败点能精确到 0x04/0x05/0x06 中的哪一个。
 * 返回 0 = 三条全部成功; <0 = 首个失败的 ioctl 返回值(失败明细已由 hr_op_result 记下)。 */
static int hr_fifo_clear(void)
{
  static const uint8_t regs[3] =
  {
    REG_FIFO_WR_PTR, REG_FIFO_OVF, REG_FIFO_RD_PTR
  };

  int k;

  for (k = 0; k < 3; k++)
    {
      int ret = i2c_write_reg(g_hr.fd, regs[k], 0x00);

      hr_op_result(HR_OP_WR_CLR, regs[k], 0, ret);
      if (ret < 0)
        {
          return ret;
        }
    }

  return 0;
}

/* 把 FIFO 样本的 3 个字节拼成 18bit 原始值(大端, 高 14 位保留位丢弃)。
 * 采样循环(排空早停)与喂算法两处都要用, 抽出来保证两处**同一公式**
 * —— 早停判据"与前一拍相同"必须和 feed 的值完全一致, 否则会误判。 */
static uint32_t hr_sample_raw(const uint8_t *b)
{
  return (((uint32_t)b[0] << 16) | ((uint32_t)b[1] << 8) | (uint32_t)b[2]) & 0x3FFFFu;
}

/* 读一个 FIFO 样本(3 字节 18bit 大端)到 b[0..2]。
 *   HR_FIFO_BYTE_WISE=0: 一次 3 字节读 (Zephyr max30101_sample_fetch 的做法);
 *   HR_FIFO_BYTE_WISE=1: 三次 1 字节读 (只要本板"单次 length>1 的 message"仍有缺陷,
 *                        翻这个开关即可, 一行回退)。
 * 注意 byte-wise 形态的**前提**: 它依赖 MAX30102 在"写 0x07 地址+STOP, 再读 1 字节"
 * 之后仍把 FIFO 内部字节计数保持在同一个样本内。三家参考实现(SparkFun/Linux/Zephyr)
 * 都没有这么用过 —— 它们都至少一次读完一个样本的 3 字节。所以这是"最后手段",
 * 只有真机证明连 3 字节的单次 message 也挂时才翻这个开关, 并在真机上确认拼出来的
 * 18bit 样本不是错位的(用 stat 行的 red/min/max 是否随脉搏起伏判断)。 */
static int hr_fifo_read_sample(uint8_t *b)
{
#if HR_FIFO_BYTE_WISE
  int k;

  for (k = 0; k < 3; k++)
    {
      int ret = i2c_read_reg(g_hr.fd, REG_FIFO_DATA, &b[k]);

      /* sub = 1..3: 这个样本的第几个字节。3 字节都失败会各记一笔, 便于分辨
       * "整个样本读不动" 与 "读到一半断"(后者有字节错位风险)。 */
      hr_op_result(HR_OP_RD_FIFO, REG_FIFO_DATA, (uint8_t)(k + 1), ret);
      if (ret < 0)
        {
          return -EIO;
        }
    }

  return 0;
#else
  int ret = i2c_read_regs(g_hr.fd, REG_FIFO_DATA, b, 3);

  hr_op_result(HR_OP_RD_FIFO, REG_FIFO_DATA, 0, ret);
  return ret;
#endif
}

/****************************************************************************
 * 采样
 ****************************************************************************/

static void hr_drain(void)
{
  uint8_t st1 = 0, st2 = 0, wr = 0, rd = 0, ovf = 0;
  uint8_t buf[3 * HR_FIFO_SAMPLES_PER_POLL];
  uint32_t prev_round_last;    /* 上一轮最后一个样本(fresh 的基准, 本轮循环中不变) */
  uint32_t stream_prev;        /* 流中前一拍(排空早停与 rdup 用, 逐样本滚动) */
  bool gate_open;
  bool have_prev_round;
  bool have_stream_prev;
  bool bulk_ok = false;        /* HR_FIFO_BULK_READ=1 且本轮整块读成功 */
  bool fifo_cleared = false;
  unsigned fresh = 0;          /* 本轮 != 上一轮最后一个的样本数(stat: fresh=x/N) */
  unsigned rdup = 0;           /* 本轮 == 流中前一拍的样本数(stat: rdup=) */
  int got = 0;
  int ret;
  int n, i;

  if (g_hr.fd < 0)
    {
      /* 上一次恢复没能把设备开回来: 直接跳过。这里**必须**早退 —— 否则每轮都会拿着
       * 无效 fd 去 ioctl(立即 -EBADF), 日志刷屏且掩盖真正的故障。 */
      return;
    }

  /* 一轮采样只记一次失败(hr_fail 内部去重), 见 hr_round_begin 的注释 */
  hr_round_begin();

  /* 新鲜度基准: 进入本轮前取一次, 循环中**不变** —— fresh 的口径就是"与上一轮最后一个
   * 样本不同"; stream_prev 逐样本滚动(排空早停 + rdup 用)。have_* 在第一个样本之前为
   * false(首轮没有"上一轮", 不能拿 memset 出来的 0 当真实样本比较)。 */
  prev_round_last  = g_hr.raw_last;
  stream_prev      = g_hr.raw_last;
  have_prev_round  = g_hr.have_raw_prev;
  have_stream_prev = g_hr.have_raw_prev;

  /* ① 读 0x00 INT_STATUS1 —— 本轮"有没有数据"的**判据**(不再看 0x04/0x05 指针)。
   *    位定义与来源见 REG_INT_STATUS1 上方的注释块: bit6 = PPG_RDY = 数据手册定义的
   *    "新样本已进 FIFO"; 本板有过"读回值不可信"的历史, 所以门控**保守放宽**为
   *    "0x00 或 0x01 任一非 0 就当可能有数据"(多读一轮的代价只是 3*N 字节 + FIFO 空时
   *    返回的旧样本, 漏读的代价是整轮没有波形)。
   *    读 INT_STATUS 寄存器本身即清其中的状态位(MAX30102 语义: 读清), 不需要额外写。 */
  ret = i2c_read_reg(g_hr.fd, REG_INT_STATUS1, &st1);
  hr_op_result(HR_OP_RD_INT, REG_INT_STATUS1, 0, ret);
  if (ret < 0)
    {
      /* 状态读失败 = 总线可能已挂。**短路本轮**: 0x01/0x05/0x04/0x06/0x07 一条都不再发,
       * 理由同下面指针段的短路 —— 每多发一条就要多等一次下层 I2C_XFER_TIMEOUT(5000ms,
       * sifli_i2c.c:41/:536)。恢复交给 hr_fail() 的双限流。 */
      g_hr.int1 = g_hr.int2 = -1;
      g_hr.fifo_last = 0;
      /* 本轮一个样本都没读到: 新鲜度字段一起归零。否则 stat/algo 行会拿**上一轮**的
       * fresh=x/N 配本轮的 n_poll=0/N —— 现场是两个自相矛盾的数(n_poll=0/16 却
       * fresh=16/16), 正好会误导"读侧到底还重不重复"的判断。 */
      g_hr.round_n = 0;
      g_hr.round_fresh = 0;
      g_hr.round_dup = 0;
      g_hr.fifo_wr = g_hr.fifo_rd = -1;
      g_hr.empty_streak = 0;
      hr_fail();
      hr_diag_fifo(&g_hr.diag_err,
                   "INT_STATUS(0x00) 读失败 —— 总线/电气问题, 不是\"没数据\"");
      return;
    }

  /* ①b 读 0x01 INT_STATUS2: 只有 DIE_TEMP_RDY 之类的次要标志, 不参与判据; 读它是为了
   *     清标志 + 在 stat 行打印现场(int2=)。读失败照旧记一次失败(与"任何读/写失败都
   *     记账"的恢复契约一致), 但不短路本轮。 */
  ret = i2c_read_reg(g_hr.fd, REG_INT_STATUS2, &st2);
  hr_op_result(HR_OP_RD_INT, REG_INT_STATUS2, 0, ret);
  if (ret < 0)
    {
      hr_fail();
    }

  g_hr.int1 = (int)st1;
  g_hr.int2 = (ret < 0) ? -1 : (int)st2;

  /* 门控 + 兜底(见 HR_NOINT_STREAK): 连续 HR_NOINT_STREAK 轮一个状态位都没置起来, 就认为
   * "PPG_RDY 位定义/使能位"的假设在本板不成立, 改成每轮盲读, 免得采样被一个位理解错饿死。
   * noint_streak 饱和在 HR_NOINT_STREAK(不再增长), 所以兜底一旦生效就保持生效;
   * 哪一轮重新读到非 0 状态立刻清零 -> 自动回到门控。 */
  if (st1 != 0 || st2 != 0)
    {
      g_hr.noint_streak = 0;
    }
  else if (g_hr.noint_streak < HR_NOINT_STREAK)
    {
      g_hr.noint_streak++;
      if (g_hr.noint_streak == HR_NOINT_STREAK)
        {
          /* 一次性打印(只在状态跃迁这一轮), 采样热路径绝不逐拍 printf */
          printf("hr_sensor: INT_STATUS 连续 %u 轮全 0(0x00 bit6 PPG_RDY=0x%02x 从未置位)"
                 " —— 门控假设不成立, 改为每轮固定 N=%d 盲读(读到非 0 状态即自动恢复门控)\n",
                 (unsigned)HR_NOINT_STREAK, (unsigned)HR_INT1_PPG_RDY,
                 HR_FIFO_SAMPLES_PER_POLL);
        }
    }

  gate_open = (st1 != 0) || (st2 != 0) ||
              (g_hr.noint_streak >= HR_NOINT_STREAK);

  /* ② 读 0x05 溢出计数 —— **只记账 + 限流日志, 不清零、不复位算法、不触发恢复**
   *    (第四次改的核心)。它不参与"读几个样本"的计算, 也不参与恢复判定;
   *    stat 的 ovf= 与 algo 行的 ovfs= 只是现场证据。 */
  ret = i2c_read_reg(g_hr.fd, REG_FIFO_OVF, &ovf);
  hr_op_result(HR_OP_RD_OVF, REG_FIFO_OVF, 0, ret);

  if (ret == 0)
    {
      if (ovf != 0)
        {
          g_hr.overflows++;
          g_hr.ovf_streak++;

          /* ★★ 本次修复的核心位置 ★★
           * 旧代码在这里调 hr_fifo_clear() 写 0x04/0x05/0x06 = 0; 真机 OVF(0x05)恒非 0
           * -> **每 50ms 把器件写指针拉回起点** -> 每轮读到同一批 FIFO 槽 ->
           * dup=97~100%/byeq=100%/d1=0/acdc=73%(生理 0.1~2%)/dc≈202204 近满量程
           * -> 波形根本不存在, 算法(正确地)gate=warm 拒绝出值。
           * 现在这里**一条写都不发**, 理由逐条:
           *   - 0x04(FIFO_WR_PTR)/0x06(FIFO_RD_PTR) 是**器件自己的** FIFO 指针: 采样期
           *     写指针由器件产出样本时自增、读指针由主机读 0x07 时自增; 主机写它只应出现
           *     在"初始化 / 彻底重同步"两个限流出口(SparkFun 的 0x04/0x05/0x06 清零也
           *     只出现在 begin(), 采样循环里一次都不写: src/MAX30105.cpp:319-322);
           *   - 0x05 是溢出**计数**(写 0 只清计数): 保留它做统计。真机"恒非 0"很可能正是
           *     "每轮把写指针拉回起点"的**后果**(写指针倒退 -> 器件认为 FIFO 被覆盖 ->
           *     溢出计数只增不减), 而不是独立故障;
           *   - 溢出 = 我们没跟上产出速率。补救是"每轮多读/排空"
           *     (HR_FIFO_SAMPLES_PER_POLL=16 + HR_FIFO_DRAIN_ALL=1), 清指针一个样本都
           *     追不回来, 只会把 FIFO 拉回起点(还可能丢掉积压的样本);
           *   - 也不再据此 hr_fail()/触发 hr_recover(): 恢复里同样要清一遍指针并复位滤波器,
           *     在 OVF 假阳性下等于每 2s 复现一次同一个 bug。
           * 读侧真卡死改由 fresh=0 判定(见下面的"极端陈旧出口"); 就算 0x05 的读法/语义
           * 本身可疑, 也不影响任何控制流 —— 这也是本次要求的"只用 dup/fresh 判定"。 */
          hr_diag_fifo(&g_hr.diag_ovf,
                       "FIFO 溢出计数(0x05)非 0 —— 只记账/限流日志, 不清 0x04/0x05/0x06"
                       "(第四次改: 每轮清指针正是重复样本的根因)");

          /* 到 HR_OVF_STUCK_ROUNDS 那一轮打一条一次性提示, 让"OVF 卡死"这个历史现象
           * 在日志里仍有痕迹, 但不再有任何控制流动作。 */
          if (g_hr.ovf_streak == HR_OVF_STUCK_ROUNDS)
            {
              printf("hr_sensor: 连续 %u 轮 OVF(0x05)非 0 —— 已按新设计只记账"
                     "(不清零/不触发恢复); 读侧是否正常请看 stat 行的 fresh=/rdup=\n",
                     (unsigned)HR_OVF_STUCK_ROUNDS);
            }

          hr_ok();                  /* 只记账: 不是 ioctl 失败, 不参与恢复限流 */
        }
      else
        {
          g_hr.ovf_streak = 0;      /* 没溢出 = 正在正常出数据 */
          hr_ok();
        }
    }
  else
    {
      hr_fail();                  /* 0x05 读失败: 记账, 仍按原样继续读 0x04/0x06 */
    }

  /* ④ 读 0x04/0x06 指针: **只作诊断**(stat 行 wr=/rd=), 不参与任何控制流 ——
   *    真机已确证本板读回的指针不可信/不前进(wr=1 rd=0 恒定)。短路语义同旧代码:
   *    0x04 失败就不再读 0x06(总线已挂时少发一条就少等一次下层 5s 超时)。
   *    指针读失败只影响两个诊断字段, 但说明总线此刻不健康 -> 本轮不再发 0x07 的 3*N 条读,
   *    按"0 个样本"计, 由下面的统一出口记账/限流打印。 */
  ret = i2c_read_reg(g_hr.fd, REG_FIFO_WR_PTR, &wr);
  hr_op_result(HR_OP_RD_PTR, REG_FIFO_WR_PTR, 0, ret);
  if (ret >= 0)
    {
      ret = i2c_read_reg(g_hr.fd, REG_FIFO_RD_PTR, &rd);
      hr_op_result(HR_OP_RD_PTR, REG_FIFO_RD_PTR, 0, ret);
    }

  if (ret < 0)
    {
      g_hr.fifo_wr = g_hr.fifo_rd = -1;
      gate_open = false;
      hr_fail();
      /* 首次失败立即打印原因(限流在 hr_diag_fifo 内部: 之后每 HR_DIAG_EVERY 次补一条),
       * 单次偶发失败也留一条证据但不刷屏; errno 明细仍由 i2c_read_err_log 负责。 */
      hr_diag_fifo(&g_hr.diag_err,
                   "FIFO 指针 0x04/0x06 读失败(仅诊断字段) —— 总线/电气问题");
    }
  else
    {
      hr_ok();
      g_hr.fifo_wr = (int)wr;
      g_hr.fifo_rd = (int)rd;
    }

  /* ④b ★ 极端陈旧出口(计次 + 限流): 连续 HR_STALE_CLEAR_ROUNDS 轮 fresh=0(读侧一个
   *     新鲜样本都没有)且距上次 >= HR_STALE_CLEAR_MIN_MS, 才清一次 FIFO 指针并复位滤波器。
   *     这是采样热路径上**唯一**写 0x04/0x06 的地方(另一处是 hr_recover 的重同步)。
   *     判据刻意用 fresh 而**不是** 0x05(OVF): 真机 0x05 恒非 0 是假阳性, 用它就会变成
   *     "每 2s 清一次指针" —— 正是本次要修的 bug(见 HR_STALE_CLEAR_ROUNDS 的定义)。
   *     fifo_cleared 交给 ⑤ 决定本轮要不要读(HR_SKIP_SAMPLE_AFTER_CLEAR=0 -> 照读)。 */
  if (g_hr.stale_streak >= HR_STALE_CLEAR_ROUNDS)
    {
      uint32_t t = now_ms();

      if (g_hr.stale_clear == 0 ||
          (uint32_t)(t - g_hr.last_stale_clear_ms) >= HR_STALE_CLEAR_MIN_MS)
        {
          int clr = hr_fifo_clear();

          g_hr.last_stale_clear_ms = t;
          g_hr.stale_clear++;
          g_hr.stale_streak = 0;      /* 清完重新计: 下一轮看 fresh 有没有回来 */
          fifo_cleared = (clr >= 0);

          if (clr >= 0)
            {
              /* 样本流被清断过一次 -> 只复位滤波器/包络/峰位置(保留 RR 历史与输出稳定
               * 计数, 理由同 hr_reset_filters 的注释) */
              hr_reset_filters();
            }
          else
            {
              /* 清零写失败是**真实的 ioctl 失败**, 按"任何读/写失败都记账"的恢复契约记
               * 一轮失败(失败明细已由 hr_fifo_clear 内部的 hr_op_result 记到 wr_clr)。
               * 后面的轮次若总线仍坏, 0x00 读失败会继续累计 -> HR_RECOVER_FAILS 后走
               * hr_recover 的双限流; 这里不额外做别的动作。 */
              hr_fail();
            }

          hr_diag_fifo(&g_hr.diag_stale,
                       "连续多轮 fresh=0(读侧卡在同一个 FIFO 槽)—— 极端出口: 清一次"
                       " 0x04/0x05/0x06 + 复位滤波器(计次 + 限流)");
        }
    }

  /* ⑤ 采样: 每轮最多 HR_FIFO_SAMPLES_PER_POLL 个样本(默认 16), 不再依据 0x04/0x05 算
   *    数量; HR_FIFO_DRAIN_ALL=1 时"读到 FIFO 空为止"(见文件顶部"FIFO 读法"与 ⑤b)。
   *    每个样本一次独立传输(3 字节; HR_FIFO_BYTE_WISE=1 时退化为三次 1 字节)。
   *    关键点: 一旦某个样本读失败就**立刻 break**, 不再试后面的样本 —— 总线已经卡住时,
   *    每多试一次就要多等一次下层 I2C_XFER_TIMEOUT(5000ms, sifli_i2c.c:41/:536)。
   *    失败只算一轮一次(hr_fail 去重), 恢复交给限流器; 读不到就按"本轮样本数少"记,
   *    不额外刷屏(限流已在 hr_diag_fifo 内部)。 */
  if (gate_open
#if HR_SKIP_SAMPLE_AFTER_CLEAR
      && !fifo_cleared
#endif
      )
    {
#if HR_FIFO_BULK_READ
      /* ⑤a 整块读(HR_FIFO_BULK_READ=1 才编译): 一次 ioctl 读回 3*N 字节 = 一次性排空。
       *     失败**一次**即粘滞降级为逐样本读(本板 length>1 的读有前科, 而且一次失败要等
       *     下层 I2C_XFER_TIMEOUT=5000ms, 不能每轮重试) —— 这就是"降级路径"。 */
      if (!g_hr.bulk_failed)
        {
          ret = i2c_read_regs(g_hr.fd, REG_FIFO_DATA, buf, (int)sizeof(buf));
          hr_op_result(HR_OP_RD_FIFO, REG_FIFO_DATA, 0, ret);
          if (ret < 0)
            {
              g_hr.bulk_failed = true;   /* 粘滞: 以后一律逐样本读 */
              hr_fail();
              hr_diag_fifo(&g_hr.diag_err,
                           "FIFO 整块读(0x07, length=3N)失败 —— 永久降级为逐样本读");
            }
          else
            {
              bulk_ok = true;
              got = HR_FIFO_SAMPLES_PER_POLL;
            }
        }
#endif

      /* ⑤b 逐样本读(默认路径; 整块读失败降级后也走这里) */
      if (!bulk_ok)
        {
          for (n = 0; n < HR_FIFO_SAMPLES_PER_POLL; n++)
            {
              if (hr_fifo_read_sample(&buf[3 * n]) < 0)
                {
                  /* 走 i2c_read_regs() 分发器(当前=路径B), errno 明细由 i2c_read_err_log
                   * 限流打印 */
                  hr_fail();
                  hr_diag_fifo(&g_hr.diag_err,
                               "FIFO_DATA(0x07) 逐样本读失败(每个样本一次独立传输)");
                  break;
                }

#if HR_FIFO_DRAIN_ALL
              /* 排空早停: 已经读到"一轮的产出量"(HR_FIFO_MIN_SAMPLES)之后, 再读到与流中
               * 前一拍**完全相同**的样本, 就判为空 FIFO 返回的那个陈旧槽 -> 丢掉它(不计数、
               * 不喂算法)并结束本轮读取。这样"有多少新鲜样本就取多少", 不会落后于器件写
               * 指针, 也不会把陈旧样本送进算法。 */
              {
                uint32_t raw = hr_sample_raw(&buf[3 * n]);

                if (have_stream_prev && got >= HR_FIFO_MIN_SAMPLES &&
                    raw == stream_prev)
                  {
                    break;
                  }

                stream_prev = raw;
                have_stream_prev = true;
              }
#endif
              got++;
            }
        }
#if HR_FIFO_DRAIN_ALL && HR_FIFO_BULK_READ
      else
        {
          /* 整块读没有逐样本的早停机会: 在缓冲区里裁掉尾部的陈旧重复样本 —— 从
           * HR_FIFO_MIN_SAMPLES 起找第一个"与前一拍相同"的位置, 它和它后面的都不要。 */
          for (i = HR_FIFO_MIN_SAMPLES; i < got; i++)
            {
              if (hr_sample_raw(&buf[3 * i]) == hr_sample_raw(&buf[3 * (i - 1)]))
                {
                  got = i;
                  break;
                }
            }
        }
#endif
    }

  g_hr.fifo_last = (uint32_t)got;        /* 本轮实际读到的样本数 = got 个 = 3*got 字节 */
  g_hr.round_n   = (uint32_t)got;        /* 与 fresh=x/N 的分母同一个数 */

  /* rdup 的滚动基准重新从"上一轮最后一个样本"起算: ⑤b 里的 stream_prev 已经滚到本轮
   * 末尾, 不能接着用(否则 first sample 会跟本轮最后一个比, 语义就错了)。 */
  stream_prev      = g_hr.raw_last;
  have_stream_prev = g_hr.have_raw_prev;

  if (got > 0)
    {
      hr_ok();
      g_hr.empty_streak = 0;
    }
  else if (!fifo_cleared)
    {
      /* 本轮一个样本都没读到(门控说没数据 / 状态或指针读不了 / 器件没在产样本)。
       * 50ms 轮询下单拍为 0 属正常, 连续 HR_DIAG_STREAK 轮(约 5s)才是真"没数据"。
       * 刚清零过的那一轮 FIFO 必然是空的, 不计入(否则卡死态下每轮清一遍会误报
       * "器件没在产样本")。 */
      g_hr.empty_streak++;
      if (g_hr.empty_streak >= HR_DIAG_STREAK)
        {
          hr_diag_fifo(&g_hr.diag_empty,
                       "连续多轮 0 个样本(INT_STATUS 全 0 / FIFO 无新样本)—— 器件没在产"
                       "样本(模式/LED/供电/FIFO 配置)或门控判据在本板不成立");
        }
    }

  /* 整包全 0: 本板坏读路径的典型症状(读什么都返回常数), 与"有数据"区分开 */
  {
    bool all_zero = (got > 0);

    for (i = 0; i < got * 3; i++)
      {
        if (buf[i] != 0)
          {
            all_zero = false;
            break;
          }
      }

    if (all_zero)
      {
        /* 首次整包全 0 立即打印(限流同上): 真实的 PPG 样本不可能整包都是 0 */
        hr_diag_fifo(&g_hr.diag_zero,
                     "FIFO 读整包全 0 —— 读到的不是真实样本(LED 灭/读路径不可信)");
      }
  }

  for (i = 0; i < got; i++)
    {
      uint32_t raw = hr_sample_raw(&buf[3 * i]);
      uint32_t bpm;
      uint8_t mode = 0;

      /* 读侧新鲜度取证(第四次改新增, 直接回答"读到的样本还重不重复"):
       *   fresh: 与**上一轮最后一个样本**比(基准 prev_round_last 在循环外取一次, 口径就是
       *          "本轮这 N 个里有多少与上一轮最后一个不同" —— stat 行的 fresh=x/N);
       *   rdup:  与**流中前一拍**比(含跨轮边界) —— 把"轮内也在重复"这一半也照出来。
       * 这两个计数只做取证, 不参与任何控制流(控制流用的是 ⑤b 的排空早停)。 */
      if (!have_prev_round || raw != prev_round_last)
        {
          fresh++;
        }

      if (have_stream_prev && raw == stream_prev)
        {
          rdup++;
        }

      stream_prev = raw;
      have_stream_prev = true;

      /* 逐字节读(HR_FIFO_BYTE_WISE=1)的**错位指纹**, 只计数不参与控制流(每 2s 由
       * hr_algo_diag() 汇总成 byeq=x%/y%):
       *   b1==b2        —— 一个样本的中间字节与低字节相同; 随机波形下约 1/256;
       *   b0==b1==b2    —— 三次读回的是**同一个字节**(FIFO 字节计数没前进),
       *                    比例明显偏高就说明 18bit 样本是拼出来的假值, 该翻
       *                    HR_FIFO_BYTE_WISE 或改回一次读 3 字节。 */
      if (buf[3 * i + 1] == buf[3 * i + 2])
        {
          g_hr.win_b12eq++;
        }

      if (buf[3 * i] == buf[3 * i + 1] && buf[3 * i + 1] == buf[3 * i + 2])
        {
          g_hr.win_b012eq++;
        }

      g_hr.samples++;
      if (g_hr.samples > 1 && raw == g_hr.raw_last)
        {
          g_hr.same_streak++;
        }
      else
        {
          g_hr.same_streak = 0;
        }

      /* red= 的来源: 每个成功解析出的样本都覆盖它, 所以它**永远是"最近一次成功读到
       * 的样本值"**(不是某个固定槽/未被更新的字段)。若真机上 red= 逐轮不变, 那是
       * "每轮读到的最后一个槽确实是同一个值"(FIFO 指针/清零点位的后果), 不是打印错了
       * —— 用 algo 行的 last10= 可以直接看到这 10 个值里有没有恒定尾。 */
      g_hr.raw_last = raw;
      if (raw < g_hr.raw_min || g_hr.raw_min == 0) g_hr.raw_min = raw;
      if (raw > g_hr.raw_max) g_hr.raw_max = raw;

      if (hr_feed(raw, &bpm))
        {
          if (bpm >= BPM_MIN && bpm <= BPM_MAX)
            {
              g_hr.last_bpm = bpm;
              g_hr.last_bpm_ts = now_ms();
              g_hr.have_bpm = true;
              mode = 1;
            }
        }

      (void)mode;

      /* 样本恒为常数(约 3s): 强烈提示读到的不是真实 PPG 波形 —— 与"有数据但算法
       * 没检出"区分开(后者 raw 一定在动)。同一次持续里按 HR_DIAG_EVERY 限流。 */
      if (g_hr.same_streak >= HR_CONST_STREAK)
        {
          hr_diag_fifo(&g_hr.diag_const,
                       "连续样本完全相同(读数恒为常数) —— 不是真实波形, 算法不可能出 bpm");
        }
    }

  /* ---- 本轮新鲜度取证(第四次改新增; 全部只读/只统计, 不参与控制流) ----
   * 稳态(读侧修好后): fresh == got(接近 n_poll 上限)、rdup == 0、stclr == 0;
   * 旧 bug 现场: fresh == 0/got(97~100% 重复)、rdup == got。 */
  g_hr.round_fresh = (uint32_t)fresh;
  g_hr.round_dup   = (uint32_t)rdup;

  /* 陈旧轮计数(极端陈旧出口的判据): 只有"读到了样本却一个都不新鲜"才算一轮陈旧;
   * 门控说没数据(got=0)是另一回事(empty_streak 管), 不能混进来把出口提前触发。 */
  if (got > 0 && fresh == 0)
    {
      g_hr.stale_streak++;
    }
  else if (fresh > 0)
    {
      g_hr.stale_streak = 0;
    }
}

#ifdef HAVE_HR_TOPIC
/* 本树的 orb_advertise() 返回 int fd (不是 orb_advert_t 句柄),
 * orb_publish(meta, fd, data) 配套使用 (apps/system/uorb/uORB/uORB.h:326/428)。 */
static int g_hr_pub = -1;

static void hr_publish(void)
{
  struct algo_heartrate hr;

  if (g_hr_pub < 0)
    {
      g_hr_pub = orb_advertise(ORB_ID(algo_heartrate), NULL);
      if (g_hr_pub < 0)
        {
          printf("hr_sensor: orb_advertise(algo_heartrate) failed: %d\n",
                 g_hr_pub);
          return;
        }

      printf("hr_sensor: uORB advertise ok (fd=%d)\n", g_hr_pub);
    }

  memset(&hr, 0, sizeof(hr));
  hr.timestamp_us = (uint64_t)now_ms() * 1000ULL;
  hr.bpm = g_hr.last_bpm;
  hr.heartrate_section = 0;
  hr.heartrate_section_max = 0;
  hr.heartrate_section_min = 0;

  /* 返回值以前是丢掉的: 发布失败时算法侧一切正常(gate=ok / q=100 / bpm 有值),
   * 只有订阅端永远收不到 —— "按钮不出数"里最难查的一类现场。只记第一次失败。 */
  {
    static bool pub_err_logged = false;
    ssize_t     pret = orb_publish(ORB_ID(algo_heartrate), g_hr_pub, &hr);

    if (pret != (ssize_t)sizeof(hr) && !pub_err_logged)
      {
        pub_err_logged = true;
        printf("hr_sensor: uORB publish failed: %d (expect %u)\n",
               (int)pret, (unsigned)sizeof(hr));
      }
  }
}
#endif

static int hr_thread(int argc, char *argv[])
{
  uint32_t last_pub  = 0;
  uint32_t last_stat = now_ms();     /* first stat 在 2s 后, 避免开局打一堆 0 */

  printf("hr_sensor: 采样线程启动 (100sps 输出, 每 %ums 轮询一次)\n",
         HR_POLL_MS);

  while (g_hr.running)
    {
      hr_drain();

      uint32_t t = now_ms();
      if (g_hr.have_bpm && (t - last_pub) >= 1000)
        {
          last_pub = t;
#ifdef HAVE_HR_TOPIC
          hr_publish();
#endif
          printf("hr_sensor: bpm=%u (samples=%u, ovf=%u, raw=%u)\n",
                 (unsigned)g_hr.last_bpm, (unsigned)g_hr.samples,
                 (unsigned)g_hr.overflows, (unsigned)g_hr.raw_last);
        }

      /* 周期统计(限流): 固定每 HR_STAT_MS 一条, 不在采样循环里逐拍打印 */
      if ((t - last_stat) >= HR_STAT_MS)
        {
          last_stat = t;
          hr_print_stat();
          hr_algo_diag();          /* 新增: 算法链路/信号质量 2s 一行(限流同上) */
        }

      usleep(HR_POLL_MS * 1000);
    }

  /* LED 必须在这里关: SHDN 要用的 fd 属于【本任务组】, 而 `hr_sensor stop` 是另一个
   * 任务组(新进程), 它手里的 g_hr.fd 在本组之外没有意义(真机 P0 已验证: 跨进程那条
   * 路径写不出 SHDN, LED 常亮)。放在采集线程自己的退出路径上是唯一能保证写对 fd 的做法。 */
  if (g_hr.inited && g_hr.fd >= 0)
    {
      i2c_write_reg(g_hr.fd, REG_MODE_CONFIG, 0x80);   /* SHDN: 关 LED */
      close(g_hr.fd);
      g_hr.fd = -1;
      g_hr.inited = false;
      printf("hr_sensor: SHDN 已写(关 LED), 总线已释放\n");
    }

  /* 采样线程是 SENSOR_OWNER_HR 的持有者，退出时必须归还 —— 早一步（比如
   * hr_stop 里）会留出"线程还没关灯、锁已经放掉"的窗口，血氧趁虚而入就是
   * 又一次抢硬件。 */
  sensor_release(SENSOR_OWNER_HR);

  printf("hr_sensor: 采样线程退出\n");
  return 0;
}

/****************************************************************************
 * Public commands
 ****************************************************************************/

void max30102_hr_abort(void)
{
  g_hr_abort = true;
}

int max30102_hr_running(void)
{
  return g_hr.running ? 1 : 0;
}

static int hr_start(void)
{
  if (g_hr.running)
    {
      printf("hr_sensor: 已在运行\n");
      return 0;
    }

  /* 血氧正在测量时不能动硬件 —— 那边把器件切在 MODE_SPO2（6 字节/样本），
   * 而 hr_drain 按 3 字节解析，会读到错位的垃圾数据；两边还会抢同一个
   * FIFO 指针。这里必须在打开/初始化**之前**就判断，否则又会出现
   * "init 已完成、running 还没置位"的 TOCTOU 窗口。 */
  if (!sensor_claim(SENSOR_OWNER_HR))
    {
      printf("hr_sensor: 传感器被血氧测量占用, 稍后再试\n");
      return 1;
    }

  /* 新一轮开始，清掉上一轮可能残留的 abort 请求 */
  g_hr_abort = false;

  if (!g_hr.inited)
    {
      g_hr.fd = open(I2C_DEV_PATH, O_RDWR);
      if (g_hr.fd < 0)
        {
          printf("hr_sensor: open %s failed: %d\n", I2C_DEV_PATH, errno);
          sensor_release(SENSOR_OWNER_HR);
          return 1;
        }

      if (max30102_init(g_hr.fd) < 0)
        {
          printf("hr_sensor: 初始化失败 (检查接线: VIN=3V3, GND, SCL=PA41, SDA=PA42)\n");
          close(g_hr.fd);
          g_hr.fd = -1;
          sensor_release(SENSOR_OWNER_HR);
          return 1;
        }

      g_hr.inited = true;
      hr_reset_algo();
    }

  g_hr.running = true;
  g_hr.samples = 0;
  g_hr.overflows = 0;
  g_hr.raw_min = g_hr.raw_max = 0;

  /* 清掉上一轮的"已锁定"状态: 否则新一轮在算法重新锁定之前, 调用方(如 hr_measure)
   * 会读到上一轮遗留的 last_bpm 并当成"本次测到了", 或者把旧值重播一遍。 */
  g_hr.have_bpm = false;
  g_hr.last_bpm = 0;
  g_hr.last_bpm_ts = 0;

  /* 诊断/统计字段清零(不改 start/stop/status 语义): 让新一轮的 diag 从"首次"重新计,
   * 否则上一轮的计数会吃掉首次打印, 真机上就看不到原因了 */
  g_hr.fifo_last = 0;
  g_hr.fifo_wr = g_hr.fifo_rd = -1;
  g_hr.int1 = g_hr.int2 = -1;
  g_hr.noint_streak = 0;
  g_hr.empty_streak = 0;
  g_hr.same_streak = 0;
  g_hr.diag_empty = 0;
  g_hr.diag_err = 0;
  g_hr.diag_zero = 0;
  g_hr.diag_const = 0;
  g_hr.diag_rec = 0;
  g_hr.diag_ovf = 0;
  g_hr.diag_stale = 0;

  /* 读侧新鲜度取证清零(第四次改新增字段, 不改 start/stop/status 语义):
   * 新一轮从"没有上一轮样本/fresh 从 0 计"开始, 并解除上一轮可能遗留的粘滞降级 ——
   * bulk_failed 只在一次运行内有效, stop/start 后重新给整块读一次机会(默认宏为 0 时
   * 这个字段恒为 false, 无副作用)。 */
  g_hr.round_n = 0;
  g_hr.round_fresh = 0;
  g_hr.round_dup = 0;
  g_hr.stale_streak = 0;
  g_hr.stale_clear = 0;
  g_hr.last_stale_clear_ms = 0;
  g_hr.bulk_failed = false;

  /* 算法与算法诊断窗口清零(新增字段, 不改 start/stop/status 语义):
   * 先全量复位算法(含 RR 历史/输出稳定计数), 再把诊断计数(fed=/rst=/窗口)归零 ——
   * 顺序不能反: hr_reset_algo() 内部会 ++algo_rst, 由 hr_algo_diag_reset() 收尾归零。 */
  hr_reset_algo();
  hr_algo_diag_reset();

  /* 逐操作记账清零: 新一轮从"一次都没失败过"重新计, 否则上一轮的 lastfail/firstfail
   * 会带到新一轮的 stat 行里, 真机上就分不清是谁的错了 */
  memset(g_hr.op, 0, sizeof(g_hr.op));
  g_hr.last_fail_op = HR_OP_NONE;
  g_hr.round_fail_first = HR_OP_NONE;
  g_hr.ovf_streak = 0;

  /* 故障记账/恢复计数清零: 新一轮从"没失败过"重新计, 否则上一轮的连续失败会立刻
   * 触发一次本不该有的恢复 */
  g_hr.fails = 0;
  g_hr.fail_streak = 0;
  g_hr.fail_counted = false;
  g_hr.recover_count = 0;
  g_hr.recover_ok = 0;
  g_hr.last_recover_ms = 0;
  g_hr.reset_ioctl_ret = 0;

  /* ── abort 检查 ─────────────────────────────────────────────────────
   * 上层 hr_measure 的 start 已经超时放弃时会把这个标志置起来。
   *
   * 为什么必须有这一步（对抗审查 2026-10-19 指出）：start 超时最常见的
   * 原因是本函数还卡在 max30102_init 里（PART_ID 50 次重试 + 软复位 200ms
   * + 6 个寄存器各 3 次重试，而单次 I2C 超时是 5s）。等 hr_measure 放弃之后
   * init 才成功、采样线程才起来 —— 没有人会去停它，g_hr.running 永远为 true，
   * 于是血氧从此被 "心率采集正在运行" 永久挡住，只能重启恢复。 */
  if (g_hr_abort)
    {
      g_hr_abort = false;
      g_hr.running = false;
      sensor_release(SENSOR_OWNER_HR);
      printf("hr_sensor: abort 请求已生效, 不创建采样线程\n");
      return 1;
    }

  int pid = task_create("hr_sensor", 120, 4096, hr_thread, NULL);
  if (pid < 0)
    {
      printf("hr_sensor: task_create failed: %d\n", errno);
      g_hr.running = false;
      sensor_release(SENSOR_OWNER_HR);
      return 1;
    }

  return 0;
}

static int hr_stop(void)
{
  if (!g_hr.running)
    {
      printf("hr_sensor: 未运行\n");
      return 0;
    }

  g_hr.running = false;

  /* 不在这里写 SHDN: 本进程(新任务组)手里的 g_hr.fd 不是采集线程的 fd, 写了也没用
   * (P0 实测)。只负责置 running=false, SHDN 与 close 由采集线程退出路径完成。 */
  printf("hr_sensor: 已请求停止 (累计 %u 样本, 溢出 %u 次) —— SHDN 由采集线程写入\n",
         (unsigned)g_hr.samples, (unsigned)g_hr.overflows);
  return 0;
}

static int hr_status(void)
{
  printf("hr_sensor: %s, inited=%d, samples=%u, overflows=%u\n",
         g_hr.running ? "running" : "stopped", g_hr.inited,
         (unsigned)g_hr.samples, (unsigned)g_hr.overflows);

  if (g_hr.have_bpm)
    {
      printf("            bpm=%u (更新于 %ums 前)\n", (unsigned)g_hr.last_bpm,
             (unsigned)(now_ms() - g_hr.last_bpm_ts));
    }
  else
    {
      printf("            bpm=<尚未测得>\n");
    }

  printf("            raw last=%u min=%u max=%u\n",
         (unsigned)g_hr.raw_last, (unsigned)g_hr.raw_min, (unsigned)g_hr.raw_max);
  return 0;
}

/****************************************************************************
 * 自检 (hr_sensor selftest): 只做诊断 —— 不启动采集、不改 uORB、不动 start/stop 状态
 ****************************************************************************/

/* 本 SoC I2C 分频器下限, 用来判断某一档速率"根本发不出去":
 *   SCL = i2c_clk / (2*FLV + dnf + 7), i2c_clk 固定 48MHz, dnf=0
 *   (vendor/sifli/chips/drivers/hal/bf0_hal_i2c.c:270-294)
 *   FLV 字段只有 9bit, 最大 511 (cmsis/sf32lb52x/i2c.h:200-202)
 *   -> 可达最低约 48MHz/(2*511+7) = 46.6kHz。
 *   请求 25kHz 会算出 FLV=957 超界, 触发 HAL_ASSERT(flv <= 511) 展开成的 while(1)
 *   (bf0_hal.h:406-423; 本板 bf0_hal_conf_hcpu.h 未定义 USE_FULL_ASSERT)
 *   -> 会把 hr_sensor 任务永久挂死。为守住"不阻塞超过 2 秒、不挂板"的约束,
 *   25kHz 这一档只打印原因、不真发请求。 */
#define ST_I2C_CLK_HZ   48000000u
#define ST_FLV_MAX      511u
#define ST_MIN_FREQ_HZ  (ST_I2C_CLK_HZ / (2u * ST_FLV_MAX + 7u))   /* 46610 Hz */
#define ST_NFREQ        3
#define ST_NREADS       8
/* 时间预算守护: 自检本身固定次数、无重试、无 sleep, 但单条 ioctl 的超时由下层 HAL
 * 决定 (vendor/sifli/chips/sf32lb52/sifli_i2c.c:41 I2C_XFER_TIMEOUT=5000ms), 应用层
 * 拦不住"已经在飞"的那一次传输。这里的守护保证: 一旦累计超过预算就不再发起新传输,
 * 把总时长收敛到 预算 + 最多一次下层超时, 而不是 45 次传输 × 5s。 */
#define ST_BUDGET_MS    2000u

static uint32_t st_flv(uint32_t freq)
{
  return ((ST_I2C_CLK_HZ + (freq >> 1)) / freq - 7u + 1u) / 2u;
}

static bool st_budget_left(uint32_t t0)
{
  return (now_ms() - t0) <= ST_BUDGET_MS;
}

static void st_dump_vals(const char *tag, const uint8_t *v, int n,
                         int hits, int oks)
{
  int i;

  printf("hr_sensor:   %s: ", tag);
  for (i = 0; i < n; i++)
    {
      printf("%02x ", v[i]);
    }

  printf("-> 0x%02x 命中 %d/%d, ioctl 成功 %d/%d\n", PART_ID_VAL, hits, n, oks, n);
}

static int hr_selftest(void)
{
  static const uint32_t freqs[ST_NFREQ] = { 100000u, 50000u, 25000u };
  static const uint8_t  wvals[3]        = { 0x3f, 0x00, 0x3f };  /* 最后一次写回 0x3f=LED_PA 默认 */
  uint8_t vals[ST_NREADS];
  int budget_hit = 0;
  int hit_a = 0;
  int ok_a  = 0;
  int hit_b = 0;
  int ok_b  = 0;
  int wr_a  = 0;
  int wr_b  = 0;
  uint32_t t0;
  int fd;
  int f;
  int i;

  fd = open(I2C_DEV_PATH, O_RDWR);
  if (fd < 0)
    {
      printf("hr_sensor: selftest: open %s failed: errno=%d(%s)\n",
             I2C_DEV_PATH, errno, strerror(errno));
      return 1;
    }

  g_i2c_freq = I2C_FREQ;
  t0 = now_ms();

  printf("hr_sensor: === selftest: 只诊断, 不启动采集/不发布 uORB ===\n");
  printf("hr_sensor: 总线 %s @0x%02x, 默认 %ukHz; 切频用 msg.frequency"
         " (本树无 I2CIOC_SET_FREQUENCY ioctl, 但速率逐条消息生效)\n",
         I2C_DEV_PATH, MAX30102_ADDR, I2C_FREQ / 1000);

  /* [1/4] 三档频率各连读 0xff 8 次 —— 走**主读路径**(由 HR_USE_SPLIT_READ 决定), 量的
   * 是 `hr_sensor start` 真正会用的那条路; A/B 两条路径的对照在 [2/4][3/4]。 */
  printf("hr_sensor: [1/4] 频率扫描(主读路径 %s): 每档连读 0x%02x %d 次\n",
         HR_READ_PATH_NAME, PART_ID_REG, ST_NREADS);

  for (f = 0; f < ST_NFREQ; f++)
    {
      char tag[40];
      int hits = 0;
      int oks  = 0;

      if (freqs[f] < ST_MIN_FREQ_HZ)
        {
          printf("hr_sensor:   %6u Hz: 跳过(本 SoC 分频器不可达: 需要 FLV=%u > 上限 %u,"
                 " 可达最低 %u Hz); 硬发会踩 HAL_ASSERT 的 while(1) 挂死任务, 故只列不跑\n",
                 (unsigned)freqs[f], (unsigned)st_flv(freqs[f]),
                 (unsigned)ST_FLV_MAX, (unsigned)ST_MIN_FREQ_HZ);
          continue;
        }

      g_i2c_freq = freqs[f];

      for (i = 0; i < ST_NREADS; i++)
        {
          if (!st_budget_left(t0))
            {
              budget_hit = 1;
              break;
            }

          vals[i] = 0;
          if (i2c_read_reg(fd, PART_ID_REG, &vals[i]) == 0)
            {
              oks++;
            }

          if (vals[i] == PART_ID_VAL)
            {
              hits++;
            }
        }

      snprintf(tag, sizeof(tag), "%6u Hz (FLV=%u)", (unsigned)freqs[f],
               (unsigned)st_flv(freqs[f]));
      st_dump_vals(tag, vals, i, hits, oks);

      if (i < ST_NREADS)
        {
          printf("hr_sensor:   (预算 %ums 用尽, 后续频率不再扫描)\n",
                 (unsigned)ST_BUDGET_MS);
          break;
        }
    }

  /* [2/4] 写-回读 0x0c: 写 0x3f/0x00/0x3f 各一次, A/B 两条路径各回读 */
  g_i2c_freq = I2C_FREQ;
  printf("hr_sensor: [2/4] 写-回读 0x%02x (LED1_PA) @%ukHz: 依次写 3f/00/3f,"
         " 每次 A/B 两条路径各回读一次\n", REG_LED1_PA, I2C_FREQ / 1000);

  for (i = 0; i < 3; i++)
    {
      uint8_t rb_a = 0;
      uint8_t rb_b = 0;
      int ok_this_a;
      int ok_this_b;
      int ret_w;
      int ret_a;
      int ret_b;

      if (!st_budget_left(t0))
        {
          budget_hit = 1;
          printf("hr_sensor:   (预算 %ums 用尽, 余下写-回读步骤跳过)\n",
                 (unsigned)ST_BUDGET_MS);
          break;
        }

      /* A/B 两侧在这里都写死(不走 i2c_read_reg 的分发): 对照实验要的正是"同一次写入,
       * 两条读路径各读一遍", 不能随主路径开关漂移。 */
      ret_w = i2c_write_reg(fd, REG_LED1_PA, wvals[i]);
      ret_a = i2c_read_regs_combined(fd, REG_LED1_PA, &rb_a, 1);
      ret_b = i2c_read_regs_split(fd, REG_LED1_PA, &rb_b, 1);

      ok_this_a = (ret_w == 0 && ret_a == 0 && rb_a == wvals[i]);
      ok_this_b = (ret_w == 0 && ret_b == 0 && rb_b == wvals[i]);
      wr_a += ok_this_a;
      wr_b += ok_this_b;

      printf("hr_sensor:   写 0x%02x -> 回读A=%02x(%s) 回读B=%02x(%s)%s\n",
             wvals[i], rb_a, ok_this_a ? "一致" : "不一致",
             rb_b, ok_this_b ? "一致" : "不一致",
             ret_w < 0 ? " [写入 ioctl 失败]" : "");
    }

  /* [3/4] PART_ID 8 次读数统计: A/B 两条路径各一轮 */
  printf("hr_sensor: [3/4] PART_ID(0x%02x) 各 %d 次读数统计 @%ukHz\n",
         PART_ID_REG, ST_NREADS, I2C_FREQ / 1000);

  for (i = 0; i < ST_NREADS; i++)
    {
      if (!st_budget_left(t0))
        {
          budget_hit = 1;
          break;
        }

      vals[i] = 0;
      if (i2c_read_regs_combined(fd, PART_ID_REG, &vals[i], 1) == 0)
        {
          ok_a++;
          if (vals[i] == PART_ID_VAL)
            {
              hit_a++;
            }
        }
    }

  st_dump_vals("A 组合读(NOSTOP+READ, 旧主路径/i2c 工具默认)",
               vals, i, hit_a, ok_a);

  for (i = 0; i < ST_NREADS; i++)
    {
      if (!st_budget_left(t0))
        {
          budget_hit = 1;
          break;
        }

      vals[i] = 0;
      if (i2c_read_regs_split(fd, PART_ID_REG, &vals[i], 1) == 0)
        {
          ok_b++;
          if (vals[i] == PART_ID_VAL)
            {
              hit_b++;
            }
        }
    }

  st_dump_vals("B 分两次传输(写地址+STOP 再读, 等价 i2c get -s)",
               vals, i, hit_b, ok_b);

  /* [4/4] 结论: 二值结论(退出码)按**主读路径**给出 —— 也就是 `hr_sensor start` 实际
   * 会用的那条(HR_USE_SPLIT_READ 决定), 便于脚本/人一眼判断"能不能开始采集"。
   * A/B 两条路径的原始读数照旧全部打印、不做修饰: 本板现状是 A 失败/B 成功,
   * 这个差异是下层驱动的已知缺陷, 也是回归证据, 不能为了好看而抹掉。 */
  g_i2c_freq = I2C_FREQ;

  printf("hr_sensor: [4/4] 结论 (selftest 耗时 %ums, 预算 %ums)\n",
         (unsigned)(now_ms() - t0), (unsigned)ST_BUDGET_MS);

  if (budget_hit)
    {
      printf("hr_sensor:   [注意] 已超出 %ums 预算, 自检提前结束(不再发起新传输);"
             " 结论只覆盖已完成的部分\n", (unsigned)ST_BUDGET_MS);
    }

  if (hit_a == ST_NREADS && wr_a == 3)
    {
      printf("hr_sensor:   bus OK —— PART_ID 8/8 命中(路径A) 且 0x%02x 写-回读 3/3 一致\n",
             REG_LED1_PA);
      printf("hr_sensor:   下一步: 可以直接 hr_sensor start; 拼接的 SCL 仍建议重做并加"
             " 2.2~4.7k 外部上拉到 3V3, 留电气裕量\n");
    }
  else if (hit_b == ST_NREADS && wr_b == 3)
    {
#if HR_USE_SPLIT_READ
      printf("hr_sensor:   bus OK(主读路径B) —— 路径B 8/8 命中且写-回读 3/3 一致;"
             " 路径A(组合读) 命中 %d/8\n", hit_a);
      printf("hr_sensor:   判定: 电气通路是好的。路径A 失败是本板下层驱动的已知缺陷"
             "(sifli_i2c.c:492-519 只认第二条消息带 I2C_M_NOSTART, 组合读会丢寄存器地址"
             "消息 -> :556-558), 不是接线问题\n");
      printf("hr_sensor:   下一步: 可以 hr_sensor start (app 已在用路径B);"
             " 路径A 保留为回归证据, 下层驱动修好后应能命中 8/8; 拼接的 SCL 仍建议重做并加"
             " 2.2~4.7k 外部上拉到 3V3, 留电气裕量\n");
#else
      printf("hr_sensor:   bus UNSTABLE(路径A), 但路径B 8/8 命中且写-回读 3/3 一致\n");
      printf("hr_sensor:   判定: 电气通路是好的, 问题在**软件读路径** —— 组合读"
             "(NOSTOP+READ)在本板下层驱动里丢了寄存器地址消息"
             "(sifli_i2c.c:492-519 只认第二条消息带 I2C_M_NOSTART)\n");
      printf("hr_sensor:   下一步: 把 HR_USE_SPLIT_READ 置 1 走路径B(或给 msg[1] 补"
             " I2C_M_NOSTART), 再跑一次 selftest 确认; 顺便重做 SCL 接头\n");
#endif
    }
  else
    {
      printf("hr_sensor:   bus UNSTABLE —— 两条路径都没稳定读到 0x15"
             " (A %d/8, B %d/8; 写-回读一致 A %d/3, B %d/3)\n",
             hit_a, hit_b, wr_a, wr_b);
      printf("hr_sensor:   下一步: ①换/重做 SCL 拼接处(压接/焊点/杜邦线)"
             " ②SDA/SCL 各加 2.2~4.7k 上拉到 3V3 ③换更短的线"
             " ④仍不行换模块\n");
    }

  /* 交叉验证提示(所有结论分支都打): i2c 工具默认走组合读(路径A), 本板不可用;
   * 必须加 -s 才拆成"写地址+STOP 再单独读"(i2c_get.c:195-206) = 路径B。
   * 不加 -s 读出来的是常数(本次 0x26), 会把人带偏。 */
  printf("hr_sensor:   交叉验证请用: i2c get -b 1 -a 0x%02x -r <reg> -s"
         "  (必须带 -s: 本板默认的组合读不可用; 与本 app 同速可再给 -f %u)\n",
         MAX30102_ADDR, (unsigned)I2C_FREQ);

  close(fd);
#if HR_USE_SPLIT_READ
  return (hit_b == ST_NREADS && wr_b == 3) ? 0 : 1;
#else
  return (hit_a == ST_NREADS && wr_a == 3) ? 0 : 1;
#endif
}

/* ── 血氧 (SpO2) ─────────────────────────────────────────────────────
 *
 * 为什么以前完全没有: 心率路径配的是 MODE_HR(0x02) —— **只有红光单通道**,
 *   而且 IR 的驱动电流寄存器 LED2_PA(0x0D) 虽然定义了却从未写入。
 *   只有红光拿不到 R 比值, 结构上就不可能算血氧。
 *
 * 原理 (R 比值法):
 *     R = (AC_red / DC_red) / (AC_ir / DC_ir)
 *     SpO2 ≈ 110 - 25 * R
 *   AC 用窗口内峰峰值, DC 用窗口均值。这个 110/25 是常见端点近似,
 *   **精确系数必须按具体传感器模块标定** —— 所以下面的结果只作参考,
 *   R 值本身才是可比的物理量。
 *
 * 刻意独立于 hr_drain()/hr_feed(): 自己配模式、自己读 FIFO、结束恢复 MODE_HR。
 * 这样即使血氧这一版还不准, 也不会碰坏已经调通的心率热路径。
 */
#define SPO2_ROUNDS        300     /* 轮询轮数, 每轮 50ms => 约 15s 采集 */
#define SPO2_POLL_MS       50
#define SPO2_PER_POLL      4       /* 每轮最多取 4 个样本 */
#define SPO2_MIN_SAMPLES   200     /* 少于这么多样本不出结论 */
#define SPO2_LED2_PA       0x2F    /* IR LED 电流, 与红灯同档(见 LED_PA 注释) */

/* ★ 两个关键修正(2026-10-18 第一次实测后):
 *
 * 1. SPO2_SKIP_SAMPLES —— 必须跳过 LED 上电稳定期。
 *    第一次实测(未跳过)拿到 RED dc=151454 ac=70171 => AC/DC = 46%,
 *    而**真实 PPG 的 AC/DC 只有 1~5%**。46% 不是脉搏, 是 IR LED 第一次
 *    点亮后的建立过程 + 手指接触压力变化造成的整体漂移。
 *
 * 2. AC 必须"分段峰峰值取中位数", 不能用整段 min/max。
 *    整段 min/max 会把 10 秒里的任何缓慢漂移都算成 AC(第一次实测
 *    min=82666 max=152837, 近乎 2 倍摆动)。改成每 SPO2_SEG 个样本
 *    (~0.5s)算一次峰峰值, 再对所有段取中位数 —— 缓慢漂移在每段内被
 *    抵消, 而脉搏(1Hz 量级)在 0.5s 段里正好有一两个完整周期。
 */
#define SPO2_SKIP_SAMPLES  200     /* 跳过前 200 样本(~2s)的 LED 稳定期 */
#define SPO2_SEG           50      /* 每段样本数(~0.5s @100Hz) */
#define SPO2_SEG_MAX       24      /* 最多累计的段数(取中位数用) */

/* ── 供上层读取最近一次血氧结果 ────────────────────────────────────
 *
 * 必须**非 static**: max30102.c 编进 libapps_hr_sensor.a, 而调用方
 * (apps/packages/ai_agent 的 spo2_measure.c) 在 libapps_ai_agent.a,
 * 两者同在链接行的 --start-group 里, 全局符号可以互相解析。
 *
 * 之所以走"全局变量"而不是返回值: 上层的点击回调跑在 LVGL render 线程,
 * 不能在那里阻塞 19 秒做采集, 只能起 worker 线程去调 hr_sensor_main(),
 * 而那个入口是 main() 形态只返回 int, 拿不到具体数值。 */
float g_max30102_spo2_r   = 0.0f;   /* 最近一次 R 比值 */
float g_max30102_spo2_pct = 0.0f;   /* 最近一次 SpO2 估算(%) */
int   g_max30102_spo2_ok  = 0;      /* 1 = 本次结果有效(调制比/R 都合理) */

/* 统一收尾：关灯 → 关 fd → 清 inited。
 *
 * 为什么必须是"三件一起做"（2026-10-19 对抗审查指出）：
 *   - 只写 SHDN 不 close → 每测一次漏一个 fd（本函数在 ai_agent 的 pthread 里跑，
 *     进程不退出，fd 不会被自动回收），连测十几次后 open 失败，功能彻底失效；
 *   - close 了却不把 inited 置 false → 下一次 hr_start 看到 inited==true 会**跳过
 *     open+init**（:2766），拿着 -1 或一个已被复用成别的设备的 fd 去 ioctl。
 *     最坏情况那个号被复用成 /dev/i2c0，就是往**触摸总线**上写 MAX30102 寄存器。
 *
 * 顺序也有讲究：先写 SHDN 再 close —— close 之后 fd 就不可用了，灯会一直亮着。 */
static void sensor_power_off(void)
{
  if (g_hr.fd >= 0)
    {
      i2c_write_reg(g_hr.fd, REG_MODE_CONFIG, MODE_SHDN);
      close(g_hr.fd);
      g_hr.fd = -1;
    }

  /* 写了 SHDN 之后 MODE 位归零，下次必须重新 init 才能进 HR 模式，
   * 所以这里必须把 inited 清掉，让 hr_start 走完整的 open+init。 */
  g_hr.inited = false;
}

static int spo2_measure(void)
{
  int rc;
  /* 分段峰峰值的中位数收集(去漂移) */
  static uint32_t seg_r[SPO2_SEG_MAX], seg_i[SPO2_SEG_MAX];
  uint32_t cur_rmin = 0, cur_rmax = 0, cur_imin = 0, cur_imax = 0;
  int cur_n = 0;
  int nseg = 0;
  uint64_t rsum = 0, isum = 0;
  uint32_t n = 0;              /* 计入统计的样本数(已跳过稳定期) */
  uint32_t nseen = 0;          /* 读到的总样本数(含待跳过的) */
  int round, k;
  int ret = 0;

  /* 每次开始都先把"有没有有效结果"清掉, 免得上层读到上一轮的旧值
   * (与 g_hr_pub 那段注释是同一类陷阱: flat build 静态量跨进程保留)。 */
  g_max30102_spo2_ok  = 0;
  g_max30102_spo2_r   = 0.0f;
  g_max30102_spo2_pct = 0.0f;

  if (g_hr.running)
    {
      printf("spo2: 心率采集正在运行, 先 hr_sensor stop\n");
      return 1;
    }

  /* g_hr.running 只覆盖"心率线程已经在跑"，覆盖不了"hr_start 正在初始化"
   * 这段窗口（running 是 init 之后才置位的）。归属锁从根上堵住这个 TOCTOU。 */
  if (!sensor_claim(SENSOR_OWNER_SPO2))
    {
      printf("spo2: 传感器被心率采集占用, 稍后再试\n");
      return 1;
    }

  /* ★ 每次调用都重新 open + 初始化, **不能**用 g_hr.inited 做守卫。
   *
   * 原因(2026-10-18 实测踩到): flat build 里应用不是"重新加载", 只是同一份
   * 二进制里的 main 再跑一次 —— static 变量(g_hr)保留上一轮的值, 但**上一轮
   * 进程退出时内核已经关掉了它的 fd**。于是 g_hr.inited==true 而 g_hr.fd 是个
   * 失效编号, 第二次起写寄存器全部失败, 表现为:
   *     spo2: 配置 MODE_SPO2 失败
   * 这与本文件 g_hr_pub 那段注释记录的是同一类陷阱("只有开机后第一次能出数")。
   *
   * 刻意**不 close() 旧 fd**: 它早随上一轮进程退出关闭, 编号可能已被别的文件
   * 复用, close 会误关别人的(同 g_hr_pub 的处理)。直接覆盖即可。 */
  g_hr.fd = open(I2C_DEV_PATH, O_RDWR);
  if (g_hr.fd < 0)
    {
      printf("spo2: open %s failed: %d\n", I2C_DEV_PATH, errno);
      return 1;
    }

  if (max30102_init(g_hr.fd) < 0)
    {
      printf("spo2: 初始化失败 (检查接线: VIN=3V3, GND, SCL=PA41, SDA=PA42)\n");
      /* init 失败时器件可能已经被写进 MODE_HR（init 末尾那一步在检查 fails 之前），
       * LED 可能还亮着 —— 一并交给统一收尾去关。 */
      sensor_power_off();
      return 1;
    }

  g_hr.inited = true;

  /* 切到 RED+IR 双通道, 并把 IR 的驱动电流写上(这步是旧代码从来没做过的) */
  if (i2c_write_reg(g_hr.fd, REG_LED2_PA, SPO2_LED2_PA) < 0 ||
      i2c_write_reg(g_hr.fd, REG_MODE_CONFIG, MODE_SPO2) < 0)
    {
      printf("spo2: 配置 MODE_SPO2 失败\n");
      goto out_fail;
    }

  /* 清 FIFO, 丢掉切换模式前后的旧样本 */
  i2c_write_reg(g_hr.fd, REG_FIFO_WR_PTR, 0x00);
  i2c_write_reg(g_hr.fd, REG_FIFO_OVF, 0x00);
  i2c_write_reg(g_hr.fd, REG_FIFO_RD_PTR, 0x00);

  printf("spo2: 采集中(%u 轮 x %ums, 手指贴稳别动)...\n",
         (unsigned)SPO2_ROUNDS, (unsigned)SPO2_POLL_MS);

  for (round = 0; round < SPO2_ROUNDS; round++)
    {
      uint8_t st = 0;

      usleep(SPO2_POLL_MS * 1000);

      /* 有数据才读: 状态位没置就跳过这一轮 */
      if (i2c_read_reg(g_hr.fd, REG_INT_STATUS1, &st) < 0)
        {
          ret = -1;
          break;
        }

      if ((st & 0xC0) == 0)
        {
          continue;
        }

      for (k = 0; k < SPO2_PER_POLL; k++)
        {
          uint8_t b[6];
          uint32_t red, ir;

          /* MODE_SPO2 下一个样本 6 字节: RED(3) + IR(3), 各自 18bit 大端 */
          if (i2c_read_regs(g_hr.fd, REG_FIFO_DATA, b, 6) < 0)
            {
              ret = -1;
              goto done;
            }

          red = (((uint32_t)b[0] << 16) | ((uint32_t)b[1] << 8) | b[2]) & 0x3FFFFu;
          ir  = (((uint32_t)b[3] << 16) | ((uint32_t)b[4] << 8) | b[5]) & 0x3FFFFu;

          if (red == 0 || ir == 0)
            {
              continue;     /* LED 还没稳定, 丢掉 */
            }

          /* 跳过 LED 上电稳定期(见 SPO2_SKIP_SAMPLES 的注释) */
          nseen++;
          if (nseen <= SPO2_SKIP_SAMPLES)
            {
              continue;
            }

          if (cur_n == 0)
            {
              cur_rmin = cur_rmax = red;
              cur_imin = cur_imax = ir;
            }
          else
            {
              if (red < cur_rmin) cur_rmin = red;
              if (red > cur_rmax) cur_rmax = red;
              if (ir  < cur_imin) cur_imin = ir;
              if (ir  > cur_imax) cur_imax = ir;
            }

          rsum += red;
          isum += ir;
          n++;
          cur_n++;

          /* 满一段就记下这一段的峰峰值(段内漂移被抵消) */
          if (cur_n >= SPO2_SEG)
            {
              if (nseg < SPO2_SEG_MAX)
                {
                  seg_r[nseg] = cur_rmax - cur_rmin;
                  seg_i[nseg] = cur_imax - cur_imin;
                  nseg++;
                }

              cur_n = 0;
            }
        }
    }

done:
  /* 这里原来写 "恢复成心率模式"，但那样 LED 会继续按心率模式的占空比闪烁
   * （实测约 1.5mA 平均），而本次测量已经结束 —— 没有任何理由继续耗电。
   * 现在统一由 out:/out_fail: 走 sensor_power_off()（SHDN + close + 清 inited）。
   * 反正写了 SHDN 之后 MODE 位归零，下次必须重新 init，不需要"恢复"中间态。 */

  if (ret < 0)
    {
      printf("spo2: 读 FIFO 失败 (n=%u)\n", (unsigned)n);
      goto out_fail;
    }

  if (n < SPO2_MIN_SAMPLES)
    {
      printf("spo2: 样本太少(n=%u, 需要 >=%u) —— 手指没贴稳或 LED 没亮\n",
             (unsigned)n, (unsigned)SPO2_MIN_SAMPLES);
      goto out_fail;
    }

  {
    double dc_red = (double)rsum / (double)n;
    double dc_ir  = (double)isum / (double)n;
    double ac_red, ac_ir, ratio, ratio_r, spo2;
    double mod_red, mod_ir;
    uint32_t mr[SPO2_SEG_MAX], mi[SPO2_SEG_MAX];
    int i, j;

    if (nseg < 3)
      {
        printf("spo2: 有效分段太少(nseg=%d) —— 采集时间不够或手指没贴稳\n", nseg);
        goto out_fail;
      }

    /* 段峰峰值的中位数 —— 对"某一段有动作伪迹"免疫 */
    memcpy(mr, seg_r, sizeof(uint32_t) * (size_t)nseg);
    memcpy(mi, seg_i, sizeof(uint32_t) * (size_t)nseg);

    for (i = 1; i < nseg; i++)
      {
        uint32_t kk = mr[i];
        j = i - 1;
        while (j >= 0 && mr[j] > kk) { mr[j + 1] = mr[j]; j--; }
        mr[j + 1] = kk;
      }

    for (i = 1; i < nseg; i++)
      {
        uint32_t kk = mi[i];
        j = i - 1;
        while (j >= 0 && mi[j] > kk) { mi[j + 1] = mi[j]; j--; }
        mi[j + 1] = kk;
      }

    ac_red = (double)mr[nseg / 2];
    ac_ir  = (double)mi[nseg / 2];

    printf("spo2: n=%u nseg=%d  RED dc=%.0f ac(中位段)=%.0f\n",
           (unsigned)n, nseg, dc_red, ac_red);
    printf("spo2:                 IR  dc=%.0f ac(中位段)=%.0f\n",
           dc_ir, ac_ir);

    if (dc_red <= 0.0 || dc_ir <= 0.0)
      {
        printf("spo2: DC 为 0 —— LED 没亮或读不到\n");
        goto out_fail;
      }

    mod_red = ac_red / dc_red;
    mod_ir  = ac_ir / dc_ir;

    printf("spo2: 调制比 AC/DC  red=%.3f%%  ir=%.3f%%\n",
           mod_red * 100.0, mod_ir * 100.0);

    /* ★ 生理范围检查: 真实 PPG 的 AC/DC 是 1~5%(最多到 10%)。
     * 第一次实测(整段 min/max)得到 46% —— 那明显不是脉搏, 是漂移/伪迹。
     * 这里把"调制比不合理"显式判为信号不可用, 而不是硬算一个 SpO2 出来。 */
    if (mod_red < 0.003 || mod_red > 0.15 ||
        mod_ir  < 0.003 || mod_ir  > 0.15)
      {
        printf("spo2: 调制比不在生理范围(0.3%%~15%%) —— 信号不可用, 不出 SpO2\n");
        goto out_fail;
      }

    ratio_r = mod_red;
    ratio   = mod_red / mod_ir;

    printf("spo2: R = %.3f\n", ratio);

    if (ratio < 0.3 || ratio > 1.5)
      {
        printf("spo2: R 超出合理范围(0.3~1.5), 不予采信\n");
        goto out_fail;
      }

    /* 110 - 25*R 是通用端点近似, **未按本模块标定** —— 只作参考 */
    spo2 = 110.0 - 25.0 * ratio;
    if (spo2 > 100.0) spo2 = 100.0;
    if (spo2 < 70.0)  spo2 = 70.0;

    printf("spo2: SpO2 ~ %.0f%%  (未标定估算; 物理量 R=%.3f 更可信)\n",
           spo2, ratio);

    /* 只有走到这里才算"结果有效" —— 前面每一条提前返回都对应
     * "信号不可用", 上层据此给用户诚实提示, 而不是编一个数。 */
    g_max30102_spo2_r   = (float)ratio;
    g_max30102_spo2_pct = (float)spo2;
    g_max30102_spo2_ok  = 1;
  }

  rc = 0;
  goto out;

out_fail:
  rc = 1;

out:
  /* 唯一的收尾出口 —— 所有路径（含 7 个提前 return）都经过这里。
   * 这是 2026-10-19 修的那个 bug：原来只有成功路径会走到函数末尾，
   * 失败路径直接 return，LED 就那样一直亮着。
   *
   * 顺序: 先关硬件(sensor_power_off 内部写 SHDN + close + 清 inited),
   *       再放归属锁 —— 反过来的话, 下一个 owner 可能在我们还没关灯时
   *       就进了 hr_start, 又变成两个流程抢同一颗传感器。 */
  sensor_power_off();
  sensor_release(SENSOR_OWNER_SPO2);
  return rc;
}

int main(int argc, FAR char *argv[])
{
  const char *cmd = (argc > 1) ? argv[1] : "status";

  /* 只在"从未初始化且未运行"时清零 —— hr_sensor stop 是一个【新进程】(新任务组),
   * 无条件 memset 会把正在运行的采集状态(running/inited/fd)清掉。真机实测表现为:
   * stop 打印"未运行"并直接返回, SHDN 永远写不出去, LED 常亮(P0 验证 2026-10-13)。
   * 加守卫后 stop 才能真正把 running 置 false, 由采集线程自己走退出路径写 SHDN。 */
  if (!g_hr.inited && !g_hr.running)
    {
      memset(&g_hr, 0, sizeof(g_hr));
      g_hr.fd = -1;

#ifdef HAVE_HR_TOPIC
      /* ★ g_hr_pub 是**文件级 static**, 不在 memset 范围内 —— 而 flat build 下
       * 应用不是"重新加载", 只是同一二进制里的函数再跑一遍, 静态变量保留上一轮
       * 的值。于是从第二轮起 `if (g_hr_pub < 0)` 为假、**advertise 被整段跳过**,
       * 采集线程拿着上一轮那个**已随进程退出而失效的 fd** 去 publish(EBADF),
       * 订阅端永远收不到 —— 算法侧却一切正常(gate=ok / q=100 / bpm 有值)。
       * 真机症状就是"只有开机后第一次测心率能出数, 之后每次都是 timeout"。
       * 这里强制复位, 让每一轮都重新 advertise。
       * 刻意**不 close() 旧 fd**: 它早已随上一轮进程退出关闭, 编号可能已被别的
       * 文件复用, close 会误关别人的。 */
      g_hr_pub = -1;
#endif
    }

  /* 打开总线之前先把 PA41/PA42 复用成 I2C2 (老固件里没人配它) */
  max30102_pinmux();

  if (strcmp(cmd, "start") == 0)
    {
      return hr_start();
    }
  else if (strcmp(cmd, "stop") == 0)
    {
      return hr_stop();
    }
  else if (strcmp(cmd, "status") == 0)
    {
      return hr_status();
    }
  else if (strcmp(cmd, "info") == 0)
    {
      printf("hr_sensor: MAX30102 @ %s, addr 0x%02x, mode=HR(RED)\n",
             I2C_DEV_PATH, MAX30102_ADDR);
      printf("           寄存器: 0x08=0x%02x 0x0A=0x%02x 0x09=0x%02x 0x0C=0x%02x\n",
             FIFO_CFG, SPO2_CFG, MODE_HR, LED_PA);
#ifdef HAVE_HR_TOPIC
      printf("           uORB: algo_heartrate 发布已启用\n");
#else
      printf("           uORB: 未启用 (需 CONFIG_UORB + CONFIG_FITNESS_ALGO)\n");
#endif
      return 0;
    }
  else if (strcmp(cmd, "selftest") == 0)
    {
      /* 只诊断: 不开采集、不改 uORB; 结论见 [4/4] 行 */
      return hr_selftest();
    }
  else if (strcmp(cmd, "spo2") == 0)
    {
      /* 血氧: 切 MODE_SPO2(RED+IR) 单独采集一轮, 结束恢复 MODE_HR */
      return spo2_measure();
    }

  printf("用法: hr_sensor start|stop|status|info|selftest|spo2\n");
  return 1;
}
