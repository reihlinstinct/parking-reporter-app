package io.github.reihlinstinct.reporter.recognition

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Issue #210: the plate recognition engine. Google ML Kit measured clearly better than Tesseract on real photos. */
enum class OcrEngine(val code: String) {
    MLKIT("mlkit"), TESSERACT("tesseract");

    companion object {
        val DEFAULT = MLKIT
        /** Anything missing or unknown means the default. */
        fun parse(code: String?): OcrEngine = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

interface OcrEngineStore {
    fun get(): OcrEngine
    fun set(engine: OcrEngine)
}

/** Only the engine code is stored, in the plain "ui-prefs" file next to the language (no personal data). */
class UiPrefsOcrEngineStore(private val prefs: SharedPreferences) : OcrEngineStore {
    override fun get(): OcrEngine = try { OcrEngine.parse(prefs.getString(KEY, null)) } catch (_: ClassCastException) { OcrEngine.DEFAULT }
    override fun set(engine: OcrEngine) { prefs.edit { putString(KEY, engine.code) } }

    companion object {
        const val KEY = "ocr_engine"
        fun of(context: Context): UiPrefsOcrEngineStore =
            UiPrefsOcrEngineStore(context.getSharedPreferences("ui-prefs", Context.MODE_PRIVATE))
    }
}
