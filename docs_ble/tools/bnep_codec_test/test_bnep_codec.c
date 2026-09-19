#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "bnep_codec.h"

static int g_fail;
#define CHECK(cond) do { \
    if (!(cond)) { printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); g_fail++; } \
} while (0)
#define CHECK_MEM(got, want, n) do { \
    if (memcmp((got), (want), (n)) != 0) { \
        printf("FAIL %s:%d  bytes differ\n  got : ", __FILE__, __LINE__); \
        for (size_t _i = 0; _i < (size_t)(n); _i++) printf("%02x ", ((const uint8_t*)(got))[_i]); \
        printf("\n  want: "); \
        for (size_t _i = 0; _i < (size_t)(n); _i++) printf("%02x ", ((const uint8_t*)(want))[_i]); \
        printf("\n"); g_fail++; \
    } \
} while (0)

/* tshark 已独立确认：01 01 02 11 16 11 15 */
static void test_setup_req(void)
{
    uint8_t out[16];
    const uint8_t want[] = { 0x01, 0x01, 0x02, 0x11, 0x16, 0x11, 0x15 };
    int n = bnep_encode_setup_req(out, sizeof(out),
                                  BNEP_UUID16_NAP, BNEP_UUID16_PANU);
    CHECK(n == 7);
    if (n == 7) { CHECK_MEM(out, want, 7); }

    /* 容量不足必须报错而不越界 */
    CHECK(bnep_encode_setup_req(out, 6, BNEP_UUID16_NAP, BNEP_UUID16_PANU)
          == BNEP_ERR_NOSPACE);
}

/* tshark 已独立确认：01 02 00 00 = Operation Successful */
static void test_setup_rsp(void)
{
    uint8_t out[16];
    const uint8_t want[] = { 0x01, 0x02, 0x00, 0x00 };
    int n = bnep_encode_setup_rsp(out, sizeof(out), BNEP_RSP_SUCCESS);
    CHECK(n == 4);
    if (n == 4) { CHECK_MEM(out, want, 4); }

    const uint8_t want_fail[] = { 0x01, 0x02, 0x00, 0x03 };
    n = bnep_encode_setup_rsp(out, sizeof(out), BNEP_RSP_INVALID_UUID_SIZE);
    CHECK(n == 4);
    if (n == 4) { CHECK_MEM(out, want_fail, 4); }
}

static void test_parse_control_setup_rsp(void)
{
    struct bnep_control info;
    /* 标准 4 字节响应，成功 */
    const uint8_t ok[] = { 0x02, 0x00, 0x00 };  /* 不含帧头字节 0x01 */
    CHECK(bnep_parse_control(ok, sizeof(ok), &info) == BNEP_OK);
    CHECK(info.msg_type == BNEP_CTRL_SETUP_CONN_RSP);
    CHECK(info.rsp_code == BNEP_RSP_SUCCESS);
    CHECK(info.is_success == true);

    /* 0x0003 = Invalid Service UUID Size —— 必须判失败 */
    const uint8_t bad[] = { 0x02, 0x00, 0x03 };
    CHECK(bnep_parse_control(bad, sizeof(bad), &info) == BNEP_OK);
    CHECK(info.rsp_code == BNEP_RSP_INVALID_UUID_SIZE);
    CHECK(info.is_success == false);

    /* 截断输入 */
    const uint8_t trunc[] = { 0x02, 0x00 };
    CHECK(bnep_parse_control(trunc, sizeof(trunc), &info) == BNEP_ERR_TRUNCATED);
}

static void test_parse_control_setup_req(void)
{
    struct bnep_control info;
    /* 对端要求我们当 NAP（不支持）：dst=NAP src=PANU，UUID Size=2 */
    const uint8_t req[] = { 0x01, 0x02, 0x11, 0x16, 0x11, 0x15 };
    CHECK(bnep_parse_control(req, sizeof(req), &info) == BNEP_OK);
    CHECK(info.msg_type == BNEP_CTRL_SETUP_CONN_REQ);
    CHECK(info.uuid_size == 2);
    CHECK(info.dst_uuid16 == BNEP_UUID16_NAP);
    CHECK(info.src_uuid16 == BNEP_UUID16_PANU);

    /* UUID Size = 16 也必须能解析（取低 16 位） */
    uint8_t req16[2 + 32] = { 0x01, 0x10 };
    req16[2 + 2] = 0x11; req16[2 + 3] = 0x15;          /* dst 128-bit 的 UUID16 段 */
    req16[2 + 16 + 2] = 0x11; req16[2 + 16 + 3] = 0x16;/* src */
    CHECK(bnep_parse_control(req16, sizeof(req16), &info) == BNEP_OK);
    CHECK(info.uuid_size == 16);
    CHECK(info.dst_uuid16 == 0x1115);
    CHECK(info.src_uuid16 == 0x1116);

    /* 非法 UUID Size */
    const uint8_t bad[] = { 0x01, 0x08, 0, 0, 0, 0, 0, 0, 0, 0 };
    CHECK(bnep_parse_control(bad, sizeof(bad), &info) == BNEP_ERR_BADUUID);
}

