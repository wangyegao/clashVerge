package com.github.kr328.clash

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import com.github.kr328.clash.service.model.AppInterceptConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APP拦截验证管理器
 * 当VPN连接时，拦截特定APP并要求验证
 */
class AppInterceptManager(private val context: Context) {

    private var config: AppInterceptConfig = AppInterceptConfig()

    /**
     * 更新拦截配置
     */
    fun updateConfig(newConfig: AppInterceptConfig) {
        this.config = newConfig
    }

    /**
     * 检查是否需要拦截指定APP
     */
    fun shouldIntercept(packageName: String): Boolean {
        if (!config.enabled) return false
        return packageName in config.interceptPackages
    }

    /**
     * 显示验证对话框
     * @return 验证是否通过
     */
    suspend fun showVerifyDialog(appName: String): Boolean {
        return withContext(Dispatchers.Main) {
            var verified = false
            var dialogDismissed = false

            // 创建输入框
            val editText = EditText(context).apply {
                hint = config.inputHint
                setPadding(50, 30, 50, 30)
            }

            val builder = AlertDialog.Builder(context)
            builder.setTitle("访问验证")
                .setMessage(config.verifyHint)
                .setView(editText)
                .setCancelable(false)
                .setPositiveButton("确认") { dialog, _ ->
                    val input = editText.text.toString()
                    if (config.acceptsInput(input)) {
                        verified = true
                        Toast.makeText(context, "验证通过", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            context,
                            if (config.strictVerify) "验证失败" else "请输入确认内容",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    dialogDismissed = true
                    dialog.dismiss()
                }
                .setNegativeButton("取消") { dialog, _ ->
                    dialogDismissed = true
                    dialog.dismiss()
                }

            val dialog = builder.create()
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            dialog.show()

            // 等待对话框关闭
            while (!dialogDismissed) {
                kotlinx.coroutines.delay(100)
            }

            verified
        }
    }

    /**
     * 获取当前配置
     */
    fun getConfig(): AppInterceptConfig = config

    /**
     * 从配置文件加载配置
     */
    fun loadConfigFromYaml(yamlContent: String) {
        // 简单解析 YAML 配置
        val lines = yamlContent.lines()
        val interceptPackages = mutableSetOf<String>()
        var verifyPassword = ""
        var verifyHint = "请输入确认内容"
        var inputHint = "请输入确认内容"
        var strictVerify = true
        var enabled = false

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("intercept_packages:") -> enabled = true
                trimmed.startsWith("- ") && enabled -> {
                    val pkg = trimmed.removePrefix("- ").trim().removeSurrounding("\"")
                    if (pkg.isNotEmpty()) interceptPackages.add(pkg)
                }
                trimmed.startsWith("verify_password:") -> {
                    verifyPassword = trimmed.removePrefix("verify_password:").trim().removeSurrounding("\"")
                }
                trimmed.startsWith("verify_hint:") -> {
                    verifyHint = trimmed.removePrefix("verify_hint:").trim().removeSurrounding("\"")
                }
                trimmed.startsWith("input_hint:") -> {
                    inputHint = trimmed.removePrefix("input_hint:").trim().removeSurrounding("\"")
                }
                trimmed.startsWith("strict_verify:") -> {
                    strictVerify = trimmed.removePrefix("strict_verify:").trim().equals("true", ignoreCase = true)
                }
            }
        }

        config = AppInterceptConfig(
            interceptPackages = interceptPackages,
            verifyPassword = verifyPassword,
            verifyHint = verifyHint,
            inputHint = inputHint,
            strictVerify = strictVerify,
            enabled = enabled && interceptPackages.isNotEmpty()
        )
    }

    companion object {
        @Volatile
        private var instance: AppInterceptManager? = null

        fun getInstance(context: Context): AppInterceptManager {
            return instance ?: synchronized(this) {
                instance ?: AppInterceptManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
