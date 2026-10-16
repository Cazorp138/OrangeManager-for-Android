package com.orangemanager.app

import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Calendar

/** 一天里的最后一分钟（23:59），以"当天第几分钟"计。 */
const val LAST_MINUTE = 23 * 60 + 59

/** 把"当天第几分钟"格式化成 HH:mm。 */
fun formatMinutes(minutesOfDay: Int): String =
    "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)

/** 把年月日格式化成 yyyy-MM-dd（parseWhenMillis 认这个格式）。 */
fun formatDate(year: Int, month: Int, day: Int): String =
    "%04d-%02d-%02d".format(year, month, day)

/** 当前时刻是当天的第几分钟。默认值用它，所以默认时间就是"现在"。 */
fun nowMinuteOfDay(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

/** 某年某月的天数。minSdk 26，可以直接用 java.time。 */
fun daysInMonth(year: Int, month: Int): Int = java.time.YearMonth.of(year, month).lengthOfMonth()

/**
 * 弹窗里强制用白天的浅色底。
 *
 * 时间设置弹窗本身是浅色的（AlertDialog 走 MaterialTheme，而我们的主题始终是
 * lightColorScheme），如果里面的滚轮跟着全局黑夜模式变深，就会变成
 * "浅色弹窗里嵌着几块深色"，很违和。所以弹窗里把滚轮底色锁成白天那套。
 */
private val LIGHT_FIELD = Color(0xFFF6F7F5)
/**
 * true 时滚轮一律用白天的浅色底。
 *
 * 公开出来是给专注页的时长滚轮用的 —— 它不在弹窗里，但同样需要浅色底：
 * NumberPicker 是系统控件，文字颜色取自应用主题（我们用的是 Light 主题），
 * 永远是深色，所以只要滚轮底色一变深，字就直接看不见了。
 */
val LocalLightField = compositionLocalOf { false }

/**
 * 通用滚动选择轮。
 *
 * 用系统的 NumberPicker，而不是自己用 LazyColumn 搭 —— 吸附、惯性滑动、
 * 边界回弹这些行为由 Android 平台保证，不用我自己去调对齐误差。
 *
 * value/min/max 都是"轮子自己的数值"，怎么显示、怎么解释交给调用方，
 * 所以像"5 分钟一档"这种步长不需要在轮子里做特殊处理。
 */
@Composable
fun WheelBox(
    value: Int,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    display: (Int) -> String,
    /** 底色。默认跟随全局主题；时间设置弹窗里被 LocalLightField 锁成白天色。 */
    background: Color = if (LocalLightField.current) LIGHT_FIELD else FieldBg,
    onChange: (Int) -> Unit
) {
    // 手势/滚动回调里必须读最新值，否则会拿重组前的旧快照
    val latestValue = rememberUpdatedState(value)
    val latestDisplay = rememberUpdatedState(display)
    val latestOnChange = rememberUpdatedState(onChange)

    Box(
        modifier.height(104.dp).background(background, RoundedCornerShape(10.dp))
    ) {
        AndroidView(
            factory = { ctx ->
                NumberPicker(ctx).apply {
                    minValue = min
                    maxValue = max
                    wrapSelectorWheel = false
                    // 挡住内部 EditText 抢焦点，否则会出现输入法弹出来盖住滚轮
                    descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                    setFormatter { v -> latestDisplay.value(v) }
                    setOnValueChangedListener { _, _, newValue ->
                        if (newValue != latestValue.value) latestOnChange.value(newValue)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { picker ->
                // 顺序重要：先收敛区间再写值，避免中途越界被 NumberPicker 改掉又回灌
                if (picker.minValue != min) picker.minValue = min
                if (picker.maxValue != max) picker.maxValue = max
                if (picker.value != value) picker.value = value
            }
        )
    }
}

/** 带标题的滚轮，用于年/月/日这种一格一格的场景。 */
@Composable
fun LabeledWheel(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    display: (Int) -> String,
    onChange: (Int) -> Unit
) {
    Column(modifier) {
        Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(5.dp))
        WheelBox(value, min, max, Modifier.fillMaxWidth(), display, onChange = onChange)
    }
}

/**
 * 年 / 月 / 日 三个滚轮。
 *
 * 改了年或月之后，日的上限会跟着变（2 月只有 28/29 天），
 * 所以调用方需要在年/月变化时把 day 夹回当月最后一天 —— 这里也做一次兜底。
 */
@Composable
fun DateWheels(
    year: Int,
    month: Int,
    day: Int,
    modifier: Modifier = Modifier,
    onYearChange: (Int) -> Unit,
    onMonthChange: (Int) -> Unit,
    onDayChange: (Int) -> Unit
) {
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    val maxDay = daysInMonth(year, month)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledWheel("年", year, thisYear, thisYear + 3, Modifier.weight(1.3f), { "$it" }, onYearChange)
        LabeledWheel("月", month, 1, 12, Modifier.weight(0.85f), { "$it" }, onMonthChange)
        LabeledWheel("日", day.coerceIn(1, maxDay), 1, maxDay, Modifier.weight(0.85f), { "$it" }, onDayChange)
    }
}

// ---------- 时间设置：页面上只放一行，滚轮收进弹窗 ----------

/** 时间设置弹窗的载体：日期可选（运动计划没有日期），结束时间可选（事件是单点）。 */
data class TimeDraft(
    val year: Int,
    val month: Int,
    val day: Int,
    val startMin: Int,
    val endMin: Int
)

/**
 * 「当前值 + 设置时间」一行。
 *
 * 之前把两三排滚轮直接铺在页面上，一屏就被占掉大半；改成点开弹窗再调，
 * 页面只留这一行摘要。
 */
@Composable
fun TimeSettingRow(summary: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(summary, color = Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClick) { Text("设置时间") }
    }
}

/**
 * 精简版时间设置弹窗。
 *
 * 内部用的是**草稿值**，所以点"取消"不会改动外面的数据。
 * 结束时间的下限永远跟着开始时间走，滚不出"结束早于开始"。
 */
@Composable
fun TimeSettingDialog(
    showDate: Boolean,
    showEnd: Boolean,
    initial: TimeDraft,
    onDismiss: () -> Unit,
    onConfirm: (TimeDraft) -> Unit
) {
    var year by remember { mutableIntStateOf(initial.year) }
    var month by remember { mutableIntStateOf(initial.month) }
    var day by remember { mutableIntStateOf(initial.day) }
    var startMin by remember { mutableIntStateOf(initial.startMin) }
    var endMin by remember { mutableIntStateOf(initial.endMin) }

    /** 改开始时间时把结束时间推到"开始 + 1 小时"。 */
    fun setStart(total: Int) {
        startMin = total
        if (showEnd) endMin = (total + 60).coerceAtMost(LAST_MINUTE)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置时间", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            // 锁成白天配色：弹窗本身是浅色的，里面的滚轮也跟着浅色才一致
            CompositionLocalProvider(LocalLightField provides true) {
            Column(Modifier.fillMaxWidth()) {
                if (showDate) {
                    DateWheels(
                        year = year,
                        month = month,
                        day = day,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        onYearChange = { y -> year = y; day = day.coerceAtMost(daysInMonth(y, month)) },
                        onMonthChange = { m -> month = m; day = day.coerceAtMost(daysInMonth(year, m)) },
                        onDayChange = { day = it }
                    )
                }

                Text(
                    if (showEnd) "开始时间" else "时间",
                    color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp
                )
                Spacer(Modifier.height(5.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelBox(startMin / 60, 0, 23, Modifier.weight(1f), { "%02d".format(it) }) { h -> setStart(h * 60 + startMin % 60) }
                    Text(":", color = Ink, fontWeight = FontWeight.Bold)
                    WheelBox(startMin % 60, 0, 59, Modifier.weight(1f), { "%02d".format(it) }) { m -> setStart(startMin / 60 * 60 + m) }
                }

                if (showEnd) {
                    Spacer(Modifier.height(8.dp))
                    Text("结束时间", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(5.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WheelBox(endMin / 60, startMin / 60, 23, Modifier.weight(1f), { "%02d".format(it) }) { h ->
                            val m = if (h == startMin / 60) (endMin % 60).coerceAtLeast(startMin % 60) else endMin % 60
                            endMin = h * 60 + m
                        }
                        Text(":", color = Ink, fontWeight = FontWeight.Bold)
                        WheelBox(endMin % 60, if (endMin / 60 == startMin / 60) startMin % 60 else 0, 59, Modifier.weight(1f), { "%02d".format(it) }) { m ->
                            endMin = endMin / 60 * 60 + m
                        }
                    }
                }
            }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(TimeDraft(year, month, day, startMin, endMin)) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ---------- 从已存的展示字符串里解析回各字段（编辑已有条目时要用） ----------

/** 待办里存的是 "2026-10-15 09:00–10:30"。 */
data class ParsedRange(val year: Int, val month: Int, val day: Int, val startMinute: Int, val endMinute: Int)

/** 事件里存的是 "2026-10-20 18:00"。 */
data class ParsedMoment(val year: Int, val month: Int, val day: Int, val minuteOfDay: Int)

/** 从文本里取第一个 HH:mm，返回"当天第几分钟"。 */
private fun parseClock(text: String): Int? {
    val m = Regex("""(\d{1,2}):(\d{2})""").find(text) ?: return null
    val hour = m.groupValues[1].toIntOrNull() ?: return null
    val minute = m.groupValues[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

fun parseRange(time: String): ParsedRange? {
    val startMs = parseWhenMillis(time) ?: return null
    val cal = Calendar.getInstance().apply { timeInMillis = startMs }
    val startMinute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    val endMinute = parseClock(time.substringAfter('–', ""))?.coerceAtLeast(startMinute)
        ?: (startMinute + 60).coerceAtMost(LAST_MINUTE)
    return ParsedRange(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
        startMinute,
        endMinute
    )
}

fun parseMoment(text: String): ParsedMoment? {
    val ms = parseWhenMillis(text) ?: return null
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    return ParsedMoment(
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH),
        cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    )
}

/** 运动计划的起止时段，形如 "19:00–20:00"。 */
fun parseTimeRange(text: String): Pair<Int, Int>? {
    val clocks = Regex("""(\d{1,2}):(\d{2})""").findAll(text).map { it.value }.toList()
    if (clocks.isEmpty()) return null
    val start = parseClock(clocks[0]) ?: return null
    val end = clocks.getOrNull(1)?.let { parseClock(it) }?.takeIf { it >= start }
        ?: (start + 60).coerceAtMost(LAST_MINUTE)
    return start to end
}
