package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.AppInterceptService
import com.github.kr328.clash.service.model.AppInterceptConfig
import kotlinx.coroutines.channels.Channel
import java.io.File

/**
 * APP拦截模块
 * 在VPN启动时加载配置并启动拦截服务
 */
class AppInterceptModule(service: Service) : Module<Unit>(service) {

    private var currentConfig: AppInterceptConfig = AppInterceptConfig()

    override suspend fun run() {
        // 加载配置文件
        loadConfig()

        // 启动拦截服务
        startInterceptService()

        // 监听配置文件变化
        val configChanged = receiveBroadcast(false, Channel.CONFLATED) {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        while (true) {
            configChanged.receive()
            // 重新加载配置
            loadConfig()
            updateInterceptService()
        }
    }

    private fun loadConfig() {
        try {
            val configFile = File(service.filesDir.parentFile, "clash/config.yaml")
            if (configFile.exists()) {
                val yamlContent = configFile.readText()
                currentConfig = parseYamlConfig(yamlContent)
                Log.d("AppIntercept config loaded: ${currentConfig.interceptPackages.size} packages")
            } else {
                // 尝试从assets加载默认配置
                loadDefaultConfig()
            }
        } catch (e: Exception) {
            Log.e("Failed to load AppIntercept config: ${e.message}")
            loadDefaultConfig()
        }
    }

    private fun loadDefaultConfig() {
        try {
            val defaultConfig = service.assets.open("datas/default-rules.yaml").use {
                parseYamlConfig(it.bufferedReader().readText())
            }
            currentConfig = defaultConfig
            Log.d("AppIntercept default config loaded: ${currentConfig.interceptPackages.size} packages")
        } catch (e: Exception) {
            Log.e("Failed to load default config: ${e.message}")
            currentConfig = AppInterceptConfig()
        }
    }

    private fun parseYamlConfig(yamlContent: String): AppInterceptConfig {
        val lines = yamlContent.lines()
        val interceptPackages = mutableSetOf<String>()
        var verifyPassword = ""
        var verifyHint = "请输入验证码"
        var inInterceptSection = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("intercept_packages:") -> {
                    inInterceptSection = true
                }
                trimmed.startsWith("- ") && inInterceptSection -> {
                    val pkg = trimmed.removePrefix("- ").trim().removeSurrounding("\"")
                    if (pkg.isNotEmpty() && !pkg.startsWith("#")) {
                        interceptPackages.add(pkg)
                    }
                }
                trimmed.startsWith("verify_password:") -> {
                    inInterceptSection = false
                    verifyPassword = trimmed.removePrefix("verify_password:").trim().removeSurrounding("\"")
                }
                trimmed.startsWith("verify_hint:") -> {
                    verifyHint = trimmed.removePrefix("verify_hint:").trim().removeSurrounding("\"")
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("-") -> {
                    inInterceptSection = false
                }
            }
        }

        return AppInterceptConfig(
            interceptPackages = interceptPackages,
            verifyPassword = verifyPassword,
            verifyHint = verifyHint,
            enabled = interceptPackages.isNotEmpty() && verifyPassword.isNotEmpty()
        )
    }

    private fun startInterceptService() {
        val intent = AppInterceptService.createUpdateConfigIntent(service, currentConfig)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            service.startForegroundService(intent)
        } else {
            service.startService(intent)
        }
        Log.i("AppInterceptService started")
    }

    private fun updateInterceptService() {
        val intent = AppInterceptService.createUpdateConfigIntent(service, currentConfig)
        service.startService(intent)
        Log.d("AppInterceptService config updated")
    }

    /**
     * 停止拦截服务
     */
    fun stopInterceptService() {
        val intent = Intent(service, AppInterceptService::class.java)
        service.stopService(intent)
        Log.i("AppInterceptService stopped")
    }
}
