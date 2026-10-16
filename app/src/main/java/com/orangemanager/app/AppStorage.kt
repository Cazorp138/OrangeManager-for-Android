package com.orangemanager.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 底栏可选的全部页面。
 * "首页" 固定在最左、"设置" 固定在最右，两者都不占用用户的可选名额。
 */
val ALL_TABS = listOf("首页", "待办", "事件", "记事", "专注", "购物", "运动")

/** 底栏默认显示的功能。 */
val DEFAULT_TABS = listOf("首页", "待办", "事件", "记事", "专注")

/** 需要跨进程存活的所有状态，一次性打包读写。 */
data class AppSnapshot(
    val todos: List<Todo> = emptyList(),
    val events: List<EventItem> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val shopping: List<String> = emptyList(),
    val checked: Set<String> = emptySet(),
    val notes: List<Note> = emptyList(),
    val focusMinutes: Int = 25,
    val homeImageUri: String? = null,
    val bg: BgTransform = BgTransform(),
    val focusSoundUri: String? = null,
    /** 专注模式里是否允许点"提前结束"退出。关掉之后只能等倒计时走完。 */
    val focusAllowEarlyStop: Boolean = true,
    /** 黑夜模式。false = 原来的白天模式。 */
    val darkTheme: Boolean = false,
    val selectedTabs: List<String> = DEFAULT_TABS
)

private const val PREFS_NAME = "orange_manager_state"
private const val KEY_SNAPSHOT = "snapshot"

/**
 * 规范化底栏配置，顺带做一次历史迁移。
 *
 * 早期版本把「事件」页错标成了「时间」（标签写"时间"，图标和跳转的却都是事件页），
 * 这种旧值可能已经存进磁盘，所以要在这里映射回来。
 * 同时统一按 ALL_TABS 的顺序输出，避免底栏顺序随用户点击先后漂移。
 *
 * 不做数量限制：所有页面都允许放进底栏。
 */
private fun sanitizeTabs(raw: List<String>): List<String> {
    if (raw.isEmpty()) return DEFAULT_TABS
    val migrated = raw.map { if (it == "时间") "事件" else it }
    val picked = ALL_TABS.filter { it != "首页" && migrated.contains(it) }
    return listOf("首页") + picked
}

/**
 * 轻量持久化：把整个快照序列化成一个 JSON 存进 SharedPreferences。
 *
 * 之所以不上 Room / DataStore：这里的数据量就是个位数十来条，
 * 整存整取完全够用，而且不引入任何新依赖 —— 也就不会和
 * Compose BOM 2024.12.01 这种被钉死的版本产生冲突。
 */
class AppStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 任何解析异常都退回默认值 —— 磁盘上就算躺着半截损坏的数据，
     * 也只是表现为"数据被重置"，绝不会让应用启动即崩。
     */
    fun load(): AppSnapshot {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return AppSnapshot()
        return try {
            val root = JSONObject(raw)
            AppSnapshot(
                todos = objectsOf(root.optJSONArray("todos")).mapNotNull { it.toTodo() },
                events = objectsOf(root.optJSONArray("events")).mapNotNull { it.toEvent() },
                exercises = objectsOf(root.optJSONArray("exercises")).mapNotNull { it.toExercise() },
                shopping = stringsOf(root.optJSONArray("shopping")),
                checked = stringsOf(root.optJSONArray("checked")).toSet(),
                notes = notesOf(root.optJSONArray("notes")),
                focusMinutes = root.optInt("focusMinutes", 25).coerceIn(1, 600),
                homeImageUri = if (root.isNull("homeImageUri")) null
                else root.optString("homeImageUri").ifEmpty { null },
                bg = root.optJSONObject("bg").toBgTransform(),
                focusSoundUri = if (root.isNull("focusSoundUri")) null
                else root.optString("focusSoundUri").ifEmpty { null },
                focusAllowEarlyStop = root.optBoolean("focusAllowEarlyStop", true),
                darkTheme = root.optBoolean("darkTheme", false),
                selectedTabs = sanitizeTabs(stringsOf(root.optJSONArray("selectedTabs")))
            )
        } catch (_: Exception) {
            AppSnapshot()
        }
    }

    fun save(snapshot: AppSnapshot) {
        val root = JSONObject()
        root.put("todos", toJsonArray(snapshot.todos) { it.toJson() })
        root.put("events", toJsonArray(snapshot.events) { it.toJson() })
        root.put("exercises", toJsonArray(snapshot.exercises) { it.toJson() })
        root.put("shopping", toStringArray(snapshot.shopping))
        root.put("checked", toStringArray(snapshot.checked))
        root.put("notes", toJsonArray(snapshot.notes) { it.toJson() })
        root.put("focusMinutes", snapshot.focusMinutes)
        root.put("homeImageUri", snapshot.homeImageUri ?: JSONObject.NULL)
        root.put("bg", snapshot.bg.toJson())
        root.put("focusSoundUri", snapshot.focusSoundUri ?: JSONObject.NULL)
        root.put("focusAllowEarlyStop", snapshot.focusAllowEarlyStop)
        root.put("darkTheme", snapshot.darkTheme)
        root.put("selectedTabs", toStringArray(snapshot.selectedTabs))
        prefs.edit().putString(KEY_SNAPSHOT, root.toString()).apply()
    }
}

