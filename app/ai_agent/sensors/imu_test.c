/****************************************************************************
 * imu_test.c - 读 /dev/mpu0 的原始六轴数据（NSH 命令）
 *
 * 为什么需要这个命令：
 *   板子上没有现成的读 IMU 的工具。`i2ctool` 在这块板子上对**任何**总线
 *   都返回 "Transfer failed: 1"（实测连已知能工作的 MAX30102 那条也不行），
 *   所以不能用它验证。只能自己读字符设备。
 *
 * 用法：
 *   imu          读一行打印
 *   imu 50       连续读 50 次（间隔 20ms），用来观察晃动时数值变化
 *
 * 数据来自 NuttX 的 mpu60x0 驱动（nuttx/drivers/sensors/mpu60x0.c）。
 * read() 返回的是驱动内部的一个结构体，内容为原始 int16 的
 * accel x/y/z + gyro x/y/z + temp。具体布局由驱动的 buf 结构决定，
 * 这里按最保守的方式：整块读出来后按 int16 打印前 8 个值。
 *
 * Team 181 - Contest 2026
 ****************************************************************************/

#include <nuttx/config.h>

#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <nuttx/i2c/i2c_master.h>

#define IMU_DEV   "/dev/mpu0"

/* 由板级初始化写入的 HAL_PIN_Set 返回值（-99 = 该代码段没跑）。
 * 板级那段在控制台就绪之前执行，它的 syslog 会丢，只能这样带出来。 */
extern int g_imu_pinmux_scl;
extern int g_imu_pinmux_sda;
#define MAX_WORDS 16


/* ── 原始 I2C 探测 ────────────────────────────────────────────────
 *
 * 用和 MAX30102 一样的"分两次传输"方式读寄存器：先只写寄存器地址
 * （带 STOP），再单独发一次读。这是这块板子的 SiFli I2C 驱动唯一支持
 * 的读法；NuttX 规范的组合读（NOSTOP+READ）在这里会被忽略掉寄存器地址。
 *
 * 为什么需要它：mpu60x0 驱动读失败时不会报错（mpu_read 忽略了
 * __mpu_read_imu 的返回值），所以"全是 0"既可能是驱动问题也可能是
 * 芯片根本没响应。用这个函数直接看 WHO_AM_I，能把两种情况分开。
 */

static int imu_i2c_probe_at(int fd, uint8_t addr, uint8_t reg, uint8_t *out)
{
  int ret;
  struct i2c_msg_s msg;
  struct i2c_transfer_s xfer;

  /* step 1: 写寄存器地址 */
  msg.frequency = 100000;
  msg.addr      = addr;
  msg.flags     = 0;
  msg.buffer    = &reg;
  msg.length    = 1;

  xfer.msgv = &msg;
  xfer.msgc = 1;

  ret = ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer);
  if (ret < 0)
    {
      close(fd);
      return -1;
    }

  /* step 2: 读 */
  msg.flags  = I2C_M_READ;
  msg.buffer = out;
  msg.length = 1;

  xfer.msgv = &msg;
  xfer.msgc = 1;

  ret = ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer);

  if (ret < 0)
    {
      return -1;
    }

  return 0;
}

/* 扫描整条总线，看看有哪些地址会 ACK。
 * 一次零长度写就是最标准的"探活"手法 —— 有 ACK 就会返回 0。 */
static void imu_i2c_scan(int fd)
{
  int addr;
  int found = 0;

  printf("imu: scanning 0x57 (MAX30102, control), 0x68, 0x69 ...\n");

  /* 只探三个地址。全量扫描 0x08..0x77 在这块板子上会卡死 ——
   * 对不存在地址的传输会长时间阻塞（实测跑了 46 秒还没完）。
   *
   * 0x57 是关键对照：MAX30102 就挂在这条总线上，它必须应答。
   * 如果 0x57 应答而 0x68/0x69 不应答，说明扫描代码是对的，
   * MPU6050 确实没接到这条总线上。 */
  for (addr = 0x57; addr <= 0x69; addr++)
    {
      if (addr != 0x57 && addr != 0x68 && addr != 0x69)
        {
          continue;
        }

      struct i2c_msg_s msg;
      struct i2c_transfer_s xfer;
      uint8_t dummy = 0;

      msg.frequency = 100000;
      msg.addr      = (uint16_t)addr;
      msg.flags     = 0;
      msg.buffer    = &dummy;
      msg.length    = 1;

      xfer.msgv = &msg;
      xfer.msgc = 1;

      if (ioctl(fd, I2CIOC_TRANSFER, (unsigned long)&xfer) == 0)
        {
          printf("imu:   found device at 0x%02x\n", addr);
          found++;
        }
    }

  printf("imu: scan done, %d device(s) found\n", found);
}

