package com.pointerwander.luminawatch.feature.health

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.AssessmentRecord
import com.pointerwander.luminawatch.ui.FrostedCard
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.LiquidGlassChoiceChip
import com.pointerwander.luminawatch.ui.PageColumn
import com.pointerwander.luminawatch.ui.PageHeader
import com.pointerwander.luminawatch.ui.PrimaryButton
import com.pointerwander.luminawatch.ui.SecondaryButton
import com.pointerwander.luminawatch.ui.SectionTitle
import com.pointerwander.luminawatch.ui.SurfaceCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class Mode(val label: String) { BOTH("完整问卷"), GAD("焦虑风险"), PHQ("抑郁风险") }

// GAD-7 与 PHQ-9：原有量表分别计分，过去两周为回顾时间范围。
internal val questions = listOf(
    "感到紧张、焦虑或急切",
    "无法停止或控制担忧",
    "对各种各样的事情担忧过多",
    "很难放松下来",
    "由于不安而无法静坐",
    "变得容易烦躁或易怒",
    "感到似乎将有可怕的事情发生",
    "做事时提不起劲或没有兴趣",
    "感到心情低落、沮丧或绝望",
    "入睡困难、睡不安稳或睡眠过多",
    "感到疲倦或没有活力",
    "食欲不振或吃得太多",
    "觉得自己很糟，或觉得自己失败了，让自己或家人失望",
    "对事物专注有困难，例如阅读或看电视",
    "动作或说话变得很慢，或比平时更加坐立不安",
    "有不如死掉或以某种方式伤害自己的念头",
)
internal val choices = listOf("从来没有", "有几天", "超过一半天数", "接近每天")

internal fun gadLevel(score: Int) = when (score) {
    in 0..4 -> "极轻"
    in 5..9 -> "轻度"
    in 10..14 -> "中度"
    else -> "重度"
}

internal fun phqLevel(score: Int) = when (score) {
    in 0..4 -> "极轻"
    in 5..9 -> "轻度"
    in 10..14 -> "中度"
    in 15..19 -> "中重度"
    else -> "重度"
}

