package com.github.kr328.clash

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

object AppInterceptOverlayTip {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentPopup: PopupWindow? = null
    private var dismissRunnable: Runnable? = null

    fun show(
        context: Context,
        anchorDialog: Dialog,
        message: String,
        anchorView: View? = null,
        icon: Drawable? = null,
        durationMillis: Long = 1500L,
    ) {
        val anchorHost = anchorDialog.window?.decorView ?: return
        val tipView = LayoutInflater.from(context).inflate(R.layout.dialog_risk_tip, null)
        val iconView = tipView.findViewById<ImageView>(R.id.iv_tip_icon)
        tipView.findViewById<TextView>(R.id.tv_tip_message).text = message
        if (icon != null) {
            iconView.visibility = View.VISIBLE
            iconView.setImageDrawable(icon)
        } else {
            iconView.visibility = View.GONE
        }

        currentPopup?.dismiss()
        dismissRunnable?.let(mainHandler::removeCallbacks)
        dismissRunnable = null

        val popup = PopupWindow(
            tipView,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isTouchable = false
            isFocusable = false
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setWindowLayoutType(anchorDialog.window?.attributes?.type ?: WindowManager.LayoutParams.TYPE_APPLICATION)
            }

            setOnDismissListener {
                if (currentPopup === this) {
                    currentPopup = null
                }
                dismissRunnable = null
            }
        }

        currentPopup = popup
        anchorHost.post {
            if (currentPopup === popup && anchorHost.windowToken != null) {
                popup.showAtLocation(
                    anchorHost,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    0,
                    resolveYOffset(context, anchorDialog, anchorView),
                )
            }
        }

        val dismissAction = Runnable {
            if (currentPopup === popup) {
                currentPopup = null
            }
            popup.dismiss()
            dismissRunnable = null
        }
        dismissRunnable = dismissAction
        mainHandler.postDelayed(dismissAction, durationMillis)
    }

    private fun resolveYOffset(
        context: Context,
        anchorDialog: Dialog,
        anchorView: View?,
    ): Int {
        val fallback = context.dpToPx(96)
        val decorView = anchorDialog.window?.decorView ?: return fallback
        val dialogLocation = IntArray(2)
        decorView.getLocationOnScreen(dialogLocation)

        val dialogTargetY = dialogLocation[1] + (decorView.height * 2 / 5)
        val inputAdjustedY = anchorView?.let {
            val inputLocation = IntArray(2)
            it.getLocationOnScreen(inputLocation)
            inputLocation[1] + (it.height / 2) - context.dpToPx(44)
        }

        val targetY = inputAdjustedY?.let { min(dialogTargetY, it) } ?: dialogTargetY
        val loweredTargetY = targetY + context.dpToPx(92)
        val maxSafeY = context.resources.displayMetrics.heightPixels - context.dpToPx(180)
        return loweredTargetY.coerceIn(context.dpToPx(96), maxSafeY)
    }

    private fun Context.dpToPx(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }
}
