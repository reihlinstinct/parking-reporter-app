package il.sidewalks.reporter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.recognition.OfflinePlateOcr
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineOcrInstrumentedTest {
    @Test fun bundledNativeModelReadsSyntheticPlateWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1200, 260, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.drawText("12345678", 65f, 190f, Paint().apply {
                color = Color.BLACK; textSize = 160f; isAntiAlias = true
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
            })
            assertTrue(OfflinePlateOcr(context).recognize(bitmap).contains("12345678"))
        } finally { bitmap.recycle() }
    }
    @Test fun blankImageHasNoAutomaticPlate() {
        val bitmap = Bitmap.createBitmap(600, 200, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            assertTrue(OfflinePlateOcr(InstrumentationRegistry.getInstrumentation().targetContext).recognize(bitmap).isEmpty())
        } finally { bitmap.recycle() }
    }
}
