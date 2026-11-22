package com.iphone.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap

data class MediaInfo(
    val title: String,
    val artist: String,
    val appName: String,
    val playing: Boolean
)

data class CallInfo(
    val caller: String,
    val status: String
)

object IslandState {
    var call by mutableStateOf<CallInfo?>(null)
    val badgeCounts = ConcurrentHashMap<String, Int>()
    var badgeVersion by mutableStateOf(0)
    var latestBanner by mutableStateOf<Pair<String, String>?>(null)

    fun refreshBadges(sbnList: Array<StatusBarNotification>?) {
        badgeCounts.clear()
        sbnList?.forEach { sbn ->
            val pkg = sbn.packageName
            if (!sbn.isOngoing) {
                badgeCounts[pkg] = (badgeCounts[pkg] ?: 0) + 1
            }
        }
        badgeVersion += 1
    }
}

class NotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            IslandState.refreshBadges(activeNotifications)
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val title = it.notification.extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = it.notification.extras.getCharSequence("android.text")?.toString().orEmpty()
            if (title.isNotBlank() || text.isNotBlank()) {
                IslandState.latestBanner = it.packageName to (if (title.isNotBlank()) title else text)
            }
        }
        try {
            IslandState.refreshBadges(activeNotifications)
        } catch (_: Exception) {
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        try {
            IslandState.refreshBadges(activeNotifications)
        } catch (_: Exception) {
        }
    }
}

class MediaWatcher(private val context: Context) {
    var info by mutableStateOf<MediaInfo?>(null)
        private set

    private var activeController: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindController(controllers?.firstOrNull())
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            syncSessions()
            handler.postDelayed(this, 3000L)
        }
    }

    fun start() {
        syncSessions()
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, 3000L)
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(context, NotificationListener::class.java)
            msm.addOnActiveSessionsChangedListener(sessionListener, cn, handler)
        } catch (_: Exception) {
        }
    }

    fun stop() {
        handler.removeCallbacks(pollRunnable)
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {
        }
        activeController = null
    }

    private fun syncSessions() {
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val cn = ComponentName(context, NotificationListener::class.java)
            val controllers = msm.getActiveSessions(cn)
            val chosen = controllers.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING
            } ?: controllers.firstOrNull()
            bindController(chosen)
        } catch (_: Exception) {
        }
    }

    private fun bindController(controller: MediaController?) {
        activeController = controller
        if (controller == null) {
            info = null
            return
        }
        val meta = controller.metadata
        val state = controller.playbackState
        val title = meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown Track"
        val artist = meta?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: controller.packageName
        val appName = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
        } catch (_: Exception) {
            controller.packageName
        }
        val playing = state?.state == PlaybackState.STATE_PLAYING
        info = MediaInfo(title = title, artist = artist, appName = appName, playing = playing)
    }

    fun playPause() {
        val c = activeController
        if (c != null) {
            if (info?.playing == true) c.transportControls.pause() else c.transportControls.play()
        } else {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            } catch (_: Exception) {
            }
        }
    }

    fun next() {
        activeController?.transportControls?.skipToNext()
    }

    fun prev() {
        activeController?.transportControls?.skipToPrevious()
    }
}

@Composable
fun PlayerIcon(kind: Int, onClick: () -> Unit) {
    Canvas(
        Modifier.size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        val w = size.width
        val h = size.height
        when (kind) {
            0 -> {
                drawRect(Color.White, Offset(w * 0.16f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.56f))
                val p = Path().apply {
                    moveTo(w * 0.82f, h * 0.22f)
                    lineTo(w * 0.82f, h * 0.78f)
                    lineTo(w * 0.34f, h * 0.5f)
                    close()
                }
                drawPath(p, Color.White)
            }
            1 -> {
                val p = Path().apply {
                    moveTo(w * 0.28f, h * 0.2f)
                    lineTo(w * 0.82f, h * 0.5f)
                    lineTo(w * 0.28f, h * 0.8f)
                    close()
                }
                drawPath(p, Color.White)
            }
            2 -> {
                drawRect(Color.White, Offset(w * 0.24f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.56f))
                drawRect(Color.White, Offset(w * 0.6f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.56f))
            }
            else -> {
                val p = Path().apply {
                    moveTo(w * 0.18f, h * 0.22f)
                    lineTo(w * 0.66f, h * 0.5f)
                    lineTo(w * 0.18f, h * 0.78f)
                    close()
                }
                drawPath(p, Color.White)
                drawRect(Color.White, Offset(w * 0.72f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.12f, h * 0.56f))
            }
        }
    }
}

@Composable
fun DynamicIsland(
    media: MediaWatcher,
    call: CallInfo?,
    hasAccess: Boolean,
    onNeedAccess: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val info = media.info
    val banner = IslandState.latestBanner

    Box(
        Modifier.clip(RoundedCornerShape(if (expanded) 28.dp else 22.dp))
            .background(Color.Black)
            .animateContentSize(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
            .clickable {
                if (!hasAccess) {
                    onNeedAccess()
                } else {
                    expanded = !expanded
                }
            }
            .padding(horizontal = if (expanded) 16.dp else 14.dp, vertical = if (expanded) 12.dp else 8.dp)
    ) {
        if (!expanded) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                call != null -> Color(0xFF30D158)
                                info?.playing == true -> Color(0xFFFF9F0A)
                                !hasAccess -> Color(0xFFFF453A)
                                else -> Color(0xFF636366)
                            }
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        call != null -> "${call.caller} • ${call.status}"
                        info != null -> info.title
                        banner != null -> banner.second
                        !hasAccess -> "Enable Notification Access"
                        else -> "iPhone Island"
                    },
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Column(Modifier.width(300.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = info?.appName ?: if (call != null) "Active Call" else "Dynamic Island",
                        color = Color(0xFF0A84FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap to collapse",
                        color = Color(0xFF8E8E93),
                        fontSize = 10.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (call != null) {
                    Text(call.caller, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(call.status, color = Color(0xFF30D158), fontSize = 12.sp)
                } else if (info != null) {
                    Text(info.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(info.artist, color = Color(0xFFB0B0B5), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        PlayerIcon(0) { media.prev() }
                        PlayerIcon(if (info.playing) 2 else 1) { media.playPause() }
                        PlayerIcon(3) { media.next() }
                    }
                } else {
                    Text(
                        text = banner?.second ?: "No active media or call",
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

