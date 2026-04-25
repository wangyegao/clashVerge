package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.AppInterceptConfigLoader
import com.github.kr328.clash.service.util.createOverlaySettingsIntent
import com.github.kr328.clash.service.util.createUsageAccessSettingsIntent
import com.github.kr328.clash.service.util.queryAppInterceptPermissionState
import com.github.kr328.clash.store.AppStore
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.core.bridge.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : BaseActivity<MainDesign>() {
    private val appStore by lazy { AppStore(this) }
    private var appInterceptGuideRunning = false

    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs -> {
                            if (LogcatService.running) {
                                startActivity(LogcatActivity::class.intent)
                            } else {
                                startActivity(LogsActivity::class.intent)
                            }
                        }
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }

        setMode(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        if (!ensureAppInterceptPermissions(force = true)) {
            return
        }

        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(R.string.no_profile_selected, ToastDuration.Long) {
                setAction(R.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(R.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName + "\n" + Bridge.nativeCoreVersion().replace("_", "-")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(RequestPermission()
                ) { isGranted: Boolean ->
                }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() {
        super.onResume()

        launch {
            ensureAppInterceptPermissions(force = false)
        }
    }

    private suspend fun ensureAppInterceptPermissions(force: Boolean): Boolean {
        val config = AppInterceptConfigLoader.load(this)
        if (!config.enabled || !config.hasValidationRule()) {
            appStore.appInterceptPermissionGuideCompleted = true
            return true
        }

        val permissionState = queryAppInterceptPermissionState()
        if (!appStore.appInterceptOnboardingShown) {
            val continueFlow = showAppInterceptIntroDialog(permissionState.canStartIntercept)
            appStore.appInterceptOnboardingShown = true

            if (!continueFlow) {
                return false
            }
        }

        if (permissionState.canStartIntercept) {
            appStore.appInterceptPermissionGuideCompleted = true
            return true
        }

        appStore.appInterceptPermissionGuideCompleted = false
        if (!force && appStore.appInterceptPermissionGuideShown) {
            return false
        }

        if (appInterceptGuideRunning) {
            return false
        }

        appInterceptGuideRunning = true
        appStore.appInterceptPermissionGuideShown = true

        try {
            if (!permissionState.usageStatsGranted) {
                val usageGranted = requestAppInterceptPermission(
                    title = "启用应用拦截功能",
                    message = "首次使用前，请先开启“查看使用情况”权限。授权后应用拦截功能才能识别已配置目标应用的打开状态。将优先跳转到 Clash Verge 的授权页；如果看到的是应用列表，请点开 Clash Verge 后再开启。",
                    settingsIntent = createUsageAccessSettingsIntent(),
                    checkGranted = { queryAppInterceptPermissionState().usageStatsGranted },
                    failureMessage = "未开启“查看使用情况”权限，应用拦截功能暂未启用",
                )

                if (!usageGranted) {
                    return false
                }
            }

            if (!queryAppInterceptPermissionState().overlayGranted) {
                val overlayGranted = requestAppInterceptPermission(
                    title = "继续完成权限设置",
                    message = "还需要开启悬浮窗权限，应用拦截功能才能在检测到目标应用时立即弹出验证框。将优先跳转到 Clash Verge 的授权页；如果看到的是应用列表，请点开 Clash Verge 后再开启。",
                    settingsIntent = createOverlaySettingsIntent(),
                    checkGranted = { queryAppInterceptPermissionState().overlayGranted },
                    failureMessage = "未开启悬浮窗权限，应用拦截功能暂未启用",
                )

                if (!overlayGranted) {
                    return false
                }
            }

            appStore.appInterceptPermissionGuideCompleted = true
            return true
        } finally {
            appInterceptGuideRunning = false
        }
    }

    private suspend fun requestAppInterceptPermission(
        title: String,
        message: String,
        settingsIntent: android.content.Intent,
        checkGranted: () -> Boolean,
        failureMessage: String,
    ): Boolean {
        if (!showAppInterceptPermissionDialog(title, message)) {
            return false
        }

        startActivityForResult(ActivityResultContracts.StartActivityForResult(), settingsIntent)

        if (checkGranted()) {
            return true
        }

        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
        return false
    }

    private suspend fun showAppInterceptPermissionDialog(
        title: String,
        message: String,
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCoroutine { continuation ->
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("去开启") { dialog, _ ->
                    dialog.dismiss()
                    continuation.resume(true)
                }
                .setNegativeButton("稍后处理") { dialog, _ ->
                    dialog.dismiss()
                    continuation.resume(false)
                }
                .show()
        }
    }

    private suspend fun showAppInterceptIntroDialog(
        permissionsReady: Boolean,
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCoroutine { continuation ->
            val title = if (permissionsReady) {
                "应用拦截功能已就绪"
            } else {
                "首次使用请完成权限设置"
            }
            val message = if (permissionsReady) {
                "当前安装包已启用应用拦截功能，所需权限也已就绪。后续检测到已配置目标应用时，会按规则弹出验证提醒。"
            } else {
                "为确保应用拦截功能正常工作，首次使用前请先开启“查看使用情况”和“悬浮窗”权限。完成后，应用才能按规则检测已配置目标应用并弹出验证提醒。"
            }

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(if (permissionsReady) "我知道了" else "继续设置") { dialog, _ ->
                    dialog.dismiss()
                    continuation.resume(true)
                }
                .setNegativeButton("稍后处理") { dialog, _ ->
                    dialog.dismiss()
                    continuation.resume(false)
                }
                .show()
        }
    }
}
