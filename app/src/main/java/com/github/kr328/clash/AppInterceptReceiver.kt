package com.github.kr328.clash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
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
 * APP拦截广播接收器
 * 接收拦截通知并显示验证对话框
 */
class AppInterceptReceiver : BroadcastReceiver() {
    companion object {
        private const val ALERT_CHANNEL_ID = "app_intercept_alerts"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("AppInterceptReceiver: Received broadcast, action=${intent.action}")

        if (intent.action != AppInterceptConstants.ACTION_APP_INTERCEPT_REQUIRED) {
            Log.w("AppInterceptReceiver: Unknown action ${intent.action}")
            return
        }

        val packageName = intent.getStringExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME)
        val verifyHint = intent.getStringExtra(AppInterceptConstants.EXTRA_VERIFY_HINT) ?: "请输入确认内容"
        val inputHint = intent.getStringExtra(AppInterceptConstants.EXTRA_INPUT_HINT) ?: "请输入确认内容"
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG, AppInterceptConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG)
        } ?: AppInterceptConfig(verifyHint = verifyHint, inputHint = inputHint)

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
        showVerifyDialog(context, appName, packageName, config)
    }

    private fun showVerifyDialog(
        context: Context,
        appName: String,
        packageName: String,
        config: AppInterceptConfig,
    ) {
        Log.d(
            "AppInterceptReceiver: Config enabled=${config.enabled}, " +
                "strictVerify=${config.strictVerify}, password=${config.verifyPassword.isNotEmpty()}"
        )

        if (!config.hasValidationRule()) {
            Log.e("AppInterceptReceiver: Missing validation rule for $packageName")
            return
        }

        // 检查悬浮窗权限
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

        Log.d("AppInterceptReceiver: hasOverlayPermission=$hasOverlayPermission")

        if (!hasOverlayPermission) {
            Log.i("AppInterceptReceiver: No overlay permission, using notification fallback")
            showInterceptNotification(context, appName, packageName, config)
            return
        }

        // 使用自定义布局
        try {
            val inflater = LayoutInflater.from(context)
            val dialogView = inflater.inflate(R.layout.dialog_risk_warning, null)

            val ivWarningIcon = dialogView.findViewById<ImageView>(R.id.iv_warning_icon)
            val tvAppName = dialogView.findViewById<TextView>(R.id.tv_app_name)
            val tvHint = dialogView.findViewById<TextView>(R.id.tv_hint)
            val etInput = dialogView.findViewById<EditText>(R.id.et_input)
            val btnConfirm = dialogView.findViewById<TextView>(R.id.btn_confirm)
            val ivClose = dialogView.findViewById<ImageView>(R.id.iv_close)
            val targetAppIcon = resolveTargetAppIcon(context, packageName)

            // 顶部标题已足够表达风险提示，这里仅保留承诺/输入相关文案。
            targetAppIcon?.let {
                ivWarningIcon.setImageDrawable(it)
            }
            tvAppName.visibility = View.GONE
            tvHint.text = AppInterceptDialogText.resolveContentHint(config.verifyHint)
            etInput.hint = AppInterceptDialogText.resolveInputHint(config.inputHint)
            AppInterceptDialogInputControl.prepareForManualFocus(etInput, btnConfirm)

            // 创建对话框
            val dialog = Dialog(context)
            dialog.setContentView(dialogView)
            dialog.setCancelable(false)
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            AppInterceptDialogInputControl.suppressAutoKeyboard(dialog.window)
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
                val input = etInput.text.toString().trim()
                if (input.isBlank()) {
                    AppInterceptOverlayTip.show(
                        context = context,
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
                        context,
                        appName,
                        packageName,
                        agreementText,
                        input,
                    )

                    if (result.approved) {
                        AppInterceptOverlayTip.show(
                            context = context,
                            anchorDialog = dialog,
                            message = result.message,
                            anchorView = etInput,
                            icon = targetAppIcon,
                        )
                        markVerified(context, packageName)
                        delay(1200)
                        dialog.dismiss()
                    } else {
                        AppInterceptOverlayTip.show(
                            context = context,
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

            dialog.show()
            AppInterceptDialogInputControl.clearFocus(etInput, btnConfirm)
            Log.i("AppInterceptReceiver: Dialog shown successfully")
        } catch (e: Exception) {
            Log.e("AppInterceptReceiver: Failed to show dialog: ${e.message}")
            showInterceptNotification(context, appName, packageName, config)
        }
    }

    private fun resolveTargetAppIcon(context: Context, packageName: String): Drawable? {
        return runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull()
    }

    private fun markVerified(context: Context, packageName: String) {
        runCatching {
            context.startService(
                AppInterceptService.createMarkVerifiedIntent(context, packageName)
            )
            Log.i("AppInterceptReceiver: Marked as verified: $packageName")
        }.onFailure {
            Log.e("AppInterceptReceiver: Failed to mark verified for $packageName: ${it.message}")
        }
    }

    private fun showInterceptNotification(
        context: Context,
        appName: String,
        packageName: String,
        config: AppInterceptConfig,
    ) {
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.w("AppInterceptReceiver: Notifications disabled, falling back to Activity launch")
            launchDialogActivity(context, appName, packageName, config, null)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "钱包应用验证提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "当检测到风险应用打开时，显示验证提醒"
            }
            manager.createNotificationChannel(channel)
        }

        val notificationId = packageName.hashCode()
        val dialogIntent = createDialogIntent(
            context = context,
            appName = appName,
            packageName = packageName,
            config = config,
            notificationId = notificationId,
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            dialogIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("应用异常访问验证")
            .setContentText("$appName 需要验证后才能继续使用")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "检测到钱包异常 \"$appName\" 被打开，点击立即验证。若你希望始终弹出验证框，请授予悬浮窗权限。"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .setTimeoutAfter(60_000)
            .build()

        notificationManager.notify(notificationId, notification)
        Log.i("AppInterceptReceiver: Notification fallback shown for $packageName")
    }

    private fun launchDialogActivity(
        context: Context,
        appName: String,
        packageName: String,
        config: AppInterceptConfig,
        notificationId: Int?,
    ) {
        runCatching {
            context.startActivity(
                createDialogIntent(
                    context = context,
                    appName = appName,
                    packageName = packageName,
                    config = config,
                    notificationId = notificationId,
                )
            )
        }.onFailure {
            Log.e("AppInterceptReceiver: Failed to launch dialog Activity: ${it.message}")
        }
    }

    private fun createDialogIntent(
        context: Context,
        appName: String,
        packageName: String,
        config: AppInterceptConfig,
        notificationId: Int?,
    ): Intent {
        return Intent(context, AppInterceptDialogActivity::class.java).apply {
            putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
            putExtra("app_name", appName)
            putExtra(AppInterceptConstants.EXTRA_VERIFY_HINT, config.verifyHint)
            putExtra(AppInterceptConstants.EXTRA_INPUT_HINT, config.inputHint)
            putExtra(AppInterceptConstants.EXTRA_CONFIG, config)
            notificationId?.let {
                putExtra(AppInterceptConstants.EXTRA_NOTIFICATION_ID, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