static void test_filter_and_unknown(void)
{
    uint8_t out[8];
    const uint8_t want_f[] = { 0x01, 0x04, 0x00, 0x01 };  /* NetType RSP, Unsupported */
    int n = bnep_encode_filter_rsp(out, sizeof(out),
                                   BNEP_CTRL_FILTER_NET_TYPE_RSP,
                                   BNEP_FILTER_RSP_UNSUPPORTED);
    CHECK(n == 4);
    if (n == 4) { CHECK_MEM(out, want_f, 4); }

    const uint8_t want_u[] = { 0x01, 0x00, 0x7f };
    n = bnep_encode_cmd_not_understood(out, sizeof(out), 0x7f);
    CHECK(n == 3);
    if (n == 3) { CHECK_MEM(out, want_u, 3); }
}

static void test_mac_byte_order(void)
{
    /* zblue bt_addr_t.val 是小端；MAC 是网络序，必须整体反转 */
    const uint8_t le48[6] = { 0x66, 0x55, 0x44, 0x33, 0x22, 0x11 };
    const uint8_t want[6] = { 0x11, 0x22, 0x33, 0x44, 0x55, 0x66 };
    uint8_t mac[6];
    bnep_mac_from_le48(mac, le48);
    CHECK_MEM(mac, want, 6);
}

/* 本机 MAC 与对端 MAC，供压缩判定使用 */
static const uint8_t LOCAL_MAC[6] = { 0xaa,0xbb,0xcc,0xdd,0xee,0x01 };
static const uint8_t PEER_MAC[6]  = { 0xaa,0xbb,0xcc,0xdd,0xee,0x02 };

static size_t build_eth(uint8_t *buf, const uint8_t dst[6],
                        const uint8_t src[6], uint16_t proto,
                        size_t payload_len)
{
    memcpy(&buf[0], dst, 6);
    memcpy(&buf[6], src, 6);
    buf[12] = (uint8_t)(proto >> 8);
    buf[13] = (uint8_t)(proto & 0xff);
    for (size_t i = 0; i < payload_len; i++) { buf[14 + i] = (uint8_t)i; }
    return 14 + payload_len;
}

static void test_encode_general(void)
{
    uint8_t eth[64], out[128];
    size_t eth_len = build_eth(eth, PEER_MAC, LOCAL_MAC, 0x0800, 20);

    /* compress=false -> 一律 General Ethernet：1 + 14 + 20 = 35 */
    int n = bnep_encode_eth(out, sizeof(out), eth, eth_len,
                            LOCAL_MAC, PEER_MAC, false);
    CHECK(n == 35);
    if (n == 35) {
        CHECK(out[0] == BNEP_GENERAL_ETHERNET);
        CHECK_MEM(&out[1], eth, eth_len);   /* 以太头与载荷原样跟随 */
    }
}

/* 这条是 DHCP / ARP 能否工作的核心：广播绝不能压缩 */
static void test_encode_broadcast_never_compressed(void)
{
    uint8_t eth[64], out[128];
    const uint8_t bcast[6] = { 0xff,0xff,0xff,0xff,0xff,0xff };
    size_t eth_len = build_eth(eth, bcast, LOCAL_MAC, 0x0806, 28);

    /* 即使显式要求压缩，广播也必须落到 General Ethernet */
    int n = bnep_encode_eth(out, sizeof(out), eth, eth_len,
                            LOCAL_MAC, PEER_MAC, true);
    CHECK(n == (int)(1 + eth_len));
    if (n > 0) {
        CHECK(out[0] == BNEP_GENERAL_ETHERNET);
        CHECK_MEM(&out[1], bcast, 6);       /* dst 必须真实保留为全 F */
    }
}

static void test_encode_multicast_never_compressed(void)
{
    uint8_t eth[64], out[128];
    const uint8_t mcast[6] = { 0x01,0x00,0x5e,0x00,0x00,0xfb };
    size_t eth_len = build_eth(eth, mcast, LOCAL_MAC, 0x0800, 10);
    int n = bnep_encode_eth(out, sizeof(out), eth, eth_len,
                            LOCAL_MAC, PEER_MAC, true);
    CHECK(n > 0);
    if (n > 0) { CHECK(out[0] == BNEP_GENERAL_ETHERNET); }
}

