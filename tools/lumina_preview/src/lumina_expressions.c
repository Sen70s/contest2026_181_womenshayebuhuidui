#include "lumina_expressions.h"

/* Order and labels match LuminaWatch CloudAvatars.kt. Source PNG paths are
   design assets. The platform converts chosen images to LVGL descriptors. */
const lumina_expression_t lumina_expressions[LUMINA_EXPRESSION_COUNT] = {
    {"wei_xiao", "微笑", "cloud-expressions/emoji_cloud_wei_xiao.png"},
    {"ping_jing", "平静", "cloud-expressions/emoji_cloud_ping_jing.png"},
    {"xi_xi", "嘻嘻", "cloud-expressions/emoji_cloud_xi_xi.png"},
    {"ou_ye", "欧耶", "cloud-expressions/emoji_cloud_ou_ye.png"},
    {"ha_ha_ha", "哈哈哈", "cloud-expressions/emoji_cloud_ha_ha_ha.png"},
    {"ai", "哀", "cloud-expressions/emoji_cloud_ai.png"},
    {"ao_li_gei", "奥利给", "cloud-expressions/emoji_cloud_ao_li_gei.png"},
    {"bao_chi_wei_xiao", "保持微笑", "cloud-expressions/emoji_cloud_bao_chi_wei_xiao.png"},
    {"bao_tui", "抱腿", "cloud-expressions/emoji_cloud_bao_tui.png"},
    {"bie_xiao", "憋笑", "cloud-expressions/emoji_cloud_bie_xiao.png"},
    {"bu_fu", "不服", "cloud-expressions/emoji_cloud_bu_fu.png"},
    {"de_yi", "得意", "cloud-expressions/emoji_cloud_de_yi.png"},
    {"dian_zan", "点赞", "cloud-expressions/emoji_cloud_dian_zan.png"},
    {"ei_hei", "诶嘿", "cloud-expressions/emoji_cloud_ei_hei.png"},
    {"gan_fan", "干饭", "cloud-expressions/emoji_cloud_gan_fan.png"},
    {"hai_xiu", "害羞", "cloud-expressions/emoji_cloud_hai_xiu.png"},
    {"heng", "哼", "cloud-expressions/emoji_cloud_heng.png"},
    {"hua_chi", "花痴", "cloud-expressions/emoji_cloud_hua_chi.png"},
    {"huai_xiao", "坏笑", "cloud-expressions/emoji_cloud_huai_xiao.png"},
    {"ji_chou", "记仇", "cloud-expressions/emoji_cloud_ji_chou.png"},
    {"jia_you", "加油", "cloud-expressions/emoji_cloud_jia_you.png"},
    {"jiao_lv", "焦虑", "cloud-expressions/emoji_cloud_jiao_lv.png"},
    {"jing_ya", "惊讶", "cloud-expressions/emoji_cloud_jing_ya.png"},
    {"kou_xiao_bu_de", "哭笑不得", "cloud-expressions/emoji_cloud_kou_xiao_bu_de.png"},
    {"le", "乐", "cloud-expressions/emoji_cloud_le.png"},
    {"leng_jing", "冷静", "cloud-expressions/emoji_cloud_leng_jing.png"},
    {"li_wu_lai_la", "礼物来啦", "cloud-expressions/emoji_cloud_li_wu_lai_la.png"},
    {"lve", "略", "cloud-expressions/emoji_cloud_lve.png"},
    {"man_fen", "满分", "cloud-expressions/emoji_cloud_man_fen.png"},
    {"man_lian_wen_hao", "满脸问号", "cloud-expressions/emoji_cloud_man_lian_wen_hao.png"},
    {"ni_bu_tai_xing", "你不太行", "cloud-expressions/emoji_cloud_ni_bu_tai_xing.png"},
    {"nu", "生气", "cloud-expressions/emoji_cloud_nu.png"},
    {"ping_guo", "苹果", "cloud-expressions/emoji_cloud_ping_guo.png"},
    {"qie", "切", "cloud-expressions/emoji_cloud_qie.png"},
    {"shen_hao", "疑惑", "cloud-expressions/emoji_cloud_shen_hao.png"},
    {"tou_xiao", "偷笑", "cloud-expressions/emoji_cloud_tou_xiao.png"},
    {"tu_xie", "吐血", "cloud-expressions/emoji_cloud_tu_xie.png"},
    {"wa_wa_da_ku", "大哭", "cloud-expressions/emoji_cloud_wa_wa_da_ku.png"},
    {"wei_ni_chang_ge", "为你唱歌", "cloud-expressions/emoji_cloud_wei_ni_chang_ge.png"},
    {"wei_qu", "委屈", "cloud-expressions/emoji_cloud_wei_qu.png"},
    {"wo_ok_de", "我OK的", "cloud-expressions/emoji_cloud_wo_ok_de.png"},
    {"wu_lian", "捂脸", "cloud-expressions/emoji_cloud_wu_lian.png"},
    {"xi", "喜", "cloud-expressions/emoji_cloud_xi.png"},
    {"xi_huan", "喜欢", "cloud-expressions/emoji_cloud_xi_huan.png"},
    {"xiao_diao_da_ya", "笑掉大牙", "cloud-expressions/emoji_cloud_xiao_diao_da_ya.png"},
    {"xiao_zhe_liu_lei", "笑着流泪", "cloud-expressions/emoji_cloud_xiao_zhe_liu_lei.png"},
    {"xin_dong", "心动", "cloud-expressions/emoji_cloud_xin_dong.png"},
    {"xin_hua_nu_fang", "心花怒放", "cloud-expressions/emoji_cloud_xin_hua_nu_fang.png"},
    {"xin_sui", "心碎", "cloud-expressions/emoji_cloud_xin_sui.png"},
    {"zha_xin", "扎心", "cloud-expressions/emoji_cloud_zha_xin.png"}
};

const lumina_expression_t *lumina_expression(uint8_t index)
{
    return &lumina_expressions[index < LUMINA_EXPRESSION_COUNT ? index : 0];
}
