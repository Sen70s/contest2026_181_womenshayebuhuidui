# host 单元测试：直接编译 codec 源码，不链接 zblue/NuttX
#
# docs_ble 是个符号链接（-> contest2026_181_womenshayebuhuidui/docs_ble），而
# frameworks/ 是那个仓库的兄弟目录。写 ../../../frameworks 会被编译器按物理路径
# 解析、落到 contest2026_181_womenshayebuhuidui/frameworks（不存在）——而报错
# 恰好也是 "bnep_codec.h: No such file"，跟"头文件还没写"这个预期失败长得一样，
# 排查起来会很费时间。所以从 Makefile 自身位置推导，并打印出来自查。
MKFILE_DIR := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
CODEC_DIR := $(abspath $(MKFILE_DIR)/../../../../frameworks/connectivity/bluetooth/service/stacks/zephyr)
$(info CODEC_DIR = $(CODEC_DIR))

CFLAGS := -std=c11 -Wall -Wextra -Werror -g -O1 -fsanitize=address,undefined \
          -I$(CODEC_DIR)
LDFLAGS := -fsanitize=address,undefined

.PHONY: all test clean
all: test

bnep_codec_test: test_bnep_codec.c $(CODEC_DIR)/bnep_codec.c $(CODEC_DIR)/bnep_codec.h
	$(CC) $(CFLAGS) -o $@ test_bnep_codec.c $(CODEC_DIR)/bnep_codec.c $(LDFLAGS)

test: bnep_codec_test
	./bnep_codec_test

clean:
	rm -f bnep_codec_test
