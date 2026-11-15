package io.github.reihlinstinct.reporter.recognition

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Invoke on a worker thread. Bundled OCR processes images locally; SDK sends Google metrics. */
object MlKitPlateOcr {
    fun recognize(bitmap: Bitmap): List<String> = PlateCandidates.fromText(recognizeText(bitmap))
    /** Raw text for encrypted private diagnostics only; no logs or public output. */
    internal fun recognizeText(bitmap: Bitmap): String {
        require(bitmap.width in 1..2560 && bitmap.height in 1..2560)
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            return result.text.take(64_000)
        } finally { client.close() }
    }

    /** Experimental, measured only by the private benchmark (not wired into the app). Reads the whole frame, then a
     * 3 x 3 grid of half-size windows, evenly spread so the last one ends at the frame edge, optionally enlarged 2x (a window is at most 1280 px per side, so no clamping), with one recognizer. A small plate that the
     * full frame misses can sit whole inside one window. Returns one text per window, full frame first. */
    internal fun recognizeTileTexts(bitmap: Bitmap, scale: Int): List<String> {
        require(bitmap.width in 2..2560 && bitmap.height in 2..2560 && scale in 1..2)
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val texts = ArrayList<String>(10)
            fun read(image: Bitmap) {
                val result = Tasks.await(client.process(InputImage.fromBitmap(image, 0)), 30, TimeUnit.SECONDS)
                texts.add(result.text.take(64_000))
            }
            read(bitmap)
            val w = bitmap.width / 2
            val h = bitmap.height / 2
            for (row in 0..2) for (col in 0..2) {
                val x = col * (bitmap.width - w) / 2
                val y = row * (bitmap.height - h) / 2
                val crop = Bitmap.createBitmap(bitmap, x, y, w, h)
                val sized = if (scale == 1) crop else Bitmap.createScaledBitmap(crop, w * scale, h * scale, true)
                try { read(sized) } finally {
                    if (sized !== crop) sized.recycle()
                    crop.recycle()
                }
            }
            return texts
        } finally { client.close() }
    }
}
