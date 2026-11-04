package com.pointerwander.luminawatch.feature.health

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.AssessmentRecord
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassConfirmDialog
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val assessmentBlue = Color(0xFF0A84FF)
private val assessmentInk = Color(0xFF171719)
private val assessmentMuted = Color(0xFF85858A)
private val assessmentGroup = Color(0xFFF2F1F7)

internal fun assessmentLevel(score: Int, depression: Boolean): String =
    if (depression) phqLevel(score) else gadLevel(score)

/** Full-height sheet so introduction, questions, and results share the same upward transition. */
@Composable
internal fun MentalAssessmentSheet(store: AppStore, onDismiss: () -> Unit) {
    var stage by rememberSaveable { mutableIntStateOf(0) } // intro, question, confirm, result
    var question by rememberSaveable { mutableIntStateOf(0) }
    var answers by rememberSaveable { mutableStateOf("-".repeat(16)) }
    var resultId by rememberSaveable { mutableStateOf(0L) }
    var showExitConfirmation by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val questionListState = rememberLazyListState()
    val dismiss = {
        if (stage == 1 || stage == 2) showExitConfirmation = true else onDismiss()
    }
    LiquidGlassBottomSheet(dismiss, fullHeight = true, showNavigation = false,
        surfaceColor = if (stage == 1) assessmentGroup else Color.White,
        contentPadding = 0.dp) {
        when (stage) {
            0 -> AssessmentIntro(onClose = onDismiss, onStart = {
                answers = "-".repeat(16)
                question = 0
                scope.launch { questionListState.scrollToItem(0) }
                stage = 1
            }, storageError = store.assessmentStorageError)
            1 -> AssessmentQuestion(answers, questionListState,
                onBack = {
                    val current = questionListState.firstVisibleItemIndex
                    if (current > 0) {
                        question = current - 1
                        scope.launch { questionListState.animateScrollToItem(current - 1) }
                    } else stage = 0
                },
                onClose = dismiss,
                onChoice = { index, choice ->
                    answers = answers.replaceRange(index, index + 1, "$choice")
                    question = index
                    scope.launch {
                        delay(120)
                        if (stage == 1 && question == index) {
                            if (index < 15) {
                                question = index + 1
                                questionListState.animateScrollToItem(index + 1)
                            } else if (answers.take(15).all { it != '-' }) stage = 2
                            else {
                                val missing = answers.take(15).indexOf('-')
                                question = missing
                                questionListState.animateScrollToItem(missing)
                            }
                        }
                    }
                },
                onSkip = { if (answers.take(15).all { it != '-' }) stage = 2 })
            2 -> AssessmentConfirm(answers[15] != '-', answers[15] in '1'..'3',
                onBack = { stage = 1; question = 15; scope.launch { questionListState.animateScrollToItem(15) } }, onClose = dismiss,
                onSubmit = {
                    val gad = (0..6).map { answers[it].digitToInt() }
                    val phq = if (answers[15] != '-') (7..15).map { answers[it].digitToInt() }
                        else emptyList()
                    store.addAssessment(gad, phq)?.let { resultId = it.id; stage = 3 }
                })
            else -> AssessmentResult(store.assessments.firstOrNull { it.id == resultId },
                onDone = onDismiss)
        }
    }
    if (showExitConfirmation) LiquidGlassConfirmDialog(
        title = "退出问卷？",
        message = "未提交的答案不会保存为评估记录。",
        confirmLabel = "退出",
        onConfirm = onDismiss,
        onDismiss = { showExitConfirmation = false },
    )
}

@Composable
private fun AssessmentIntro(onClose: () -> Unit, onStart: () -> Unit, storageError: Boolean) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            LiquidGlassIconButton(Icons.Rounded.Close, "关闭问卷", onClose, tint = assessmentInk)
        }
        Spacer(Modifier.height(50.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Psychology, null, tint = Color(0xFF21BBB4),
                modifier = Modifier.size(96.dp))
        }
        Spacer(Modifier.height(44.dp))
        Text("心理健康问卷调查", color = assessmentInk, fontSize = 27.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("此评估使用标准化问题，帮助你了解过去两周与焦虑、抑郁相关的感受。结果仅供参考，不等于诊断。",
            color = assessmentMuted, fontSize = 17.sp, lineHeight = 26.sp)
        Spacer(Modifier.height(12.dp))
        Text("共 16 题，选择答案后自动进入下一题。答案只保存在本机。",
            color = assessmentMuted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.weight(1f))
        if (storageError) Text("本机记录暂时无法读取，请稍后重试。",
            color = Color(0xFFAA3030), modifier = Modifier.padding(bottom = 12.dp))
        LiquidGlassButton("开始", onStart, Modifier.fillMaxWidth(), enabled = !storageError,
            tint = assessmentBlue)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AssessmentQuestion(
    answers: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onBack: () -> Unit, onClose: () -> Unit,
    onChoice: (Int, Int) -> Unit, onSkip: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(assessmentGroup)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            LiquidGlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "上一题", onBack,
                tint = assessmentInk)
            Spacer(Modifier.weight(1f))
            LiquidGlassIconButton(Icons.Rounded.Close, "退出问卷", onClose, tint = assessmentInk)
        }
        Text("在过去两周，你经常受到以下问题的困扰吗？",
            color = assessmentInk, fontSize = 27.sp, fontWeight = FontWeight.Bold,
            lineHeight = 35.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 22.dp, end = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(questions) { index, prompt ->
            val selected = answers[index]
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                .background(Color.White)) {
                Text("问题 ${index + 1}/16${if (index == 15) "（选填）" else ""}",
                    color = assessmentMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 18.dp, top = 20.dp))
                Text(prompt, color = assessmentInk, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 27.sp,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 9.dp, bottom = 20.dp))
                choices.forEachIndexed { option, label ->
                    Row(Modifier.fillMaxWidth().height(61.dp)
                        .background(if (selected == '0' + option) Color(0xFFEAF5FF) else Color.White)
                        .clickable { onChoice(index, option) }.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = assessmentInk, fontSize = 17.sp,
                            modifier = Modifier.weight(1f))
                        Box(Modifier.size(24.dp).clip(CircleShape)
                            .background(if (selected == '0' + option) assessmentBlue
                                else Color(0xFFB3B3B8)), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(if (selected == '0' + option) 10.dp else 17.dp)
                                .clip(CircleShape).background(Color.White))
                        }
                    }
                    if (option < 3) Box(Modifier.fillMaxWidth().height(1.dp)
                        .background(Color(0xFFE5E5E8)))
                }
            }
            if (index == 15) {
                Spacer(Modifier.height(16.dp))
                Text("如果你此刻可能伤害自己，请立即联系当地紧急救援服务，或告诉身边可信任的人。",
                    color = Color(0xFF9E2929), fontSize = 14.sp, lineHeight = 21.sp)
            }
            }
        }
        Column(Modifier.fillMaxWidth().background(assessmentGroup)
            .padding(horizontal = 22.dp, vertical = 16.dp)
            .padding(bottom = 16.dp)) {
            val visibleIndex = listState.firstVisibleItemIndex.coerceIn(0, 15)
            Text("${visibleIndex + 1} / 16", color = assessmentMuted, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            if (visibleIndex == 15 && answers[15] == '-' && answers.take(15).all { it != '-' }) {
                Spacer(Modifier.height(8.dp))
                LiquidGlassButton("跳过并完成", onSkip, Modifier.fillMaxWidth(),
                    filled = false, tint = assessmentBlue)
            }
        }
    }
}

