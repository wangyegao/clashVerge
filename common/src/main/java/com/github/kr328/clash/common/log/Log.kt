package com.github.kr328.clash.common.log

import com.github.kr328.clash.common.BuildConfig

object Log {
    private const val TAG = "ClashMetaForAndroid"
    private const val ENABLE_VERBOSE_LOGS = BuildConfig.DEBUG

    fun i(message: String, throwable: Throwable? = null) =
        if (ENABLE_VERBOSE_LOGS) android.util.Log.i(TAG, message, throwable) else 0

    fun w(message: String, throwable: Throwable? = null) =
        android.util.Log.w(TAG, message, throwable)

    fun e(message: String, throwable: Throwable? = null) =
        android.util.Log.e(TAG, message, throwable)

    fun d(message: String, throwable: Throwable? = null) =
        if (ENABLE_VERBOSE_LOGS) android.util.Log.d(TAG, message, throwable) else 0

    fun v(message: String, throwable: Throwable? = null) =
        if (ENABLE_VERBOSE_LOGS) android.util.Log.v(TAG, message, throwable) else 0

    fun f(message: String, throwable: Throwable) =
        android.util.Log.wtf(message, throwable)
}
