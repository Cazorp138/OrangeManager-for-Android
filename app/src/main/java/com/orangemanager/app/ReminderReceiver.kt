package com.orangemanager.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** 闹钟到点后的入口。真正发通知的逻辑在 ReminderScheduler.post 里，两边共用。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "橘manager 提醒"
        val body = intent.getStringExtra(ReminderScheduler.EXTRA_BODY).orEmpty()
        val id = intent.getIntExtra(
            ReminderScheduler.EXTRA_ID,
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        )
        val silent = intent.getBooleanExtra(ReminderScheduler.EXTRA_SILENT, false)
        val sound = intent.getStringExtra(ReminderScheduler.EXTRA_SOUND)

        ReminderScheduler.post(context, id, title, body, silent)
        // 自定义铃声由应用自己放（渠道是静音的），见 FocusSound 的说明
        if (!sound.isNullOrBlank()) FocusSound.play(context, sound)

        // 每周事件：这一响之后把下一次排上，形成链式重复。
        // 只有"已经开始"那条带这个标记 —— 否则两条通知各自往下链会指数增长。
        if (intent.getBooleanExtra(ReminderScheduler.EXTRA_CHAIN_WEEKLY, false)) {
            val weekdays = intent.getStringExtra(ReminderScheduler.EXTRA_WEEKDAYS)
                .orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }
            val minuteOfDay = intent.getIntExtra(ReminderScheduler.EXTRA_MINUTE_OF_DAY, -1)
            val name = intent.getStringExtra(ReminderScheduler.EXTRA_NAME).orEmpty()
            val lead = intent.getIntExtra(ReminderScheduler.EXTRA_LEAD, 0)
            if (weekdays.isNotEmpty() && minuteOfDay in 0..1439 && name.isNotEmpty()) {
                // 用"这一响所属的发生时刻"当基准，而不是当前时刻：
                // 闹钟可能早几十毫秒触发，拿 now 当基准会把刚响完的今天这次
                // 又算成"下一次"，于是重复排一遍（还会多发一条"即将开始"）。
                val fired = intent.getLongExtra(ReminderScheduler.EXTRA_START_MILLIS, 0L)
                val base = if (fired > 0L) fired else System.currentTimeMillis()
                val next = nextWeeklyOccurrence(weekdays, minuteOfDay, base, strictlyAfter = true)
                if (next != null) {
                    ReminderScheduler.scheduleReminder(
                        context = context,
                        name = name,
                        detail = body,
                        startMillis = next,
                        leadMinutes = lead,
                        weekdays = weekdays,
                        minuteOfDay = minuteOfDay
                    )
                }
            }
        }
    }
}

object ReminderScheduler {
    const val CHANNEL_ID = "orange_manager_reminders"

    /**
     * 选自定义提示音时横幅走这个渠道：它没有声音，声音由 FocusSound 自己播。
     * Android 8 起通知的声音由渠道决定、不能按条指定，所以只能这么绕。
     */
    const val CHANNEL_ID_SILENT = "orange_manager_focus_silent"
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_ID = "notificationId"
    const val EXTRA_SILENT = "silent"
    const val EXTRA_SOUND = "soundUri"

    // ---- 每周事件的链式重排用 ----
    /** 原始名称。标题带了"已经开始"后缀，重排时必须用原始名重新拼。 */
    const val EXTRA_NAME = "name"
    /** 逗号分隔的 ISO 星期，例如 "1,3,5"。空 = 普通事件，不重排。 */
    const val EXTRA_WEEKDAYS = "weekdays"
    const val EXTRA_MINUTE_OF_DAY = "minuteOfDay"
    const val EXTRA_LEAD = "lead"
    /** 这一响所属的发生时刻。链式重排必须以它为基准（见 onReceive 的说明）。 */
    const val EXTRA_START_MILLIS = "startMillis"
    /** 只有"已经开始"那条带这个标记，避免两条通知各自往下链造成指数增长。 */
    const val EXTRA_CHAIN_WEEKLY = "chainWeekly"

