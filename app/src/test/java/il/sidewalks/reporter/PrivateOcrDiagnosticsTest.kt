package il.sidewalks.reporter

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PrivateOcrDiagnosticsTest {
    private fun source(path: String): String = listOf(File(path), File("app", path)).first { it.isFile }.readText()
    @Test fun productionDelegatesToSameCandidateFilterAndPrivateTestDoesNotLogRawText() {
        for (name in listOf("OfflinePlateOcr", "MlKitPlateOcr")) {
            val text = source("src/main/java/il/sidewalks/reporter/recognition/$name.kt")
            assertTrue(text.contains("fun recognize(bitmap: Bitmap): List<String> = PlateCandidates.fromText(recognizeText(bitmap))"))
            assertTrue(text.contains("internal fun recognizeText(bitmap: Bitmap): String"))
        }
        val test = source("src/androidTest/java/il/sidewalks/reporter/PrivateRealPhotoOcrTest.kt")
        assertTrue(test.contains("answer.put(\"raw_text\", raw)"))
        assertFalse(test.contains("println("))
        assertFalse(test.contains("Log."))
    }
}
