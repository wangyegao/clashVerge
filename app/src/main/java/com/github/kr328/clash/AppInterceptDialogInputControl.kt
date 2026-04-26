package com.github.kr328.clash

import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText

object AppInterceptDialogInputControl {
    fun prepareForManualFocus(
        inputView: EditText,
        fallbackFocusView: View,
    ) {
        inputView.isFocusable = false
        inputView.isFocusableInTouchMode = false
        inputView.isCursorVisible = false

        inputView.setOnTouchListener { _, event ->
            if ((event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) &&
                !inputView.isFocusableInTouchMode
            ) {
                inputView.isFocusable = true
                inputView.isFocusableInTouchMode = true
                inputView.isCursorVisible = true
                inputView.requestFocus()
            }

            false
        }

        fallbackFocusView.isFocusable = true
        fallbackFocusView.isFocusableInTouchMode = true
        fallbackFocusView.requestFocus()
    }

    fun suppressAutoKeyboard(window: Window?) {
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    fun clearFocus(
        inputView: EditText,
        fallbackFocusView: View,
    ) {
        inputView.clearFocus()
        inputView.isCursorVisible = false
        fallbackFocusView.requestFocus()
    }
}
