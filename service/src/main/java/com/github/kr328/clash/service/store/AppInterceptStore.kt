package com.github.kr328.clash.service.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider
import com.github.kr328.clash.service.PreferenceProvider

class AppInterceptStore(context: Context) {
    private val store = Store(
        PreferenceProvider
            .createSharedPreferencesFromContext(context)
            .asStoreProvider()
    )

    var verifiedPackages by store.stringSet(
        key = "app_intercept_verified_packages",
        defaultValue = emptySet(),
    )

    var packageLastUpdateTime by store.long(
        key = "app_intercept_package_last_update_time",
        defaultValue = 0L,
    )
}
