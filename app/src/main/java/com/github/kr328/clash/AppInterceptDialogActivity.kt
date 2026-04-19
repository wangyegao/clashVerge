package com.github.kr328.clash

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.github.kr328.clash.common.constants.AppInterceptConstants

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

        val config = AppInterceptManager.getInstance(this).getConfig()

        val editText = EditText(this).apply {
            hint = verifyHint
            setPadding(50, 30, 50, 30)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("访问验证")
            .setMessage("应用 \"$appName\" 需要验证才能通过VPN访问网络")
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton("确认") { dialog, _ ->
                val input = editText.text.toString()
                if (input == config.verifyPassword) {
                    Toast.makeText(this, "验证通过", Toast.LENGTH_SHORT).show()
                    markVerified(packageName)
                    finish()
                } else {
                    Toast.makeText(this, "验证失败，请重试", Toast.LENGTH_SHORT).show()
                    // 清空输入框
                    editText.text.clear()
                }
            }
            .setNegativeButton("取消") { dialog, _ ->
                Toast.makeText(this, "已取消验证，应用将被阻止访问网络", Toast.LENGTH_LONG).show()
                finish()
            }
            .setOnDismissListener {
                finish()
            }

        builder.create().show()
    }

    private fun markVerified(packageName: String) {
        val intent = Intent(AppInterceptConstants.ACTION_MARK_VERIFIED).apply {
            putExtra(AppInterceptConstants.EXTRA_PACKAGE_NAME, packageName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
}