// ---------- JSON 编解码 ----------

private fun BgTransform.toJson(): JSONObject {
    val o = JSONObject()
    o.put("scale", scale.toDouble())
    o.put("offsetX", offsetX.toDouble())
    o.put("offsetY", offsetY.toDouble())
    o.put("dim", dim.toDouble())
    o.put("fill", fill)
    return o
}

/** 缺字段或数值越界都夹到合法范围，避免旧数据把渲染搞出奇怪效果。 */
private fun JSONObject?.toBgTransform(): BgTransform {
    if (this == null) return BgTransform()
    return BgTransform(
        scale = optDouble("scale", 1.0).toFloat().coerceIn(1f, 3f),
        offsetX = optDouble("offsetX", 0.0).toFloat().coerceIn(-1f, 1f),
        offsetY = optDouble("offsetY", 0.0).toFloat().coerceIn(-1f, 1f),
        dim = optDouble("dim", 1.0).toFloat().coerceIn(0f, 1f),
        fill = optBoolean("fill", false)
    )
}

private fun Todo.toJson(): JSONObject {
    val o = JSONObject()
    o.put("title", title)
    o.put("time", time)
    o.put("place", place)
    o.put("priority", priority)
    o.put("reminderMinutes", reminderMinutes)
    o.put("remind", remind)
    o.put("category", category)
    return o
}

private fun JSONObject.toTodo(): Todo? {
    val title = optString("title", "")
    if (title.isEmpty()) return null
    return Todo(
        title = title,
        time = optString("time", ""),
        place = optString("place", ""),
        priority = optString("priority", "必做"),
        reminderMinutes = optInt("reminderMinutes", 0),
        category = optString("category", "待办"),
        remind = optBoolean("remind", true)
    )
}

private fun EventItem.toJson(): JSONObject {
    val o = JSONObject()
    o.put("title", title)
    o.put("time", time)
    o.put("place", place)
    o.put("reminderMinutes", reminderMinutes)
    o.put("remind", remind)
    // 每周事件重复的星期；普通事件写空数组
    o.put("weekdays", toStringArray(weekdays.map { it.toString() }))
    return o
}

private fun JSONObject.toEvent(): EventItem? {
    val title = optString("title", "")
    if (title.isEmpty()) return null
    return EventItem(
        title = title,
        time = optString("time", ""),
        place = optString("place", ""),
        reminderMinutes = optInt("reminderMinutes", 0),
        remind = optBoolean("remind", true),
        // 缺字段（旧数据）就是普通事件；越界的星期丢弃，避免脏数据算出奇怪的日期
        weekdays = stringsOf(optJSONArray("weekdays")).mapNotNull { it.toIntOrNull() }.filter { it in 1..7 }.distinct().sorted()
    )
}

private fun Exercise.toJson(): JSONObject {
    val o = JSONObject()
    o.put("title", title)
    o.put("goal", goal)
    o.put("time", time)
    return o
}

private fun JSONObject.toExercise(): Exercise? {
    val title = optString("title", "")
    if (title.isEmpty()) return null
    return Exercise(title, optString("goal", ""), optString("time", ""))
}

private fun Note.toJson(): JSONObject {
    val o = JSONObject()
    o.put("title", title)
    o.put("content", content)
    return o
}

/**
 * 记事读取，**兼容旧格式**。
 *
 * 上一版把整条笔记当成一个字符串存在数组里，现在改成了 {title, content} 对象。
 * 这里遇到字符串元素就当成"没有标题、整段都是正文"迁移过来 ——
 * 否则用户已经写好的笔记会在升级后凭空消失。
 */
private fun notesOf(array: JSONArray?): List<Note> {
    if (array == null) return emptyList()
    val out = ArrayList<Note>(array.length())
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i)
        if (obj != null) {
            val title = obj.optString("title", "")
            val content = obj.optString("content", "")
            if (title.isNotBlank() || content.isNotBlank()) out.add(Note(title, content))
        } else {
            val legacy = array.optString(i, "")
            if (legacy.isNotEmpty()) out.add(Note("", legacy))
        }
    }
    return out
}

private fun <T> toJsonArray(items: Iterable<T>, mapper: (T) -> JSONObject): JSONArray {
    val arr = JSONArray()
    items.forEach { arr.put(mapper(it)) }
    return arr
}

private fun toStringArray(items: Iterable<String>): JSONArray {
    val arr = JSONArray()
    items.forEach { arr.put(it) }
    return arr
}

private fun objectsOf(array: JSONArray?): List<JSONObject> {
    if (array == null) return emptyList()
    val out = ArrayList<JSONObject>(array.length())
    for (i in 0 until array.length()) array.optJSONObject(i)?.let { out.add(it) }
    return out
}

private fun stringsOf(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    val out = ArrayList<String>(array.length())
    for (i in 0 until array.length()) {
        val s = array.optString(i, "")
        if (s.isNotEmpty()) out.add(s)
    }
    return out
}
