package io.github.reihlinstinct.reporter.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Public catalog only, never credentials or query text. Atomic last-good replacement. */
class CityTopicCache(private val file: File, private val now: () -> Instant = Instant::now) {
    data class Snapshot(val topics: List<MunicipalSubject>, val checkedAt: Instant, val fingerprint: String, val checkDateKnown: Boolean = checkedAt != Instant.EPOCH) {
        /** Unknown timestamps stay refresh-due without claiming a measurable age. */
        fun knownCheckDate(): java.time.LocalDate? = checkedAt.takeIf { checkDateKnown }
            ?.atZone(java.time.ZoneId.of("Asia/Jerusalem"))?.toLocalDate()
        fun stale(now: Instant): Boolean = checkDateKnown && checkedAt.plusSeconds(90L * 86400).isBefore(now)
        fun refreshDue(now: Instant): Boolean = !checkDateKnown || checkedAt.plusSeconds(7L * 86400).isBefore(now)
    }
    @Synchronized fun read(): Snapshot? = try {
        val root = JSONObject(file.readText())
        val array = root.getJSONArray("subjects")
        val topics = (0 until array.length()).map { i ->
            val row = array.getJSONObject(i); val path = row.getJSONArray("path")
            MunicipalSubject(row.getString("code"), row.getString("name"), (0 until path.length()).map { path.getString(it) }, MunicipalSubjects.plateFlag(row))
        }
        validate(topics)
        val fingerprint = MunicipalSubjects.canonicalFingerprint(topics)
        require(root.getString("fingerprint") == fingerprint)
        val checkedAt = Instant.parse(root.getString("checked_at"))
        // A clock correction must never suppress the next successful-send refresh.
        Snapshot(topics, checkedAt, fingerprint, checkDateKnown = checkedAt != Instant.EPOCH && !checkedAt.isAfter(now()))
    } catch (_: Exception) { null }

    @Synchronized fun replace(topics: List<MunicipalSubject>): Snapshot {
        validate(topics)
        val previous = read()
        // A dramatically truncated city reply must not remove most existing topics.
        require(previous == null || topics.map { it.code }.distinct().size * 2 >= previous.topics.map { it.code }.distinct().size)
        val checkedAt = now(); val fingerprint = MunicipalSubjects.canonicalFingerprint(topics)
        val rows = if (previous?.fingerprint == fingerprint) previous.topics else topics
        val array = JSONArray()
        rows.forEach { array.put(JSONObject().put("code", it.code).put("name", it.name).put("path", JSONArray(it.path)).apply { it.plateRequired?.let { flag -> put("plateRequired", flag) } }) }
        val root = JSONObject().put("version", 1).put("checked_at", checkedAt.toString()).put("fingerprint", fingerprint).put("subjects", array)
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        val temporary = File.createTempFile(file.name, ".tmp", file.parentFile)
        java.io.FileOutputStream(temporary).use { it.write(root.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        check(temporary.renameTo(file))
        return Snapshot(rows.toList(), checkedAt, fingerprint)
    }
    companion object {
        private fun validate(topics: List<MunicipalSubject>) {
            require(topics.groupBy { it.code }.values.all { rows -> rows.map { it.plateRequired }.distinct().size == 1 })
            require(topics.isNotEmpty() && topics.size <= 10000)
            require(topics.all { it.code.isNotEmpty() && it.code.all { c -> c in '0'..'9' } && it.name.isNotBlank() && it.name.length <= 2000 && it.path.size in 1..16 && it.path.all { p -> p.isNotBlank() && p.length <= 2000 } })
        }
        fun flatten(tree: JSONArray): List<MunicipalSubject> {
            val rows = mutableListOf<MunicipalSubject>()
            fun visit(nodes: JSONArray, path: List<String>) {
                require(path.size < 16 && nodes.length() <= 10000)
                for (i in 0 until nodes.length()) {
                    val node = nodes.getJSONObject(i); val name = node.getString("categoryName")
                    require(name.isNotBlank())
                    if (node.has("subjectCode") && !node.isNull("subjectCode")) {
                        // City contract requires a string, never silently coerce numbers.
                        rows += MunicipalSubject(node.get("subjectCode") as String, name, path + name, MunicipalSubjects.plateFlag(node))
                        require(rows.size <= 10000)
                    }
                    if (node.has("children") && !node.isNull("children")) visit(node.getJSONArray("children"), path + name)
                }
            }
            visit(tree, emptyList()); validate(rows); return rows
        }
    }
}
