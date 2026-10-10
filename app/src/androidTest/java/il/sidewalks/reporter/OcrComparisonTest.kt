package il.sidewalks.reporter

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.recognition.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class OcrComparisonTest {
    @Test fun compareBothEnginesOnSameSyntheticInputs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = JSONArray()
        val cases = listOf("12345678", "12-345-67", "123-45-678", "7654321", "", "123456", "AB12345", "123456789")
        for ((index, value) in cases.withIndex()) {
            val bitmap = Bitmap.createBitmap(1200, 300, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap); canvas.drawColor(if (index % 2 == 0) Color.WHITE else Color.YELLOW)
                canvas.save(); if (index == 3) canvas.rotate(5f, 600f, 150f)
                canvas.drawText(value, 45f, 190f, Paint().apply {
                    color = Color.BLACK; textSize = if (index == 2) 100f else 130f; isAntiAlias = true
                    typeface = Typeface.create("monospace", Typeface.BOLD)
                }); canvas.restore()
                val expected = when (index) { 0,2 -> "12345678"; 1 -> "1234567"; 3 -> "7654321"; else -> null }
                val started = System.nanoTime(); val tess = OfflinePlateOcr(context).recognize(bitmap); val middle = System.nanoTime()
                val google = MlKitPlateOcr.recognize(bitmap); val ended = System.nanoTime()
                rows.put(JSONObject().put("case", index).put("expected", expected ?: JSONObject.NULL)
                    .put("tesseract", JSONArray(tess)).put("mlkit", JSONArray(google))
                    .put("tesseract_ms", (middle-started)/1_000_000).put("mlkit_ms", (ended-middle)/1_000_000))
                assertTrue(tess.all { it.matches(Regex("[0-9]{7,8}")) }); assertTrue(google.all { it.matches(Regex("[0-9]{7,8}")) })
                if (index == 4) { assertTrue(tess.isEmpty()); assertTrue(google.isEmpty()) }
            } finally { bitmap.recycle() }
        }
        File(context.filesDir, "ocr-comparison.json").writeText(rows.toString(2))
    }
}
