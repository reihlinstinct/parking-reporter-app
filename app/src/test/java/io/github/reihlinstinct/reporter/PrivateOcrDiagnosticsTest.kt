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
    private fun engineFindings(text: String, rawCallers: Int): List<String> = buildList {
        for (token in forbidden) if (text.contains(token)) add(token)
        if (!text.contains("fun recognize(bitmap: Bitmap): List<String> = PlateCandidates.fromText(recognizeText(bitmap))")) add("delegation")
        if (!text.contains("internal fun recognizeText(bitmap: Bitmap): String")) add("internal declaration")
        if (rawText.findAll(text).count() != rawCallers) add("raw callers")
        if (!text.contains(".take(64_000)")) add("bound")
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
            if (file.name in engines) assertEquals(file.name, emptyList<String>(), engineFindings(text, if (file.name == "MlKitPlateOcr.kt") 3 else 2))
            else assertFalse(file.name, rawText.containsMatchIn(text))
        }
        assertEquals(emptyList<String>(), privateTestFindings(source("src/androidTest/java/io/github/reihlinstinct/reporter/PrivateRealPhotoOcrTest.kt")))
    }
    @Test fun rawLoggingPrintingUnboundedAndExceptionMutantsAreRejected() {
        val engine = source("src/main/java/io/github/reihlinstinct/reporter/recognition/MlKitPlateOcr.kt")
        assertTrue(engineFindings(engine.replace("return result.text.take(64_000)", "val raw = result.text.take(64_000); android.util.Log.d(\"OCR\", raw); return raw"), 3).isNotEmpty())
        assertTrue(engineFindings(engine.replace(".take(64_000)", ""), 3).isNotEmpty())
        // The window reader is covered too: a logging mutant inside it, and a second raw-text function, are both rejected.
        assertTrue(engineFindings(engine.replace("texts.add(result.text.take(64_000))", "texts.add(result.text.take(64_000)); android.util.Log.d(\"OCR\", texts.last())"), 3).isNotEmpty())
        assertTrue(engineFindings(engine + "\ninternal fun recognizeTileTexts2(b: Bitmap): List<String> = recognizeTileTexts(b, 1)", 3).isNotEmpty())
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
