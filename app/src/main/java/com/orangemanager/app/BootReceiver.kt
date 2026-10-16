package com.orangemanager.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新之后把待办和事件的提醒重新排上。
 *
 * 系统的 AlarmManager 闹钟是存在内存里的，重启会被清空，覆盖安装也会清空。
 * 数据本身是持久化的，所以这里读回磁盘重排一遍就恢复了。
 *
 * 注意用的是 sendImmediatelyIfPast = false —— 开机时不能给历史条目补发通知，
 * 否则每次重启都会弹一串"xxx已经开始"。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val snapshot = AppStorage(context).load()
        val now = System.currentTimeMillis()

        snapshot.todos.forEach { todo ->
            if (!todo.remind) return@forEach
            // 开始时刻由固定的 "yyyy-MM-dd HH:mm" 前缀还原
            val startMillis = parseWhenMillis(todo.time) ?: return@forEach
            if (startMillis <= now) return@forEach
            ReminderScheduler.scheduleReminder(
                context = context,
                name = todo.title,
                detail = "${todo.time} · ${todo.place}",
                startMillis = startMillis,
                leadMinutes = todo.reminderMinutes,
                endMillis = endMillisOf(todo.time),
                sendImmediatelyIfPast = false
            )
        }

        snapshot.events.forEach { event ->
            if (!event.remind) return@forEach
            // 每周事件里存的 time 是"最初设定的那个日期"，早就过去了，
            // 所以不能直接拿它排闹钟 —— 要算下一个命中的星期几。
            val minuteOfDay = parseMoment(event.time)?.minuteOfDay
            val startMillis = if (event.weekly && minuteOfDay != null) {
                nextWeeklyOccurrence(event.weekdays, minuteOfDay, now) ?: return@forEach
            } else {
                parseWhenMillis(event.time) ?: return@forEach
            }
            if (startMillis <= now) return@forEach
            ReminderScheduler.scheduleReminder(
                context = context,
                name = event.title,
                detail = "${event.time} · ${event.place}",
                startMillis = startMillis,
                leadMinutes = event.reminderMinutes,
                weekdays = event.weekdays,
                minuteOfDay = minuteOfDay,
                sendImmediatelyIfPast = false
            )
        }
    }
}
