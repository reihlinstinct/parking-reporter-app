package il.sidewalks.reporter.ui

import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object HebrewPresentation {
    fun evidenceTime(value: String?): String = try {
        val date = OffsetDateTime.parse(value)
        date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT)) +
            " (UTC" + (if (date.offset.totalSeconds == 0) "+00:00" else date.offset.id) + ")"
    } catch (_: Exception) {
        try { LocalDateTime.parse(value).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT)) + " (אזור זמן לא ידוע)" }
        catch (_: Exception) { value?.takeIf { it.isNotBlank() } ?: "חסר" }
    }
    fun category(code: String): String = when (code) {
        "sidewalk_parking" -> "חניה על המדרכה"
        else -> "קטגוריה לא מוכרת"
    }
}
