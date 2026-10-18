package com.jackdengler.quiet.takeover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.jackdengler.quiet.R
import com.jackdengler.quiet.data.LiveParlay
import com.jackdengler.quiet.data.ParlayBoard
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The parlay card: a header with the event and what's still riding, a "NOW" line for the fight
 * on, then one row per placed parlay: name, stake → payout, a pip per leg (green hit, rose
 * lost, gold live, hollow open) and its status. Open parlays first. [flash] outlines a parlay
 * whose leg just hit (+1) or that just busted (-1). Drawn on a Canvas in design px (1920 wide).
 */
class ParlayCardView(context: Context) : View(context) {
    var board: ParlayBoard? = null
        set(value) { field = value; requestLayout(); invalidate() }
    var flash: Map<String, Int> = emptyMap()
        set(value) { field = value; invalidate() }

    private val unit get() = max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 1920f
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_semibold) ?: Typeface.DEFAULT_BOLD
    private val regular: Typeface = ResourcesCompat.getFont(context, R.font.manrope_regular) ?: Typeface.DEFAULT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }

    private val w = 400f
    private val headH = 40f
    private val nowH = 30f
    private val rowH = 50f

    /** Open parlays first, then wins and cash-outs, then losses; at most [MAX_ROWS]. */
    private val rows: List<LiveParlay>
        get() = board?.parlays.orEmpty()
            .sortedBy { when (it.status) { "pending" -> 0; "win", "cashout" -> 1; else -> 2 } }
            .take(MAX_ROWS)

    private val cardH: Float
        get() {
            val b = board ?: return 0f
            val more = if (b.parlays.size > MAX_ROWS) 22f else 0f
            return headH + (if (b.now != null) nowH else 0f) + rowH * rows.size + more + 8f
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val u = unit
        setMeasuredDimension(((w + 24f) * u).roundToInt(), ((cardH + 24f) * u).roundToInt().coerceAtLeast(1))
    }

    // A drawing error must not crash Quiet; skip the frame instead.
    override fun onDraw(canvas: Canvas) {
        val b = board ?: return
        runCatching { drawBoard(canvas, b) }
    }

    private fun drawBoard(canvas: Canvas, b: ParlayBoard) {
        canvas.save()
        canvas.scale(unit, unit)
        canvas.translate(12f, 12f)
        paint.color = Color.argb(205, 10, 12, 16)
        canvas.drawRoundRect(RectF(0f, 0f, w, cardH), 16f, 16f, paint)

        // --- Header: event, and what the open parlays pay if they all hit ---
        paint.color = GOLD
        canvas.drawRoundRect(RectF(0f, 0f, 5f, headH), 3f, 3f, paint)
        text.typeface = bold
        text.textSize = 12f
        text.color = GOLD
        canvas.drawText("PARLAYS", 16f, 25f, text)
        val tagW = text.measureText("PARLAYS")
        val open = b.open
        val right = if (open.isEmpty()) "ALL SETTLED" else "${open.size} ALIVE · ${money(open.sumOf { it.payout })}"
        text.textSize = 13f
        text.color = Color.WHITE
        val rightW = text.measureText(right)
        canvas.drawText(right, w - 14f - rightW, 25f, text)
        text.typeface = regular
        text.color = Color.argb(190, 255, 255, 255)
        canvas.drawText(ellipsize(b.event, w - 14f - rightW - 26f - tagW - 10f), 26f + tagW, 25f, text)
        var y = headH

        // --- The fight on right now ---
        b.now?.let { now ->
            paint.color = Color.argb(26, 255, 255, 255)
            canvas.drawRect(0f, y, w, y + nowH, paint)
            paint.color = LIVE
            canvas.drawCircle(18f, y + nowH / 2, 4f, paint)
            text.typeface = bold
            text.textSize = 12f
            text.color = LIVE
            canvas.drawText("NOW", 30f, y + 19.5f, text)
            text.typeface = regular
            text.textSize = 13f
            text.color = Color.WHITE
            canvas.drawText(ellipsize(now, w - 80f), 64f, y + 19.5f, text)
            y += nowH
        }

        for (p in rows) {
            drawRow(canvas, p, y)
            y += rowH
        }
        if (b.parlays.size > MAX_ROWS) {
            text.typeface = regular
            text.textSize = 12f
            text.color = Color.argb(150, 255, 255, 255)
            canvas.drawText("+${b.parlays.size - MAX_ROWS} more", 16f, y + 15f, text)
        }
        canvas.restore()
    }

    private fun drawRow(canvas: Canvas, p: LiveParlay, top: Float) {
        val status = p.status
        val dim = status == "loss" || status == "push"
        val alpha = if (dim) 110 else 255
        paint.color = Color.argb(40, 255, 255, 255)
        canvas.drawRect(14f, top, w - 14f, top + 1f, paint)
        flash[p.id]?.let { f ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.color = if (f > 0) GOLD else ROSE
            canvas.drawRoundRect(RectF(6f, top + 4f, w - 6f, top + rowH - 4f), 10f, 10f, paint)
            paint.style = Paint.Style.FILL
        }

        // Status on the right.
        val label = when (status) {
            "win" -> "WON"
            "loss" -> "BUST"
            "push" -> "PUSH"
            "cashout" -> "CASHED"
            else -> "${p.hits}/${p.legs.size}"
        }
        text.typeface = bold
        text.textSize = 16f
        text.color = when (status) {
            "win", "cashout" -> GOLD
            "loss" -> Color.argb(alpha, Color.red(ROSE), Color.green(ROSE), Color.blue(ROSE))
            else -> Color.argb(alpha, 255, 255, 255)
        }
        val labelW = text.measureText(label)
        canvas.drawText(label, w - 16f - labelW, top + 31f, text)

        // A pip per leg, packed tighter when there are many.
        val pipsRight = w - 16f - max(labelW, 44f) - 14f
        val step = min(16f, 130f / max(p.legs.size, 1))
        val r = min(5.5f, step * 0.36f)
        var x = pipsRight - step * (p.legs.size - 1)
        for (leg in p.legs) {
            val cy = top + rowH / 2
            when {
                leg.live -> { paint.color = GOLD; canvas.drawCircle(x, cy, r + 1f, paint) }
                leg.result == "win" -> { paint.color = withAlpha(LIVE, alpha); canvas.drawCircle(x, cy, r, paint) }
                leg.result == "loss" -> { paint.color = withAlpha(ROSE, alpha); canvas.drawCircle(x, cy, r, paint) }
                else -> {
                    // Open or pushed: hollow (a push is fainter).
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.6f
                    paint.color = Color.argb(if (leg.result == "push") alpha / 3 else alpha * 3 / 4, 255, 255, 255)
                    canvas.drawCircle(x, cy, r - 0.8f, paint)
                    paint.style = Paint.Style.FILL
                }
            }
            x += step
        }
        val pipsLeft = pipsRight - step * (p.legs.size - 1) - r - 12f

        // Name, and stake → payout under it.
        text.typeface = bold
        text.textSize = 16f
        text.color = Color.argb(alpha, 255, 255, 255)
        canvas.drawText(ellipsize(p.name.ifBlank { "Parlay" }, pipsLeft - 16f), 16f, top + 23f, text)
        text.typeface = regular
        text.textSize = 12.5f
        text.color = Color.argb(alpha * 3 / 4, 255, 255, 255)
        val line = when (status) {
            "cashout" -> "${money(p.stake)} → ${money(p.payout)} cashed"
            "loss" -> "${money(p.stake)} lost"
            "win" -> "${money(p.stake)} → ${money(p.payout)} paid"
            else -> "${money(p.stake)} → ${money(p.payout)}"
        }
        canvas.drawText(ellipsize(line, pipsLeft - 16f), 16f, top + 40f, text)
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun money(v: Double) =
        if (v >= 1000) "$" + String.format(Locale.US, "%,.0f", v) else "$" + String.format(Locale.US, "%.0f", v)

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    private companion object {
        const val MAX_ROWS = 6
        val GOLD = Color.rgb(242, 196, 92)
        val ROSE = Color.rgb(255, 138, 128)
        val LIVE = Color.rgb(98, 222, 138)
    }
}
