package io.github.reihlinstinct.reporter

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PrivateOcrDiagnosticsTest {
    private fun sourceFile(path: String): File = listOf(File(path), File("app", path)).first { it.exists() }
    private fun source(path: String): String = sourceFile(path).readText()
    private val forbidden = listOf("Log.", "android.util.Log", "Timber.", "print(", "println(", "System.out", "System.err", "printStackTrace")
    // Every function that returns raw OCR text, including the experimental window reader (#442).
    private val rawText = Regex("recognize(?:Text|TileTexts)\\s*\\(")
    private fun count(text: String, token: String) = text.windowed(token.length).count { it == token }
    // #448: bounds and raw text sources are counted per engine file, so one unbounded site cannot hide behind another bounded one.
    private fun engineFindings(text: String, rawCallers: Int, bounds: Int = 1, sources: Int = bounds, sourceToken: String = "result.text"): List<String> = buildList {
        for (token in forbidden) if (text.contains(token)) add(token)
        if (!text.contains("fun recognize(bitmap: Bitmap): List<String> = PlateCandidates.fromText(recognizeText(bitmap))")) add("delegation")
        if (!text.contains("internal fun recognizeText(bitmap: Bitmap): String")) add("internal declaration")
        if (rawText.findAll(text).count() != rawCallers) add("raw callers")
        if (count(text, ".take(64_000)") != bounds) add("bound")
        if (count(text, sourceToken) != sources) add("raw text source")
    }
    private fun privateTestFindings(text: String): List<String> = buildList {
        for (token in forbidden) if (text.contains(token)) add(token)
        if (!text.contains("answer.put(\"raw_text\", raw)")) add("private raw field")
        if (!text.contains(".put(\"error\", failure.javaClass.simpleName)")) add("class-only failure")
        if (text.contains("failure.toString()") || text.contains("failure.message")) add("failure detail")
    }
    @Test fun productionDelegatesToSameCandidateFilterAndPrivateTestDoesNotLogRawText() {
        val engines = setOf("OfflinePlateOcr.kt", "MlKitPlateOcr.kt")
        val main = sourceFile("src/main/java")
        for (file in main.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            val text = file.readText()
            if (file.name in engines) assertEquals(file.name, emptyList<String>(),
                if (file.name == "MlKitPlateOcr.kt") engineFindings(text, 3, bounds = 2)
                else engineFindings(text, 2, bounds = 1, sourceToken = "tess.utF8Text"))
            else assertFalse(file.name, rawText.containsMatchIn(text))
        }
        assertEquals(emptyList<String>(), privateTestFindings(source("src/androidTest/java/io/github/reihlinstinct/reporter/PrivateRealPhotoOcrTest.kt")))
    }
    @Test fun rawLoggingPrintingUnboundedAndExceptionMutantsAreRejected() {
        val engine = source("src/main/java/io/github/reihlinstinct/reporter/recognition/MlKitPlateOcr.kt")
        assertTrue(engineFindings(engine.replace("return result.text.take(64_000)", "val raw = result.text.take(64_000); android.util.Log.d(\"OCR\", raw); return raw"), 3, bounds = 2).isNotEmpty())
        assertTrue(engineFindings(engine.replace(".take(64_000)", ""), 3, bounds = 2).isNotEmpty())
        // The window reader is covered too: a logging mutant inside it, and a second raw-text function, are both rejected.
        assertTrue(engineFindings(engine.replace("texts.add(result.text.take(64_000))", "texts.add(result.text.take(64_000)); android.util.Log.d(\"OCR\", texts.last())"), 3, bounds = 2).isNotEmpty())
        assertTrue(engineFindings(engine + "\ninternal fun recognizeTileTexts2(b: Bitmap): List<String> = recognizeTileTexts(b, 1)", 3, bounds = 2).isNotEmpty())
        // #448 mutants: the bound removed on the window reader only, and a new differently named function that returns result.text.
        val windowOnly = engine.replace("texts.add(result.text.take(64_000))", "texts.add(result.text)")
        assertNotEquals(engine, windowOnly)
        assertTrue(engineFindings(windowOnly, 3, bounds = 2).contains("bound"))
        val sneaky = engine + "\ninternal fun peek(result: Any): String = result.text"
        assertTrue(engineFindings(sneaky, 3, bounds = 2).contains("raw text source"))
        assertEquals(emptyList<String>(), engineFindings(engine, 3, bounds = 2))
        val offline = source("src/main/java/io/github/reihlinstinct/reporter/recognition/OfflinePlateOcr.kt")
        assertTrue(engineFindings(offline.replace(".take(64_000)", ""), 2, bounds = 1, sourceToken = "tess.utF8Text").contains("bound"))
        assertTrue(rawText.containsMatchIn("MlKitPlateOcr.recognizeTileTexts(bitmap, 1).also { println(it) }"))
        val productionMutant = "PlateCandidates.fromText(MlKitPlateOcr.recognizeText(bitmap).also { android.util.Log.d(\"OCR\", it) })"
        assertTrue(rawText.containsMatchIn(productionMutant))
        val test = source("src/androidTest/java/io/github/reihlinstinct/reporter/PrivateRealPhotoOcrTest.kt")
        for (print in listOf("print(raw)", "println(raw)", "System.out.print(raw)", "System.err.println(raw)", "failure.printStackTrace()")) {
            assertTrue(print, privateTestFindings(test + print).isNotEmpty())
        }
        assertTrue(privateTestFindings(test.replace("failure.javaClass.simpleName", "failure.toString()")).isNotEmpty())
    }
}
