package com.github.kr328.clash.common.constants

/**
 * APP拦截功能相关常量
 */
object AppInterceptConstants {
    const val ACTION_UPDATE_CONFIG = "com.github.kr328.clash.action.UPDATE_INTERCEPT_CONFIG"
    const val ACTION_CLEAR_VERIFIED = "com.github.kr328.clash.action.CLEAR_VERIFIED"
    const val ACTION_MARK_VERIFIED = "com.github.kr328.clash.action.MARK_VERIFIED"
    const val ACTION_APP_INTERCEPT_REQUIRED = "com.github.kr328.clash.action.APP_INTERCEPT_REQUIRED"

    const val EXTRA_CONFIG = "config"
    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_VERIFY_HINT = "verify_hint"
}