@Composable
fun MentalAssessmentScreen(store: AppStore, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    var stage by rememberSaveable { mutableIntStateOf(0) } // 0 介绍，1 答题，2 结果，3 历史
    var modeName by rememberSaveable { mutableStateOf(Mode.BOTH.name) }
    val mode = Mode.valueOf(modeName)
    var confirmedAge by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var answers by rememberSaveable { mutableStateOf("-".repeat(16)) }
    var latestId by rememberSaveable { mutableStateOf(0L) }
    val indices = when (mode) {
        Mode.BOTH -> (0..15).toList()
        Mode.GAD -> (0..6).toList()
        Mode.PHQ -> (7..15).toList()
    }
    val pages = remember(mode) { indices.chunked(3) }
    fun back() {
        when (stage) {
            0 -> onBack()
            1 -> if (page > 0) page-- else stage = 0
            else -> stage = 0
        }
    }
    BackHandler { back() }

    when (stage) {
        0 -> PageColumn {
            PageHeader("心理健康问卷", "了解过去两周的感受", onBack = ::back)
            FrostedCard {
                Text("更了解自己，从这里开始", color = colors.textPrimary, fontSize = 21.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Text("使用两份标准化问卷，分别了解焦虑与抑郁相关症状。结果供自我了解和与专业人士交流，不是医疗诊断。",
                    color = colors.textSecondary, fontSize = 14.sp, lineHeight = 22.sp)
            }
            SectionTitle("选择问卷")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Mode.entries.forEach { item ->
                    LiquidGlassChoiceChip(item.label + when (item) {
                            Mode.BOTH -> " · 16 题"
                            Mode.GAD -> " · 7 题"
                            Mode.PHQ -> " · 9 题"
                        }, mode == item, { modeName = item.name; page = 0 })
                }
            }
            SurfaceCard(Modifier.clickable { confirmedAge = !confirmedAge }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = confirmedAge, onClick = { confirmedAge = !confirmedAge })
                    Text("我已年满 13 周岁", color = colors.textPrimary, fontSize = 15.sp)
                }
            }
            Text("答题与计分在本机完成；不会自动分享给小云或其他人。可随时返回，未提交的答案不会成为评估记录。",
                color = colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            if (store.assessmentStorageError) {
                Text("本机加密记录暂时无法读取。为避免覆盖原有记录，当前不能开始新问卷。",
                    color = Color(0xFFC73333), fontSize = 14.sp)
            }
            PrimaryButton("开始", { answers = "-".repeat(16); page = 0; stage = 1 },
                Modifier.fillMaxWidth(), enabled = confirmedAge && !store.assessmentStorageError)
            SecondaryButton("查看以往结果", { stage = 3 }, Modifier.fillMaxWidth())
        }
        1 -> PageColumn {
            PageHeader("过去两周", "${page + 1} / ${pages.size} · 每题选择最接近的情况", onBack = ::back)
            pages[page].forEach { index ->
                SurfaceCard {
                    Text("问题 ${indices.indexOf(index) + 1} / ${indices.size}",
                        color = colors.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(questions[index], color = colors.textPrimary, fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                    Spacer(Modifier.height(10.dp))
                    choices.forEachIndexed { choiceIndex, choice ->
                        val selected = answers[index] == ('0' + choiceIndex)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                .background(if (selected) colors.accentSoft else Color.Transparent,
                                    RoundedCornerShape(12.dp))
                                .clickable { answers = answers.replaceRange(index, index + 1, "$choiceIndex") }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(choice, modifier = Modifier.weight(1f), color = colors.textPrimary, fontSize = 14.sp)
                            RadioButton(selected = selected,
                                onClick = { answers = answers.replaceRange(index, index + 1, "$choiceIndex") })
                        }
                    }
                }
            }
            if (pages[page].contains(15) && answers[15] in '1'..'3') {
                SurfaceCard {
                    Text("如果你此刻有伤害自己的危险，请立即联系当地紧急救援服务或身边可信任的人。",
                        color = Color(0xFF9E2929), fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
            PrimaryButton(if (page == pages.lastIndex) "查看结果" else "下一步", {
                if (page < pages.lastIndex) page++ else {
                    val gad = if (mode != Mode.PHQ) (0..6).map { answers[it].digitToInt() } else emptyList()
                    val phq = if (mode != Mode.GAD) (7..15).map { answers[it].digitToInt() } else emptyList()
                    store.addAssessment(gad, phq)?.let { latestId = it.id; stage = 2 }
                }
            }, Modifier.fillMaxWidth(), enabled = pages[page].all { answers[it] != '-' })
            if (store.assessmentStorageError) Text("保存失败，请检查设备的本地存储状态。", color = Color.Red)
        }
        2 -> {
            val record = store.assessments.firstOrNull { it.id == latestId }
            ResultContent(record, onBack = { stage = 0 })
        }
        else -> PageColumn {
            PageHeader("以往结果", onBack = ::back)
            if (store.assessments.isEmpty()) {
                SurfaceCard { Text("还没有完成的问卷。", color = colors.textSecondary) }
            } else {
                store.assessments.forEach { record ->
                    SurfaceCard(Modifier.clickable { latestId = record.id; stage = 2 }) {
                        Text(formatDate(record.timestamp), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(record.gadScore?.let { "焦虑 $it 分" },
                            record.phqScore?.let { "抑郁 $it 分" }).joinToString(" · "),
                            color = colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultContent(record: AssessmentRecord?, onBack: () -> Unit) {
    val colors = LocalWatchColors.current
    PageColumn {
        PageHeader("你的结果", record?.let { formatDate(it.timestamp) }, onBack = onBack)
        if (record == null) {
            SurfaceCard { Text("这份结果已不存在。", color = colors.textSecondary) }
            return@PageColumn
        }
        record.gadScore?.let { score -> ResultScale("焦虑相关症状", score, 21, gadLevel(score)) }
        record.phqScore?.let { score -> ResultScale("抑郁相关症状", score, 27, phqLevel(score)) }
        if (record.phqAnswers.getOrNull(8)?.let { it > 0 } == true) {
            SurfaceCard {
                Text("请优先照顾当下的安全", color = Color(0xFF9E2929), fontWeight = FontWeight.Bold)
                Text("如果你现在可能伤害自己，请立即联系当地紧急救援服务，并告诉身边可信任的人。",
                    color = colors.textPrimary, fontSize = 14.sp, lineHeight = 22.sp)
            }
        }
        SurfaceCard {
            Text("接下来可以做什么", color = colors.textPrimary, fontWeight = FontWeight.Bold)
            Text("如果这些感受持续、加重或影响生活，建议与医生或心理健康专业人士讨论。量表是筛查工具，不能单独用于诊断。",
                color = colors.textSecondary, fontSize = 14.sp, lineHeight = 22.sp)
        }
        Text("问卷结果仅保存在本机，不会自动分享。", color = colors.textSecondary, fontSize = 12.sp)
        PrimaryButton("完成", onBack, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ResultScale(title: String, score: Int, max: Int, level: String) {
    val colors = LocalWatchColors.current
    SurfaceCard {
        Text(title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("$score / $max", color = colors.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("$level · 供参考", color = colors.textSecondary, fontSize = 14.sp)
    }
}

private fun formatDate(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 HH:mm"))