    /**
     * 为一条待办或事件安排提醒，一共两条：
     *  - 开始前 [leadMinutes] 分钟 → "<名称>即将开始"
     *  - 开始时刻 → "<名称>已经开始"
     *
     * 如果某个时间点已经过去了，就**立刻发出去**，而不是排到过去的时间上
     * （排到过去要么立刻触发、要么永远不触发，两种都不是用户想看到的）。
     *
     * [sendImmediatelyIfPast] 传 false 时过去的时间点直接跳过、不补发 ——
     * 开机恢复提醒用的就是这个模式，否则每次重启都会给历史条目弹一串通知。
     */
    fun scheduleReminder(
        context: Context,
        name: String,
        detail: String,
        startMillis: Long,
        leadMinutes: Int,
        endMillis: Long? = null,
        /** 每周事件：要重复的 ISO 星期。非空时"已经开始"那条闹钟响后会自动排下一次。 */
        weekdays: List<Int> = emptyList(),
        /** 每周事件的时间（当天第几分钟），重排下一次要用。 */
        minuteOfDay: Int? = null,
        sendImmediatelyIfPast: Boolean = true
    ) {
        val now = System.currentTimeMillis()
        val lead = leadMinutes.coerceAtLeast(0)
        val leadAt = startMillis - lead * 60_000L
        val soonText = "${name}即将开始"
        val startText = "${name}已经开始"
        val soonId = idFor(name, startMillis, 0)
        val startId = idFor(name, startMillis, 1)

        // 提前量为 0 表示"准时提醒"：这时两条会落在同一时刻，
        // 只发"已经开始"那条，否则同一秒钟会弹两条通知。
        if (lead > 0) {
            if (leadAt > now) {
                schedule(context, soonText, detail, leadAt, soonId)
            } else if (sendImmediatelyIfPast && now < startMillis) {
                // 已经落在提前提醒的时间窗里了 —— 按要求直接发"即将开始"
                post(context, soonId, soonText, detail)
            }
        }

        if (startMillis > now) {
            // 只有这一条带每周标记：响过之后由接收器排下一次，形成链式重复
            schedule(
                context, startText, detail, startMillis, startId,
                weekly = weekdays, minuteOfDay = minuteOfDay, name = name, lead = lead
            )
        } else if (sendImmediatelyIfPast) {
            // 连开始时间都过了，直接发"已经开始"
            post(context, startId, startText, detail)
        }

        // 第三条：结束时刻。"57 分结束的事，到 57 分就该响"。
        // 只有带结束时刻的（待办）才会有这条，事件是单点时刻所以没有。
        if (endMillis != null && endMillis > startMillis) {
            val endText = "${name}已结束"
            val endId = idFor(name, startMillis, 2)
            if (endMillis > now) {
                schedule(context, endText, detail, endMillis, endId)
            } else if (sendImmediatelyIfPast) {
                post(context, endId, endText, detail)
            }
        }
    }

