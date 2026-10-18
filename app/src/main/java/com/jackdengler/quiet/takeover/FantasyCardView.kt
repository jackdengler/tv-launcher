package com.jackdengler.quiet.takeover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.jackdengler.quiet.R
import com.jackdengler.quiet.data.FantasyScore
import com.jackdengler.quiet.data.FantasyTeam
import kotlin.math.max
import kotlin.math.roundToInt

/** Everything one league's graphic needs. [history] is my win chance over the matchup (0..1). */
class LeagueCard(
    val score: FantasyScore,
    val history: FloatArray,
    val myLogo: Bitmap?,
    val oppLogo: Bitmap?,
    /** +1 just took the lead, -1 just lost it, 0 nothing to celebrate. */
    val leadChange: Int = 0,
)

/**
 * Broadcast-style fantasy graphics, drawn directly on a Canvas (no layouts) so the overlay
 * stays cheap. Styles:
 * - "bug": a scorebug: logos, abbreviations and scores, with a pennant underneath showing both
 *   projected finals and pointing at whoever is projected to win.
 * - "card": the bug plus a win-probability chart for the matchup and a status line.
 * - "full": the card plus every starter's points, side by side.
 * Sizes are design px (1920-wide screen) scaled by [unit].
 */
class FantasyCardView(context: Context) : View(context) {
    var cards: List<LeagueCard> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }
    var style: String = "bug"
        set(value) { field = value; requestLayout(); invalidate() }

    /** Real px per design px; [scaleUp] enlarges everything (used in Quiet's own panel). */
    var scaleUp = 1f
        set(value) { field = value; requestLayout(); invalidate() }
    private val unit get() = scaleUp * max(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) / 1920f

    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.manrope_semibold) ?: Typeface.DEFAULT_BOLD
    private val regular: Typeface = ResourcesCompat.getFont(context, R.font.manrope_regular) ?: Typeface.DEFAULT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }

    private val cardW get() = if (style == "full") 560f else 340f
    private val bugH = 52f
    private val pennantH = 26f
    private val chartH = 70f
    private val rowH = 24f
    private val gap = 14f

    private fun cardHeight(c: LeagueCard): Float {
        var h = bugH + 6f + pennantH
        if (style != "bug") h += 10f + chartH + 22f
        if (style == "full") h += 12f + rowH * max(c.score.me.starters.size, c.score.opp.starters.size)
        return h
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val u = unit
        val h = cards.sumOf { cardHeight(it).toDouble() }.toFloat() + gap * (cards.size - 1).coerceAtLeast(0)
        setMeasuredDimension(((cardW + 24f) * u).roundToInt(), ((h + 24f) * u).roundToInt().coerceAtLeast(1))
    }

    // A drawing error must not crash Quiet; skip the frame instead.
    override fun onDraw(canvas: Canvas) {
        runCatching { drawAll(canvas) }
    }

    private fun drawAll(canvas: Canvas) {
        val u = unit
        canvas.save()
        canvas.scale(u, u)
        canvas.translate(12f, 12f)
        for (c in cards) {
            drawLeague(canvas, c)
            canvas.translate(0f, cardHeight(c) + gap)
        }
        canvas.restore()
    }

    private fun drawLeague(canvas: Canvas, c: LeagueCard) {
        val s = c.score
        val w = cardW

        // Panel behind card/full styles; the bug carries its own bar.
        if (style != "bug") {
            paint.shader = null
            paint.color = Color.argb(200, 10, 12, 16)
            canvas.drawRoundRect(RectF(-8f, -8f, w + 8f, cardHeight(c) + 8f), 18f, 18f, paint)
        }

        // --- Scorebug bar ---
        val bar = RectF(0f, 0f, w, bugH)
        paint.shader = LinearGradient(0f, 0f, 0f, bugH, Color.rgb(34, 38, 46), Color.rgb(14, 16, 20), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(bar, 12f, 12f, paint)
        paint.shader = null
        if (c.leadChange != 0) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = if (c.leadChange > 0) GOLD else ROSE
            canvas.drawRoundRect(bar, 12f, 12f, paint)
            paint.style = Paint.Style.FILL
        }
        // Team colour strips (mine gold, theirs slate) at the outer edges.
        paint.color = GOLD
        canvas.drawRoundRect(RectF(0f, 0f, 5f, bugH), 3f, 3f, paint)
        paint.color = SLATE
        canvas.drawRoundRect(RectF(w - 5f, 0f, w, bugH), 3f, 3f, paint)

        val half = w / 2f
        drawSide(canvas, s.me, c.myLogo, 0f, half, mine = true, leading = s.lead >= 0)
        drawSide(canvas, s.opp, c.oppLogo, half, w, mine = false, leading = s.lead <= 0)
        paint.color = Color.argb(60, 255, 255, 255)
        canvas.drawRect(half - 0.75f, 10f, half + 0.75f, bugH - 10f, paint)

        // --- Pennant: projected finals, pointing at whoever is projected to win ---
        val myProj = s.me.expected
        val oppProj = s.opp.expected
        val favMine = myProj >= oppProj
        val label = "PROJ  ${FantasyScore.fmt(myProj)} – ${FantasyScore.fmt(oppProj)}"
        text.typeface = bold
        text.textSize = 14f
        val tw = text.measureText(label)
        val tipDepth = 12f
        val pw = tw + 26f + tipDepth
        val top = bugH + 6f
        // Centered, nudged toward the favourite so the tip sits under their half.
        val cx = half + if (favMine) -14f else 14f
        val left = cx - pw / 2
        val right = cx + pw / 2
        val pennant = Path().apply {
            if (favMine) {
                moveTo(left, top + pennantH / 2)
                lineTo(left + tipDepth, top)
                lineTo(right, top)
                lineTo(right, top + pennantH)
                lineTo(left + tipDepth, top + pennantH)
            } else {
                moveTo(right, top + pennantH / 2)
                lineTo(right - tipDepth, top)
                lineTo(left, top)
                lineTo(left, top + pennantH)
                lineTo(right - tipDepth, top + pennantH)
            }
            close()
        }
        paint.color = if (favMine) GOLD else SLATE
        canvas.drawPath(pennant, paint)
        text.color = if (favMine) Color.rgb(32, 24, 8) else Color.WHITE
        val tx = if (favMine) left + tipDepth + (pw - tipDepth - tw) / 2 else left + (pw - tipDepth - tw) / 2
        canvas.drawText(label, tx, top + pennantH / 2 + 5f, text)

        if (style == "bug") return

        // --- Win chance chart ---
        val chartTop = top + pennantH + 10f
        drawChart(canvas, c.history, RectF(0f, chartTop, w, chartTop + chartH))
        text.typeface = regular
        text.textSize = 13f
        text.color = Color.argb(190, 255, 255, 255)
        val league = s.leagueName.ifBlank { "Fantasy" }
        canvas.drawText(ellipsize("$league · ${s.detail}", w), 0f, chartTop + chartH + 17f, text)

        if (style != "full") return

        // --- Every starter, side by side ---
        var y = chartTop + chartH + 22f + 12f
        val rows = max(s.me.starters.size, s.opp.starters.size)
        for (i in 0 until rows) {
            val a = s.me.starters.getOrNull(i)
            val b = s.opp.starters.getOrNull(i)
            if (i % 2 == 0) {
                paint.color = Color.argb(22, 255, 255, 255)
                canvas.drawRect(-4f, y, w + 4f, y + rowH, paint)
            }
            text.textSize = 12f
            text.typeface = bold
            text.color = Color.argb(150, 255, 255, 255)
            canvas.drawText((a ?: b)?.slot.orEmpty(), 0f, y + 16f, text)
            a?.let { drawPlayer(canvas, it.name, it.points, it.state, 46f, half - 10f, y) }
            b?.let { drawPlayer(canvas, it.name, it.points, it.state, half + 10f, w, y) }
            y += rowH
        }
    }

    private fun drawSide(canvas: Canvas, t: FantasyTeam, logo: Bitmap?, from: Float, to: Float, mine: Boolean, leading: Boolean) {
        val r = 17f
        val cy = bugH / 2
        val logoX = if (mine) from + 14f + r else to - 14f - r
        drawLogo(canvas, t, logo, logoX, cy, r)
        text.typeface = bold
        text.textSize = 13f
        text.color = Color.argb(185, 255, 255, 255)
        val abbr = t.abbrev.take(4).uppercase()
        text.textSize = 25f
        val score = FantasyScore.fmt(t.points)
        val scoreW = text.measureText(score)
        text.textSize = 13f
        val abbrW = text.measureText(abbr)
        if (mine) {
            val x = logoX + r + 10f
            canvas.drawText(abbr, x, cy + 5f, text)
            text.textSize = 25f
            text.color = if (leading) Color.WHITE else Color.argb(170, 255, 255, 255)
            canvas.drawText(score, to - 12f - scoreW, cy + 9f, text)
        } else {
            val x = logoX - r - 10f - abbrW
            canvas.drawText(abbr, x, cy + 5f, text)
            text.textSize = 25f
            text.color = if (leading) Color.WHITE else Color.argb(170, 255, 255, 255)
            canvas.drawText(score, from + 12f, cy + 9f, text)
        }
    }

    private fun drawLogo(canvas: Canvas, t: FantasyTeam, logo: Bitmap?, cx: Float, cy: Float, r: Float) {
        if (logo != null) {
            val shader = BitmapShader(logo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = (2 * r) / minOf(logo.width, logo.height)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(cx - logo.width * scale / 2, cy - logo.height * scale / 2)
            })
            paint.shader = shader
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
        } else {
            // No usable logo (ESPN's defaults are SVG): initials on a colour from the name.
            val hue = (t.name.hashCode() and 0x7fffffff) % 360
            paint.color = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.55f))
            canvas.drawCircle(cx, cy, r, paint)
            text.typeface = bold
            text.textSize = 13f
            text.color = Color.WHITE
            val initials = t.abbrev.take(3).uppercase()
            canvas.drawText(initials, cx - text.measureText(initials) / 2, cy + 4.5f, text)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(120, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawChart(canvas: Canvas, history: FloatArray, box: RectF) {
        paint.color = Color.argb(28, 255, 255, 255)
        canvas.drawRoundRect(box, 8f, 8f, paint)
        val mid = box.centerY()
        paint.color = Color.argb(70, 255, 255, 255)
        var x = box.left + 4f
        while (x < box.right - 4f) { canvas.drawRect(x, mid - 0.5f, x + 5f, mid + 0.5f, paint); x += 10f }
        text.typeface = regular
        text.textSize = 10f
        text.color = Color.argb(140, 255, 255, 255)
        canvas.drawText("50%", box.right - 26f, mid - 4f, text)
        if (history.isEmpty()) return
        val points = if (history.size == 1) floatArrayOf(history[0], history[0]) else history
        val step = (box.width() - 8f) / (points.size - 1)
        fun yOf(p: Float) = box.bottom - 4f - p * (box.height() - 8f)
        val line = Path()
        points.forEachIndexed { i, p -> val px = box.left + 4f + i * step; if (i == 0) line.moveTo(px, yOf(p)) else line.lineTo(px, yOf(p)) }
        // Fill toward the 50% line: gold above (I'm favoured), slate below.
        val fill = Path(line).apply {
            lineTo(box.left + 4f + (points.size - 1) * step, mid)
            lineTo(box.left + 4f, mid)
            close()
        }
        canvas.save()
        canvas.clipRect(box.left, box.top, box.right, mid)
        paint.color = Color.argb(70, Color.red(GOLD), Color.green(GOLD), Color.blue(GOLD))
        canvas.drawPath(fill, paint)
        canvas.restore()
        canvas.save()
        canvas.clipRect(box.left, mid, box.right, box.bottom)
        paint.color = Color.argb(70, Color.red(SLATE), Color.green(SLATE), Color.blue(SLATE))
        canvas.drawPath(fill, paint)
        canvas.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = Color.WHITE
        canvas.drawPath(line, paint)
        paint.style = Paint.Style.FILL
        val last = points.last()
        canvas.drawCircle(box.left + 4f + (points.size - 1) * step, yOf(last), 3.5f, paint)
    }

    private fun drawPlayer(canvas: Canvas, name: String, pts: Double, state: String, from: Float, to: Float, y: Float) {
        text.typeface = regular
        text.textSize = 13f
        text.color = if (state == "pre") Color.argb(150, 255, 255, 255) else Color.WHITE
        val ptsText = FantasyScore.fmt(pts)
        val pw = text.measureText(ptsText)
        val nameMax = to - from - pw - 18f
        canvas.drawText(ellipsize(name, nameMax), from, y + 16f, text)
        canvas.drawText(ptsText, to - pw, y + 16f, text)
        if (state == "in") {
            paint.color = LIVE
            canvas.drawCircle(to - pw - 8f, y + 12f, 3f, paint)
        }
    }

    private fun ellipsize(s: String, maxWidth: Float): String {
        if (text.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 1 && text.measureText(s.substring(0, end) + "…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }

    private companion object {
        val GOLD = Color.rgb(242, 196, 92)
        val SLATE = Color.rgb(120, 134, 156)
        val ROSE = Color.rgb(255, 138, 128)
        val LIVE = Color.rgb(98, 222, 138)
    }
}
