package io.github.jherobred.lodisave

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Lodi floating over every app. Tap Copy link on a post in any app, then tap Lodi: the share card
 * opens over the app and saves the original file from that link. Drag him to either edge; hold him
 * to hide him.
 */
class BubbleService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE || !Settings.canDrawOverlays(this)) {
            if (intent?.action == ACTION_HIDE) Prefs(this).bubble = false
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 13 and older know no "special use" type, and refuse one they don't know.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION, note(), type)
        if (shown == null) show()
        return START_STICKY
    }

    private fun note(): Notification {
        val copy = Copy.of(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, copy.bubble, NotificationManager.IMPORTANCE_MIN))
        val hide = PendingIntent.getService(this, 0, Intent(this, BubbleService::class.java).setAction(ACTION_HIDE), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lodi)
            .setContentTitle(copy.bubble)
            .setContentText(copy.bubbleNote)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, copy.bubbleHide, hide)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility") // a tap opens the share card through performClick below
    private fun show() {
        val wm = getSystemService(WindowManager::class.java)
        val prefs = Prefs(this)
        val density = resources.displayMetrics.density
        val size = (60 * density).toInt()
        val width = { resources.displayMetrics.widthPixels }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.bubbleLeft) 0 else width() - size
            y = prefs.bubbleY
        }
        val lodi = ImageView(this).apply {
            setImageResource(R.drawable.lodi)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(this@BubbleService, R.color.icon_background))
            }
            val pad = (7 * density).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 6 * density
            contentDescription = getString(R.string.share_label)
            setOnClickListener {
                animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
                // With the picker on, Lodi shows round checkboxes on the app's photos and videos.
                // Without it he saves the copied link.
                PickerService.instance?.toggle() ?: startActivity(Intent(this@BubbleService, ShareActivity::class.java)
                    .putExtra(ShareActivity.EXTRA_CLIPBOARD, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            setOnLongClickListener {
                prefs.bubble = false
                stopSelf()
                true
            }
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        lodi.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) { dragging = true; v.cancelLongPress() }
                    if (dragging) {
                        params.x = startX + (e.rawX - downX).toInt()
                        params.y = startY + (e.rawY - downY).toInt()
                        wm.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    // Settle against the nearer edge, where he stays out of the way.
                    val left = params.x + size / 2 < width() / 2
                    prefs.bubbleLeft = left
                    prefs.bubbleY = params.y
                    ValueAnimator.ofInt(params.x, if (left) 0 else width() - size).apply {
                        duration = 220
                        addUpdateListener { if (v.isAttachedToWindow) { params.x = it.animatedValue as Int; wm.updateViewLayout(v, params) } }
                    }.start()
                    return@setOnTouchListener true
                }
            }
            dragging
        }
        wm.addView(lodi, params)
        shown = lodi
    }

    override fun onDestroy() {
        shown?.let { getSystemService(WindowManager::class.java).removeView(it) }
        shown = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "bubble"
        const val NOTIFICATION = 8
        const val ACTION_HIDE = "hide"

        /** Lodi while he floats, which the picker moves out of the way of its shots and taps. */
        @SuppressLint("StaticFieldLeak")
        var shown: View? = null
            private set

        /** Floats Lodi when the setting is on and Android allows drawing over other apps, and hides him otherwise. */
        fun sync(ctx: Context) {
            val intent = Intent(ctx, BubbleService::class.java)
            if (Prefs(ctx).bubble && Settings.canDrawOverlays(ctx)) ContextCompat.startForegroundService(ctx, intent)
            else ctx.stopService(intent)
        }
    }
}
