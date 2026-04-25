package com.github.kr328.clash.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.log.Log
import java.security.MessageDigest
import kotlin.system.exitProcess

object ReleaseHardening {
    private const val TAMPER_TOAST = "安装包签名异常，请重新安装受信任版本"

    fun allowStartup(context: Context, processName: String?): Boolean {
        if (!BuildConfig.HARDENING_ENABLED) {
            return true
        }

        val expectedSigner = BuildConfig.EXPECTED_SIGNER_SHA256
        if (expectedSigner.isBlank()) {
            Log.w("ReleaseHardening: Missing expected signer digest")
            return true
        }

        val actualSigner = runCatching {
            resolveCurrentSignerSha256(context)
        }.onFailure {
            Log.e("ReleaseHardening: Failed to read current signer: ${it.message}")
        }.getOrNull() ?: return blockStartup(context, processName)

        if (actualSigner.equals(expectedSigner, ignoreCase = true)) {
            return true
        }

        Log.e(
            "ReleaseHardening: Signer mismatch in process=$processName, " +
                "expected=$expectedSigner, actual=$actualSigner"
        )
        return blockStartup(context, processName)
    }

    private fun blockStartup(context: Context, processName: String?): Boolean {
        if (processName == context.packageName) {
            Toast.makeText(context, TAMPER_TOAST, Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(0)
                },
                1200L,
            )
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(0)
        }

        return false
    }

    private fun resolveCurrentSignerSha256(context: Context): String {
        val packageInfo = context.packageManager.queryPackageInfoCompat(context.packageName)
        val signerBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = requireNotNull(packageInfo.signingInfo) {
                "Missing signing info"
            }
            val signatures = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            requireNotNull(signatures.firstOrNull()) {
                "Missing package signature"
            }.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            requireNotNull(packageInfo.signatures?.firstOrNull()) {
                "Missing package signature"
            }.toByteArray()
        }

        return MessageDigest.getInstance("SHA-256")
            .digest(signerBytes)
            .joinToString(":") { "%02X".format(it) }
    }

    private fun PackageManager.queryPackageInfoCompat(packageName: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, flags)
        }
    }
}
