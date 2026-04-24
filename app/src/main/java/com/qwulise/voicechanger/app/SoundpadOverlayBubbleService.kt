package com.qwulise.voicechanger.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs

class SoundpadOverlayBubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureBubble()
        return START_STICKY
    }

    override fun onDestroy() {
        bubbleView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        bubbleView = null
        layoutParams = null
        windowManager = null
        super.onDestroy()
    }

    private fun ensureBubble() {
        if (!canDraw(this) || bubbleView != null) {
            return
        }

        windowManager = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(18)
            y = dp(120)
        }
        val bubble = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(212, 24, 18, 14))
                setStroke(dp(1), Color.argb(108, 255, 255, 255))
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dp(12).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(ImageView(context).apply {
                setImageResource(resolveAvatarRes())
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.TRANSPARENT)
                }
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
            })
        }

        bubble.setOnTouchListener(DragClickListener(params) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_START_PAGE, MainActivity.START_PAGE_SOUNDPAD)
                },
            )
        })

        windowManager?.addView(bubble, params)
        bubbleView = bubble
        layoutParams = params
    }

    private fun resolveAvatarRes(): Int {
        val resource = resources.getIdentifier("qwulivoice_avatar", "drawable", packageName)
        return if (resource != 0) resource else android.R.drawable.sym_def_app_icon
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private inner class DragClickListener(
        private val params: WindowManager.LayoutParams,
        private val onClick: () -> Unit,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragged = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (downX - event.rawX).toInt()
                    val deltaY = (event.rawY - downY).toInt()
                    if (abs(deltaX) > dp(3) || abs(deltaY) > dp(3)) {
                        dragged = true
                    }
                    params.x = startX + deltaX
                    params.y = startY + deltaY
                    windowManager?.updateViewLayout(view, params)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        onClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    companion object {
        fun sync(context: Context, show: Boolean, userInitiated: Boolean) {
            if (!show) {
                context.stopService(Intent(context, SoundpadOverlayBubbleService::class.java))
                return
            }
            if (!canDraw(context)) {
                if (userInitiated) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}"),
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }
                return
            }
            context.startService(Intent(context, SoundpadOverlayBubbleService::class.java))
        }

        fun canDraw(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }
}
