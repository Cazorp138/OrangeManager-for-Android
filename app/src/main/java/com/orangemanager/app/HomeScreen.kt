package com.orangemanager.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 首页：全屏背景图 + 叠加在上面的「未来 24 小时」时间线。 */
@Composable
fun HomeScreen(
    todos: List<Todo>,
    events: List<EventItem>,
    bitmap: Bitmap?,
    bg: BgTransform,
    onDeleteTodo: (Todo) -> Unit,
    onDeleteEvent: (EventItem) -> Unit,
    contentPadding: PaddingValues
) {
    val agenda = remember(todos, events) { buildAgenda(todos, events) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        // 底图和压暗遮罩都在 BackgroundImage 里，与专注页、设置页预览共用同一套渲染
        BackgroundImage(bitmap, bg, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)) {
                // "橘"字用主题橙，后面的 manager 保持白字（首页是压在底图上的深色底）
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("橘", color = Orange, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("manager", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Text(todayLabel(), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
            Text(
                "未来 24 小时",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
            )
            if (agenda.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("未来 24 小时没有安排", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(agenda) { entry -> AgendaRow(entry, onDeleteTodo, onDeleteEvent) }
                }
            }
        }
    }
}

/**
 * 时间线的一行：左侧时间徽标 + 类型色条 + 标题/详情 + 右侧类型与状态。
 *
 * 只有**已结束**的条目能左滑露出红色删除按钮 —— 未结束的不给删，
 * 避免手滑把正在进行的事删掉。
 */
@Composable
private fun AgendaRow(
    entry: AgendaEntry,
    onDeleteTodo: (Todo) -> Unit,
    onDeleteEvent: (EventItem) -> Unit
) {
    val now = System.currentTimeMillis()
    val status = agendaStatusLabel(entry.startMs, entry.endMs, now)
    val started = entry.startMs != null && now >= entry.startMs
    val isEvent = entry.event != null
    val weekly = entry.event?.weekly == true

    // 状态文字：事件一旦开始就不再显示任何文字 —— 不留"正在进行"，也不换"已结束"。
    // 待办照旧保留三态；"时间待定"照常显示。
    val label = if (isEvent && started) "" else status
    // 变淡：每周事件始终不变淡；普通事件"开始后就"变淡；待办是结束后变淡。
    val dim = when {
        !entry.dimWhenFinished -> false
        isEvent -> started
        else -> status == "已结束"
    }

    // 首页可删的时机：普通事件"发生后"就能删（与变淡一致）；
    // 待办仍是结束后；每周事件永远只能在事件页删。
    val deletable = when {
        !entry.deletableFromHome -> false
        isEvent -> started
        else -> status == "已结束"
    }

    SwipeToRevealDelete(
        // 每周事件不能在首页删，只能去事件页删
        enabled = deletable,
        onDelete = {
            entry.todo?.let(onDeleteTodo)
            entry.event?.let(onDeleteEvent)
        }
    ) {
        Row(
            Modifier.fillMaxWidth()
                .background(Surface.copy(alpha = if (dim) 0.70f else 0.93f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    entry.timeLabel,
                    color = if (dim) Color.Gray else Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                // 待办有结束时间，补在开始时间下面一行（之前只显示开始时间）
                if (entry.endLabel != null) {
                    Text(entry.endLabel, color = Color.Gray, fontSize = 11.sp, maxLines = 1)
                }
            }
            Box(
                Modifier.width(3.dp).height(32.dp)
                    // 每周事件用绿色竖条，和普通待办 / 事件区分开
                    .background(
                        when {
                            dim -> Color.Gray
                            weekly -> Green
                            else -> Orange
                        },
                        RoundedCornerShape(50)
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    color = if (dim) Color.Gray else Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                if (entry.detail.isNotBlank()) Text(entry.detail, color = Color.Gray, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(entry.kind, color = Color.Gray, fontSize = 10.sp)
                // 开始了的事件整行不渲染：不留空文字占位，也不替换成别的词
                if (label.isNotBlank()) {
                    Text(
                        label,
                        color = when (label) {
                            "正在进行" -> Orange
                            "已结束" -> Color.Gray
                            else -> Ink.copy(alpha = 0.55f)
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