static void test_encode_compressed(void)
{
    uint8_t eth[64], out[128];
    /* dst == peer, src == local -> 两端都可省 -> Compressed: 1 + 2 + 20 = 23 */
    size_t eth_len = build_eth(eth, PEER_MAC, LOCAL_MAC, 0x0800, 20);
    int n = bnep_encode_eth(out, sizeof(out), eth, eth_len,
                            LOCAL_MAC, PEER_MAC, true);
    CHECK(n == 23);
    if (n == 23) {
        CHECK(out[0] == BNEP_COMPRESSED_ETHERNET);
        CHECK(out[1] == 0x08 && out[2] == 0x00);
    }
}

static void test_encode_bounds(void)
{
    uint8_t eth[BNEP_MAX_ETH_FRAME], out[BNEP_MIN_L2CAP_MTU];

    /* 满尺寸 1514 字节以太帧，General 编码 = 1515，必须 <= 1691 */
    size_t eth_len = build_eth(eth, PEER_MAC, LOCAL_MAC, 0x0800, 1500);
    CHECK(eth_len == 1514);
    int n = bnep_encode_eth(out, sizeof(out), eth, eth_len,
                            LOCAL_MAC, PEER_MAC, false);
    CHECK(n == 1515);

    /* 输出容量不足 -> NOSPACE，不得越界（ASan 会抓） */
    CHECK(bnep_encode_eth(out, 10, eth, eth_len,
                          LOCAL_MAC, PEER_MAC, false) == BNEP_ERR_NOSPACE);

    /* 输入短于 14 字节以太头 -> TRUNCATED */
    CHECK(bnep_encode_eth(out, sizeof(out), eth, 13,
                          LOCAL_MAC, PEER_MAC, false) == BNEP_ERR_TRUNCATED);
}

static void test_decode_roundtrip_all_types(void)
{
    uint8_t eth[128], bnep[256], back[256];
    const uint8_t *ctrl; size_t ctrl_len;

    /* General：dst 是第三方地址，src 虽然等于 local_mac 但这里传 compress=false，
     * 强制走不压缩路径。注意不能传 true——那样 src 是合法可省的，编码器会
     * 正确地发 DEST_ONLY(0x04)，而接收端重建 src 时只能填 peer_mac，
     * 于是 roundtrip 比较必然不等（实测 back[11] 得到 0x02 而非 0x01）。
     * 这不是缺陷：线路上省掉的 src 语义就是「发送方自己」，只是本测试同时
     * 固定了 local/peer 两个身份，无法表达它。 */
    const uint8_t other[6] = { 0x02,0x03,0x04,0x05,0x06,0x07 };
    size_t eth_len = build_eth(eth, other, LOCAL_MAC, 0x0800, 32);
    int n = bnep_encode_eth(bnep, sizeof(bnep), eth, eth_len,
                            LOCAL_MAC, PEER_MAC, false);
    CHECK(bnep[0] == BNEP_GENERAL_ETHERNET);
    int m = bnep_decode_eth(back, sizeof(back), bnep, (size_t)n,
                            LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len);
    CHECK(m == (int)eth_len);
    if (m == (int)eth_len) { CHECK_MEM(back, eth, eth_len); }

    /* Compressed：解码方向是「手机发给我们」，所以 dst=local, src=peer */
    eth_len = build_eth(eth, LOCAL_MAC, PEER_MAC, 0x0800, 32);
    bnep[0] = BNEP_COMPRESSED_ETHERNET;
    bnep[1] = 0x08; bnep[2] = 0x00;
    memcpy(&bnep[3], &eth[14], 32);
    m = bnep_decode_eth(back, sizeof(back), bnep, 3 + 32,
                        LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len);
    CHECK(m == (int)eth_len);
    if (m == (int)eth_len) { CHECK_MEM(back, eth, eth_len); }

    /* SrcOnly：帧内带 src，dst 补 local */
    bnep[0] = BNEP_COMPRESSED_ETHERNET_SRC_ONLY;
    memcpy(&bnep[1], PEER_MAC, 6);
    bnep[7] = 0x08; bnep[8] = 0x00;
    memcpy(&bnep[9], &eth[14], 32);
    m = bnep_decode_eth(back, sizeof(back), bnep, 9 + 32,
                        LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len);
    CHECK(m == (int)eth_len);
    if (m == (int)eth_len) { CHECK_MEM(back, eth, eth_len); }
}

static void test_decode_dest_only_and_broadcast(void)
{
    uint8_t bnep[128], back[256];
    const uint8_t *ctrl; size_t ctrl_len;
    const uint8_t bcast[6] = { 0xff,0xff,0xff,0xff,0xff,0xff };

    /* DestOnly 携带广播 dst，src 补 peer */
    bnep[0] = BNEP_COMPRESSED_ETHERNET_DEST_ONLY;
    memcpy(&bnep[1], bcast, 6);
    bnep[7] = 0x08; bnep[8] = 0x06;
    for (int i = 0; i < 28; i++) { bnep[9 + i] = (uint8_t)i; }

    int m = bnep_decode_eth(back, sizeof(back), bnep, 9 + 28,
                            LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len);
    CHECK(m == 14 + 28);
    if (m == 14 + 28) {
        CHECK_MEM(&back[0], bcast, 6);
        CHECK_MEM(&back[6], PEER_MAC, 6);
        CHECK(back[12] == 0x08 && back[13] == 0x06);
    }
}

