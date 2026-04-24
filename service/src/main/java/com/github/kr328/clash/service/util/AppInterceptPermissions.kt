package com.github.kr328.clash.service.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

data class AppInterceptPermissionState(
    val usageStatsGranted: Boolean,
    val overlayGranted: Boolean,
    val notificationsGranted: Boolean,
) {
    val canStartIntercept: Boolean
        get() = usageStatsGranted && overlayGranted

    val canShowFallbackPrompt: Boolean
        get() = overlayGranted || notificationsGranted
}

fun Context.queryAppInterceptPermissionState(): AppInterceptPermissionState {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val usageStatsGranted = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        packageName,
    ) == AppOpsManager.MODE_ALLOWED

    return AppInterceptPermissionState(
        usageStatsGranted = usageStatsGranted,
        overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this),
        notificationsGranted = NotificationManagerCompat.from(this).areNotificationsEnabled(),
    )
}

fun Context.createUsageAccessSettingsIntent(): Intent {
    return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

fun Context.createOverlaySettingsIntent(): Intent {
    return Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName"),
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

fun Context.createNotificationSettingsIntent(): Intent {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
