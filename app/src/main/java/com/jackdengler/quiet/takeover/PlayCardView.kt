package com.jackdengler.quiet.takeover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.jackdengler.quiet.R
import com.jackdengler.quiet.data.FantasyScore
import com.jackdengler.quiet.data.PlayPop
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A lower-third style card for one play: player, what happened, fantasy points, and whose
 * starter it is (gold stripe = mine, slate = my opponent's). Touchdowns, turnovers and field
 * goals get a full-colour card. Same width as the scorebug so they stack neatly.
 */
class PlayCardView(context: Context) : View(context) {
    var pop: PlayPop? = null
        set(value) { field = value; invalidate() }

    private val unit get() = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 1920f
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_semibold) ?: Typeface.DEFAULT_BOLD
    private val regular: Typeface = ResourcesCompat.getFont(context, R.font.manrope_regular) ?: Typeface.DEFAULT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }

    private val cardW = 340f
    private val cardH = 66f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(((cardW + 24f) * unit).roundToInt(), ((cardH + 12f) * unit).roundToInt())
    }

    // A drawing error must not crash Quiet; skip the frame instead.
    override fun onDraw(canvas: Canvas) {
        runCatching { drawAll(canvas) }
    }

    private fun drawAll(canvas: Canvas) {
        val p = pop ?: return
        canvas.save()
        canvas.scale(unit, unit)
        canvas.translate(12f, 4f)
        val accent = if (p.mine) GOLD else SLATE
        val box = RectF(0f, 0f, cardW, cardH)
        if (p.big) {
            paint.shader = LinearGradient(0f, 0f, cardW, 0f, accent, darker(accent), Shader.TileMode.CLAMP)
        } else {
            paint.shader = LinearGradient(0f, 0f, 0f, cardH, Color.rgb(34, 38, 46), Color.rgb(14, 16, 20), Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(box, 12f, 12f, paint)
        paint.shader = null
        if (!p.big) {
            paint.color = accent
            canvas.drawRoundRect(RectF(0f, 0f, 6f, cardH), 3f, 3f, paint)
        }
        val ink = if (p.big) Color.rgb(24, 20, 12) else Color.WHITE
        val soft = if (p.big) Color.argb(200, 24, 20, 12) else Color.argb(185, 255, 255, 255)

        // Points, right-aligned.
        val pts = p.points?.let { (if (it > 0) "+" else "") + FantasyScore.fmt(it) }
        var right = cardW - 14f
        if (pts != null) {
            text.typeface = bold
            text.textSize = 24f
            text.color = if (p.big) ink else if ((p.points ?: 0.0) >= 0) GREEN else ROSE
            val w = text.measureText(pts)
            canvas.drawText(pts, right - w, 41f, text)
            right -= w + 14f
        }
        // Player and what happened.
        text.typeface = bold
        text.textSize = 19f
        text.color = ink
        canvas.drawText(fit(p.player, right - 20f), 20f, 28f, text)
        text.typeface = regular
        text.textSize = 15f
        text.color = soft
        val owner = if (p.mine) "Your starter" else "Opponent"
        canvas.drawText(fit("${p.what}  ·  $owner", right - 20f), 20f, 51f, text)
        canvas.restore()
    }

    private fun fit(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    private fun darker(c: Int) = Color.rgb((Color.red(c) * 0.78f).toInt(), (Color.green(c) * 0.78f).toInt(), (Color.blue(c) * 0.78f).toInt())

    private companion object {
        val GOLD = Color.rgb(242, 196, 92)
        val SLATE = Color.rgb(120, 134, 156)
        val GREEN = Color.rgb(98, 222, 138)
        val ROSE = Color.rgb(255, 138, 128)
    }
}
