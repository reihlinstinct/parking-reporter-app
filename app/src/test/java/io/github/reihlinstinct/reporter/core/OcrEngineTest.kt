package io.github.reihlinstinct.reporter.core

import io.github.reihlinstinct.reporter.recognition.OcrEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #210: Google ML Kit is the default; only a stored "tesseract" selects the other engine. */
class OcrEngineTest {
    @Test fun defaultIsMlKit() {
        assertEquals(OcrEngine.MLKIT, OcrEngine.DEFAULT)
        assertEquals(OcrEngine.MLKIT, OcrEngine.parse(null))
    }
    @Test fun storedCodesRoundTrip() {
        for (engine in OcrEngine.values()) assertEquals(engine, OcrEngine.parse(engine.code))
        assertEquals(OcrEngine.TESSERACT, OcrEngine.parse("tesseract"))
    }
    @Test fun unknownOrBlankMeansTheDefault() {
        assertEquals(OcrEngine.MLKIT, OcrEngine.parse(""))
        assertEquals(OcrEngine.MLKIT, OcrEngine.parse("TESSERACT "))
        assertEquals(OcrEngine.MLKIT, OcrEngine.parse("other"))
    }
}
