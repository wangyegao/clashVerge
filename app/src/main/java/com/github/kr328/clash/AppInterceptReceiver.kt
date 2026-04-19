package com.github.kr328.clash

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import com.github.kr328.clash.common.constants.AppInterceptConstants

/**
 * APP拦截广播接收器
 * 接收拦截通知并显示验证对话框
 */
class AppInterceptReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppInterceptConstants.ACTION_APP_INTERCEPT_REQUIRED) return

        val packageName = intent.getStringExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME) ?: return
        val verifyHint = intent.getStringExtra(AppInterceptConstants.EXTRA_VERIFY_HINT) ?: "请输入验证码"

        // 获取应用名称
        val appName = try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
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
        // 获取当前配置
        val config = AppInterceptManager.getInstance(context).getConfig()

        val editText = EditText(context).apply {
            hint = verifyHint
            setPadding(50, 30, 50, 30)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("访问验证")
            .setMessage("应用 \"$appName\" 需要验证才能通过VPN访问网络")
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton("确认") { dialog, _ ->
                val input = editText.text.toString()
                if (input == config.verifyPassword) {
                    Toast.makeText(context, "验证通过", Toast.LENGTH_SHORT).show()
                    // 标记已验证
                    markVerified(context, packageName)
                } else {
                    Toast.makeText(context, "验证失败，请重试", Toast.LENGTH_SHORT).show()
                    // 重新显示对话框
                    showVerifyDialog(context, appName, packageName, verifyHint)
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                // 用户取消，不标记验证，APP将被阻止访问网络
                Toast.makeText(context, "已取消验证，应用将被阻止访问网络", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }

        try {
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
        } catch (e: Exception) {
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
        // 发送广播通知服务标记已验证
        val intent = Intent(AppInterceptConstants.ACTION_MARK_VERIFIED).apply {
            putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