static void test_decode_extension_headers(void)
{
    uint8_t bnep[128], back[256];
    const uint8_t *ctrl; size_t ctrl_len;
    size_t o = 0;

    /* Compressed + 3 个扩展头，最后一个的 more 位清零 */
    bnep[o++] = BNEP_COMPRESSED_ETHERNET | BNEP_EXT_FLAG;
    bnep[o++] = 0x08; bnep[o++] = 0x00;
    bnep[o++] = 0x80; bnep[o++] = 2; bnep[o++] = 0xa1; bnep[o++] = 0xa2;
    bnep[o++] = 0x80; bnep[o++] = 1; bnep[o++] = 0xb1;
    bnep[o++] = 0x00; bnep[o++] = 3; bnep[o++] = 0xc1; bnep[o++] = 0xc2;
    bnep[o++] = 0xc3;
    size_t hdr_end = o;
    for (int i = 0; i < 16; i++) { bnep[o++] = (uint8_t)(0x40 + i); }

    int m = bnep_decode_eth(back, sizeof(back), bnep, o,
                            LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len);
    CHECK(m == 14 + 16);
    if (m == 14 + 16) {
        CHECK_MEM(&back[0], LOCAL_MAC, 6);
        CHECK_MEM(&back[6], PEER_MAC, 6);
        CHECK_MEM(&back[14], &bnep[hdr_end], 16);
    }

    /* 扩展头长度撒谎，越过帧尾 -> TRUNCATED */
    bnep[4] = 200;
    CHECK(bnep_decode_eth(back, sizeof(back), bnep, o,
                          LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len)
          == BNEP_ERR_TRUNCATED);
}

static void test_decode_control_and_bounds(void)
{
    uint8_t back[256];
    const uint8_t *ctrl = NULL; size_t ctrl_len = 0;

    /* 控制帧：返回 IS_CONTROL 并指向 message type 字节 */
    const uint8_t rsp[] = { 0x01, 0x02, 0x00, 0x00 };
    CHECK(bnep_decode_eth(back, sizeof(back), rsp, sizeof(rsp),
                          LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len)
          == BNEP_DECODE_IS_CONTROL);
    CHECK(ctrl == &rsp[1]);
    CHECK(ctrl_len == 3);

    /* 带扩展头的控制帧同样要能识别 */
    const uint8_t rsp_ext[] = { 0x01 | BNEP_EXT_FLAG, 0x02, 0x00, 0x00 };
    CHECK(bnep_decode_eth(back, sizeof(back), rsp_ext, sizeof(rsp_ext),
                          LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len)
          == BNEP_DECODE_IS_CONTROL);

    /* 未知帧类型 */
    const uint8_t bad[] = { 0x7f, 0x00 };
    CHECK(bnep_decode_eth(back, sizeof(back), bad, sizeof(bad),
                          LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len)
          == BNEP_ERR_BADTYPE);

    /* 空帧 */
    CHECK(bnep_decode_eth(back, sizeof(back), bad, 0,
                          LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len)
          == BNEP_ERR_TRUNCATED);

    /* General 帧声明了 14 字节头却只给了 8 字节 */
    const uint8_t shortg[] = { 0x00, 1,2,3,4,5,6,7 };
    CHECK(bnep_decode_eth(back, sizeof(back), shortg, sizeof(shortg),
                          LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len)
          == BNEP_ERR_TRUNCATED);

    /* 输出缓冲不足 */
    uint8_t small[10];
    const uint8_t comp[] = { 0x02, 0x08, 0x00, 1,2,3,4,5,6,7,8 };
    CHECK(bnep_decode_eth(small, sizeof(small), comp, sizeof(comp),
                          LOCAL_MAC, PEER_MAC, &ctrl, &ctrl_len)
          == BNEP_ERR_NOSPACE);
}

int main(void)
{
    test_setup_req();
    test_setup_rsp();
    test_parse_control_setup_rsp();
    test_parse_control_setup_req();
    test_filter_and_unknown();
    test_mac_byte_order();
    test_encode_general();
    test_encode_broadcast_never_compressed();
    test_encode_multicast_never_compressed();
    test_encode_compressed();
    test_encode_bounds();
    test_decode_roundtrip_all_types();
    test_decode_dest_only_and_broadcast();
    test_decode_extension_headers();
    test_decode_control_and_bounds();

    if (g_fail) { printf("\n%d check(s) FAILED\n", g_fail); return 1; }
    printf("all checks passed\n");
    return 0;
}