    /**
     * 专注结束的提醒**走闹钟**，而不是靠进程里的协程计时。
     *
     * 原因就是熄屏：屏幕一关，CPU 会睡，协程里的 delay 不会把设备唤醒，
     * 只能等下次亮屏时才补发 —— 那这个提醒就白做了。
     * RTC_WAKEUP 能在到点时把设备叫醒，进程被杀掉也照样会响。
     */
    fun scheduleFocusReminder(context: Context, endsAt: Long, minutes: Int, soundUri: String?) {
        val requestCode = focusRequestCode(endsAt)
        val hasCustomSound = !soundUri.isNullOrBlank()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, "专注结束")
            putExtra(EXTRA_BODY, "恭喜你专注了 $minutes 分钟")
            putExtra(EXTRA_ID, requestCode)
            putExtra(EXTRA_SILENT, hasCustomSound)
            if (hasCustomSound) putExtra(EXTRA_SOUND, soundUri)
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, endsAt, pending)
        } else {
            alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, endsAt, pending)
        }
    }

    /** 提前结束、或者用户已经点掉结果页时撤销，免得"都返回了还响一声"。 */
    fun cancelFocusReminder(context: Context, endsAt: Long) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, focusRequestCode(endsAt), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(pending)
        pending.cancel()
    }

    private fun focusRequestCode(endsAt: Long): Int = "focus|$endsAt".hashCode()

    /**
     * 真正把通知发出去。
     * [silent] 为 true 时用静音渠道 —— 声音由调用方（FocusSound）自己播。
     */
    fun post(
        context: Context,
        notificationId: Int,
        title: String,
        body: String,
        silent: Boolean = false
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(manager)
        val channelId = if (silent) CHANNEL_ID_SILENT else CHANNEL_ID

        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 锁屏亮屏用。full-screen intent 会拉起 Activity，配合 MainActivity 里的
        // setTurnScreenOn / setShowWhenLocked，才能真正点亮屏幕并显示在锁屏之上。
        val wakeUp = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // 归到 ALARM 类：Android 14 起 full-screen intent 只对闹钟/来电类应用自动放行
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(wakeUp, true)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setVibrate(longArrayOf(0, 350, 180, 350))
        if (body.isNotBlank()) {
            builder.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // 用户没给通知权限，静默跳过（按需求就是"权限已经设置的情况下"才发）
        }
        vibrate(context)
    }

    /**
     * IMPORTANCE_HIGH 才会有横幅（heads-up）。渠道一旦建好，重要性就不能再改了，
     * 所以两个渠道都必须是 HIGH；区别只在静音渠道 setSound(null, null)。
     */
    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "橘manager 提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "橘manager 待办与事件提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 180, 350)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_SILENT, "专注提示音", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "使用自定义提示音时走这里，声音由应用播放"
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 350, 180, 350)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun schedule(
        context: Context,
        title: String,
        body: String,
        triggerAtMillis: Long,
        requestCode: Int,
        weekly: List<Int> = emptyList(),
        minuteOfDay: Int? = null,
        name: String = "",
        lead: Int = 0
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
            putExtra(EXTRA_ID, requestCode)
            putExtra(EXTRA_START_MILLIS, triggerAtMillis)
            // 每周事件：把"重排下一次"所需的信息随闹钟一起带过去
            if (weekly.isNotEmpty() && minuteOfDay != null) {
                putExtra(EXTRA_CHAIN_WEEKLY, true)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_WEEKDAYS, weekly.joinToString(","))
                putExtra(EXTRA_MINUTE_OF_DAY, minuteOfDay)
                putExtra(EXTRA_LEAD, lead)
            }
        }
        val pending = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        } else {
            // 用 setAlarmClock，而不是 setExactAndAllowWhileIdle。
            //
            // while-idle 那类闹钟在 Doze 下会被系统攒批 —— 官方限制是"每个应用每 9 分钟
            // 最多放行一次"。一条待办同时排了"提前 N 分钟"和"开始时刻"两个闹钟时，
            // 前者响完，后者往往就落在 9 分钟窗口里被压住，表现正是
            // "提前的通知收到了，开始时那一条没响"。
            //
            // setAlarmClock 是系统给闹钟应用的口子：Doze 下不延迟、也不受攒批限制。
            // 代价是状态栏会出现一个闹钟图标（系统用它表示"有即将触发的闹钟"）。
            val showIntent = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(
                android.app.AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
                pending
            )
        }
    }

    /**
     * 两条提醒必须用不同的 requestCode。
     *
     * PendingIntent 判断"是不是同一个"只比较 requestCode + Intent 的
     * action/data/type/class —— **不比较 extras**。两条用同一个 requestCode 的话，
     * 第二条会把第一条整个覆盖掉，用户就只会收到一条提醒。
     */
    private fun idFor(name: String, startMillis: Long, kind: Int): Int =
        "$name|$startMillis|$kind".hashCode()

    private fun vibrate(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 180, 350), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 180, 350), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 350, 180, 350), -1)
                }
            }
        } catch (_: Exception) { }
    }
}
