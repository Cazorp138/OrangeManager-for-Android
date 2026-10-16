package com.orangemanager.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 专注页的三个状态。状态本身由 OrangeManagerApp 持有，这里只负责展示。 */
enum class FocusPhase { Idle, Running, Done }

/** 专注时长的档位步长：轮子一格 = 5 分钟。 */
private const val STEP_MINUTES = 5
private const val MAX_FOCUS_MINUTES = 300

/**
 * 专注页（纯展示）。
 *
 * 计时状态**故意不放在这里**：如果放在页面内部，切到别的标签页会让这个 composable
 * 离开组合、协程被取消。状态放在上层，切页面、切后台都能继续走。
 */
@Composable
fun FocusScreen(
    minutes: Int,
    onMinutes: (Int) -> Unit,
    phase: FocusPhase,
    remaining: Int,
    doneMinutes: Int,
    allowEarlyStop: Boolean,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    bitmap: Bitmap?,
    bg: BgTransform,
    contentPadding: PaddingValues
) {
    val stepIndex = (minutes / STEP_MINUTES).coerceIn(1, MAX_FOCUS_MINUTES / STEP_MINUTES)

    // 关掉"提前结束"之后，点屏幕会蹦一个 ㊗️，一秒后自己消失 ——
    // 给个回应，否则用户会以为界面卡死了。
    var showStuck by remember { mutableStateOf(false) }
    LaunchedEffect(showStuck) {
        if (showStuck) {
            kotlinx.coroutines.delay(1000)
            showStuck = false
        }
    }

    when (phase) {
        FocusPhase.Idle -> Column(Modifier.fillMaxSize().background(AppBg).padding(contentPadding)) {
            Header("专注模式")
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(16.dp))
                CardBox {
                    Text("专注时长", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    // 时长滚轮同样锁成白天浅色底：NumberPicker 的文字是系统主题给的
                    // 深色，底色一变深就看不清了。和时间设置弹窗保持一致。
                    androidx.compose.runtime.CompositionLocalProvider(LocalLightField provides true) {
                        WheelBox(
                            value = stepIndex,
                            min = 1,
                            max = MAX_FOCUS_MINUTES / STEP_MINUTES,
                            modifier = Modifier.fillMaxWidth(),
                            display = { "${it * STEP_MINUTES} 分" },
                            onChange = { onMinutes(it * STEP_MINUTES) }
                        )
                    }
                    Button(
                        onClick = { onStart(stepIndex * STEP_MINUTES) },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
                    ) { Text("开始专注") }
                }
            }
        }

        FocusPhase.Running -> Box(Modifier.fillMaxSize().background(Ink)) {
            BackgroundImage(bitmap, bg, Modifier.fillMaxSize())
            if (!allowEarlyStop) {
                Box(Modifier.fillMaxSize().clickable { showStuck = true })
            }
            Column(
                Modifier.fillMaxSize().padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "%02d:%02d".format(remaining / 60, remaining % 60),
                    color = Color.White,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(20.dp))
                if (allowEarlyStop) {
                    // 留一个出口，否则选了 300 分钟就只能干等
                    TextButton(onClick = onStop) {
                        Text("提前结束", color = Color.White.copy(alpha = 0.9f))
                    }
                } else if (showStuck) {
                    Text("㊗️", fontSize = 40.sp)
                }
            }
        }

        FocusPhase.Done -> Box(
            Modifier.fillMaxSize().background(Ink).clickable { onDismiss() }
        ) {
            BackgroundImage(bitmap, bg, Modifier.fillMaxSize())
            Column(
                Modifier.fillMaxSize().padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("恭喜你专注了", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("$doneMinutes 分钟", color = Orange, fontSize = 42.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(20.dp))
                Text("点击屏幕返回", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            }
        }
    }
}
