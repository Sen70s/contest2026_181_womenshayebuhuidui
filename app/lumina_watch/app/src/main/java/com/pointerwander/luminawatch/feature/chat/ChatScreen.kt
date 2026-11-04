package com.pointerwander.luminawatch.feature.chat

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pointerwander.luminawatch.data.AppStore
import com.pointerwander.luminawatch.data.ChatMessage
import com.pointerwander.luminawatch.data.MessageStatus
import com.pointerwander.luminawatch.ui.ActionRow
import com.pointerwander.luminawatch.ui.CloudImage
import com.pointerwander.luminawatch.ui.CircleIconButton
import com.pointerwander.luminawatch.ui.LocalBottomBarInset
import com.pointerwander.luminawatch.ui.LocalWatchColors
import com.pointerwander.luminawatch.ui.statusBarHeight
import com.pointerwander.luminawatch.ui.LiquidGlassBottomSheet
import com.pointerwander.luminawatch.ui.LiquidGlassButton
import com.pointerwander.luminawatch.ui.LiquidGlassIconButton
import com.pointerwander.luminawatch.ui.LiquidGlassSurface
import com.pointerwander.luminawatch.ui.LiquidGlassStickyHeader
import com.pointerwander.luminawatch.ui.LiquidGlassTopBarIconButton
import com.pointerwander.luminawatch.ui.SurfaceCard
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    store: AppStore,
    onOpenHistory: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenStyle: () -> Unit,
    onBack: (() -> Unit)? = null,
    initialMessageId: Long? = null,
) {
    val colors = LocalWatchColors.current
    val statusInset = statusBarHeight()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var input by rememberSaveable { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    val inputGlassAlpha by animateFloatAsState(
        if (inputFocused) .96f else .84f, tween(180), label = "chatInputGlassFocus",
    )
    var showMenu by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var attachReady by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    var focusedMessageId by remember(initialMessageId) { mutableStateOf(initialMessageId) }
    val scope = rememberCoroutineScope()
    val inputFocusRequester = remember { FocusRequester() }
    val contextEvent = store.careEvent(store.activeCareContextId)
    val headerBackdrop = rememberLayerBackdrop()

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            pendingImage = copyImageToFiles(context, uri)
            showAttach = false
        }
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            pendingImage = saveBitmapToFiles(context, bitmap)
            showAttach = false
        }
    }

    fun send() {
        val image = pendingImage
        if (image == null && input.isBlank()) return
        if (image != null) store.sendImage(image)
        if (input.isNotBlank()) store.sendMessage(input)
        pendingImage = null
        input = ""
        focusedMessageId = null
        scope.launch {
            delay(650)
            store.completeReply()
        }
    }

    LaunchedEffect(store.messages.size, focusedMessageId) {
        if (store.messages.isNotEmpty()) {
            val target = focusedMessageId?.let { id ->
                store.messages.indexOfFirst { it.id == id }.takeIf { it >= 0 }
            } ?: store.messages.lastIndex
            if (focusedMessageId == null) listState.animateScrollToItem(target)
            else listState.scrollToItem(target)
        }
    }

    LaunchedEffect(showAttach) {
        if (showAttach) {
            attachReady = false
            delay(220)
            attachReady = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(headerBackdrop)) {
            Column(
                Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)
                    .padding(bottom = LocalBottomBarInset.current),
            ) {
                Spacer(Modifier.height(84.dp + statusInset))

                if (contextEvent != null) {
                    SurfaceCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "这次关怀",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            LiquidGlassButton("关闭", { store.activeCareContextId = null },
                                height = 36.dp, filled = false)
                        }
                        Text(contextEvent.title, color = colors.textSecondary, fontSize = 12.sp)
                        Text(
                            if (contextEvent.contextAuthorized) "已允许小云使用这次关怀信息" else "健康数值不会发送给小云",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (store.messages.isEmpty()) {
                        item {
                            Text("你好，我是${store.cloudName}。今天想聊点什么？",
                                color = colors.textPrimary, fontSize = 17.sp, lineHeight = 27.sp,
                                modifier = Modifier.padding(top = 24.dp, start = 8.dp, end = 8.dp))
                        }
                    }
                    items(store.messages.size, key = { store.messages[it].id }) { index ->
                        val message = store.messages[index]
                        val previous = store.messages.getOrNull(index - 1)
                        val next = store.messages.getOrNull(index + 1)
                        MessageBubble(message,
                            avatarId = store.avatarId,
                            showAvatar = message.fromCloud && previous?.fromCloud != true,
                            showTime = next == null || next.fromCloud != message.fromCloud ||
                                next.timeLabel != message.timeLabel,
                            groupedWithPrevious = previous?.fromCloud == message.fromCloud,
                            highlighted = message.id == focusedMessageId) {
                            store.retryLastMessage()
                            scope.launch { delay(650); store.completeReply() }
                        }
                    }
                    if (store.pendingReply) {
                        item { Text("小云正在输入…", color = colors.textSecondary, fontSize = 12.sp) }
                    }
                }

                pendingImage?.let { path ->
                    AttachmentPreview(path) { pendingImage = null }
                }

                LiquidGlassSurface(
                    onClick = { inputFocusRequester.requestFocus() },
                    modifier = Modifier.fillMaxWidth(),
                    height = 62.dp,
                    glassAlpha = inputGlassAlpha,
                    border = colors.outline.copy(alpha = .55f),
                    contentPadding = 6.dp,
                ) {
                    LiquidGlassSurface(
                        onClick = { keyboard?.hide(); showAttach = true },
                        modifier = Modifier.size(46.dp), filled = true,
                        tint = Color(0xFFEDEFE9), height = 46.dp,
                        contentPadding = 0.dp,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "添加照片",
                            tint = Color.Black, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).focusRequester(inputFocusRequester)
                            .onFocusChanged { inputFocused = it.isFocused },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.textPrimary, fontSize = 15.sp,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        decorationBox = { innerTextField ->
                            Box {
                                if (input.isEmpty()) {
                                    Text("今天想聊点什么？", color = colors.textSecondary, fontSize = 15.sp)
                                }
                                innerTextField()
                            }
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    if (input.isBlank() && pendingImage == null) {
                        LiquidGlassSurface(
                            onClick = {
                                Toast.makeText(context, "语音能力尚未接入", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(46.dp), filled = true,
                            tint = Color.Black, fillAlpha = 1f,
                            height = 46.dp, contentPadding = 0.dp,
                        ) {
                            Canvas(Modifier.size(24.dp)) {
                                val bars = floatArrayOf(8f, 15f, 21f, 14f, 8f)
                                bars.forEachIndexed { index, height ->
                                    val x = size.width * (.18f + index * .16f)
                                    val half = height.dp.toPx() / 2f
                                    drawLine(Color.White,
                                        Offset(x, size.height / 2f - half),
                                        Offset(x, size.height / 2f + half),
                                        strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                                }
                            }
                        }
                    } else {
                        LiquidGlassIconButton(Icons.AutoMirrored.Rounded.Send, "发送消息",
                            { send() }, filled = true, tint = Color.Black, size = 46.dp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        LiquidGlassStickyHeader(
            title = store.cloudName,
            onBack = onBack ?: onOpenHistory,
            leadingIcon = if (onBack == null) Icons.Rounded.Menu else null,
            topInset = statusInset,
            backdrop = headerBackdrop,
            actions = {
                LiquidGlassTopBarIconButton(Icons.Rounded.Palette, "聊天风格", onOpenStyle,
                    headerBackdrop)
                Spacer(Modifier.width(8.dp))
                LiquidGlassTopBarIconButton(Icons.Rounded.MoreHoriz, "小云菜单",
                    { keyboard?.hide(); showMenu = true }, headerBackdrop)
            },
        )

    }

    if (showAttach) {
        LiquidGlassBottomSheet(onDismiss = { showAttach = false }, surfaceColor = colors.surface) {
            AttachmentSheet(
                onDismiss = { showAttach = false },
                onPickPhoto = {
                    pickPhoto.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onTakePhoto = { takePhoto.launch(null) },
                enabled = attachReady,
            )
        }
    }

    if (showMenu) {
        LiquidGlassBottomSheet(onDismiss = { showMenu = false }, surfaceColor = colors.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CloudImage(Modifier.size(48.dp), avatarId = store.avatarId)
                Spacer(Modifier.width(12.dp))
                Text(store.cloudName, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                ActionRow("记忆", "查看小云记住的信息", Icons.Rounded.AutoAwesome, onClick = {
                    showMenu = false; onOpenMemory()
                }, iconBackground = Color(0xFF7B61DC))
                ActionRow("聊天记录", icon = Icons.Rounded.History, onClick = {
                    showMenu = false; onOpenHistory()
                }, iconBackground = Color(0xFF1687E8))
                ActionRow("聊天风格", "调整名称、性格与简介", Icons.Rounded.Palette, onClick = {
                    showMenu = false; onOpenStyle()
                }, iconBackground = Color(0xFFE663A0))
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    avatarId: String,
    showAvatar: Boolean,
    showTime: Boolean,
    groupedWithPrevious: Boolean,
    highlighted: Boolean = false,
    onRetry: () -> Unit,
) {
    val colors = LocalWatchColors.current
    val maxBubbleWidth = LocalConfiguration.current.screenWidthDp.dp * .76f
    val bubbleShape = if (message.fromCloud) {
        RoundedCornerShape(topStart = 8.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 8.dp, bottomEnd = 20.dp, bottomStart = 20.dp)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = if (groupedWithPrevious) 0.dp else 10.dp),
        horizontalArrangement = if (message.fromCloud) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Top,
    ) {
        if (message.fromCloud) {
            if (showAvatar) CloudImage(Modifier.size(30.dp), avatarId = avatarId)
            else Spacer(Modifier.width(30.dp))
            Spacer(Modifier.width(7.dp))
        }
        Column(
            Modifier.widthIn(max = maxBubbleWidth),
            horizontalAlignment = if (message.fromCloud) Alignment.Start else Alignment.End,
        ) {
            Column(Modifier.clip(bubbleShape)
                .background(if (message.fromCloud) Color(0xFFF1F4F6) else Color(0xFFE8F3FF))
                .then(if (highlighted) Modifier.border(1.dp,
                    colors.accent.copy(alpha = .55f), bubbleShape) else Modifier)
                .padding(horizontal = 14.dp, vertical = 11.dp)) {
                message.imageUri?.let { path ->
                    val bitmap = rememberLocalImage(path)
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "聊天照片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 180.dp)
                                .clip(RoundedCornerShape(13.dp)),
                        )
                        Spacer(Modifier.height(7.dp))
                        Text("照片 · 仅保存在本机", color = colors.textSecondary, fontSize = 12.sp)
                    } else {
                        Text("照片暂时无法显示", color = colors.textSecondary, fontSize = 14.sp)
                    }
                }
                if (message.imageUri == null ||
                    (message.text.isNotBlank() && message.text != "发送了一张照片")) {
                    Text(message.text, color = colors.textPrimary,
                        fontSize = 16.sp, lineHeight = 24.sp)
                }
                if (message.status == MessageStatus.FAILED) {
                    Spacer(Modifier.height(8.dp))
                    LiquidGlassButton("发送失败 · 重试", onRetry,
                        height = 38.dp, filled = false, tint = colors.danger)
                }
            }
            if (showTime) {
                Spacer(Modifier.height(4.dp))
                Text(message.timeLabel, color = colors.textSecondary, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 5.dp))
            }
        }
    }
}

@Composable
private fun AttachmentPreview(path: String, onRemove: () -> Unit) {
    val colors = LocalWatchColors.current
    val bitmap = rememberLocalImage(path)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceMuted.copy(alpha = .85f))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "待发送照片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("已选择照片", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text("仅保存在本机 · 未上传云岸", color = colors.textSecondary, fontSize = 11.sp)
        }
        LiquidGlassIconButton(Icons.Rounded.Close, "移除照片", onRemove, size = 38.dp)
    }
}

@Composable
private fun rememberLocalImage(path: String?): ImageBitmap? {
    val context = LocalContext.current
    return remember(path) {
        path?.let {
            runCatching {
                val uri = Uri.parse(it)
                val stream = if (uri.scheme == "file") {
                    File(uri.path!!).inputStream()
                } else {
                    context.contentResolver.openInputStream(uri)
                }
                stream?.use { input -> BitmapFactory.decodeStream(input)?.asImageBitmap() }
            }.getOrNull()
        }
    }
}
