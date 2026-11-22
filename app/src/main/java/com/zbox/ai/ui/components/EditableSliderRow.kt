package com.zbox.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zbox.ai.ui.ZColors
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * 通用参数调节滑杆：
 * 1) 左右拖动调节数值；
 * 2) 点击右侧数值胶囊可直接键入精确数字；
 * 3) 双击滑杆条（或参数名）直接重置为默认值（通常为 0）。
 */
@Composable
internal fun EditableSliderRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    defaultValue: Float = 0f,
    format: String = "%+.0f",
    inputDecimals: Int = 0,
    onDragStart: (() -> Unit)? = null,
    onBeforeDirectEdit: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var dragStarted by remember { mutableStateOf(false) }
    var showInputDialog by remember { mutableStateOf(false) }

    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnBeforeDirectEdit by rememberUpdatedState(onBeforeDirectEdit)

    val resetToDefault: () -> Unit = {
        if (abs(currentValue - defaultValue) > 1e-4f) {
            currentOnBeforeDirectEdit?.invoke()
        }
        currentOnChange(defaultValue)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val currentReset by rememberUpdatedState(resetToDefault)

    val modified = abs(value - defaultValue) > 0.01f

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = ZColors.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { currentReset() })
                    }
                    .padding(vertical = 2.dp),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        if (modified) ZColors.NikonYellow.copy(alpha = 0.14f) else ZColors.SurfaceHigh
                    )
                    .border(
                        width = 1.dp,
                        color = if (modified) ZColors.NikonYellow.copy(alpha = 0.5f) else ZColors.Outline.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(5.dp),
                    )
                    .clickable { showInputDialog = true }
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = format.format(if (abs(value) < 1e-4f) 0f else value),
                    color = if (modified) ZColors.NikonYellow else ZColors.TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var lastTapTime = 0L
                        var lastTapPos = Offset.Zero
                        var activePointerId: PointerId? = null
                        var downPos = Offset.Zero
                        var downTime = 0L
                        var moved = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            val changes = event.changes
                            if (activePointerId == null) {
                                val down = changes.firstOrNull { !it.previousPressed && it.pressed }
                                if (down != null && changes.count { it.pressed } == 1) {
                                    activePointerId = down.id
                                    downPos = down.position
                                    downTime = down.uptimeMillis
                                    moved = false
                                }
                            } else {
                                val cur = changes.firstOrNull { it.id == activePointerId }
                                if (cur == null || changes.count { it.pressed } > 1) {
                                    activePointerId = null
                                } else if (cur.pressed) {
                                    if ((cur.position - downPos).getDistance() > viewConfiguration.touchSlop) {
                                        moved = true
                                    }
                                } else {
                                    val upTime = cur.uptimeMillis
                                    val upPos = cur.position
                                    val isTap = !moved &&
                                        (upPos - downPos).getDistance() <= viewConfiguration.touchSlop &&
                                        (upTime - downTime) <= 350L
                                    activePointerId = null
                                    if (isTap) {
                                        val dt = upTime - lastTapTime
                                        val dist = (upPos - lastTapPos).getDistance()
                                        if (lastTapTime > 0L &&
                                            dt in 1L..viewConfiguration.doubleTapTimeoutMillis &&
                                            dist <= viewConfiguration.touchSlop * 2f
                                        ) {
                                            lastTapTime = 0L
                                            currentReset()
                                        } else {
                                            lastTapTime = upTime
                                            lastTapPos = upPos
                                        }
                                    } else {
                                        lastTapTime = 0L
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            Slider(
                value = value.coerceIn(min, max),
                onValueChange = { v ->
                    if (!dragStarted) {
                        dragStarted = true
                        currentOnDragStart?.invoke()
                    }
                    currentOnChange(v)
                },
                onValueChangeFinished = { dragStarted = false },
                valueRange = min..max,
                modifier = Modifier.fillMaxWidth().height(26.dp),
                colors = SliderDefaults.colors(
                    thumbColor = ZColors.NikonYellow,
                    activeTrackColor = ZColors.NikonYellow,
                    inactiveTrackColor = ZColors.Outline,
                ),
            )
        }
    }

    if (showInputDialog) {
        NumericSliderInputDialog(
            label = label,
            currentValue = value,
            min = min,
            max = max,
            defaultValue = defaultValue,
            defaultDisplay = format.format(if (abs(defaultValue) < 1e-4f) 0f else defaultValue),
            inputDecimals = inputDecimals,
            onDismiss = { showInputDialog = false },
            onApply = { nextVal ->
                if (abs(nextVal - value) > 1e-4f) {
                    onBeforeDirectEdit?.invoke()
                }
                onChange(nextVal)
                showInputDialog = false
            },
        )
    }
}