@Composable
private fun AssessmentConfirm(lastAnswered: Boolean, selfHarmConcern: Boolean,
    onBack: () -> Unit, onClose: () -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LiquidGlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回最后一题", onBack)
            Spacer(Modifier.weight(1f))
            LiquidGlassIconButton(Icons.Rounded.Close, "退出问卷", onClose)
        }
        Spacer(Modifier.height(70.dp))
        Icon(Icons.Rounded.Check, null, tint = assessmentBlue, modifier = Modifier.size(70.dp))
        Spacer(Modifier.height(24.dp))
        Text("确认提交问卷", color = assessmentInk, fontSize = 28.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(if (lastAnswered) "全部 16 题已完成。提交后将分别生成焦虑和抑郁筛查结果。"
            else "已完成前 15 题。最后一题未作答，本次只生成完整的焦虑筛查结果，抑郁量表不计分。",
            color = assessmentMuted, fontSize = 17.sp, lineHeight = 26.sp)
        if (selfHarmConcern) {
            Spacer(Modifier.height(24.dp))
            Text("如果你此刻可能伤害自己，请立即联系当地紧急救援服务，并告诉身边可信任的人。",
                color = Color(0xFF9E2929), fontSize = 16.sp, lineHeight = 24.sp)
        }
        Spacer(Modifier.weight(1f))
        LiquidGlassButton("完成并查看结果", onSubmit, Modifier.fillMaxWidth(), tint = assessmentBlue)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun AssessmentResult(record: AssessmentRecord?, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.End) {
            LiquidGlassIconButton(Icons.Rounded.Check, "完成", onDone,
                filled = true, tint = assessmentBlue)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp)) {
            Icon(Icons.Rounded.Psychology, null, tint = Color(0xFF22BDB5),
                modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(32.dp))
            Text("你的结果", color = assessmentInk, fontSize = 28.sp,
                fontWeight = FontWeight.Bold)
            if (record == null) {
                Text("结果保存失败，请检查本机存储。", color = Color(0xFFAA3030))
            } else {
                record.gadScore?.let { AssessmentScale("焦虑风险", it, 21, gadLevel(it)) }
                record.phqScore?.let { AssessmentScale("抑郁风险", it, 27, phqLevel(it)) }
                if (record.phqScore == null) Text("第 16 题未作答，抑郁量表不计分。",
                    color = assessmentMuted, fontSize = 15.sp,
                    modifier = Modifier.padding(top = 20.dp))
                if (record.phqAnswers.getOrNull(8)?.let { it > 0 } == true) {
                    Text("请优先照顾当下的安全。如果现在可能伤害自己，请立即联系当地紧急救援服务，并告诉身边可信任的人。",
                        color = Color(0xFF9E2929), fontSize = 15.sp, lineHeight = 23.sp,
                        modifier = Modifier.padding(top = 24.dp))
                }
            }
            Spacer(Modifier.height(30.dp))
            Text("后续步骤", color = assessmentInk, fontSize = 21.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("如果这些感受持续、加重或影响生活，可以与医生或心理健康专业人士讨论。问卷是筛查工具，不能单独用于诊断。",
                color = assessmentInk, fontSize = 16.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun AssessmentScale(title: String, score: Int, max: Int, level: String) {
    Column(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        Text(title, color = assessmentInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("$level · $score / $max", color = assessmentBlue,
            fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(20.dp).clip(CircleShape)
            .background(Color(0xFFE4E4E9))) {
            Box(Modifier.fillMaxWidth(score.toFloat() / max).height(20.dp)
                .clip(CircleShape).background(assessmentBlue))
        }
    }
}
