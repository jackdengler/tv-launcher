package com.jackdengler.quiet.takeover

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.jackdengler.quiet.R
import com.jackdengler.quiet.data.FantasyScore

/**
 * Top-right, text-only score overlay in a TYPE_ACCESSIBILITY_OVERLAY window (no overlay
 * permission needed). Never focusable or touchable, so it can't steal remote input.
 * Plain Views keep it light inside the service.
 */
class FantasyOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private lateinit var line1: TextView
    private lateinit var line2: TextView
    private lateinit var banner: TextView

    /** Design px (1920-wide) to real px. */
    private fun px(v: Float): Float {
        val m = context.resources.displayMetrics
        return v * maxOf(m.widthPixels, m.heightPixels) / 1920f
    }

    /** [lead] = 1 just took the lead, -1 just lost it, 0 no change (banner hidden). */
    fun show(score: FantasyScore, lead: Int = 0) {
        val view = root ?: create().also { root = it }
        line1.text = score.headline
        line2.text = score.detail
        if (lead != 0) {
            banner.text = if (lead > 0) "▲ Took the lead" else "▼ Lost the lead"
            banner.setTextColor(if (lead > 0) GOLD else ROSE)
            line1.setTextColor(if (lead > 0) GOLD else ROSE)
            banner.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
            line1.setTextColor(Color.WHITE)
        }
        view.visibility = View.VISIBLE
    }

    fun clearHighlight() {
        if (root == null) return
        banner.visibility = View.GONE
        line1.setTextColor(Color.WHITE)
    }

    fun hide() {
        root?.visibility = View.GONE
    }

    fun destroy() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private companion object {
        val GOLD = Color.rgb(255, 210, 122)
        val ROSE = Color.rgb(255, 138, 128)
    }

    private fun create(): LinearLayout {
        fun label(sizePx: Float, font: Int, alpha: Float) = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizePx))
            typeface = ResourcesCompat.getFont(context, font)
            setTextColor(Color.WHITE)
            this.alpha = alpha
            setShadowLayer(px(10f), 0f, px(1.5f), Color.argb(170, 0, 0, 0))
            fontFeatureSettings = "tnum"
            gravity = Gravity.END
            maxLines = 1
        }
        line1 = label(22f, R.font.manrope_semibold, 0.95f)
        line2 = label(15f, R.font.manrope_regular, 0.75f)
        banner = label(16f, R.font.manrope_semibold, 1f).apply { visibility = View.GONE }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(banner)
            addView(line1)
            addView(line2)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = px(48f).toInt()
            y = px(48f).toInt()
        }
        wm.addView(layout, params)
        return layout
    }
}