@Composable
private fun NumericSliderInputDialog(
    label: String,
    currentValue: Float,
    min: Float,
    max: Float,
    defaultValue: Float,
    defaultDisplay: String,
    inputDecimals: Int,
    onDismiss: () -> Unit,
    onApply: (Float) -> Unit,
) {
    val cleanCurrent = if (abs(currentValue) < 1e-4f) 0f else currentValue
    val initialText = remember(currentValue, inputDecimals) {
        if (inputDecimals <= 0) "%.0f".format(cleanCurrent)
        else "%.${inputDecimals}f".format(cleanCurrent)
    }
    var field by remember(initialText) {
        mutableStateOf(TextFieldValue(initialText, selection = TextRange(0, initialText.length)))
    }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val rangeFmt: (Float) -> String = { v ->
        if (inputDecimals <= 0) "%.0f".format(v) else "%.${inputDecimals}f".format(v)
    }

    LaunchedEffect(Unit) {
        delay(60)
        runCatching { focusRequester.requestFocus() }
    }

    fun submit() {
        val cleaned = field.text
            .trim()
            .replace('－', '-')
            .replace('—', '-')
            .replace('。', '.')
            .replace("EV", "", ignoreCase = true)
            .replace("%", "")
            .trim()
        val parsed = cleaned.toFloatOrNull()
        if (parsed == null || parsed.isNaN() || parsed.isInfinite()) {
            errorMsg = "请输入有效数字（范围 ${rangeFmt(min)} ~ ${rangeFmt(max)}）"
            return
        }
        val clamped = parsed.coerceIn(min, max)
        onApply(if (abs(clamped) < 1e-4f) 0f else clamped)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ZColors.Surface,
        title = {
            Text(label, color = ZColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "输入数值（范围 ${rangeFmt(min)} ~ ${rangeFmt(max)}，双击滑杆也可快速重置）",
                    color = ZColors.TextTertiary,
                    fontSize = 11.sp,
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        errorMsg = null
                    },
                    singleLine = true,
                    isError = errorMsg != null,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ZColors.NikonYellow,
                        unfocusedBorderColor = ZColors.Outline,
                        focusedTextColor = ZColors.TextPrimary,
                        unfocusedTextColor = ZColors.TextPrimary,
                        cursorColor = ZColors.NikonYellow,
                    ),
                )
                if (errorMsg != null) {
                    Text(errorMsg!!, color = ZColors.Danger, fontSize = 11.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (min < 0f) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ZColors.SurfaceHigh)
                                .border(1.dp, ZColors.Outline, RoundedCornerShape(6.dp))
                                .clickable {
                                    val t = field.text.trim()
                                    val next = when {
                                        t.isEmpty() -> "-"
                                        t.startsWith("-") -> t.drop(1)
                                        t.startsWith("+") -> "-" + t.drop(1)
                                        else -> "-$t"
                                    }
                                    field = TextFieldValue(next, selection = TextRange(next.length))
                                    errorMsg = null
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text("± 正负号", color = ZColors.TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ZColors.SurfaceHigh)
                            .border(1.dp, ZColors.Outline, RoundedCornerShape(6.dp))
                            .clickable { onApply(defaultValue) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text("重置 ($defaultDisplay)", color = ZColors.NikonYellow, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZColors.ButtonBg,
                    contentColor = ZColors.ButtonFg,
                ),
            ) {
                Text("确定", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = ZColors.TextSecondary)
            }
        },
    )
}
