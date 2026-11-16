package io.github.reihlinstinct.reporter

import android.os.Bundle
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asImageBitmap
import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.ui.ReviewScreen
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import java.util.concurrent.atomic.AtomicInteger

/** Non-exported debug-only host: exercises the real screen's rotation write/read hooks with synthetic OCR. */
class ReviewOcrTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImageBitmap()
        setContent { AppTheme { ReviewScreen(initial, image, {}, {}, plateRecognizer = {
            recognitions.incrementAndGet()
            listOf("7654321")
        }) } }
    }

    companion object {
        val recognitions = AtomicInteger()
        val initial = ReviewDraft(Evidence("ocr-recreate-synthetic", "e".repeat(64),
            "2026-01-01T12:00:00+02:00", 31.77, 35.21), "1234567", "רחוב בדיקה 1",
            "sidewalk_parking", "בדיקה סינתטית", emptySet())
    }
}
