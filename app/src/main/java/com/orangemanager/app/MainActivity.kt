package com.orangemanager.app

import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知带 full-screen intent 时，系统会拉起这个 Activity 用来点亮屏幕；
        // 这两个标志让它能显示在锁屏之上、并主动把屏幕点亮（配合通知里的
        // setFullScreenIntent —— 普通 heads-up 通知是不会唤醒屏幕的）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        appContext = applicationContext
        setContent { OrangeManagerApp() }
    }
}

// remind = false 表示这条完全不提醒（连"已经开始"都不发）。
// 页面在关闭开关时会直接传 startMillis = null，路由层现成的判空逻辑就跳过了排程。
data class Todo(val title: String, val time: String, val place: String, val priority: String, val reminderMinutes: Int = 0, val category: String = "待办", val remind: Boolean = true)
data class EventItem(
    val title: String,
    val time: String,
    val place: String,
    val reminderMinutes: Int = 0,
    val remind: Boolean = true,
    /** 每周事件要重复的星期（ISO：1=周一 … 7=周日）。空 = 普通事件。 */
    val weekdays: List<Int> = emptyList()
) {
    val weekly: Boolean get() = weekdays.isNotEmpty()
}
data class Exercise(val title: String, val goal: String, val time: String)

/**
 * 记事。标题可以不填，列表里会显示成"未命名"；正文是多行文本。
 */
data class Note(val title: String = "", val content: String = "")

