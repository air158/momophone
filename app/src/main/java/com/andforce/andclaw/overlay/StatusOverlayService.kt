package com.andforce.andclaw.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.andforce.andclaw.AgentController
import com.andforce.andclaw.ChatHistoryActivity
import com.andforce.andclaw.databinding.OverlayStatusBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatusOverlayService : Service() {

    private var binding: OverlayStatusBinding? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showOverlay()
    }

    private fun showOverlay() {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val b = OverlayStatusBinding.inflate(inflater)
        binding = b

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 160
        }
        params = lp

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        wm.addView(b.root, lp)

        b.root.setOnTouchListener(DragTouchListener(lp, wm, b.root) {
            startActivity(
                Intent(this, ChatHistoryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        })

        b.root.setOnLongClickListener {
            AgentController.stopAgent("悬浮窗长按停止")
            true
        }

        tickJob = scope.launch {
            while (true) {
                render()
                delay(500)
            }
        }
    }

    private fun render() {
        val b = binding ?: return
        val running = AgentController.isAgentRunning
        b.dot.setBackgroundResource(
            if (running) com.andforce.andclaw.R.drawable.bg_dot_running
            else com.andforce.andclaw.R.drawable.bg_dot_idle
        )
        b.label.text = if (running) "Running" else "Idle"
    }

    override fun onDestroy() {
        tickJob?.cancel()
        try {
            binding?.root?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        binding = null
        super.onDestroy()
    }

    private class DragTouchListener(
        private val lp: WindowManager.LayoutParams,
        private val wm: WindowManager,
        private val view: View,
        private val onClick: () -> Unit
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragged = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downRawX = e.rawX
                    downRawY = e.rawY
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12) dragged = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> if (!dragged) onClick()
            }
            return true
        }
    }
}
