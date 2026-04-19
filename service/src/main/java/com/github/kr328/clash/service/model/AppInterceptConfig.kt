package com.github.kr328.clash.service.model

import android.os.Parcel
import android.os.Parcelable

/**
 * APP拦截验证配置
 */
data class AppInterceptConfig(
    // 需要拦截验证的APP包名列表
    val interceptPackages: Set<String> = emptySet(),
    // 验证密码（空表示不需要验证）
    val verifyPassword: String = "",
    // 验证提示文字
    val verifyHint: String = "请输入验证码",
    // 是否启用拦截功能
    val enabled: Boolean = false
) : Parcelable {
    constructor(parcel: Parcel) : this(
        interceptPackages = parcel.createStringArrayList()?.toSet() ?: emptySet(),
        verifyPassword = parcel.readString() ?: "",
        verifyHint = parcel.readString() ?: "请输入验证码",
        enabled = parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeStringList(interceptPackages.toList())
        parcel.writeString(verifyPassword)
        parcel.writeString(verifyHint)
        parcel.writeByte(if (enabled) 1 else 0)
    }

    override fun describeContents(): Int {
        return 0
    }

    companion object CREATOR : Parcelable.Creator<AppInterceptConfig> {
        override fun createFromParcel(parcel: Parcel): AppInterceptConfig {
            return AppInterceptConfig(parcel)
        }

        override fun newArray(size: Int): Array<AppInterceptConfig?> {
            return arrayOfNulls(size)
        }
    }
}
