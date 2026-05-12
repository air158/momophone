package com.andforce.andclaw.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object StatusOverlayController {

    fun ensureStarted(ctx: Context) {
        if (!Settings.canDrawOverlays(ctx)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
            } catch (_: Exception) {
            }
            return
        }
        val svc = Intent(ctx, StatusOverlayService::class.java)
        try {
            ctx.startService(svc)
        } catch (_: Exception) {
        }
    }
}
