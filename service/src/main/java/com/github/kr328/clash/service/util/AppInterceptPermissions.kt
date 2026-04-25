package com.github.kr328.clash.service.util

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    return AppInterceptPermissionState(
        usageStatsGranted = hasUsageStatsPermission(),
        overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this),
        notificationsGranted = NotificationManagerCompat.from(this).areNotificationsEnabled(),
    )
}

private fun Context.hasUsageStatsPermission(): Boolean {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        )
    }

    if (mode == AppOpsManager.MODE_ALLOWED) {
        return true
    }

    if (mode != AppOpsManager.MODE_DEFAULT) {
        return false
    }

    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val endTime = System.currentTimeMillis()
    val startTime = endTime - 24L * 60L * 60L * 1000L

    return runCatching {
        usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            .orEmpty()
            .isNotEmpty()
    }.getOrDefault(false)
}

fun Context.createUsageAccessSettingsIntent(): Intent {
    val fallbackIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return createAppSpecificSettingsIntent(
        activityClassName = "com.android.settings.Settings\$AppUsageAccessSettingsActivity",
        fallbackIntent = fallbackIntent,
    )
}

fun Context.createOverlaySettingsIntent(): Intent {
    return Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        createAppPackageUri(),
    ).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, applicationContext.packageName)
        putExtra("android.provider.extra.APP_PACKAGE", applicationContext.packageName)
        putExtra("package_name", applicationContext.packageName)
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

private fun Context.createAppSpecificSettingsIntent(
    activityClassName: String,
    fallbackIntent: Intent,
): Intent {
    // Some vendor ROMs route generic special-access intents to a list page.
    // Prefer the app-specific settings page when the system exposes it.
    val appPackageName = applicationContext.packageName
    val componentName = ComponentName("com.android.settings", activityClassName)
    val specificIntent = Intent().apply {
        component = componentName
        data = createAppPackageUri()
        putExtra(Settings.EXTRA_APP_PACKAGE, appPackageName)
        putExtra("android.provider.extra.APP_PACKAGE", appPackageName)
        putExtra("package_name", appPackageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return if (hasActivity(componentName)) {
        specificIntent
    } else {
        fallbackIntent
    }
}

private fun Context.createAppPackageUri(): Uri {
    return Uri.fromParts("package", applicationContext.packageName, null)
}

private fun Context.hasActivity(componentName: ComponentName): Boolean {
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getActivityInfo(componentName, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(componentName, 0)
        }
    }.isSuccess
}
