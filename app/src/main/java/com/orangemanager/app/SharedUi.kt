package com.orangemanager.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Orange = Color(0xFFFF9F1C)
val Green = Color(0xFF27A765)
val Red = Color(0xFFE64B4B)
val Yellow = Color(0xFFFFC83D)
/**
 * 全局白天 / 黑夜。
 *
 * 刻意做成"读的时候取当前值"的 getter：这样全项目几十处 Ink / Surface /
 * FieldBg 的用法一行都不用改 —— 它们读的是快照状态，切换时自动触发重组。
 * 只反黑白两色，橙 / 绿 / 红 / 黄这些语义色一律不动（按需求"其他颜色不变"）。
 */
val darkModeState = mutableStateOf(false)

/** 主文字色。 */
val Ink: Color get() = if (darkModeState.value) Color(0xFFEFF1EE) else Color(0xFF24332D)

/** 卡片、标题栏这些"白底"。 */
val Surface: Color get() = if (darkModeState.value) Color(0xFF1C1C1C) else Color.White

/** 输入框、滚轮的浅色底。 */
val FieldBg: Color get() = if (darkModeState.value) Color(0xFF2A2A2A) else Color(0xFFF6F7F5)

/** 整页背景。 */
val AppBg: Color get() = if (darkModeState.value) Color(0xFF111111) else Color(0xFFF7F8F5)

/** 供非 @Composable 的点击回调弹 Toast 用 —— 那里读不到 LocalContext。 */
lateinit var appContext: android.content.Context

/** 信息没填完整时的提示。 */
fun toastIncomplete() {
    android.widget.Toast.makeText(appContext, "未填写完整！你个㊗️", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
fun Header(title: String) {
    Column(Modifier.fillMaxWidth().background(Surface).padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Ink)
    }
}

@Composable
fun SectionTitle(text: String, count: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 15.sp)
        if (count != null) Text("  $count", color = Orange, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp)
            .background(Surface, RoundedCornerShape(12.dp)).padding(12.dp),
        content = content
    )
}

/**
 * 页面上 FilterChip 的统一配色（重要等级、每周事件、外观共用）。
 *
 * 抽出来是因为这段逻辑原本在四处各写了一遍：黑夜模式下 Material 的默认标签色
 * 取自 light colorScheme（深灰），压在深色卡片上几乎看不见，所以统一换成白；
 * 白天模式保留原来的主题色。
 *
 * 注意星期选择框里的芯片**不用**它 —— 那个弹窗始终是浅色的，白字会压在白底上。
 */
@Composable
fun appChipColors(container: Color) = FilterChipDefaults.filterChipColors(
    selectedContainerColor = container,
    labelColor = if (darkModeState.value) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
    selectedLabelColor = if (darkModeState.value) Color.White else MaterialTheme.colorScheme.onSurface
)

/**
 * 输入框的光标颜色。
 *
 * BasicTextField 的 cursorBrush 默认是纯黑，黑夜模式下压在深色输入框上几乎看不见，
 * 所以让它跟着主题走。
 */
val caretBrush: androidx.compose.ui.graphics.Brush
    get() = androidx.compose.ui.graphics.SolidColor(if (darkModeState.value) Color.White else Ink)

/**
 * 单行输入框。
 *
 * [suffix] 是贴在输入框右侧的单位提示（比如"分"）—— 这样说明单位不用再多占一行文字，
 * 只填数字的字段用它最合适。
 */
@Composable
fun Field(label: String, value: String, onValue: (String) -> Unit, suffix: String? = null) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().background(FieldBg, RoundedCornerShape(10.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value,
                    onValue,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    cursorBrush = caretBrush,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 14.sp)
                )
                if (suffix != null) Text(suffix, color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}
