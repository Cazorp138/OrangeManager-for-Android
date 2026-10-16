package com.orangemanager.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 首页时间线上的一行。
 *
 * startMs / endMs 留着给界面判断"还未开始 / 正在进行 / 已结束"，
 * todo / event 留着给左滑删除定位是哪一条。
 */
data class AgendaEntry(
    val sortKey: Long,
    val kind: String,
    val title: String,
    val detail: String,
    val timeLabel: String,
    /** 结束时刻的文字。目前只有待办有，首页会显示在开始时间下面一行。 */
    val endLabel: String? = null,
    val startMs: Long?,
    val endMs: Long?,
    /** 已结束时是否置灰。每周事件要求"结束后透明度不变"，所以传 false。 */
    val dimWhenFinished: Boolean = true,
    /** 能否在首页左滑删除。每周事件只能去事件页删，所以传 false。 */
    val deletableFromHome: Boolean = true,
    val todo: Todo? = null,
    val event: EventItem? = null
)

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** 事件没有结束时刻，按 1 小时估一个，好让"正在进行 / 已结束"能显示出来。 */
private const val EVENT_ASSUMED_MS = 60 * 60 * 1000L

private val TIME_RE = Regex("""(\d{1,2}):(\d{2})""")
private val DATE_RE = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")

/** 把"某天第几分钟"拼成一个绝对时刻。 */
private fun millisOf(year: Int, month: Int, day: Int, minuteOfDay: Int): Long =
    Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/** 从 "2026-10-15 09:00–10:30" 里取出结束时刻，用来发"已结束"通知。 */
fun endMillisOf(time: String): Long? {
    val range = parseRange(time) ?: return null
    return millisOf(range.year, range.month, range.day, range.endMinute)
}

// ---------- 每周事件 ----------

/** ISO 星期：1=周一 … 7=周日。Calendar 是 1=周日，换算一下。 */
private fun isoWeekdayOf(cal: Calendar): Int {
    val dow = cal.get(Calendar.DAY_OF_WEEK)
    return if (dow == Calendar.SUNDAY) 7 else dow - 1
}

/** 星期的中文名。 */
fun weekdayName(iso: Int): String = when (iso) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

/** 把一组星期拼成"周一、周三"。 */
fun weekdayLabel(days: List<Int>): String =
    days.sorted().joinToString("、") { weekdayName(it) }

/**
 * 下一次命中这些星期的该时刻。
 *
 * [strictlyAfter] = false 允许返回正好等于 [from] 的时刻（首页展开用这个）；
 * true 表示严格往后找（通知链式重排用这个 —— 否则闹钟响完会把自己又排一遍，
 * 同一时刻反复触发）。
 */
