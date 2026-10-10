package il.sidewalks.reporter.recognition

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Invoke on a worker thread. Bundled OCR processes images locally; SDK sends Google metrics. */
object MlKitPlateOcr {
    fun recognize(bitmap: Bitmap): List<String> {
        require(bitmap.width in 1..2560 && bitmap.height in 1..2560)
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val result = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)), 30, TimeUnit.SECONDS)
            return PlateCandidates.fromText(result.text)
        } finally { client.close() }
    }
}
