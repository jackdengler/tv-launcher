package com.jackdengler.quiet.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** QR code for [text] (one pixel per module; scaled up without smoothing when drawn). */
fun qrBitmap(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** A QR code on a white rounded card, encoded off the main thread. */
@Composable
fun QrCode(text: String, size: Int, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, text) {
        value = withContext(Dispatchers.Default) { runCatching { qrBitmap(text) }.getOrNull() }
    }
    val bmp = image ?: return
    Image(
        bmp,
        contentDescription = null,
        filterQuality = FilterQuality.None,
        modifier = modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(14.dp).size(size.dp),
    )
}
