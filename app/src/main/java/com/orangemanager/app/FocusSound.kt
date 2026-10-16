package com.orangemanager.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/**
 * 专注结束的提示音。
 *
 * 自定义音必须由应用自己播：Android 8 起通知的声音由**通知渠道**决定、
 * 不能按条指定，所以选了自定义音时横幅走静音渠道，声音在这里放。
 * 选"默认"时反过来 —— 直接让横幅渠道自己响，不用这里再放一遍，否则会响两次。
 */
object FocusSound {

    private var player: MediaPlayer? = null

    fun play(context: Context, customUri: String?) {
        stop()
        val uri: Uri = (if (customUri.isNullOrBlank()) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {
            Uri.parse(customUri)
        }) ?: return

        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.setOnCompletionListener { done ->
                done.release()
                if (player === done) player = null
            }
            mp.setOnErrorListener { bad, _, _ ->
                bad.release()
                if (player === bad) player = null
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        val current = player ?: return
        player = null
        try {
            current.release()
        } catch (_: Exception) { }
    }
}

/** 取音频文件在系统里的显示名，用于设置页展示当前选的是哪个文件。 */
fun audioDisplayName(context: Context, uriString: String?): String? {
    if (uriString.isNullOrBlank()) return null
    return try {
        context.contentResolver.query(
            Uri.parse(uriString),
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }
}
