package com.github.kr328.clash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.github.kr328.clash.common.constants.AppInterceptConstants
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.AppInterceptService
import com.github.kr328.clash.service.model.AppInterceptConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val verifyHint = intent.getStringExtra(AppInterceptConstants.EXTRA_VERIFY_HINT) ?: "请输入验证码"
        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG, AppInterceptConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AppInterceptConstants.EXTRA_CONFIG)
        } ?: AppInterceptConfig(verifyHint = verifyHint)

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
        showVerifyDialog(context, appName, packageName, verifyHint, config)
    }

    private fun showVerifyDialog(
        context: Context,
        appName: String,
        packageName: String,
        verifyHint: String,
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
            showInterceptNotification(context, appName, packageName, verifyHint, config)
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
                if (config.acceptsInput(input)) {
                    Toast.makeText(context, "确认成功，可以继续使用", Toast.LENGTH_SHORT).show()
                    markVerified(context, packageName)
                    CoroutineScope(Dispatchers.Main).launch {
                        val agreementText = config.verifyPassword.ifBlank { input }
                        val uploaded = AppInterceptUploader.uploadConfirmation(
                            context,
                            appName,
                            packageName,
                            agreementText,
                            input,
                        )
                        if (!uploaded) {
                            Toast.makeText(context, "确认成功，但承诺记录上传失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                    dialog.dismiss()
                } else {
                    Toast.makeText(
                        context,
                        if (config.strictVerify) "输入内容不正确，请重试" else "请输入确认内容后再继续",
                        Toast.LENGTH_SHORT
                    ).show()
                    etInput.text.clear()
                    etInput.requestFocus()
                }
            }

            dialog.show()
            Log.i("AppInterceptReceiver: Dialog shown successfully")
        } catch (e: Exception) {
            Log.e("AppInterceptReceiver: Failed to show dialog: ${e.message}")
            showInterceptNotification(context, appName, packageName, verifyHint, config)
        }
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
        verifyHint: String,
        config: AppInterceptConfig,
    ) {
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.w("AppInterceptReceiver: Notifications disabled, falling back to Activity launch")
            launchDialogActivity(context, appName, packageName, verifyHint, config, null)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "风险应用验证提醒",
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
            verifyHint = verifyHint,
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
            .setContentTitle("风险应用访问验证")
            .setContentText("$appName 需要验证后才能继续使用")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "检测到风险应用 \"$appName\" 被打开，点击立即验证。若你希望始终弹出验证框，请授予悬浮窗权限。"
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
        verifyHint: String,
        config: AppInterceptConfig,
        notificationId: Int?,
    ) {
        runCatching {
            context.startActivity(
                createDialogIntent(
                    context = context,
                    appName = appName,
                    packageName = packageName,
                    verifyHint = verifyHint,
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
        verifyHint: String,
        config: AppInterceptConfig,
        notificationId: Int?,
    ): Intent {
        return Intent(context, AppInterceptDialogActivity::class.java).apply {
            putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
            putExtra("app_name", appName)
            putExtra(AppInterceptConstants.EXTRA_VERIFY_HINT, verifyHint)
            putExtra(AppInterceptConstants.EXTRA_CONFIG, config)
            notificationId?.let {
                putExtra(AppInterceptConstants.EXTRA_NOTIFICATION_ID, it)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