@Composable
fun OrangeManagerApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val storage = remember { AppStorage(context) }
    // 只在首次组合时读一次磁盘，之后交给下面的 LaunchedEffect 回写
    val initial = remember { storage.load() }

    var tab by remember { mutableStateOf("首页") }
    val allTabs = ALL_TABS
    val selectedTabs = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(initial.selectedTabs) } }
    var todos by remember { mutableStateOf(initial.todos) }
    var events by remember { mutableStateOf(initial.events) }
    var exercises by remember { mutableStateOf(initial.exercises) }
    var shopping by remember { mutableStateOf(initial.shopping) }
    var checked by remember { mutableStateOf(initial.checked) }
    var focusMinutes by remember { mutableIntStateOf(initial.focusMinutes) }
    var homeImageUri by remember { mutableStateOf(initial.homeImageUri) }
    var bgTransform by remember { mutableStateOf(initial.bg) }
    var focusSoundUri by remember { mutableStateOf(initial.focusSoundUri) }
    var allowEarlyStop by remember { mutableStateOf(initial.focusAllowEarlyStop) }
    var notes by remember { mutableStateOf(initial.notes) }

    // 专注提示音的音频导入。同样必须用 OpenDocument 才能拿到持久化读权限，
    // 否则重启后读不到那个文件，提示音会静默失效。
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            focusSoundUri = uri.toString()
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }
    }

    // 底图只解码一次，首页 / 专注 / 设置预览共用同一份 ——
    // 三个界面各解一份的话，一张手机照片就会同时占三份内存。
    val bgBitmap = rememberBackgroundBitmap(homeImageUri)

    // 必须用 OpenDocument：GetContent 底层是 ACTION_GET_CONTENT，它不支持持久化读权限，
    // takePersistableUriPermission 会抛异常（原来被 catch 静默吞掉了），
    // 结果就是"选完图看着生效、一重启就没了"。
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            homeImageUri = uri.toString()
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), onResult = { })
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 首页和专注都是深色底图，状态栏图标必须切成白色，否则在照片上根本看不见。
    val rootView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(tab) {
        // view.context 有可能被 ContextThemeWrapper 包一层，所以往下再探一层
        val ctx = rootView.context
        val activity = ctx as? android.app.Activity
            ?: (ctx as? android.content.ContextWrapper)?.baseContext as? android.app.Activity
        val window = activity?.window ?: return@LaunchedEffect
        val immersive = tab == "首页" || tab == "专注"
        androidx.core.view.WindowCompat.getInsetsController(window, rootView).isAppearanceLightStatusBars = !immersive
    }

    // 任一状态变化就落盘。首帧只打标记，避免把 load() 失败后的默认值覆盖回磁盘。
    var hydrated by remember { mutableStateOf(false) }
    LaunchedEffect(todos, events, exercises, shopping, checked, notes, focusMinutes, homeImageUri, bgTransform, focusSoundUri, allowEarlyStop, darkModeState.value, selectedTabs.toList()) {
        if (!hydrated) { hydrated = true; return@LaunchedEffect }
        storage.save(
            AppSnapshot(
                todos = todos,
                events = events,
                exercises = exercises,
                shopping = shopping,
                checked = checked,
                notes = notes,
                focusMinutes = focusMinutes,
                homeImageUri = homeImageUri,
                bg = bgTransform,
                focusSoundUri = focusSoundUri,
                focusAllowEarlyStop = allowEarlyStop,
                darkTheme = darkModeState.value,
                selectedTabs = selectedTabs.toList()
            )
        )
    }
    // 专注计时的状态放在这一层、而不是 FocusScreen 内部 ——
    // 放在页面里的话，切到别的标签页会让 FocusScreen 离开组合、倒计时被取消。
    var focusPhase by remember { mutableStateOf(FocusPhase.Idle) }
    var focusRemaining by remember { mutableIntStateOf(0) }
    var focusEndsAt by remember { mutableLongStateOf(0L) }
    var focusDoneMinutes by remember { mutableIntStateOf(0) }

    /**
     * 结束或取消专注：撤销闹钟 + **立刻停掉铃声**。
     * 不停的话，用户点了屏幕回到专注页，铃声还在响。
     */
    fun endFocus() {
        ReminderScheduler.cancelFocusReminder(context, focusEndsAt)
        FocusSound.stop()
        focusPhase = FocusPhase.Idle
    }

    /**
     * 这个协程**只负责界面倒计时**，发通知交给 AlarmManager。
     *
     * 原因是熄屏：屏幕一关 CPU 会睡，协程里的 delay 没有唤醒能力，
     * 只能等下次亮屏才补发，那这个提醒就白做了。
     * 闹钟是 RTC_WAKEUP，到点能唤醒设备，进程被杀也照样响；
     * 好处是顺带不会出现"协程和闹钟各发一条"的重复通知。
     */
    LaunchedEffect(focusPhase) {
        if (focusPhase == FocusPhase.Running) {
            while (true) {
                val left = focusEndsAt - System.currentTimeMillis()
                if (left <= 0L) break
                focusRemaining = ((left + 999L) / 1000L).toInt()
                kotlinx.coroutines.delay(200)
            }
            focusRemaining = 0
            focusPhase = FocusPhase.Done
        }
    }

    // 精确闹钟是 Android 12 起的"特殊权限"，只能在系统设置页里开。
    // 挂在 ON_RESUME 上是为了从系统设置返回后能立刻刷新状态，不然会一直显示旧值。
    fun exactAlarmAllowedNow(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()

    var exactAlarmOk by remember { mutableStateOf(exactAlarmAllowedNow()) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                exactAlarmOk = exactAlarmAllowedNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 启动时把持久化的主题灌进全局状态；之后以它为唯一来源，设置页直接改它
    LaunchedEffect(Unit) { darkModeState.value = initial.darkTheme }

    // 主色统一橘色。primary 原来是 Green，导致按钮、勾选框、滚轮选中态、滑杆全是绿的。
    // background 跟着主题走：黑夜模式整页反成深色。
    MaterialTheme(colorScheme = lightColorScheme(primary = Orange, secondary = Orange, background = AppBg)) {
        // 专注进行中/结束时隐藏底栏，让用户别分心；点屏幕返回专注页后会自动恢复
        val hideBottomBar = tab == "专注" && focusPhase != FocusPhase.Idle
        Scaffold(containerColor = AppBg, bottomBar = {
            if (!hideBottomBar) {
            // 底栏全透明：不再是一条白带，直接露出后面的内容
            NavigationBar(containerColor = Color.Transparent) {
                // 底栏不限制个数，全都放进来时最多 7 格（6 个页面 + 设置）。
                // 格子一多，Material3 那个 64dp 的选中胶囊就会压到相邻项上，
                // 所以拥挤时把它设成透明，靠图标/文字变色来表示选中。
                val crowded = selectedTabs.size + 1 >= 6
                // 黑夜模式下底栏压在深色内容上，Material 默认那套深灰图标几乎看不见，
                // 所以显式换成高对比的白：选中的纯白，未选中的白但降一点亮度。
                val itemColors = when {
                    darkModeState.value -> NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.White,
                        selectedTextColor = Color.White,
                        unselectedIconColor = Color.White.copy(alpha = 0.70f),
                        unselectedTextColor = Color.White.copy(alpha = 0.70f),
                        indicatorColor = if (crowded) Color.Transparent else Color.White.copy(alpha = 0.18f)
                    )
                    crowded -> NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent)
                    else -> NavigationBarItemDefaults.colors()
                }
                selectedTabs.toList().forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = {
                    Icon(when(item) {
                        "首页" -> Icons.Default.Home
                        "待办" -> Icons.Default.CheckCircle
                        "事件" -> Icons.Default.Event
                        "记事" -> Icons.Default.Description
                        "专注" -> Icons.Default.Timer
                        "购物" -> Icons.Default.ShoppingCart
                        "运动" -> Icons.Default.FitnessCenter
                        else -> Icons.Default.Menu
                    }, contentDescription = item)
                }, label = { Text(item, maxLines = 1, fontSize = if (crowded) 10.sp else 12.sp) }, colors = itemColors) }
                // 设置固定在最右，不参与底栏自定义
                NavigationBarItem(
                    selected = tab == "设置",
                    onClick = { tab = "设置" },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                    label = { Text("设置", maxLines = 1, fontSize = if (crowded) 10.sp else 12.sp) },
                    colors = itemColors
                )
            }
            }
        }) { padding ->
            // 首页和专注要自己消费内边距：底图铺满整个窗口（含状态栏后面），
            // 文字内容才需要避开状态栏和底栏。
            if (tab == "首页") {
                HomeScreen(
                    todos = todos,
                    events = events,
                    bitmap = bgBitmap,
                    bg = bgTransform,
                    onDeleteTodo = { todos = todos - it },
                    onDeleteEvent = { events = events - it },
                    contentPadding = padding
                )
            } else if (tab == "专注") {
                FocusScreen(
                    minutes = focusMinutes,
                    onMinutes = { focusMinutes = it },
                    phase = focusPhase,
                    remaining = focusRemaining,
                    doneMinutes = focusDoneMinutes,
                    allowEarlyStop = allowEarlyStop,
                    onStart = { selected ->
                        focusDoneMinutes = selected
                        focusRemaining = selected * 60
                        val endsAt = System.currentTimeMillis() + selected * 60_000L
                        focusEndsAt = endsAt
                        // 到点由闹钟发通知（熄屏也能唤醒），协程只负责界面倒计时
                        ReminderScheduler.scheduleFocusReminder(context, endsAt, selected, focusSoundUri)
                        focusPhase = FocusPhase.Running
                    },
                    onStop = { endFocus() },
                    onDismiss = { endFocus() },
                    bitmap = bgBitmap,
                    bg = bgTransform,
                    contentPadding = padding
                )
            } else {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        "待办" -> TodoScreen(
                            todos = todos,
                            onDelete = { todos = todos - it },
                            onAdd = { item, startMillis ->
                                todos = todos + item
                                // 三条提醒：开始前 N 分钟的"即将开始"、开始时刻的"已经开始"，
                                // 以及结束时刻的"已结束"。时间点已经过去的会立刻发出去。
                                if (startMillis != null) {
                                    ReminderScheduler.scheduleReminder(context, item.title, "${item.time} · ${item.place}", startMillis, item.reminderMinutes, endMillisOf(item.time))
                                }
                            },
                            onUpdate = { original, item, startMillis ->
                                // 就地替换，保持原来的位置 —— 编辑一条不该把它挪到列表末尾
                                val index = todos.indexOf(original)
                                if (index >= 0) {
                                    todos = todos.toMutableList().also { it[index] = item }
                                }
                                if (startMillis != null) {
                                    ReminderScheduler.scheduleReminder(context, item.title, "${item.time} · ${item.place}", startMillis, item.reminderMinutes, endMillisOf(item.time))
                                }
                            }
                        )
                        "事件" -> EventScreen(
                            events = events,
                            onDeleteEvent = { events = events - it },
                            onAdd = { item, startMillis ->
                                events = events + item
                                if (startMillis != null) {
                                    ReminderScheduler.scheduleReminder(context, item.title, "${item.time} · ${item.place}", startMillis, item.reminderMinutes, weekdays = item.weekdays, minuteOfDay = parseMoment(item.time)?.minuteOfDay)
                                }
                            },
                            onUpdate = { original, item, startMillis ->
                                val index = events.indexOf(original)
                                if (index >= 0) {
                                    events = events.toMutableList().also { it[index] = item }
                                }
                                if (startMillis != null) {
                                    ReminderScheduler.scheduleReminder(context, item.title, "${item.time} · ${item.place}", startMillis, item.reminderMinutes, weekdays = item.weekdays, minuteOfDay = parseMoment(item.time)?.minuteOfDay)
                                }
                            }
                        )
                        "记事" -> NotesScreen(
                            notes = notes,
                            onDelete = { notes = notes - it },
                            onAdd = { notes = notes + it },
                            onUpdate = { original, item ->
                                val index = notes.indexOf(original)
                                if (index >= 0) notes = notes.toMutableList().also { it[index] = item }
                            }
                        )
                        "购物" -> ShoppingScreen(
                            shopping = shopping,
                            checked = checked,
                            onToggle = { name -> checked = if (name in checked) checked - name else checked + name },
                            onAdd = { shopping = shopping + it },
                            onUpdate = { original, renamed ->
                                val index = shopping.indexOf(original)
                                if (index >= 0) {
                                    shopping = shopping.toMutableList().also { it[index] = renamed }
                                    // 勾选状态跟着改名走，否则会留下一个勾不中的幽灵条目
                                    if (original in checked) checked = (checked - original) + renamed
                                }
                            },
                            onDelete = { name -> shopping = shopping - name; checked = checked - name }
                        )
                        "运动" -> ExerciseScreen(
                            exercises = exercises,
                            onDelete = { exercises = exercises - it },
                            onAdd = { exercises = exercises + it },
                            onUpdate = { original, item ->
                                val index = exercises.indexOf(original)
                                if (index >= 0) exercises = exercises.toMutableList().also { it[index] = item }
                            }
                        )
                        else -> SettingsScreen(allTabs, selectedTabs.toList(), onSelectedTabsChange = { chosen ->
                            selectedTabs.clear()
                            selectedTabs.add("首页")
                            // 不再限制个数：勾上的全都要
                            selectedTabs.addAll(allTabs.filter { it != "首页" && chosen.contains(it) })
                            if (tab !in selectedTabs && tab != "设置") tab = "首页"
                        }, imageUri = homeImageUri, bitmap = bgBitmap, bgTransform = bgTransform, onPickImage = { imagePicker.launch(arrayOf("image/*")) }, onClearImage = {
                            // 移除图片时把持久化读权限一并还回去，不要一直占着
                            homeImageUri?.let { old ->
                                try { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                            }
                            homeImageUri = null
                        }, focusSoundUri = focusSoundUri, onPickSound = { soundPicker.launch(arrayOf("audio/*")) }, onFocusSoundChange = { chosen ->
                            // 换回默认音时把旧文件的持久化权限还回去
                            if (chosen == null) {
                                focusSoundUri?.let { old ->
                                    try { context.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                                }
                                FocusSound.stop()
                            }
                            focusSoundUri = chosen
                        }, onPreviewSound = { focusSoundUri?.let { FocusSound.play(context, it) } }, allowEarlyStop = allowEarlyStop, onAllowEarlyStopChange = { allowEarlyStop = it }, exactAlarmOk = exactAlarmOk, onExactAlarmSettings = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                            .setData(Uri.fromParts("package", context.packageName, null))
                                    )
                                } catch (_: Exception) {
                                    // 个别 ROM 没有这个页面，退回应用详情页
                                    context.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                            .setData(Uri.fromParts("package", context.packageName, null))
                                    )
                                }
                            }
                        }, onBgTransformChange = { bgTransform = it }, onNotificationSettings = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        })
                    }
                }
            }
        }
    }
}



