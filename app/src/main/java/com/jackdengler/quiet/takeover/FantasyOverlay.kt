package com.jackdengler.quiet.takeover

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import com.jackdengler.quiet.data.PlayPop

/**
 * The fantasy graphics in one TYPE_ACCESSIBILITY_OVERLAY window (no overlay permission needed):
 * the scorebug/card, with play cards stacked on the side away from the screen edge. Never
 * focusable or touchable, so it can't steal remote input.
 */
class FantasyOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var bug: FantasyCardView? = null
    private var play: PlayCardView? = null
    private var corner = ""

    private fun ensure(corner: String): LinearLayout {
        val existing = root
        if (existing != null && corner == this.corner) return existing
        existing?.let { runCatching { wm.removeView(it) } }
        val b = bug ?: FantasyCardView(context).also { bug = it }
        val p = play ?: PlayCardView(context).also { it.visibility = View.GONE; play = it }
        (b.parent as? LinearLayout)?.removeAllViews()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (corner.endsWith("left")) Gravity.START else Gravity.END
            // Plays sit below the bug at the top of the screen, above it at the bottom.
            if (corner.startsWith("bottom")) { addView(p); addView(b) } else { addView(b); addView(p) }
        }
        wm.addView(layout, params(corner))
        root = layout
        this.corner = corner
        return layout
    }

    fun show(cards: List<LeagueCard>, style: String, corner: String) {
        if (cards.isEmpty()) return hide()
        val layout = ensure(corner)
        bug?.style = style
        bug?.cards = cards
        bug?.visibility = View.VISIBLE
        layout.visibility = View.VISIBLE
    }

    fun showPlay(pop: PlayPop, corner: String) {
        val layout = ensure(corner)
        val p = play ?: return
        p.pop = pop
        p.alpha = 0f
        p.visibility = View.VISIBLE
        p.animate().alpha(1f).setDuration(350).start()
        layout.visibility = View.VISIBLE
    }

    fun hidePlay() {
        val p = play ?: return
        p.animate().alpha(0f).setDuration(450).withEndAction { p.visibility = View.GONE }.start()
    }

    fun hide() {
        root?.visibility = View.GONE
    }

    fun destroy() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
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
                "top-left" -> Gravity.TOP or Gravity.START
                "bottom-left" -> Gravity.BOTTOM or Gravity.START
                "bottom-right" -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.END
            }
            x = inset
            y = inset
        }
    }
}
