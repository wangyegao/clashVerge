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
        Log.i("AppInterceptModule: Starting...")

        // 先加载默认配置
        loadDefaultConfig()
        Log.i("AppInterceptModule: Default config loaded, packages: ${currentConfig.interceptPackages}, enabled: ${currentConfig.enabled}")

        // 尝试加载用户配置（如果存在）
        loadUserConfig()

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
            loadDefaultConfig()
            loadUserConfig()
            updateInterceptService()
        }
    }

    private fun loadUserConfig() {
        try {
            // 尝试多个可能的配置文件路径
            val possiblePaths = listOf(
                File(service.filesDir, "clash/config.yaml"),
                File(service.filesDir.parentFile, "clash/config.yaml"),
                File(service.getExternalFilesDir(null), "clash/config.yaml")
            )

            for (configFile in possiblePaths) {
                if (configFile.exists()) {
                    Log.d("AppInterceptModule: Found config at ${configFile.absolutePath}")
                    val yamlContent = configFile.readText()
                    val userConfig = parseYamlConfig(yamlContent)
                    if (userConfig.interceptPackages.isNotEmpty()) {
                        currentConfig = userConfig
                        Log.i("AppInterceptModule: User config loaded, packages: ${userConfig.interceptPackages}")
                        return
                    }
                }
            }
            Log.d("AppInterceptModule: No user config found, using default")
        } catch (e: Exception) {
            Log.e("AppInterceptModule: Failed to load user config: ${e.message}")
        }
    }

    private fun loadDefaultConfig() {
        try {
            val defaultConfigStr = service.assets.open("datas/default-rules.yaml").use {
                it.bufferedReader().readText()
            }
            currentConfig = parseYamlConfig(defaultConfigStr)
            Log.i("AppInterceptModule: Default config loaded: ${currentConfig.interceptPackages.size} packages, enabled: ${currentConfig.enabled}")
        } catch (e: Exception) {
            Log.e("AppInterceptModule: Failed to load default config: ${e.message}")
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

        val config = AppInterceptConfig(
            interceptPackages = interceptPackages,
            verifyPassword = verifyPassword,
            verifyHint = verifyHint,
            enabled = interceptPackages.isNotEmpty() && verifyPassword.isNotEmpty()
        )
        Log.d("AppInterceptModule: Parsed config - packages: $interceptPackages, password: '${verifyPassword.take(10)}...', enabled: ${config.enabled}")
        return config
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
