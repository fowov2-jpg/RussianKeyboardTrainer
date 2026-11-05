package dev.russiankeyboard.prototype

import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets

/** The IME owns navigation-bar spacing; keyboard keys never occupy that strip. */
object KeyboardNavigationInsets {
    fun attach(keyboard: View, window: Window?, edge: Int, top: Int, gap: Int) {
        if (Build.VERSION.SDK_INT >= 30) {
            window?.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window?.decorView?.let { it.systemUiVisibility = it.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION }
        }
        // Cover the first frame before insets arrive, including OEM three-button bars.
        val resource = keyboard.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val fallback = if (resource != 0) keyboard.resources.getDimensionPixelSize(resource) else 0
        keyboard.setPadding(edge, top, edge, gap + fallback)
        fun apply(insets: WindowInsets) {
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.mandatorySystemGestures())
                keyboard.setPadding(edge + safe.left, top, edge + safe.right, gap + safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                keyboard.setPadding(edge + insets.systemWindowInsetLeft, top,
                    edge + insets.systemWindowInsetRight, gap + insets.systemWindowInsetBottom)
            }
        }
        keyboard.setOnApplyWindowInsetsListener { _, insets -> apply(insets); insets }
        keyboard.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.post { view.rootWindowInsets?.let(::apply); view.requestApplyInsets() }
            }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
}
