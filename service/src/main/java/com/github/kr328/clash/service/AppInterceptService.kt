package com.github.kr328.clash.service

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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

    // 已验证通过的APP（本次VPN连接期间有效）
    private val verifiedPackages = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i("AppInterceptService created")
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
                    Log.d("AppInterceptService config updated: ${it.interceptPackages.size} packages")
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

    private suspend fun runMonitor() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        while (isActive) {
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

                // 检查是否需要拦截
                if (shouldIntercept(currentApp)) {
                    Log.i("Intercepting app: $currentApp")
                    notifyInterceptRequired(currentApp)
                }
            }
        } catch (e: Exception) {
            Log.e("Error checking foreground app: ${e.message}")
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
        val intent = Intent(AppInterceptConstants.ACTION_APP_INTERCEPT_REQUIRED).apply {
            putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
            putExtra(AppInterceptConstants.EXTRA_VERIFY_HINT, config.verifyHint)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    companion object {
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
