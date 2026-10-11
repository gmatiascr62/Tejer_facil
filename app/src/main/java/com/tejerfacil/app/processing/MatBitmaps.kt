package com.tejerfacil.app.processing

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Mat
import kotlin.math.max
import kotlin.math.roundToInt

fun Bitmap.toMat(): Mat {
    val m = Mat()
    Utils.bitmapToMat(this, m) // RGBA
    return m
}

fun Mat.toBitmap(): Bitmap {
    val bmp = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(this, bmp)
    return bmp
}

fun Bitmap.scaledToMax(maxSide: Int): Bitmap {
    val s = maxSide.toFloat() / max(width, height)
    if (s >= 1f) return this
    return Bitmap.createScaledBitmap(this, max(1, (width * s).roundToInt()), max(1, (height * s).roundToInt()), true)
}
