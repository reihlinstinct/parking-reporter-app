package io.github.reihlinstinct.reporter.recognition

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.exp

/**
 * Issue #158: an experimental, suggestion-only "this looks like sidewalk parking" hint.
 *
 * The model is private and is never part of the repository, a release asset or the APK. The owner puts it on
 * the phone by hand (adb push into the app's own external files folder, see [modelFile]). With no file, a
 * blank pin or a file whose SHA-256 differs from the pin, nothing happens and the screen is unchanged.
 * Everything runs on the phone; the photo never leaves it. The result only shows a line the user may tap;
 * it never selects a category, never fills a field and never changes what is filed.
 */
object SidewalkSuggestion {
    /** SHA-256 (lowercase hex) of the approved v3 tflite. Blank until the owner supplies it: blank keeps the feature off. */
    const val PINNED_SHA256 = ""
    const val THRESHOLD = 0.5f
    const val INPUT_SIZE = 224
    const val RESIZE_SHORT_SIDE = 256
    const val MODEL_FILE_NAME = "sidewalk_v3.tflite"
    /** Category the suggestion points at: the municipal subject code of sidewalk parking. */
    const val SIDEWALK_SUBJECT_CODE = "1145"

    private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun modelFile(context: Context): File? = context.getExternalFilesDir(null)?.let { File(File(it, "models"), MODEL_FILE_NAME) }

    /** Show only for a score at or above the threshold, and not when sidewalk parking is already the chosen category. */
    fun shouldSuggest(score: Float?, selectedCode: String?): Boolean =
        score != null && score.isFinite() && score >= THRESHOLD && selectedCode != SIDEWALK_SUBJECT_CODE

    /** The head may emit a probability or a raw logit; anything outside 0..1 is treated as a logit. */
    fun probability(raw: Float): Float? =
        if (!raw.isFinite()) null else if (raw in 0f..1f) raw else (1.0 / (1.0 + exp(-raw.toDouble()))).toFloat()

    fun pinMatches(actualSha256: String, pin: String = PINNED_SHA256): Boolean =
        pin.matches(Regex("[a-f0-9]{64}")) && actualSha256.equals(pin, ignoreCase = true)

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Model files are about 45 MB; refuse anything absurd before reading it into memory. */
    const val MAX_MODEL_BYTES = 120L * 1024 * 1024

    /** Resize so the short side is 256, center crop 224, scale to 0..1, ImageNet mean/std. Pure math on ARGB pixels. */
    fun normalize(argb: IntArray, channelsFirst: Boolean): FloatArray {
        require(argb.size == INPUT_SIZE * INPUT_SIZE)
        val out = FloatArray(argb.size * 3)
        val plane = argb.size
        for (i in argb.indices) {
            val p = argb[i]
            val channels = floatArrayOf(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f)
            for (c in 0..2) {
                val v = (channels[c] - IMAGENET_MEAN[c]) / IMAGENET_STD[c]
                if (channelsFirst) out[c * plane + i] = v else out[i * 3 + c] = v
            }
        }
        return out
    }

    private fun cropped(bitmap: Bitmap): IntArray {
        val scale = RESIZE_SHORT_SIDE.toFloat() / minOf(bitmap.width, bitmap.height)
        val w = maxOf(INPUT_SIZE, Math.round(bitmap.width * scale))
        val h = maxOf(INPUT_SIZE, Math.round(bitmap.height * scale))
        val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val crop = Bitmap.createBitmap(resized, (w - INPUT_SIZE) / 2, (h - INPUT_SIZE) / 2, INPUT_SIZE, INPUT_SIZE)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        crop.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        return pixels
    }

    /**
     * Blocking; call off the main thread. Returns the sidewalk_parking score, or null when the feature is off or anything fails.
     * The file is read once, the pin is checked on exactly those bytes, and the interpreter is built from the same
     * bytes, so a file swapped after the check can never be the one that runs.
     */
    fun score(context: Context, bitmap: Bitmap): Float? {
        return try {
            if (PINNED_SHA256.isBlank()) return null
            val file = modelFile(context)?.takeIf { it.isFile && it.length() in 1..MAX_MODEL_BYTES } ?: return null
            val bytes = file.readBytes()
            if (!pinMatches(sha256(bytes))) return null
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            model.put(bytes); model.rewind()
            Interpreter(model).use { interpreter ->
                val shape = interpreter.getInputTensor(0).shape()
                val channelsFirst = shape.size == 4 && shape[1] == 3
                val input = normalize(cropped(bitmap), channelsFirst)
                val buffer = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
                input.forEach { buffer.putFloat(it) }
                buffer.rewind()
                val outShape = interpreter.getOutputTensor(0).shape()
                val output = Array(outShape.firstOrNull() ?: 1) { FloatArray(outShape.lastOrNull() ?: 1) }
                interpreter.run(buffer, output)
                probability(output[0][0])
            }
        } catch (_: Throwable) { null }
    }
}