int main(int argc, FAR char *argv[])
{
  int fd;
  int count = 1;
  int i;
  int16_t words[MAX_WORDS];

  if (argc > 1)
    {
      count = atoi(argv[1]);
      if (count < 1)   count = 1;
      if (count > 500) count = 500;
    }

  /* 先做原始 I2C 探测：读 WHO_AM_I(0x75)，正常应返回 0x68。
   * 这一步能把"芯片没响应"和"驱动读失败"彻底分开。 */
  {
    int ifd = open("/dev/i2c2", O_RDWR);
    uint8_t who = 0;

    if (ifd >= 0)
      {
        /* 先复位总线。器件在一次失败传输后可能 hold 住 SDA，
         * 不复位的话后续所有传输都会失败/阻塞。
         * I2CIOC_RESET 在 CONFIG_I2C_RESET=y 时可用（本构建已开）。 */
        if (ioctl(ifd, I2CIOC_RESET) < 0)
          {
            printf("imu: I2CIOC_RESET failed: errno=%d\n", errno);
          }
        else
          {
            printf("imu: bus reset OK\n");
          }

        printf("imu: board pinmux ret: PA39/SCL=%d  PA40/SDA=%d "
               "(-1 means HAL_PIN_Set REJECTED the pad/function pair)\n",
               g_imu_pinmux_scl, g_imu_pinmux_sda);

        /* 对照实验：i2c1 上挂着 MAX30102（0x57），它必须能被读到。
         * 如果这里也读不到，说明这套探测代码本身不可靠，
         * 那么 i2c2 上"0 个设备"的结论就不成立。 */
        /* 对照：触摸芯片 FT6146 在 /dev/i2c0 的 0x38 上，触摸一直可用，
         * 所以它必然应答。用它来验证这套探测代码本身。
         * （之前拿 MAX30102 做对照是错的：PA41/PA42 的 pinmux 写在
         *  max30102.c 内部，只有 hr_sensor 跑起来才生效，app 没跑时
         *  那条总线根本没有引脚。） */
        {
          int cfd = open("/dev/i2c0", O_RDWR);

          if (cfd >= 0)
            {
              uint8_t cid = 0;

              printf("imu: CONTROL FT6146 at 0x38 on /dev/i2c0 "
                     "(touch panel, must respond): ");
              if (imu_i2c_probe_at(cfd, 0x38, 0xA3, &cid) == 0)
                {
                  printf("OK, id=0x%02x  <-- probe code WORKS\n", cid);
                }
              else
                {
                  printf("no response  <-- probe code is broken\n");
                }

              close(cfd);
            }
          else
            {
              printf("imu: CONTROL cannot open /dev/i2c0: %d\n", errno);
            }
        }

        imu_i2c_scan(ifd);

        printf("imu: WHO_AM_I at 0x68: ");
        if (imu_i2c_probe_at(ifd, 0x68, 0x75, &who) == 0)
          {
            printf("0x%02x %s\n", who,
                   who == 0x68 ? "(MPU6050 OK)" : "(unexpected)");
          }
        else
          {
            printf("no response\n");
          }

        printf("imu: WHO_AM_I at 0x69: ");
        if (imu_i2c_probe_at(ifd, 0x69, 0x75, &who) == 0)
          {
            printf("0x%02x %s\n", who,
                   who == 0x68 ? "(MPU6050 OK, AD0 tied high)" : "(unexpected)");
          }
        else
          {
            printf("no response\n");
          }

        close(ifd);
      }
  }

  fd = open(IMU_DEV, O_RDONLY);
  if (fd < 0)
    {
      printf("imu: open %s failed: %d\n", IMU_DEV, errno);
      return 1;
    }

  for (i = 0; i < count; i++)
    {
      ssize_t n;
      int j;

      memset(words, 0, sizeof(words));
      n = read(fd, words, sizeof(words));
      if (n <= 0)
        {
          printf("imu: read failed: %d (errno %d)\n", (int)n, errno);
          break;
        }

      /* 两种解释都打出来，一次判断大端/小端：
       *   raw     = 驱动按内存顺序直接解释的 int16
       *   swapped = 把两个字节对调（MPU6050 寄存器是大端） */
      printf("imu[%d] raw:", i);
      for (j = 0; j < 7; j++)
        {
          printf(" %d", words[j]);
        }
      printf("\n");

      printf("imu[%d] be :", i);
      for (j = 0; j < 7; j++)
        {
          uint16_t u = (uint16_t)words[j];
          int16_t  s = (int16_t)((u >> 8) | (u << 8));

          printf(" %d", s);
        }
      printf("\n");

      if (count > 1)
        {
          usleep(20 * 1000);
        }
    }

  close(fd);
  return 0;
}