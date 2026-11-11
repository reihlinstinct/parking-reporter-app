package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.evidence.OutgoingEvidence
import io.github.reihlinstinct.reporter.intake.EncryptedPackage
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Asymmetric four-corner fixture catches mirrors as well as swapped dimensions. */
class OutgoingOrientationTest {
    @Test fun allEightExifOrientationsHaveCorrectUprightPixelsAndNoMetadata() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        // Expected output corners TL, TR, BL, BR from source corners R,G,B,Y.
        // Explicit EXIF mapping, independent of the production Matrix operations.
        val corners = listOf(
            listOf(0, 1, 2, 3), // 1 normal
            listOf(1, 0, 3, 2), // 2 horizontal mirror
            listOf(3, 2, 1, 0), // 3 rotate180
            listOf(2, 3, 0, 1), // 4 vertical mirror
            listOf(0, 2, 1, 3), // 5 transpose
            listOf(2, 0, 3, 1), // 6 rotate90 clockwise
            listOf(3, 1, 2, 0), // 7 transverse
            listOf(1, 3, 0, 2), // 8 rotate270 clockwise
        )
        val sheet = Bitmap.createBitmap(8 * 100, 180, Bitmap.Config.ARGB_8888)
        val sheetCanvas = Canvas(sheet)
        sheetCanvas.drawColor(Color.WHITE)
        try {
            for (orientation in 1..8) {
                val file = File.createTempFile("orientation-$orientation-", ".jpg", context.cacheDir)
                try {
                    val source = Bitmap.createBitmap(160, 80, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(source)
                        for (i in colors.indices) canvas.drawRect(
                            (i % 2 * 80).toFloat(), (i / 2 * 40).toFloat(),
                            (i % 2 * 80 + 80).toFloat(), (i / 2 * 40 + 40).toFloat(),
                            Paint().apply { color = colors[i] })
                        file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                    } finally { source.recycle() }
                    ExifInterface(file).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:01 12:00:00")
                        setAttribute(ExifInterface.TAG_MAKE, "SYNTHETIC-MAKE")
                        setAttribute(ExifInterface.TAG_USER_COMMENT, "synthetic-private-comment")
                        setLatLong(31.0, 35.0)
                        saveAttributes()
                    }
                    val original = file.readBytes()
                    val output = OutgoingEvidence.prepare(file)
                    assertArrayEquals("original $orientation unchanged", original, file.readBytes())
                    assertEquals(EncryptedPackage.sha256(original), output.originalHash)
                    assertEquals(EncryptedPackage.sha256(output.jpeg), output.hash)
                    assertTrue(output.jpeg.size <= 1_100_000)
                    assertEquals(if (orientation <= 4) 160 else 80, output.width)
                    assertEquals(if (orientation <= 4) 80 else 160, output.height)
                    val decoded = requireNotNull(BitmapFactory.decodeByteArray(output.jpeg, 0, output.jpeg.size))
                    try {
                        for (i in 0..3) {
                            val actual = decoded.getPixel(
                                (if (i % 2 == 0) 1 else 3) * decoded.width / 4,
                                (if (i / 2 == 0) 1 else 3) * decoded.height / 4)
                            val expected = colors[corners[orientation - 1][i]]
                            for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                                assertTrue("orientation=$orientation corner=$i", kotlin.math.abs(channel(actual) - channel(expected)) < 40)
                            }
                        }
                        // Actual decoded output, labeled left-to-right 1..8 for pixel inspection.
                        sheetCanvas.drawText(orientation.toString(), ((orientation - 1) * 100 + 4).toFloat(), 15f,
                            Paint().apply { color = Color.BLACK; textSize = 14f })
                        sheetCanvas.drawBitmap(decoded, null,
                            android.graphics.Rect((orientation - 1) * 100, 20, (orientation - 1) * 100 + 80,
                                20 + if (orientation <= 4) 40 else 160), null)
                    } finally { decoded.recycle() }
                    val exif = ExifInterface(ByteArrayInputStream(output.jpeg))
                    assertNull(exif.latLong)
                    for (tag in listOf(ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_USER_COMMENT))
                        assertNull("orientation=$orientation tag=$tag", exif.getAttribute(tag))
                    assertFalse(String(output.jpeg, Charsets.ISO_8859_1).contains("Exif\u0000\u0000"))
                } finally { file.delete() }
            }
            File(context.filesDir, "outgoing-all-orientations.png").outputStream().use {
                assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { sheet.recycle() }
    }
}
