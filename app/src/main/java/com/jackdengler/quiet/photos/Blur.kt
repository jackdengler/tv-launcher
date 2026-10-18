package com.jackdengler.quiet.photos

import android.graphics.Bitmap

/**
 * Three passes of a separable box blur (≈ Gaussian) on a small bitmap, in place.
 * Used once per photo on a background thread for the frosted-glass tiles.
 */
fun Bitmap.boxBlur(radius: Int): Bitmap {
    val w = width
    val h = height
    val src = IntArray(w * h)
    val tmp = IntArray(w * h)
    getPixels(src, 0, w, 0, 0, w, h)
    repeat(3) {
        blurPass(src, tmp, w, h, radius, horizontal = true)
        blurPass(tmp, src, w, h, radius, horizontal = false)
    }
    setPixels(src, 0, w, 0, 0, w, h)
    return this
}

private fun blurPass(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val lines = if (horizontal) h else w
    val len = if (horizontal) w else h
    val div = 2 * r + 1
    for (line in 0 until lines) {
        fun idx(i: Int) = if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line
        var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) {
            val c = input[idx(i)]
            sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
        }
        for (i in 0 until len) {
            output[idx(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = input[idx(i + r + 1)]
            val sub = input[idx(i - r)]
            sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
            sb += (add and 0xFF) - (sub and 0xFF)
        }
    }
}