fun nextWeeklyOccurrence(
    weekdays: List<Int>,
    minuteOfDay: Int,
    from: Long,
    strictlyAfter: Boolean = false
): Long? {
    if (weekdays.isEmpty()) return null
    val cal = Calendar.getInstance()
    // 一周最多 7 天，往后找 8 天一定覆盖得到
    for (offset in 0..7) {
        cal.timeInMillis = from
        cal.add(Calendar.DAY_OF_MONTH, offset)
        cal.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        cal.set(Calendar.MINUTE, minuteOfDay % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (isoWeekdayOf(cal) !in weekdays) continue
        val t = cal.timeInMillis
        if (if (strictlyAfter) t > from else t >= from) return t
    }
    return null
}

private fun eventDetailOf(event: EventItem): String =
    if (event.place == "未设置地点") "" else event.place

/**
 * 汇总首页要展示的时间线。
 *
 * 规则（宁可多显示，也不让用户觉得"我加的条目不见了"）：
 *  - 能解析出时间、且落在 [今天 00:00, 现在 + 24h] 内的待办/事件，按时间升序排列；
 *  - 每周事件把窗口内的每一次都展开成独立一行（24 小时内最多两次），
 *    仍然按时间先后和普通待办 / 事件并列排序；
 *  - 今天更早、已经开始的条目保留，只是置灰（每周事件除外）；
 *  - 完全解析不出时间的条目排在最后，标注"时间待定"。
 */
fun buildAgenda(todos: List<Todo>, events: List<EventItem>): List<AgendaEntry> {
    val now = System.currentTimeMillis()
    val horizon = now + DAY_MS
    val dayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val dated = ArrayList<AgendaEntry>()
    val undated = ArrayList<AgendaEntry>()
    val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault())

    // 待办的起止时刻从 "2026-10-15 09:00–10:30" 里还原
    todos.forEach { todo ->
        val range = parseRange(todo.time)
        val startMs = range?.let { millisOf(it.year, it.month, it.day, it.startMinute) }
        val endMs = range?.let { millisOf(it.year, it.month, it.day, it.endMinute) }
        val detail = listOf(todo.place, todo.priority)
            .filter { it.isNotBlank() && it != "未设置地点" }
            .joinToString(" · ")
        val entry = AgendaEntry(
            sortKey = startMs ?: Long.MAX_VALUE,
            kind = "待办",
            title = todo.title,
            detail = detail,
            timeLabel = if (startMs == null) "--:--" else hhmm.format(Date(startMs)),
            // 结束时间只在真的和开始时间不同时才显示，免得出现 "09:00 / 09:00"
            endLabel = if (endMs != null && endMs != startMs) hhmm.format(Date(endMs)) else null,
            startMs = startMs,
            endMs = endMs,
            todo = todo
        )
        if (startMs == null) undated.add(entry) else if (startMs in dayStart..horizon) dated.add(entry)
    }

    events.forEach { event ->
        val minuteOfDay = parseMoment(event.time)?.minuteOfDay
        if (event.weekly && minuteOfDay != null) {
            // 每周事件：把窗口内每一次都摊开成一行，和普通条目一起按时间排。
            // dimWhenFinished = false → 结束后不置灰；
            // deletableFromHome = false → 首页不给删，只能去事件页删。
            var cursor = dayStart - 1
            var guard = 0
            while (guard++ < 4) {
                val at = nextWeeklyOccurrence(event.weekdays, minuteOfDay, cursor, strictlyAfter = true) ?: break
                if (at > horizon) break
                dated.add(
                    AgendaEntry(
                        sortKey = at,
                        kind = "事件",
                        title = event.title,
                        detail = eventDetailOf(event),
                        timeLabel = hhmm.format(Date(at)),
                        startMs = at,
                        endMs = at + EVENT_ASSUMED_MS,
                        dimWhenFinished = false,
                        deletableFromHome = false,
                        event = event
                    )
                )
                cursor = at
            }
        } else {
            val startMs = parseWhenMillis(event.time)
            val entry = AgendaEntry(
                sortKey = startMs ?: Long.MAX_VALUE,
                kind = "事件",
                title = event.title,
                detail = eventDetailOf(event),
                timeLabel = if (startMs == null) "--:--" else hhmm.format(Date(startMs)),
                startMs = startMs,
                endMs = startMs?.plus(EVENT_ASSUMED_MS),
                event = event
            )
            if (startMs == null) undated.add(entry) else if (startMs in dayStart..horizon) dated.add(entry)
        }
    }

    return dated.sortedBy { it.sortKey } + undated
}

/** 首页那一行要显示的状态文案。 */
fun agendaStatusLabel(startMs: Long?, endMs: Long?, now: Long = System.currentTimeMillis()): String = when {
    startMs == null -> "时间待定"
    now < startMs -> "还未开始"
    endMs != null && now >= endMs -> "已结束"
    else -> "正在进行"
}

/**
 * 从自由文本里解析一个时间点。
 *
 * 支持 `2026-10-15 09:00`、`2026/10/15 09:00`、`2026.10.15 09:00`、`今天 09:00`、
 * `明天 09:00`、`后天 09:00`、`2026-10-15`（无时刻按当天 00:00）、`09:00`（无日期按今天）。
 * 解析不出来返回 null —— 调用方按"时间待定"处理，而不是丢掉这条数据。
 */
fun parseWhenMillis(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    val timeMatch = TIME_RE.find(text)
    val dateMatch = DATE_RE.find(text)
    val cal = Calendar.getInstance()

    if (dateMatch != null) {
        val year = dateMatch.groupValues[1].toIntOrNull() ?: return null
        val month = dateMatch.groupValues[2].toIntOrNull() ?: return null
        val day = dateMatch.groupValues[3].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, day)
    } else {
        // 相对日期：明/后天。其余（今天、今日、空）都按今天处理。
        val head = text.substringBefore(' ').trim()
        when {
            head.startsWith("明") -> cal.add(Calendar.DAY_OF_MONTH, 1)
            head.startsWith("后") -> cal.add(Calendar.DAY_OF_MONTH, 2)
        }
    }

    if (timeMatch != null) {
        val hour = timeMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = timeMatch.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
    } else {
        // 只有日期没有时刻，按当天 00:00，这样它至少能落进时间窗口而不是被丢掉
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
    }
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** 首页标题下面那行日期，例如 "10月15日 星期三"。 */
fun todayLabel(): String = SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(Date())
