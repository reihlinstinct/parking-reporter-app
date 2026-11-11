package il.sidewalks.reporter.core

import java.text.Normalizer

/** Topic identity is the municipal code. Legacy enum values only migrate old drafts. */
object TopicIdentity {
    fun code(subject: String): String? = ReportCategory.from(subject)?.municipalSubjectCode
        ?: subject.removePrefix("municipal:").takeIf {
            subject.startsWith("municipal:") && it.isNotEmpty() && it.all { c -> c in '0'..'9' }
        }
    fun needsPlate(subject: String): Boolean = code(subject) == "1145"
    fun isKnownShape(subject: String): Boolean = code(subject) != null
}

fun interface TopicRanker {
    fun search(query: String, topics: List<MunicipalSubject>, recentCodes: List<String>): List<MunicipalSubject>
}

/** Deterministic offline retrieval, never a claim that an AI classified the report. */
class LocalTopicRanker(private val aliases: Map<String, List<String>> = emptyMap()) : TopicRanker {
    override fun search(query: String, topics: List<MunicipalSubject>, recentCodes: List<String>): List<MunicipalSubject> {
        val q = normalize(query)
        return topics.distinctBy { it.code }.mapNotNull { topic ->
            val name = normalize(topic.name)
            val path = normalize(topic.path.joinToString(" "))
            val terms = aliases[topic.code].orEmpty().map(::normalize)
            val score = when {
                q.isBlank() -> if (topic.code in recentCodes) 100 - recentCodes.indexOf(topic.code) else 0
                q == topic.code || q == name -> 1000
                q in terms -> 950
                name.startsWith(q) -> 900
                name.contains(q) -> 800
                terms.any { it.contains(q) || (q.length >= 4 && q.contains(it)) } -> 750
                path.contains(q) -> 700
                q.length >= 4 && (listOf(name) + terms).any { distance(q, it) <= 1 } -> 500
                else -> return@mapNotNull null
            }
            topic to score
        }.sortedWith(compareByDescending<Pair<MunicipalSubject, Int>> { it.second }.thenBy { it.first.name })
            .map { it.first }
    }
    companion object {
        fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase(java.util.Locale.ROOT)
            .map { when (it) { 'ך' -> 'כ'; 'ם' -> 'מ'; 'ן' -> 'נ'; 'ף' -> 'פ'; 'ץ' -> 'צ'; else -> it } }.joinToString("")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
        // Optimal-string-alignment distance: one adjacent transposition is one edit.
        internal fun distance(a: String, b: String): Int {
            if (kotlin.math.abs(a.length - b.length) > 1) return 2
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                d[i][j] = minOf(d[i-1][j] + 1, d[i][j-1] + 1, d[i-1][j-1] + if (a[i-1] == b[j-1]) 0 else 1)
                if (i > 1 && j > 1 && a[i-1] == b[j-2] && a[i-2] == b[j-1]) d[i][j] = minOf(d[i][j], d[i-2][j-2] + 1)
            }
            return d[a.length][b.length]
        }
    }
}
