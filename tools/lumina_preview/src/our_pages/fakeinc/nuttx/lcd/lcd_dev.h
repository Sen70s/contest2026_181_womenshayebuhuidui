/* host 预览用最小 NuttX LCD ioctl 定义。
 * settings_page.c 只用这两个命令号；host 上 open("/dev/lcd0") 必然失败，
 * ioctl 不会真的执行，所以具体数值不重要，保持与 NuttX 头文件同形即可。 */
#ifndef PREVIEW_FAKE_NUTTX_LCD_DEV_H
#define PREVIEW_FAKE_NUTTX_LCD_DEV_H

#define LCDDEVIO_SETCONTRAST  1
#define LCDDEVIO_SETPOWER     2

#endif /* PREVIEW_FAKE_NUTTX_LCD_DEV_H */
