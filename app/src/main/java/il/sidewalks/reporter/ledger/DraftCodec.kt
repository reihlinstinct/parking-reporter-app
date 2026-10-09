package il.sidewalks.reporter.ledger

import il.sidewalks.reporter.core.Evidence
import il.sidewalks.reporter.core.ReviewDraft
import org.json.JSONArray
import org.json.JSONObject

/** No reporter profile or credentials belong in the ledger. */
object DraftCodec {
    fun encode(d: ReviewDraft): String = JSONObject().apply {
        put("id", d.evidence.stableId); put("hash", d.evidence.originalSha256)
        put("originalTime", d.evidence.capturedAtIso); put("originalLat", d.evidence.latitude); put("originalLon", d.evidence.longitude)
        put("time", d.confirmedTime); put("lat", d.confirmedLatitude); put("lon", d.confirmedLongitude)
        put("plate", d.plate); put("address", d.address); put("subject", d.subject); put("text", d.exactHebrewText)
        put("unresolved", JSONArray(d.unresolved.sorted()))
    }.toString()
    fun decode(value: String): ReviewDraft {
        val j = JSONObject(value)
        fun text(k: String): String? = if (j.has(k) && !j.isNull(k)) j.getString(k) else null
        fun number(k: String): Double? = if (j.has(k) && !j.isNull(k)) j.getDouble(k) else null
        val questions = j.getJSONArray("unresolved")
        return ReviewDraft(Evidence(j.getString("id"), j.getString("hash"), text("originalTime"), number("originalLat"), number("originalLon")),
            j.getString("plate"), j.getString("address"), j.getString("subject"), j.getString("text"),
            (0 until questions.length()).map { questions.getString(it) }.toSet(),
            confirmedTime = text("time"), confirmedLatitude = number("lat"), confirmedLongitude = number("lon"))
    }
}
