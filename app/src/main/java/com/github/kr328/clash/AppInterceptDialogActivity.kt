package com.github.kr328.clash

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.constants.AppInterceptConstants
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.AppInterceptService
import com.github.kr328.clash.service.model.AppInterceptConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * APP拦截验证对话框Activity
 * 当无法显示悬浮窗时使用
 */
class AppInterceptDialogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val packageName = intent.getStringExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME) ?: run {
            finish()
            return
        }
        val appName = intent.getStringExtra("app_name") ?: packageName
        val verifyHint = intent.getStringExtra(AppInterceptConstants.EXTRA_VERIFY_HINT) ?: "请输入验证码"
        val notificationId = intent.getIntExtra(AppInterceptConstants.EXTRA_NOTIFICATION_ID, -1)
        val config = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG, AppInterceptConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG)
        }

        if (notificationId != -1) {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }

        if (config == null || !config.hasValidationRule()) {
            Log.e("AppInterceptDialogActivity: Missing intercept config for $packageName")
            Toast.makeText(this, "验证配置加载失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

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
            val input = etInput.text.toString().trim()
            if (config.acceptsInput(input)) {
                btnConfirm.isEnabled = false
                btnConfirm.text = "提交中..."
                ivClose.isEnabled = false
                etInput.isEnabled = false

                CoroutineScope(Dispatchers.Main).launch {
                    val agreementText = config.verifyPassword.ifBlank { input }
                    val uploaded = AppInterceptUploader.uploadConfirmation(
                        this@AppInterceptDialogActivity,
                        appName,
                        packageName,
                        agreementText,
                        input,
                    )

                    Toast.makeText(this@AppInterceptDialogActivity, "确认成功，可以继续使用", Toast.LENGTH_SHORT).show()
                    if (!uploaded) {
                        Toast.makeText(
                            this@AppInterceptDialogActivity,
                            "确认成功，但承诺记录上传失败",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }

                    markVerified(packageName)
                    dialog.dismiss()
                    finish()
                }
            } else {
                Toast.makeText(
                    this,
                    if (config.strictVerify) "输入内容不正确，请重试" else "请输入确认内容后再继续",
                    Toast.LENGTH_SHORT
                ).show()
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
        runCatching {
            startService(AppInterceptService.createMarkVerifiedIntent(this, packageName))
        }.onFailure {
            Log.e("AppInterceptDialogActivity: Failed to mark verified for $packageName: ${it.message}")
        }
    }
}
