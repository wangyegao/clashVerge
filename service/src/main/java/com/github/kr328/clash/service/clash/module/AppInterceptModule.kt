package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.service.AppInterceptService
import com.github.kr328.clash.service.model.AppInterceptConfig
import com.github.kr328.clash.service.util.AppInterceptConfigLoader
import kotlinx.coroutines.channels.Channel

/**
 * APP拦截模块
 * 在VPN启动时加载配置并启动拦截服务
 */
class AppInterceptModule(service: Service) : Module<Unit>(service) {

    private var currentConfig: AppInterceptConfig = AppInterceptConfig()

    override suspend fun run() {
        Log.i("AppInterceptModule: Starting...")

        reloadConfig()

        // 启动拦截服务
        startInterceptService()

        // 监听当前激活配置变化
        val configChanged = receiveBroadcast(capacity = Channel.CONFLATED) {
            addAction(Intents.ACTION_PROFILE_CHANGED)
        }

        while (true) {
            configChanged.receive()
            reloadConfig()
            updateInterceptService()
        }
    }

    private fun reloadConfig() {
        currentConfig = AppInterceptConfigLoader.load(service)
        Log.i(
            "AppInterceptModule: Config reloaded, packages=${currentConfig.interceptPackages}, " +
                "enabled=${currentConfig.enabled}, strictVerify=${currentConfig.strictVerify}, " +
                "passwordSet=${currentConfig.verifyPassword.isNotEmpty()}"
        )
    }

    private fun startInterceptService() {
        val intent = AppInterceptService.createUpdateConfigIntent(service, currentConfig)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            service.startForegroundService(intent)
        } else {
            service.startService(intent)
        }
        Log.i("AppInterceptModule: Service started with config enabled=${currentConfig.enabled}")
    }

    private fun updateInterceptService() {
        val intent = AppInterceptService.createUpdateConfigIntent(service, currentConfig)
        service.startService(intent)
        Log.d("AppInterceptModule: Service updated")
    }

    /**
     * 停止拦截服务
     */
    fun stopInterceptService() {
        val intent = Intent(service, AppInterceptService::class.java)
        service.stopService(intent)
        Log.i("AppInterceptModule: Service stopped")
    }
}