/** 重要等级配色。Green 只留给「无所谓」，这一档不动。 */
fun priorityColor(priority: String): Color = when (priority) {
    "必做" -> Red
    "一般" -> Yellow
    else -> Green
}

/** 保存待办时用它算提醒时刻。内部统一走 parseWhenMillis。 */
fun parseTodoStartMillis(date: String, time: String): Long? = parseWhenMillis("$date $time")

@Composable
fun TodoScreen(
    todos: List<Todo>,
    onDelete: (Todo) -> Unit,
    onAdd: (Todo, Long?) -> Unit,
    onUpdate: (Todo, Todo, Long?) -> Unit
) {
    val clock = remember { Calendar.getInstance() }
    var title by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("必做") }
    var reminderText by remember { mutableStateOf("10") }

    // 日期和时间的默认值都是"打开这个页面时的当前时间"
    var year by remember { mutableIntStateOf(clock.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(clock.get(Calendar.MONTH) + 1) }
    var day by remember { mutableIntStateOf(clock.get(Calendar.DAY_OF_MONTH)) }
    val firstMinute = remember { clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE) }
    var startMin by remember { mutableIntStateOf(firstMinute) }
    var endMin by remember { mutableIntStateOf((firstMinute + 60).coerceAtMost(LAST_MINUTE)) }

    // 非 null 表示正在编辑这一条，保存按钮会变成"更新待办"
    var editing by remember { mutableStateOf<Todo?>(null) }
    var remind by remember { mutableStateOf(true) }

    /** 把日期和时间复位到那一刻的当前时间。 */
    fun resetToNow() {
        val c = Calendar.getInstance()
        year = c.get(Calendar.YEAR)
        month = c.get(Calendar.MONTH) + 1
        day = c.get(Calendar.DAY_OF_MONTH)
        val nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        startMin = nowMin
        endMin = (nowMin + 60).coerceAtMost(LAST_MINUTE)
    }

    fun clearForm() {
        editing = null
        title = ""
        place = ""
        priority = "必做"
        reminderText = "10"
        remind = true
        resetToNow()
    }

    /** 点列表里的一条，把它回填到上面的表单里。 */
    fun loadForEdit(todo: Todo) {
        val parsed = parseRange(todo.time) ?: return
        title = todo.title
        place = if (todo.place == "未设置地点") "" else todo.place
        priority = todo.priority
        reminderText = todo.reminderMinutes.toString()
        remind = todo.remind
        year = parsed.year
        month = parsed.month
        day = parsed.day
        startMin = parsed.startMinute
        endMin = parsed.endMinute
        editing = todo
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("待办管理")
        Column(Modifier.weight(1f)) {
            LazyColumn {
                item {
                    CardBox {
                        Field("待办事项", title, { title = it })

                        Field("地点", place, { place = it })
                        Text("重要等级", fontWeight = FontWeight.SemiBold, color = Ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            listOf("必做", "一般", "无所谓").forEach { p ->
                                FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p) },
                                    colors = appChipColors(priorityColor(p).copy(alpha = .18f)))
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        // 滚轮收进弹窗，页面只留一行摘要 + 按钮，省掉一大片纵向空间
                        var showTime by remember { mutableStateOf(false) }
                        TimeSettingRow(
                            summary = "${formatDate(year, month, day)} ${formatMinutes(startMin)}–${formatMinutes(endMin)}",
                            onClick = { showTime = true }
                        )
                        if (showTime) {
                            TimeSettingDialog(
                                showDate = true,
                                showEnd = true,
                                initial = TimeDraft(year, month, day, startMin, endMin),
                                onDismiss = { showTime = false },
                                onConfirm = { draft ->
                                    year = draft.year
                                    month = draft.month
                                    day = draft.day
                                    startMin = draft.startMin
                                    endMin = draft.endMin
                                    showTime = false
                                }
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("提醒", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Ink)
                            Switch(checked = remind, onCheckedChange = { remind = it })
                        }
                        if (remind) {
                            Field(
                                "自定义提前提醒",
                                reminderText,
                                { value -> reminderText = value.filter { it.isDigit() }.take(5) },
                                suffix = "分"
                            )
                        }
                        Button(onClick = {
                            if (title.isNotBlank()) {
                                val dateStr = formatDate(year, month, day)
                                val startStr = formatMinutes(startMin)
                                val endStr = formatMinutes(endMin)
                                val built = Todo(
                                    title = title,
                                    time = "$dateStr $startStr–$endStr",
                                    place = place.ifBlank { "未设置地点" },
                                    priority = priority,
                                    reminderMinutes = (reminderText.toIntOrNull() ?: 0).coerceIn(0, 525600),
                                    category = "待办",
                                    remind = remind
                                )
                                // 关掉提醒就给 null，路由层现成的判空逻辑会自动跳过排程
                                val startMillis = if (remind) parseTodoStartMillis(dateStr, startStr) else null
                                val original = editing
                                if (original == null) onAdd(built, startMillis) else onUpdate(original, built, startMillis)
                                clearForm()
                            } else {
                                toastIncomplete()
                            }
                        }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(if (editing == null) "保存待办" else "更新待办")
                        }
                        if (editing != null) {
                            TextButton(onClick = { clearForm() }, modifier = Modifier.fillMaxWidth()) { Text("取消编辑") }
                        }
                    }
                    SectionTitle("待办列表", todos.size)
                }
                items(todos) { todo ->
                    SwipeToRevealDelete(enabled = true, onDelete = { onDelete(todo) }) {
                        CardBox {
                            Row(
                                Modifier.fillMaxWidth().clickable { loadForEdit(todo) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(todo.title, fontWeight = FontWeight.Medium, color = Ink)
                                    Text("${todo.time} · ${todo.place}", color = Color.Gray, fontSize = 12.sp)
                                    Text(todo.priority, color = priorityColor(todo.priority), fontSize = 12.sp)
                                }
                                if (!todo.remind) Text("不提醒", color = Color.Gray, fontSize = 11.sp)
                                if (editing == todo) Text("编辑中", color = Orange, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 事件页（只管事件）。运动已经拆成独立页面，所以这里不再有模式切换。 */
@Composable
fun EventScreen(
    events: List<EventItem>,
    onDeleteEvent: (EventItem) -> Unit,
    onAdd: (EventItem, Long?) -> Unit,
    onUpdate: (EventItem, EventItem, Long?) -> Unit
) {
    val clock = remember { Calendar.getInstance() }
    var title by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var year by remember { mutableIntStateOf(clock.get(Calendar.YEAR)) }
    var month by remember { mutableIntStateOf(clock.get(Calendar.MONTH) + 1) }
    var day by remember { mutableIntStateOf(clock.get(Calendar.DAY_OF_MONTH)) }
    var eventMin by remember { mutableIntStateOf(clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)) }
    var reminderText by remember { mutableStateOf("10") }
    var editing by remember { mutableStateOf<EventItem?>(null) }
    var remind by remember { mutableStateOf(true) }
    // 每周事件：勾选的星期（ISO 1=周一…7=周日）
    var weekly by remember { mutableStateOf(false) }
    var weekdays by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showWeekdays by remember { mutableStateOf(false) }

    fun resetToNow() {
        val c = Calendar.getInstance()
        year = c.get(Calendar.YEAR)
        month = c.get(Calendar.MONTH) + 1
        day = c.get(Calendar.DAY_OF_MONTH)
        eventMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun clearForm() {
        editing = null
        title = ""
        place = ""
        reminderText = "10"
        remind = true
        weekly = false
        weekdays = emptyList()
        resetToNow()
    }

    fun loadForEdit(event: EventItem) {
        val moment = parseMoment(event.time) ?: return
        title = event.title
        place = if (event.place == "未设置地点") "" else event.place
        reminderText = event.reminderMinutes.toString()
        remind = event.remind
        weekly = event.weekly
        weekdays = event.weekdays
        year = moment.year
        month = moment.month
        day = moment.day
        eventMin = moment.minuteOfDay
        editing = event
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("重要事件")
        LazyColumn(Modifier.weight(1f)) {
            item {
                CardBox {
                    Field("事件名称", title, { title = it })

                    Field("地点", place, { place = it })

                    Spacer(Modifier.height(12.dp))
                    // 滚轮收进弹窗，页面只留一行摘要 + 按钮
                    var showTime by remember { mutableStateOf(false) }
                    TimeSettingRow(
                        summary = "${formatDate(year, month, day)} ${formatMinutes(eventMin)}",
                        onClick = { showTime = true }
                    )
                    if (showTime) {
                        TimeSettingDialog(
                            showDate = true,
                            showEnd = false,
                            initial = TimeDraft(year, month, day, eventMin, eventMin),
                            onDismiss = { showTime = false },
                            onConfirm = { draft ->
                                year = draft.year
                                month = draft.month
                                day = draft.day
                                eventMin = draft.startMin
                                showTime = false
                            }
                        )
                    }

                    // 普通事件 / 每周事件。点"每周事件"会直接弹出星期选择框。
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = !weekly,
                            onClick = { weekly = false },
                            label = { Text("普通事件") },
                            colors = appChipColors(Orange.copy(alpha = .18f))
                        )
                        FilterChip(
                            selected = weekly,
                            onClick = { weekly = true; showWeekdays = true },
                            label = { Text("每周事件") },
                            colors = appChipColors(Orange.copy(alpha = .18f))
                        )
                    }
                    if (weekly) {
                        TimeSettingRow(
                            summary = if (weekdays.isEmpty()) "未选择星期" else weekdayLabel(weekdays),
                            onClick = { showWeekdays = true }
                        )
                    }
                    if (showWeekdays) {
                        WeekdayPickerDialog(
                            initial = weekdays,
                            onDismiss = { showWeekdays = false },
                            onConfirm = { picked ->
                                weekdays = picked
                                showWeekdays = false
                                // 一天都没勾就退回普通事件，免得存出一个没有星期的"每周事件"
                                if (picked.isEmpty()) weekly = false
                            }
                        )
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("提醒", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = Ink)
                        Switch(checked = remind, onCheckedChange = { remind = it })
                    }
                    if (remind) {
                        Field(
                            "自定义提前提醒",
                            reminderText,
                            { value -> reminderText = value.filter { it.isDigit() }.take(5) },
                            suffix = "分"
                        )
                    }
                    Button(onClick = {
                        if (title.isNotBlank()) {
                            if (weekly && weekdays.isEmpty()) {
                                // 选了"每周"却一天都没勾，先把选择框弹回去
                                showWeekdays = true
                            } else {
                                val timeStr = "${formatDate(year, month, day)} ${formatMinutes(eventMin)}"
                                val days = if (weekly) weekdays.sorted() else emptyList()
                                val built = EventItem(
                                    title = title,
                                    time = timeStr,
                                    place = place.ifBlank { "未设置地点" },
                                    reminderMinutes = (reminderText.toIntOrNull() ?: 0).coerceIn(0, 525600),
                                    remind = remind,
                                    weekdays = days
                                )
                                // 关掉提醒就给 null，路由层现成的判空逻辑会自动跳过排程。
                                // 每周事件排的是"下一次命中"，而不是 timeStr 里那个历史日期。
                                val startMillis = when {
                                    !remind -> null
                                    days.isNotEmpty() -> nextWeeklyOccurrence(days, eventMin, System.currentTimeMillis())
                                    else -> parseWhenMillis(timeStr)
                                }
                                val original = editing
                                if (original == null) onAdd(built, startMillis) else onUpdate(original, built, startMillis)
                                clearForm()
                            }
                        } else {
                            toastIncomplete()
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(if (editing == null) "保存事件" else "更新事件")
                    }
                    if (editing != null) {
                        TextButton(onClick = { clearForm() }, modifier = Modifier.fillMaxWidth()) { Text("取消编辑") }
                    }
                }
                SectionTitle("事件列表", events.size)
            }
            items(events) { event ->
                SwipeToRevealDelete(enabled = true, onDelete = { onDeleteEvent(event) }) {
                    CardBox {
                        Row(
                            Modifier.fillMaxWidth().clickable { loadForEdit(event) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(event.title, fontWeight = FontWeight.Medium, color = Ink)
                                Text(
                                    // 每周事件显示重复的星期 + 时间点；普通事件还是原来的日期时间
                                    if (event.weekly) "每周 ${weekdayLabel(event.weekdays)} ${event.time.substringAfter(' ')} · ${event.place}"
                                    else "${event.time} · ${event.place}",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            if (!event.remind) Text("不提醒", color = Color.Gray, fontSize = 11.sp)
                            if (editing == event) Text("编辑中", color = Orange, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 星期选择框。结果按 ISO 星期返回（1=周一 … 7=周日）。
 *
 * 弹窗本身始终是浅色的（主题一直是 lightColorScheme），所以这里用 FilterChip
 * 的默认配色 —— 不能用黑夜模式那套白字，否则就是白字压白底。
 */
@Composable
fun WeekdayPickerDialog(
    initial: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit
) {
    var picked by remember { mutableStateOf(initial.toSet()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择星期", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7)).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        row.forEach { d ->
                            FilterChip(
                                selected = d in picked,
                                onClick = { picked = if (d in picked) picked - d else picked + d },
                                label = { Text(weekdayName(d)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Orange.copy(alpha = .18f)
                                )
                            )
                        }
                    }
                }
                if (picked.isEmpty()) Text("至少选一天", color = Color.Gray, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked.sorted()) }, enabled = picked.isNotEmpty()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun SettingsScreen(
    allTabs: List<String>,
    selectedTabs: List<String>,
    onSelectedTabsChange: (List<String>) -> Unit,
    imageUri: String?,
    bitmap: Bitmap?,
    bgTransform: BgTransform,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onBgTransformChange: (BgTransform) -> Unit,
    focusSoundUri: String?,
    onPickSound: () -> Unit,
    onFocusSoundChange: (String?) -> Unit,
    onPreviewSound: () -> Unit,
    allowEarlyStop: Boolean,
    onAllowEarlyStopChange: (Boolean) -> Unit,
    exactAlarmOk: Boolean,
    onExactAlarmSettings: () -> Unit,
    onNotificationSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val soundName = remember(focusSoundUri) { audioDisplayName(context, focusSoundUri) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("设置", modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
        LazyColumn {
            item { CardBox {
                Row(Modifier.fillMaxWidth().clickable(onClick = onNotificationSettings).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("通知设置", modifier = Modifier.weight(1f), color = Ink)
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                }
            } }
            // 精确闹钟是 Android 12 才引入的特殊权限，低版本没有这个概念，直接不显示
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item { CardBox {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onExactAlarmSettings).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Alarm, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("精确闹钟", modifier = Modifier.weight(1f), color = Ink)
                        Text(
                            if (exactAlarmOk) "已开启" else "未开启",
                            color = if (exactAlarmOk) Orange else Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                } }
            }
            item { SectionTitle("专注提示音") ; CardBox {
                Row(
                    Modifier.fillMaxWidth().clickable { onFocusSoundChange(null) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = focusSoundUri == null, onClick = { onFocusSoundChange(null) })
                    Text("默认提示音", modifier = Modifier.weight(1f), color = Ink)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { onPickSound() }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = focusSoundUri != null, onClick = { onPickSound() })
                    Text("自定义音频", modifier = Modifier.weight(1f), color = Ink)
                }
                if (focusSoundUri != null) {
                    if (!soundName.isNullOrBlank()) {
                        Text(soundName, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onPreviewSound) { Text("试听") }
                        TextButton(onClick = onPickSound) { Text("更换音频") }
                    }
                }
            } }
            item { SectionTitle("专注模式") ; CardBox {
                // 关掉之后专注中就没有"提前结束"了，只能等倒计时走完；
                // 这时点屏幕会蹦一个 ㊗️ 作为回应。
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("允许提前结束", modifier = Modifier.weight(1f), color = Ink)
                    Switch(checked = allowEarlyStop, onCheckedChange = onAllowEarlyStopChange)
                }
            } }
            item { SectionTitle("外观") ; CardBox {
                // 白天 = 原来的配色；黑夜 = 黑白反色（橙 / 绿 / 红 / 黄保持不变）
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(false to "白天", true to "黑夜").forEach { (dark, label) ->
                        FilterChip(
                            selected = darkModeState.value == dark,
                            onClick = { darkModeState.value = dark },
                            label = { Text(label) },
                            colors = appChipColors(Orange.copy(alpha = .18f))
                        )
                    }
                }
            } }
            item { SectionTitle("底栏功能") ; CardBox {
                // 不限制个数，所有页面都能勾
                allTabs.filter { it != "首页" }.forEach { name ->
                    val isOn = name in selectedTabs
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onSelectedTabsChange(if (isOn) selectedTabs - name else selectedTabs + name) }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isOn,
                            onCheckedChange = { checked -> onSelectedTabsChange(if (checked) selectedTabs + name else selectedTabs - name) }
                        )
                        Text(name, modifier = Modifier.weight(1f), color = Ink)
                    }
                }
            } }
            item { SectionTitle("首页底图") ; CardBox {
                BackgroundImageEditor(
                    bitmap = bitmap,
                    imageUri = imageUri,
                    transform = bgTransform,
                    onPickImage = onPickImage,
                    onClearImage = onClearImage,
                    onTransformChange = onBgTransformChange
                )
            } }
        }
    }
}

/**
 * 记事页：参考小米备忘录重做。
 *
 * 列表是两列网格卡片（标题 + 摘要），点卡片进入全屏编辑页。
 * 内容在"返回 / 完成"时统一落盘；左滑卡片仍可删除。
 */
@Composable
fun NotesScreen(
    notes: List<Note>,
    onDelete: (Note) -> Unit,
    onAdd: (Note) -> Unit,
    onUpdate: (Note, Note) -> Unit
) {
    // 参考小米备忘录：列表是两列网格卡片，点卡片进入**全屏编辑页**，
    // 不再是"上面一个表单、下面一个列表"。
    // editing == null 表示在列表；非 null 表示编辑器开着，存的是"进来时的原文"。
    var editing by remember { mutableStateOf<Note?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var draftBody by remember { mutableStateOf("") }

    fun openEditor(note: Note) {
        editing = note
        draftTitle = note.title
        draftBody = note.content
    }

    /** 返回列表时才落盘：内容没变就不动，清空到什么都不剩就丢弃。 */
    fun closeEditor() {
        val original = editing ?: return
        val built = Note(draftTitle.trim(), draftBody.trim())
        if (built != original) {
            when {
                // 全空：新笔记直接丢弃，已有笔记也保持原样（不静默删掉用户的东西）
                built.title.isBlank() && built.content.isBlank() -> Unit
                notes.contains(original) -> onUpdate(original, built)
                else -> onAdd(built)
            }
        }
        editing = null
    }

    if (editing != null) {
        // ---------- 全屏编辑页 ----------
        Column(Modifier.fillMaxSize().background(AppBg)) {
            Row(
                Modifier.fillMaxWidth().background(Surface).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { closeEditor() }) { Text("返回", color = Orange) }
                Spacer(Modifier.weight(1f))
                Text("编辑笔记", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { closeEditor() }) { Text("完成", color = Orange) }
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                // 标题直接写在一行上（无边框），下面才是正文，跟备忘录一致
                Box(Modifier.fillMaxWidth()) {
                    if (draftTitle.isEmpty()) {
                        Text("标题", color = Color.Gray, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                    BasicTextField(
                        value = draftTitle,
                        onValueChange = { draftTitle = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        cursorBrush = caretBrush,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold
                        )
                    )
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (draftBody.isEmpty()) Text("开始输入…", color = Color.Gray, fontSize = 15.sp)
                    BasicTextField(
                        value = draftBody,
                        onValueChange = { draftBody = it },
                        modifier = Modifier.fillMaxSize(),
                        cursorBrush = caretBrush,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 15.sp)
                    )
                }
            }
        }
    } else {
        // ---------- 列表：两列网格 ----------
        Column(Modifier.fillMaxSize().background(AppBg).padding(horizontal = 16.dp)) {
            Header("记事")
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // 新建入口做成第一张卡片
                item {
                    Column(
                        Modifier.fillMaxWidth().height(118.dp)
                            .background(Surface, RoundedCornerShape(12.dp))
                            .clickable { openEditor(Note("", "")) },
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("+", color = Orange, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("新建笔记", color = Color.Gray, fontSize = 12.sp)
                    }
                }
                // 用 item 而不是 items 扩展，省掉一个 import
                notes.forEach { note ->
                    item {
                        SwipeToRevealDelete(enabled = true, onDelete = { onDelete(note) }) {
                            Column(
                                Modifier.fillMaxWidth().height(118.dp)
                                    .background(Surface, RoundedCornerShape(12.dp))
                                    .clickable { openEditor(note) }
                                    .padding(12.dp)
                            ) {
                                Text(
                                    // 没起标题的就显示"未命名"
                                    note.title.ifBlank { "未命名" },
                                    color = if (note.title.isBlank()) Color.Gray else Ink,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    maxLines = 1
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(note.content, color = Color.Gray, fontSize = 12.sp, maxLines = 4)
                            }
                        }
                    }
                }
            }
        }
    }
}



@Composable
fun ShoppingScreen(
    shopping: List<String>,
    checked: Set<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onUpdate: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    var item by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { Header("购物清单"); LazyColumn {
        item { CardBox {
            Field("商品", item, { item = it })
            Button(onClick = {
                if (item.isNotBlank()) {
                    val original = editing
                    if (original == null) onAdd(item.trim()) else onUpdate(original, item.trim())
                    item = ""; editing = null
                } else {
                    toastIncomplete()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (editing == null) "添加" else "更新") }
            if (editing != null) {
                TextButton(onClick = { editing = null; item = "" }, modifier = Modifier.fillMaxWidth()) { Text("取消编辑") }
            }
        } }
        items(shopping) { name ->
            SwipeToRevealDelete(enabled = true, onDelete = { onDelete(name) }) {
                CardBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = name in checked, onCheckedChange = { onToggle(name) })
                        Text(
                            name,
                            modifier = Modifier.weight(1f).clickable { item = name; editing = name },
                            color = Ink
                        )
                        if (editing == name) Text("编辑中", color = Orange, fontSize = 11.sp)
                    }
                }
            }
        }
    } }
}

@Composable
fun ExerciseScreen(
    exercises: List<Exercise>,
    onDelete: (Exercise) -> Unit,
    onAdd: (Exercise) -> Unit,
    onUpdate: (Exercise, Exercise) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    val firstMinute = remember { nowMinuteOfDay() }
    var startMin by remember { mutableIntStateOf(firstMinute) }
    var endMin by remember { mutableIntStateOf((firstMinute + 60).coerceAtMost(LAST_MINUTE)) }
    var editing by remember { mutableStateOf<Exercise?>(null) }

    fun clearForm() {
        editing = null
        title = ""; goal = ""
        val now = nowMinuteOfDay()
        startMin = now
        endMin = (now + 60).coerceAtMost(LAST_MINUTE)
    }

    fun loadForEdit(exercise: Exercise) {
        val range = parseTimeRange(exercise.time) ?: return
        title = exercise.title
        goal = exercise.goal
        startMin = range.first
        endMin = range.second
        editing = exercise
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { Header("运动计划"); LazyColumn {
        item { CardBox {
            Field("运动项目", title, { title = it })
            Field("目标", goal, { goal = it })

            // 滚轮收进弹窗；运动计划没有日期，所以弹窗里只显示时间
            var showTime by remember { mutableStateOf(false) }
            TimeSettingRow(
                summary = "${formatMinutes(startMin)}–${formatMinutes(endMin)}",
                onClick = { showTime = true }
            )
            if (showTime) {
                TimeSettingDialog(
                    showDate = false,
                    showEnd = true,
                    // showDate = false 时年月日只是占位，不会被用到
                    initial = TimeDraft(2000, 1, 1, startMin, endMin),
                    onDismiss = { showTime = false },
                    onConfirm = { draft ->
                        startMin = draft.startMin
                        endMin = draft.endMin
                        showTime = false
                    }
                )
            }
            Button(onClick = {
                if (title.isNotBlank()) {
                    val built = Exercise(title, goal, "${formatMinutes(startMin)}–${formatMinutes(endMin)}")
                    val original = editing
                    if (original == null) onAdd(built) else onUpdate(original, built)
                    clearForm()
                } else {
                    toastIncomplete()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (editing == null) "添加运动计划" else "更新运动计划") }
            if (editing != null) {
                TextButton(onClick = { clearForm() }, modifier = Modifier.fillMaxWidth()) { Text("取消编辑") }
            }
        } }
        items(exercises) { ex ->
            SwipeToRevealDelete(enabled = true, onDelete = { onDelete(ex) }) {
                CardBox {
                    Row(
                        Modifier.fillMaxWidth().clickable { loadForEdit(ex) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ex.title, color = Ink, fontWeight = FontWeight.Medium)
                            Text("${ex.goal} · ${ex.time}", color = Color.Gray, fontSize = 12.sp)
                        }
                        if (editing == ex) Text("编辑中", color = Orange, fontSize = 11.sp)
                    }
                }
            }
        }
    } }
}
