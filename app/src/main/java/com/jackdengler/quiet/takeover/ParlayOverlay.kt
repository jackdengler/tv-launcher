package com.jackdengler.quiet.takeover

import android.content.Context
import android.view.View
import android.view.WindowManager
import com.jackdengler.quiet.data.ParlayBoard

/** The parlay card in its own TYPE_ACCESSIBILITY_OVERLAY window, so it can sit in its own corner. */
class ParlayOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var card: ParlayCardView? = null
    private var corner = ""

    private fun ensure(corner: String): ParlayCardView {
        val existing = card
        if (existing != null && corner == this.corner) return existing
        existing?.let { runCatching { wm.removeView(it) } }
        val v = ParlayCardView(context)
        wm.addView(v, overlayParams(context, corner))
        card = v
        this.corner = corner
        return v
    }

    fun show(board: ParlayBoard, flash: Map<String, Int>, corner: String) {
        val v = ensure(corner)
        v.board = board
        v.flash = flash
        v.visibility = View.VISIBLE
    }

    fun hide() {
        card?.visibility = View.GONE
    }

    fun destroy() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }
}
