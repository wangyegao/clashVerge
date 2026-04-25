package com.github.kr328.clash

object AppInterceptDialogText {
    fun resolveContentHint(verifyHint: String): String {
        return verifyHint.trim().ifBlank { "请输入确认内容" }
    }

    fun resolveInputHint(inputHint: String): String {
        return inputHint.trim().ifBlank { "请输入确认内容" }
    }
}
