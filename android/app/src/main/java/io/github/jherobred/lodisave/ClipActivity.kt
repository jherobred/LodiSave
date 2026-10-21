package io.github.jherobred.lodisave

import android.app.Activity

/**
 * A see-through screen that reads the link the picker just copied and closes. Android shares the
 * clipboard only with the app in front, and the picker's accessibility service never is.
 */
class ClipActivity : Activity() {
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        PickerService.instance?.clipped(copiedLink(this))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
