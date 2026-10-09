package il.sidewalks.reporter.core

import java.security.MessageDigest

/** Pure review model. Never authorizes a municipal or social transport. */
data class Evidence(
    val stableId: String,
    val originalSha256: String,
    val capturedAtIso: String?,
    val latitude: Double?,
    val longitude: Double?,
) {
    init {
        require(stableId.isNotBlank())
        require(originalSha256.matches(Regex("[a-f0-9]{64}")))
        require(latitude == null || latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude == null || longitude.isFinite() && longitude in -180.0..180.0)
    }
}
data class ReviewDraft(
    val evidence: Evidence,
    val plate: String,
    val address: String,
    val subject: String,
    val exactHebrewText: String,
    val unresolved: Set<String>,
    val approvedDigest: String? = null,
    val confirmedTime: String? = evidence.capturedAtIso,
    val confirmedLatitude: Double? = evidence.latitude,
    val confirmedLongitude: Double? = evidence.longitude,
) {
    fun isReady(): Boolean = unresolved.isEmpty() &&
        plate.matches(Regex("[0-9]{7,8}")) && address.isNotBlank() && subject.isNotBlank() &&
        exactHebrewText.isNotBlank() && validTime(confirmedTime) &&
        confirmedLatitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
        confirmedLongitude?.let { it.isFinite() && it in -180.0..180.0 } == true
    fun digest(): String {
        // Local review digest only. M4 replaces this with proven helper parity.
        val fields = listOf(evidence.stableId, evidence.originalSha256, evidence.capturedAtIso.orEmpty(),
            evidence.latitude.toString(), evidence.longitude.toString(), plate, address, subject,
            exactHebrewText, confirmedTime.orEmpty(), confirmedLatitude.toString(), confirmedLongitude.toString()) + unresolved.sorted()
        val canonical = fields.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private fun validTime(value: String?): Boolean = try {
        java.time.OffsetDateTime.parse(value); true
    } catch (_: Exception) { false }
    fun confirmEvidence(time: String, latitude: Double?, longitude: Double?): ReviewDraft =
        copy(confirmedTime = time, confirmedLatitude = latitude, confirmedLongitude = longitude,
            unresolved = emptySet(), approvedDigest = null)
    fun approve(): ReviewDraft { check(isReady()); return copy(approvedDigest = digest()) }
    fun isApproved(): Boolean = isReady() && approvedDigest == digest()
    fun edit(plate: String = this.plate, address: String = this.address,
             subject: String = this.subject, text: String = this.exactHebrewText): ReviewDraft =
        copy(plate = plate, address = address, subject = subject, exactHebrewText = text, approvedDigest = null)
}
