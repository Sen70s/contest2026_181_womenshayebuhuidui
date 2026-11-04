package com.pointerwander.luminawatch.ui

import androidx.annotation.DrawableRes
import com.pointerwander.luminawatch.R

data class CloudAvatar(val id: String, val label: String, @param:DrawableRes val drawable: Int)

/** 与 LuminaCross 的 50 款 emoji_cloud 素材保持一一对应。 */
object CloudAvatars {
    val all = listOf(
        CloudAvatar("wei_xiao", "微笑", R.drawable.emoji_cloud_wei_xiao),
        CloudAvatar("ping_jing", "平静", R.drawable.emoji_cloud_ping_jing),
        CloudAvatar("xi_xi", "嘻嘻", R.drawable.emoji_cloud_xi_xi),
        CloudAvatar("ou_ye", "欧耶", R.drawable.emoji_cloud_ou_ye),
        CloudAvatar("ha_ha_ha", "哈哈哈", R.drawable.emoji_cloud_ha_ha_ha),
        CloudAvatar("ai", "哀", R.drawable.emoji_cloud_ai),
        CloudAvatar("ao_li_gei", "奥利给", R.drawable.emoji_cloud_ao_li_gei),
        CloudAvatar("bao_chi_wei_xiao", "保持微笑", R.drawable.emoji_cloud_bao_chi_wei_xiao),
        CloudAvatar("bao_tui", "抱腿", R.drawable.emoji_cloud_bao_tui),
        CloudAvatar("bie_xiao", "憋笑", R.drawable.emoji_cloud_bie_xiao),
        CloudAvatar("bu_fu", "不服", R.drawable.emoji_cloud_bu_fu),
        CloudAvatar("de_yi", "得意", R.drawable.emoji_cloud_de_yi),
        CloudAvatar("dian_zan", "点赞", R.drawable.emoji_cloud_dian_zan),
        CloudAvatar("ei_hei", "诶嘿", R.drawable.emoji_cloud_ei_hei),
        CloudAvatar("gan_fan", "干饭", R.drawable.emoji_cloud_gan_fan),
        CloudAvatar("hai_xiu", "害羞", R.drawable.emoji_cloud_hai_xiu),
        CloudAvatar("heng", "哼", R.drawable.emoji_cloud_heng),
        CloudAvatar("hua_chi", "花痴", R.drawable.emoji_cloud_hua_chi),
        CloudAvatar("huai_xiao", "坏笑", R.drawable.emoji_cloud_huai_xiao),
        CloudAvatar("ji_chou", "记仇", R.drawable.emoji_cloud_ji_chou),
        CloudAvatar("jia_you", "加油", R.drawable.emoji_cloud_jia_you),
        CloudAvatar("jiao_lv", "焦虑", R.drawable.emoji_cloud_jiao_lv),
        CloudAvatar("jing_ya", "惊讶", R.drawable.emoji_cloud_jing_ya),
        CloudAvatar("kou_xiao_bu_de", "哭笑不得", R.drawable.emoji_cloud_kou_xiao_bu_de),
        CloudAvatar("le", "乐", R.drawable.emoji_cloud_le),
        CloudAvatar("leng_jing", "冷静", R.drawable.emoji_cloud_leng_jing),
        CloudAvatar("li_wu_lai_la", "礼物来啦", R.drawable.emoji_cloud_li_wu_lai_la),
        CloudAvatar("lve", "略", R.drawable.emoji_cloud_lve),
        CloudAvatar("man_fen", "满分", R.drawable.emoji_cloud_man_fen),
        CloudAvatar("man_lian_wen_hao", "满脸问号", R.drawable.emoji_cloud_man_lian_wen_hao),
        CloudAvatar("ni_bu_tai_xing", "你不太行", R.drawable.emoji_cloud_ni_bu_tai_xing),
        CloudAvatar("nu", "生气", R.drawable.emoji_cloud_nu),
        CloudAvatar("ping_guo", "苹果", R.drawable.emoji_cloud_ping_guo),
        CloudAvatar("qie", "切", R.drawable.emoji_cloud_qie),
        CloudAvatar("shen_hao", "疑惑", R.drawable.emoji_cloud_shen_hao),
        CloudAvatar("tou_xiao", "偷笑", R.drawable.emoji_cloud_tou_xiao),
        CloudAvatar("tu_xie", "吐血", R.drawable.emoji_cloud_tu_xie),
        CloudAvatar("wa_wa_da_ku", "大哭", R.drawable.emoji_cloud_wa_wa_da_ku),
        CloudAvatar("wei_ni_chang_ge", "为你唱歌", R.drawable.emoji_cloud_wei_ni_chang_ge),
        CloudAvatar("wei_qu", "委屈", R.drawable.emoji_cloud_wei_qu),
        CloudAvatar("wo_ok_de", "我OK的", R.drawable.emoji_cloud_wo_ok_de),
        CloudAvatar("wu_lian", "捂脸", R.drawable.emoji_cloud_wu_lian),
        CloudAvatar("xi", "喜", R.drawable.emoji_cloud_xi),
        CloudAvatar("xi_huan", "喜欢", R.drawable.emoji_cloud_xi_huan),
        CloudAvatar("xiao_diao_da_ya", "笑掉大牙", R.drawable.emoji_cloud_xiao_diao_da_ya),
        CloudAvatar("xiao_zhe_liu_lei", "笑着流泪", R.drawable.emoji_cloud_xiao_zhe_liu_lei),
        CloudAvatar("xin_dong", "心动", R.drawable.emoji_cloud_xin_dong),
        CloudAvatar("xin_hua_nu_fang", "心花怒放", R.drawable.emoji_cloud_xin_hua_nu_fang),
        CloudAvatar("xin_sui", "心碎", R.drawable.emoji_cloud_xin_sui),
        CloudAvatar("zha_xin", "扎心", R.drawable.emoji_cloud_zha_xin),
    )

    fun find(id: String): CloudAvatar = all.firstOrNull { it.id == id } ?: all.first()
}
