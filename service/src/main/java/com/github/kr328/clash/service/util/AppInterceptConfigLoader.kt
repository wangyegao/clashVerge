package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.AppInterceptConfig
import com.github.kr328.clash.service.store.ServiceStore
import java.io.File

object AppInterceptConfigLoader {
    private const val DEFAULT_RULES_ASSET = "datas/default-rules.yaml"
    private const val DEFAULT_VERIFY_HINT = "请输入验证码"

    fun load(context: Context): AppInterceptConfig {
        val defaultSpec = loadDefaultSpec(context)
        val activeProfile = ServiceStore(context).activeProfile
        val userSpec = resolveActiveProfileConfig(context, activeProfile)
            ?.let { configFile ->
                runCatching {
                    parseSpec(configFile.readText())
                }.onFailure {
                    Log.e("AppInterceptConfigLoader: Failed to read ${configFile.absolutePath}: ${it.message}")
                }.getOrNull()
            }

        val merged = merge(defaultSpec, userSpec)

        Log.i(
            "AppInterceptConfigLoader: Loaded config for profile=$activeProfile, " +
                "packages=${merged.interceptPackages.size}, enabled=${merged.enabled}, " +
                "strictVerify=${merged.strictVerify}, passwordSet=${merged.verifyPassword.isNotEmpty()}"
        )

        return merged
    }

    private fun loadDefaultSpec(context: Context): ParsedConfigSpec {
        return runCatching {
            context.assets.open(DEFAULT_RULES_ASSET).use {
                parseSpec(it.bufferedReader().readText())
            }
        }.onFailure {
            Log.e("AppInterceptConfigLoader: Failed to load defaults: ${it.message}")
        }.getOrDefault(ParsedConfigSpec())
    }

    private fun resolveActiveProfileConfig(context: Context, activeProfile: java.util.UUID?): File? {
        if (activeProfile == null) {
            Log.d("AppInterceptConfigLoader: No active profile selected")
            return null
        }

        val configFile = context.importedDir
            .resolve(activeProfile.toString())
            .resolve("config.yaml")

        if (!configFile.exists()) {
            Log.d("AppInterceptConfigLoader: Active profile config not found at ${configFile.absolutePath}")
            return null
        }

        Log.d("AppInterceptConfigLoader: Using profile config ${configFile.absolutePath}")
        return configFile
    }

    private fun parseSpec(yamlContent: String): ParsedConfigSpec {
        var interceptPackages: MutableSet<String>? = null
        var verifyPassword: String? = null
        var verifyHint: String? = null
        var strictVerify: Boolean? = null
        var inInterceptSection = false

        yamlContent.lineSequence().forEach { line ->
            val trimmed = line.trim()

            when {
                trimmed.startsWith("intercept_packages:") -> {
                    interceptPackages = mutableSetOf()
                    inInterceptSection = true
                }
                trimmed.startsWith("- ") && inInterceptSection -> {
                    val packageName = trimmed.removePrefix("- ").trim().normalizeYamlString()
                    if (packageName.isNotEmpty() && !packageName.startsWith("#")) {
                        interceptPackages?.add(packageName)
                    }
                }
                trimmed.startsWith("verify_password:") -> {
                    verifyPassword = trimmed.removePrefix("verify_password:").trim().normalizeYamlString()
                    inInterceptSection = false
                }
                trimmed.startsWith("verify_hint:") -> {
                    verifyHint = trimmed.removePrefix("verify_hint:").trim().normalizeYamlString()
                    inInterceptSection = false
                }
                trimmed.startsWith("strict_verify:") -> {
                    strictVerify = trimmed.removePrefix("strict_verify:").trim().toBooleanStrictOrNullCompat()
                    inInterceptSection = false
                }
                trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("-") -> {
                    inInterceptSection = false
                }
            }
        }

        return ParsedConfigSpec(
            interceptPackages = interceptPackages?.toSet(),
            verifyPassword = verifyPassword,
            verifyHint = verifyHint,
            strictVerify = strictVerify,
        )
    }

    private fun merge(defaults: ParsedConfigSpec, overrides: ParsedConfigSpec?): AppInterceptConfig {
        val interceptPackages = overrides?.interceptPackages ?: defaults.interceptPackages ?: emptySet()
        val verifyPassword = overrides?.verifyPassword ?: defaults.verifyPassword ?: ""
        val verifyHint = overrides?.verifyHint ?: defaults.verifyHint ?: DEFAULT_VERIFY_HINT
        val strictVerify = overrides?.strictVerify ?: defaults.strictVerify ?: true

        return AppInterceptConfig(
            interceptPackages = interceptPackages,
            verifyPassword = verifyPassword,
            verifyHint = verifyHint,
            strictVerify = strictVerify,
            enabled = interceptPackages.isNotEmpty() && if (strictVerify) verifyPassword.isNotEmpty() else true,
        )
    }

    private fun String.normalizeYamlString(): String {
        return trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
    }

    private fun String.toBooleanStrictOrNullCompat(): Boolean? {
        return when (lowercase()) {
            "true" -> true
            "false" -> false
            "fasle" -> false
            else -> null
        }
    }

    private data class ParsedConfigSpec(
        val interceptPackages: Set<String>? = null,
        val verifyPassword: String? = null,
        val verifyHint: String? = null,
        val strictVerify: Boolean? = null,
    )
}
