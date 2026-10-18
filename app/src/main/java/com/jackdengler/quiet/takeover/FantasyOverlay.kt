package com.jackdengler.quiet.takeover

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The fantasy graphic in a TYPE_ACCESSIBILITY_OVERLAY window (no overlay permission needed).
 * Never focusable or touchable, so it can't steal remote input. Sits in the chosen corner.
 */
class FantasyOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: FantasyCardView? = null
    private var corner = ""

    fun show(cards: List<LeagueCard>, style: String, corner: String) {
        if (cards.isEmpty()) return hide()
        val v = view ?: FantasyCardView(context).also { view = it }
        if (v.parent == null || corner != this.corner) {
            if (v.parent != null) runCatching { wm.removeView(v) }
            wm.addView(v, params(corner))
            this.corner = corner
        }
        v.style = style
        v.cards = cards
        v.visibility = View.VISIBLE
    }

    fun hide() {
        view?.visibility = View.GONE
    }

    fun destroy() {
        view?.let { if (it.parent != null) runCatching { wm.removeView(it) } }
        view = null
    }

    private fun params(corner: String): WindowManager.LayoutParams {
        val m = context.resources.displayMetrics
        val inset = (36f * maxOf(m.widthPixels, m.heightPixels) / 1920f).toInt()
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (corner) {
                "top-right" -> Gravity.TOP or Gravity.END
                "bottom-left" -> Gravity.BOTTOM or Gravity.START
                "bottom-right" -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.START
            }
            x = inset
            y = inset
        }
    }
}
