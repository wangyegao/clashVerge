package com.github.kr328.clash

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.content.ContextCompat
import com.github.kr328.clash.common.compat.fromHtmlCompat
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.util.AppInterceptConfigLoader
import com.github.kr328.clash.service.util.PermissionSettingsLandingPage
import com.github.kr328.clash.service.util.createOverlaySettingsLaunchInfo
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : BaseActivity<MainDesign>() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private val appStore by lazy { AppStore(this) }
    private var appInterceptGuideRunning = false
    private var skipNextAutomaticPermissionCheck = false
    private var pauseAutomaticPermissionCheck = false

    override suspend fun main() {
        val design = MainDesign(this)
        var refreshJob: Job? = null
        var refreshPending = false
        var trafficJob: Job? = null

        fun requestRefresh() {
            if (!isActive) {
                return
            }

            if (refreshJob?.isActive == true) {
                refreshPending = true
                return
            }

            refreshJob = launch {
                do {
                    refreshPending = false

                    runCatching {
                        design.fetch()
                    }.onFailure {
                        Log.w("Unable to refresh main screen state", it)
                    }
                } while (isActive && refreshPending)
            }
        }

        fun requestTrafficRefresh() {
            if (!isActive || trafficJob?.isActive == true) {
                return
            }

            trafficJob = launch {
                runCatching {
                    design.fetchTraffic()
                }.onFailure {
                    Log.w("Unable to refresh main screen traffic", it)
                }
            }
        }

        setContentDesign(design)

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            if (skipNextAutomaticPermissionCheck) {
                                skipNextAutomaticPermissionCheck = false
                            } else if (pauseAutomaticPermissionCheck) {
                            } else {
                                ensureAppInterceptPermissions(force = false)
                            }
                            requestRefresh()
                        }
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> requestRefresh()
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
                        MainDesign.Request.OpenProfiles -> {
                            if (ensureAppInterceptPermissions(force = true)) {
                                startActivity(ProfilesActivity::class.intent)
                            }
                        }
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
                        requestTrafficRefresh()
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
        if (!ensureStartupPermissions()) {
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

    private suspend fun ensureStartupPermissions(): Boolean {
        pauseAutomaticPermissionCheck = false

        val permissionState = queryAppInterceptPermissionState()
        if (permissionState.canStartIntercept) {
            return true
        }

        if (appInterceptGuideRunning) {
            return false
        }

        appInterceptGuideRunning = true

        try {
            if (!permissionState.usageStatsGranted) {
                val usageGranted = requestAppInterceptPermission(
                    title = "开启查看使用情况权限",
                    message = "需要先开启“查看使用情况”权限才能启动。请前往 Clash Meta 授权页开启；如果看到的是应用列表，请点开 Clash Meta 后再开启。",
                    settingsIntent = createUsageAccessSettingsIntent(),
                    checkGranted = { queryAppInterceptPermissionState().usageStatsGranted },
                    failureMessage = "未开启“查看使用情况”权限，暂时无法启动",
                )

                if (!usageGranted) {
                    return false
                }
            }

            if (!queryAppInterceptPermissionState().overlayGranted) {
                val overlayLaunchInfo = createOverlaySettingsLaunchInfo()
                val overlayGranted = requestAppInterceptPermission(
                    title = "开启悬浮窗权限",
                    message = buildStartupOverlayPermissionMessage(overlayLaunchInfo.landingPage),
                    settingsIntent = overlayLaunchInfo.intent,
                    checkGranted = { queryAppInterceptPermissionState().overlayGranted },
                    failureMessage = "未开启悬浮窗权限，暂时无法启动",
                    launchForResult = false,
                    keepNewTaskFlag = true,
                )

                if (!overlayGranted) {
                    return false
                }
            }

            return true
        } finally {
            appInterceptGuideRunning = false
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

    private suspend fun ensureAppInterceptPermissions(force: Boolean): Boolean {
        if (force) {
            pauseAutomaticPermissionCheck = false
        }

        val config = AppInterceptConfigLoader.load(this)
        if (!config.enabled || !config.hasValidationRule()) {
            appStore.appInterceptPermissionGuideCompleted = true
            appStore.appInterceptPermissionGuideShown = false
            pauseAutomaticPermissionCheck = false
            return true
        }

        val permissionState = queryAppInterceptPermissionState()

        if (permissionState.canStartIntercept) {
            appStore.appInterceptPermissionGuideCompleted = true
            appStore.appInterceptPermissionGuideShown = false
            pauseAutomaticPermissionCheck = false
            return true
        }

        appStore.appInterceptPermissionGuideCompleted = false
        if (appInterceptGuideRunning) {
            return false
        }

        appInterceptGuideRunning = true
        appStore.appInterceptPermissionGuideShown = true

        try {
            if (!permissionState.usageStatsGranted) {
                val usageGranted = requestAppInterceptPermission(
                    title = "启用应用拦截功能",
                    message = "首次使用前，请先开启“查看使用情况”权限。授权后应用拦截功能才能识别已配置目标应用的打开状态。将优先跳转到 Clash Meta 的授权页；如果看到的是应用列表，请点开 Clash Meta 后再开启。",
                    settingsIntent = createUsageAccessSettingsIntent(),
                    checkGranted = { queryAppInterceptPermissionState().usageStatsGranted },
                    failureMessage = "未开启“查看使用情况”权限，应用拦截功能暂未启用",
                )

                if (!usageGranted) {
                    return false
                }
            }

            if (!queryAppInterceptPermissionState().overlayGranted) {
                val overlayLaunchInfo = createOverlaySettingsLaunchInfo()
                val overlayGranted = requestAppInterceptPermission(
                    title = "开启悬浮窗权限",
                    message = buildOverlayPermissionMessage(overlayLaunchInfo.landingPage),
                    settingsIntent = overlayLaunchInfo.intent,
                    checkGranted = { queryAppInterceptPermissionState().overlayGranted },
                    failureMessage = "未开启悬浮窗权限，应用拦截功能暂未启用",
                    launchForResult = false,
                    keepNewTaskFlag = true,
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
        message: CharSequence,
        settingsIntent: android.content.Intent,
        checkGranted: () -> Boolean,
        failureMessage: String,
        launchForResult: Boolean = true,
        keepNewTaskFlag: Boolean = false,
    ): Boolean {
        if (checkGranted()) {
            return true
        }

        if (!showAppInterceptPermissionDialog(title, message)) {
            pauseAutomaticPermissionCheck = true
            return false
        }

        val launchIntent = android.content.Intent(settingsIntent).apply {
            if (!keepNewTaskFlag) {
                flags = flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK.inv()
            }
        }

        try {
            skipNextAutomaticPermissionCheck = true
            if (launchForResult) {
                startActivityForResult(ActivityResultContracts.StartActivityForResult(), launchIntent)
            } else {
                withContext(Dispatchers.Main) {
                    startActivity(launchIntent)
                }
                waitForPermissionSettingsReturn(checkGranted)
            }
        } catch (e: Exception) {
            skipNextAutomaticPermissionCheck = false
            Log.w("Unable to open permission settings for $title", e)
            Toast.makeText(this, "无法打开系统权限页，请手动到系统设置中开启相关权限", Toast.LENGTH_LONG).show()
            return false
        }

        if (awaitPermissionGrant(checkGranted)) {
            pauseAutomaticPermissionCheck = false
            return true
        }

        pauseAutomaticPermissionCheck = true
        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
        return false
    }

    private fun buildOverlayPermissionMessage(
        landingPage: PermissionSettingsLandingPage,
    ): CharSequence {
        return when (landingPage) {
            PermissionSettingsLandingPage.AppSpecific -> {
                fromHtmlCompat(
                    "需要开启“悬浮窗”权限才能正常使用，请前往 <b>Clash Meta 权限页</b>，开启“显示在其他应用的上层”。"
                )
            }
            PermissionSettingsLandingPage.AppList -> {
                fromHtmlCompat(
                    "需要开启“悬浮窗”权限才能正常使用，请前往 <b>应用列表 &gt; Clash Meta</b>，点击开启“显示在其他应用的上层”。"
                )
            }
        }
    }

    private fun buildStartupOverlayPermissionMessage(
        landingPage: PermissionSettingsLandingPage,
    ): CharSequence {
        return when (landingPage) {
            PermissionSettingsLandingPage.AppSpecific -> {
                fromHtmlCompat(
                    "需要先开启“悬浮窗”权限才能启动，请前往 <b>Clash Meta 权限页</b>，开启“显示在其他应用的上层”。"
                )
            }
            PermissionSettingsLandingPage.AppList -> {
                fromHtmlCompat(
                    "需要先开启“悬浮窗”权限才能启动，请前往 <b>应用列表 &gt; Clash Meta</b>，点击开启“显示在其他应用的上层”。"
                )
            }
        }
    }

    private suspend fun waitForPermissionSettingsReturn(
        checkGranted: () -> Boolean,
    ) {
        var activityBackgrounded = false

        repeat(300) {
            if (checkGranted()) {
                return
            }

            if (!activityStarted) {
                activityBackgrounded = true
            } else if (activityBackgrounded) {
                return
            }

            kotlinx.coroutines.delay(200)
        }
    }

    private suspend fun awaitPermissionGrant(
        checkGranted: () -> Boolean,
        maxAttempts: Int = 20,
        delayMillis: Long = 250L,
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            if (checkGranted()) {
                return true
            }

            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(delayMillis)
            }
        }

        return false
    }

    private suspend fun showAppInterceptPermissionDialog(
        title: String,
        message: CharSequence,
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
                .show()
        }
    }

}
