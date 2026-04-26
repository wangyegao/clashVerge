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

enum class PermissionSettingsLandingPage {
    AppSpecific,
    AppList,
}

data class PermissionSettingsLaunchInfo(
    val intent: Intent,
    val landingPage: PermissionSettingsLandingPage,
)

fun Context.queryAppInterceptPermissionState(): AppInterceptPermissionState {
    return AppInterceptPermissionState(
        usageStatsGranted = hasUsageStatsPermission(),
        overlayGranted = hasOverlayPermission(),
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

private fun Context.hasOverlayPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return true
    }

    if (Settings.canDrawOverlays(this)) {
        return true
    }

    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            android.os.Process.myUid(),
            packageName,
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            android.os.Process.myUid(),
            packageName,
        )
    }

    return mode == AppOpsManager.MODE_ALLOWED
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
    return createOverlaySettingsLaunchInfo().intent
}

fun Context.createOverlaySettingsLaunchInfo(): PermissionSettingsLaunchInfo {
    val appPackageName = applicationContext.packageName
    val fallbackIntent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        createAppPackageUri(),
    ).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, appPackageName)
        putExtra("android.provider.extra.APP_PACKAGE", appPackageName)
        putExtra("package_name", appPackageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    createVendorOverlaySettingsIntents(appPackageName).firstOrNull { hasActivity(it.intent) }?.let {
        return it
    }

    createAppSpecificSettingsLaunchInfo(
        activityClassName = "com.android.settings.Settings\$AppDrawOverlaySettingsActivity",
    )?.let {
        return it
    }

    val specificIntent = Intent(
        "android.settings.MANAGE_APP_OVERLAY_PERMISSION",
        createAppPackageUri(),
    ).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, appPackageName)
        putExtra("android.provider.extra.APP_PACKAGE", appPackageName)
        putExtra("package_name", appPackageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    if (hasActivity(specificIntent)) {
        return PermissionSettingsLaunchInfo(
            intent = specificIntent,
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        )
    }

    return PermissionSettingsLaunchInfo(
        intent = fallbackIntent,
        landingPage = PermissionSettingsLandingPage.AppList,
    )
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

    return if (hasActivity(specificIntent)) {
        specificIntent
    } else {
        fallbackIntent
    }
}

private fun Context.createAppSpecificSettingsLaunchInfo(
    activityClassName: String,
): PermissionSettingsLaunchInfo? {
    val appPackageName = applicationContext.packageName
    val specificIntent = Intent().apply {
        component = ComponentName("com.android.settings", activityClassName)
        data = createAppPackageUri()
        putExtra(Settings.EXTRA_APP_PACKAGE, appPackageName)
        putExtra("android.provider.extra.APP_PACKAGE", appPackageName)
        putExtra("package_name", appPackageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    return if (hasActivity(specificIntent)) {
        PermissionSettingsLaunchInfo(
            intent = specificIntent,
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        )
    } else {
        null
    }
}

private fun Context.createVendorOverlaySettingsIntents(
    appPackageName: String,
): List<PermissionSettingsLaunchInfo> {
    val packageUri = createAppPackageUri()

    fun launchInfo(
        packageName: String,
        className: String,
        landingPage: PermissionSettingsLandingPage,
    ): PermissionSettingsLaunchInfo {
        return PermissionSettingsLaunchInfo(
            intent = Intent().apply {
                setClassName(packageName, className)
                data = packageUri
                putExtra(Settings.EXTRA_APP_PACKAGE, appPackageName)
                putExtra("android.intent.extra.PACKAGE_NAME", appPackageName)
                putExtra("android.provider.extra.APP_PACKAGE", appPackageName)
                putExtra("packageName", appPackageName)
                putExtra("package_name", appPackageName)
                putExtra("pkgName", appPackageName)
                putExtra("packagename", appPackageName)
                putExtra("extra_pkgname", appPackageName)
                putExtra("extra_pkg_name", appPackageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            landingPage = landingPage,
        )
    }

    return listOf(
        launchInfo(
            packageName = "com.iqoo.secure",
            className = "com.iqoo.secure.safeguard.SoftPermissionDetailActivity",
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        ),
        launchInfo(
            packageName = "com.vivo.permissionmanager",
            className = "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        ),
        launchInfo(
            packageName = "com.miui.securitycenter",
            className = "com.miui.permcenter.permissions.PermissionsEditorActivity",
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        ),
        launchInfo(
            packageName = "com.miui.securitycenter",
            className = "com.miui.permcenter.permissions.AppPermissionsEditorActivity",
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        ),
        launchInfo(
            packageName = "com.coloros.safecenter",
            className = "com.coloros.safecenter.permission.singlepage.PermissionSinglePageActivity",
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        ),
        launchInfo(
            packageName = "com.coloros.safecenter",
            className = "com.coloros.safecenter.permission.PermissionManagerActivity",
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        ),
        launchInfo(
            packageName = "com.oplus.safecenter",
            className = "com.oplus.safecenter.permission.PermissionManagerActivity",
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        ),
        launchInfo(
            packageName = "com.oppo.safe",
            className = "com.oppo.safe.permission.PermissionAppAllPermissionActivity",
            landingPage = PermissionSettingsLandingPage.AppSpecific,
        ),
        launchInfo(
            packageName = "com.huawei.systemmanager",
            className = "com.huawei.systemmanager.addviewmonitor.AddViewMonitorActivity",
            landingPage = PermissionSettingsLandingPage.AppList,
        ),
        launchInfo(
            packageName = "com.hihonor.systemmanager",
            className = "com.hihonor.systemmanager.addviewmonitor.AddViewMonitorActivity",
            landingPage = PermissionSettingsLandingPage.AppList,
        ),
    )
}

private fun Context.hasActivity(intent: Intent): Boolean {
    val resolveInfo = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
    }.getOrNull() ?: return false

    val requiredPermission = resolveInfo.activityInfo?.permission
    if (requiredPermission.isNullOrBlank()) {
        return true
    }

    return packageManager.checkPermission(requiredPermission, packageName) == PackageManager.PERMISSION_GRANTED
}

private fun Context.createAppPackageUri(): Uri {
    return Uri.fromParts("package", applicationContext.packageName, null)
}
