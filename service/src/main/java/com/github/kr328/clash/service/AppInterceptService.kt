package com.github.kr328.clash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.constants.AppInterceptConstants
import com.github.kr328.clash.service.model.AppInterceptConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * APP拦截监控服务
 * 监控前台应用变化，当检测到需要拦截的APP时通知验证
 */
class AppInterceptService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Default) {

    private var config: AppInterceptConfig = AppInterceptConfig()
    private val configChannel = Channel<AppInterceptConfig>(Channel.CONFLATED)
    private var lastForegroundPackage: String? = null
    private var hasUsageStatsPermission = false
    private var permissionNotified = false

    // 已验证通过的APP（本次VPN连接期间有效）
    private val verifiedPackages = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hasUsageStatsPermission = checkUsageStatsPermission()
        Log.i("AppInterceptService created, usageStatsPermission: $hasUsageStatsPermission")

        // 创建前台通知
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        launch {
            runMonitor()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AppInterceptConstants.ACTION_UPDATE_CONFIG -> {
                val newConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG, AppInterceptConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG)
                }
                newConfig?.let {
                    config = it
                    configChannel.trySend(it)
                    Log.d("AppInterceptService config updated: ${it.interceptPackages.size} packages, enabled: ${it.enabled}")

                    // 更新通知
                    updateNotification()
                }
            }
            AppInterceptConstants.ACTION_CLEAR_VERIFIED -> {
                verifiedPackages.clear()
                Log.d("AppInterceptService verified packages cleared")
            }
            AppInterceptConstants.ACTION_MARK_VERIFIED -> {
                val packageName = intent.getStringExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME)
                if (packageName != null) {
                    verifiedPackages.add(packageName)
                    Log.i("App marked as verified: $packageName")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancel()
        Log.i("AppInterceptService destroyed")
        super.onDestroy()
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "APP拦截监控",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "APP拦截监控服务运行中"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val contentText = if (!hasUsageStatsPermission) {
            "请授予\"查看使用情况\"权限以启用拦截功能"
        } else if (config.enabled) {
            "正在监控 ${config.interceptPackages.size} 个风险应用"
        } else {
            "拦截功能未启用"
        }

        val pendingIntent = if (!hasUsageStatsPermission) {
            // 打开使用统计权限设置
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("APP拦截监控")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification())
    }

    private suspend fun runMonitor() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        while (isActive) {
            // 检查权限状态
            if (!hasUsageStatsPermission) {
                hasUsageStatsPermission = checkUsageStatsPermission()
                if (!hasUsageStatsPermission) {
                    if (!permissionNotified) {
                        Log.w("AppInterceptService: No usage stats permission, showing notification")
                        updateNotification()
                        permissionNotified = true
                    }
                    delay(5000) // 5秒后再检查
                    continue
                } else {
                    // 权限已授予，更新通知
                    permissionNotified = false
                    updateNotification()
                    Log.i("AppInterceptService: Usage stats permission granted")
                }
            }

            // 每秒检查一次前台应用
            if (config.enabled) {
                checkForegroundApp(usageStatsManager)
            }

            // 等待1秒
            delay(1000)
        }
    }

    private fun checkForegroundApp(usageStatsManager: UsageStatsManager) {
        if (!config.enabled || config.interceptPackages.isEmpty()) return

        val endTime = System.currentTimeMillis()
        val startTime = endTime - 5000 // 检查最近5秒

        try {
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            var currentApp: String? = null

            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    currentApp = event.packageName
                }
            }

            // 如果检测到新的前台应用
            if (currentApp != null && currentApp != lastForegroundPackage) {
                lastForegroundPackage = currentApp
                Log.d("AppInterceptService: Foreground app changed to $currentApp")

                // 检查是否需要拦截
                if (shouldIntercept(currentApp)) {
                    Log.i("AppInterceptService: Intercepting app: $currentApp")
                    notifyInterceptRequired(currentApp)
                }
            }
        } catch (e: Exception) {
            Log.e("AppInterceptService: Error checking foreground app: ${e.message}")
        }
    }

    private fun shouldIntercept(packageName: String): Boolean {
        if (!config.enabled) return false
        if (packageName !in config.interceptPackages) return false
        if (packageName in verifiedPackages) return false
        if (packageName == this.packageName) return false // 不拦截自己
        return true
    }

    private fun notifyInterceptRequired(packageName: String) {
        // 发送广播通知UI层显示验证对话框
        // 使用显式 Intent 确保广播能跨进程送达
        val intent = Intent(AppInterceptConstants.ACTION_APP_INTERCEPT_REQUIRED).apply {
            putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AppInterceptConstants.EXTRA_VERIFY_HINT, config.verifyHint)
            setPackage(this@AppInterceptService.packageName)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            // 设置接收器的类名，使其成为显式广播
            setClassName(this@AppInterceptService.packageName, "com.github.kr328.clash.AppInterceptReceiver")
        }
        sendBroadcast(intent)
        Log.i("AppInterceptService: Broadcast sent for $packageName")
    }

    companion object {
        private const val CHANNEL_ID = "app_intercept_service"
        private const val NOTIFICATION_ID = 10001

        /**
         * 创建更新配置的Intent
         */
        fun createUpdateConfigIntent(context: Context, config: AppInterceptConfig): Intent {
            return Intent(context, AppInterceptService::class.java).apply {
                action = AppInterceptConstants.ACTION_UPDATE_CONFIG
                putExtra(AppInterceptConstants.EXTRA_CONFIG, config)
            }
        }

        /**
         * 创建清除验证状态的Intent
         */
        fun createClearVerifiedIntent(context: Context): Intent {
            return Intent(context, AppInterceptService::class.java).apply {
                action = AppInterceptConstants.ACTION_CLEAR_VERIFIED
            }
        }
    }
}
