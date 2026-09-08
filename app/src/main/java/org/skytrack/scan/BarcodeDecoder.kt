// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// =============================================================
// FlightInfo - BarcodeDecoder
// Version 1.5
// Purpose : Thin wrapper around ZXing core (Apache-2.0, pure Java, no Play
//           Services) that decodes boarding-pass barcodes from a camera
//           luminance frame or from a picked image. Tries the natural
//           orientation and a 90-degree rotation, because PDF417 decoding is
//           orientation-sensitive and passes are often held sideways.
// =============================================================
package org.skytrack.scan

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

object BarcodeDecoder {

    private val formats = listOf(BarcodeFormat.PDF_417, BarcodeFormat.AZTEC, BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX)

    private fun reader(tryHarder: Boolean): MultiFormatReader {
        val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
        hints[DecodeHintType.POSSIBLE_FORMATS] = formats
        if (tryHarder) hints[DecodeHintType.TRY_HARDER] = true
        return MultiFormatReader().apply { setHints(hints) }
    }

    /**
     * Decode a Y (luminance) plane. `y` must be tightly packed (row stride == width).
     * Returns the raw barcode text or null.
     */
    fun decodeLuminance(y: ByteArray, width: Int, height: Int): String? {
        val r = reader(tryHarder = false)
        decode(r, PlanarYUVLuminanceSource(y, width, height, 0, 0, width, height, false))?.let { return it }
        val rotated = rotate90(y, width, height)
        return decode(r, PlanarYUVLuminanceSource(rotated, height, width, 0, 0, height, width, false))
    }

    /** Decode a software bitmap (picked from the gallery / a screenshot). */
    fun decodeBitmap(bmp: Bitmap): String? {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val r = reader(tryHarder = true)
        decode(r, RGBLuminanceSource(w, h, pixels))?.let { return it }
        // Rotate the pixel array by 90 degrees and retry.
        val rot = IntArray(w * h)
        for (yy in 0 until h) for (xx in 0 until w) rot[xx * h + (h - 1 - yy)] = pixels[yy * w + xx]
        return decode(r, RGBLuminanceSource(h, w, rot))
    }

    private fun decode(r: MultiFormatReader, src: LuminanceSource): String? = try {
        r.decodeWithState(BinaryBitmap(HybridBinarizer(src))).text
    } catch (e: ReaderException) {
        null
    } catch (e: Exception) {
        null
    } finally {
        r.reset()
    }

    /** Rotate a packed 8-bit grayscale image 90 degrees clockwise. */
    private fun rotate90(src: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            val col = h - 1 - y
            for (x in 0 until w) out[x * h + col] = src[row + x]
        }
        return out
    }
}
