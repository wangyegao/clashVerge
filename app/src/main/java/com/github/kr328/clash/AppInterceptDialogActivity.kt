package com.github.kr328.clash

import android.app.Dialog
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
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
import kotlinx.coroutines.delay
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
        val verifyHint = intent.getStringExtra(AppInterceptConstants.EXTRA_VERIFY_HINT) ?: "请输入确认内容"
        val inputHint = intent.getStringExtra(AppInterceptConstants.EXTRA_INPUT_HINT) ?: "请输入确认内容"
        val notificationId = intent.getIntExtra(AppInterceptConstants.EXTRA_NOTIFICATION_ID, -1)
        val config = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG, AppInterceptConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG)
        } ?: AppInterceptConfig(verifyHint = verifyHint, inputHint = inputHint)

        if (notificationId != -1) {
            NotificationManagerCompat.from(this).cancel(notificationId)
        }

        if (!config.hasValidationRule()) {
            Log.e("AppInterceptDialogActivity: Missing intercept config for $packageName")
            Toast.makeText(this, "验证配置加载失败", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 使用自定义布局
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_risk_warning, null)

        val ivWarningIcon = dialogView.findViewById<ImageView>(R.id.iv_warning_icon)
        val tvAppName = dialogView.findViewById<TextView>(R.id.tv_app_name)
        val tvHint = dialogView.findViewById<TextView>(R.id.tv_hint)
        val etInput = dialogView.findViewById<EditText>(R.id.et_input)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_confirm)
        val ivClose = dialogView.findViewById<ImageView>(R.id.iv_close)
        val targetAppIcon = resolveTargetAppIcon(packageName)

        // 顶部标题已足够表达风险提示，这里仅保留承诺/输入相关文案。
        targetAppIcon?.let {
            ivWarningIcon.setImageDrawable(it)
        }
        tvAppName.visibility = View.GONE
        tvHint.text = AppInterceptDialogText.resolveContentHint(config.verifyHint)
        etInput.hint = AppInterceptDialogText.resolveInputHint(config.inputHint)
        AppInterceptDialogInputControl.prepareForManualFocus(etInput, btnConfirm)

        // 创建对话框
        val dialog = Dialog(this)
        dialog.setContentView(dialogView)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        AppInterceptDialogInputControl.suppressAutoKeyboard(dialog.window)

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
            if (input.isBlank()) {
                AppInterceptOverlayTip.show(
                    context = this,
                    anchorDialog = dialog,
                    message = "请输入确认内容后再继续",
                    anchorView = etInput,
                    icon = targetAppIcon,
                )
                return@setOnClickListener
            }

            btnConfirm.isEnabled = false
            btnConfirm.text = "提交中..."
            ivClose.isEnabled = false
            etInput.isEnabled = false

            CoroutineScope(Dispatchers.Main).launch {
                val agreementText = config.verifyPassword.ifBlank { input }
                val result = AppInterceptUploader.uploadConfirmation(
                    this@AppInterceptDialogActivity,
                    appName,
                    packageName,
                    agreementText,
                    input,
                )

                if (result.approved) {
                    AppInterceptOverlayTip.show(
                        context = this@AppInterceptDialogActivity,
                        anchorDialog = dialog,
                        message = result.message,
                        anchorView = etInput,
                        icon = targetAppIcon,
                    )
                    markVerified(packageName)
                    delay(1200)
                    dialog.dismiss()
                    finish()
                } else {
                    AppInterceptOverlayTip.show(
                        context = this@AppInterceptDialogActivity,
                        anchorDialog = dialog,
                        message = result.message,
                        anchorView = etInput,
                        icon = targetAppIcon,
                    )
                    btnConfirm.isEnabled = true
                    btnConfirm.text = "确认"
                    ivClose.isEnabled = true
                    etInput.isEnabled = true
                    if (etInput.hasFocus()) {
                        etInput.setSelection(etInput.text.length)
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            finish()
        }

        dialog.show()
        AppInterceptDialogInputControl.clearFocus(etInput, btnConfirm)
    }

    private fun resolveTargetAppIcon(packageName: String): Drawable? {
        return runCatching {
            packageManager.getApplicationIcon(packageName)
        }.getOrNull()
    }

    private fun markVerified(packageName: String) {
        runCatching {
            startService(AppInterceptService.createMarkVerifiedIntent(this, packageName))
        }.onFailure {
            Log.e("AppInterceptDialogActivity: Failed to mark verified for $packageName: ${it.message}")
        }
    }
}
