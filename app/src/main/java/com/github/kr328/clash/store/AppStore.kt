package com.github.kr328.clash.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

class AppStore(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var updatedAt: Long by store.long(
        key = "updated_at",
        defaultValue = -1,
    )

    var appInterceptPermissionGuideShown: Boolean by store.boolean(
        key = "app_intercept_permission_guide_shown",
        defaultValue = false,
    )

    var appInterceptPermissionGuideCompleted: Boolean by store.boolean(
        key = "app_intercept_permission_guide_completed",
        defaultValue = false,
    )

    var appInterceptOnboardingShown: Boolean by store.boolean(
        key = "app_intercept_onboarding_shown",
        defaultValue = false,
    )

    companion object {
        private const val FILE_NAME = "app"
    }
}
