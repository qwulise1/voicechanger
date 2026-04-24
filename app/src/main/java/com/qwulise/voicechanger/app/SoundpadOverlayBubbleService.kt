package com.qwulise.voicechanger.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.qwulise.voicechanger.core.SoundpadPlayback
import com.qwulise.voicechanger.core.SoundpadSlot
import kotlin.math.abs

class SoundpadOverlayBubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var panelView: LinearLayout? = null
    private var padsColumn: LinearLayout? = null
    private var bubbleView: FrameLayout? = null
    private var bubbleAvatarView: ImageView? = null
    private var panelVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureOverlay()
        refreshOverlay()
        return START_STICKY
    }

    override fun onDestroy() {
        rootView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        rootView = null
        panelView = null
        padsColumn = null
        bubbleView = null
        bubbleAvatarView = null
        layoutParams = null
        windowManager = null
        super.onDestroy()
    }

    private fun ensureOverlay() {
        if (!canDraw(this)) {
            return
        }
        if (rootView != null) {
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val localPadsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                localPadsColumn,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(TextView(this).apply {
            text = "Soundpad"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(TextView(this).apply {
            text = "Тап по карточке запускает или останавливает конкретный звук."
            setTextColor(Color.argb(210, 232, 224, 214))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(6), 0, dp(10))
        })
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(dp(230), dp(300)),
        )
        panelView = panel
        padsColumn = localPadsColumn
        root.addView(
            panel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            },
        )

        val avatar = ImageView(context).apply {
            setImageResource(resolveAvatarRes())
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.TRANSPARENT)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        val bubble = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dp(14).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(avatar, FrameLayout.LayoutParams(dp(56), dp(56)))
        }
        bubble.setOnTouchListener(DragClickListener(params) {
            panelVisible = !panelVisible
            refreshOverlay()
        })
        bubbleView = bubble
        bubbleAvatarView = avatar
        root.addView(bubble)

        windowManager?.addView(root, params)
        rootView = root
        layoutParams = params
        refreshOverlay()
    }

    private fun refreshOverlay() {
        val root = rootView ?: return
        val panel = panelView ?: return
        val bubble = bubbleView ?: return
        val avatar = bubbleAvatarView ?: return
        val column = padsColumn ?: return
        val settings = UiSettingsStore.read(this)
        val library = ModuleConfigClient.loadSoundpadLibrary(this).sanitized()
        val playback = ModuleConfigClient.loadSoundpadPlayback(this).sanitized()
        val readySlots = library.slots.filter { it.isReady }
        if (readySlots.isEmpty()) {
            stopSelf()
            return
        }

        val overlayOpacity = settings.overlayOpacityPercent.coerceIn(35, 100) / 100f
        val alpha = (overlayOpacity * 255f).toInt().coerceIn(80, 255)
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.argb(alpha, 24, 18, 14))
            setStroke(dp(1), Color.argb((alpha * 0.68f).toInt(), 255, 255, 255))
        }
        panel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.argb((alpha * 0.94f).toInt().coerceIn(70, 255), 20, 16, 14))
            setStroke(dp(1), Color.argb((alpha * 0.52f).toInt().coerceIn(50, 255), 255, 255, 255))
        }
        bubble.alpha = overlayOpacity
        avatar.alpha = 1f
        panel.alpha = overlayOpacity
        panel.visibility = if (panelVisible) View.VISIBLE else View.GONE

        column.removeAllViews()
        readySlots.forEachIndexed { index, slot ->
            column.addView(
                padCard(slot, playback),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index > 0) {
                        topMargin = dp(8)
                    }
                },
            )
        }
        layoutParams?.let { params ->
            windowManager?.updateViewLayout(root, params)
        }
    }

    private fun padCard(slot: SoundpadSlot, playback: SoundpadPlayback): LinearLayout {
        val isPlaying = playback.playing && playback.activeSlotId == slot.id
        val accent = OVERLAY_ACCENTS[slot.accentIndex % OVERLAY_ACCENTS.size]
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.argb(if (isPlaying) 228 else 188, Color.red(accent), Color.green(accent), Color.blue(accent)),
                    Color.argb(if (isPlaying) 164 else 134, 28, 24, 20),
                ),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.argb(164, Color.red(accent), Color.green(accent), Color.blue(accent)))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            addView(TextView(this@SoundpadOverlayBubbleService).apply {
                text = slot.title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@SoundpadOverlayBubbleService).apply {
                text = buildString {
                    append(slot.subtitle.ifBlank { "Готов к запуску" })
                    append(" • ")
                    append(if (isPlaying) "Стоп" else "Пуск")
                }
                setTextColor(Color.argb(215, 236, 228, 216))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(6), 0, 0)
            })
            setOnClickListener { toggleSlot(slot, playback) }
        }
    }

    private fun toggleSlot(slot: SoundpadSlot, playback: SoundpadPlayback) {
        val updated = if (playback.playing && playback.activeSlotId == slot.id) {
            playback.copy(
                playing = false,
                sessionId = System.currentTimeMillis(),
            )
        } else {
            playback.copy(
                activeSlotId = slot.id,
                playing = true,
                sessionId = System.currentTimeMillis(),
            )
        }.sanitized()
        ModuleConfigClient.saveSoundpadPlayback(this, updated)
        refreshOverlay()
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
                    rootView?.let { windowManager?.updateViewLayout(it, params) }
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
        private val OVERLAY_ACCENTS = intArrayOf(
            Color.parseColor("#F0BE36"),
            Color.parseColor("#25D1D8"),
            Color.parseColor("#F16E8D"),
            Color.parseColor("#98E05B"),
            Color.parseColor("#76A6FF"),
            Color.parseColor("#FF9A52"),
        )

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
