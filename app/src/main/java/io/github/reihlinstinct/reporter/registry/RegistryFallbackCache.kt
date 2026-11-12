package io.github.reihlinstinct.reporter.registry

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Properties

/** Private no-backup storage: hashed plate keys, status/date only, bounded to 128 entries.
 * No reporter/owner identity, model, location, photo or outgoing report text is stored.
 * Hits never extend insertedAt. Every UI lookup still makes a live request.
 */
class RegistryFallbackCache(private val directory: File, private val now: () -> Instant = { Instant.now() }) {
    data class Cached(val tag: DisabilityLookupResult, val vehicle: VehicleLookupResult,
        val checkedAt: Instant, val insertedAt: Instant) {
        val expiresAt: Instant get() = listOfNotNull(insertedAt.plusSeconds(7 * 86400L),
            (vehicle as? VehicleLookupResult.Found)?.record?.licenceValidUntil?.plusDays(1)
                ?.atStartOfDay(ZoneId.of("Asia/Jerusalem"))?.toInstant()).minOrNull()!!
    }
    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun file(plate: PlateNumber): File {
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(plate.digits.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$key.properties")
    }
    @Synchronized fun get(plate: PlateNumber): Cached? = try {
        val f = file(plate)
        if (!f.isFile) null else {
            val p = Properties().apply { f.inputStream().use { load(it) } }
            val inserted = Instant.parse(p.getProperty("inserted"))
            val checked = Instant.parse(p.getProperty("checked"))
            val until = p.getProperty("until")?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
            val deadline = listOfNotNull(inserted.plusSeconds(7 * 86400L), until?.plusDays(1)?.atStartOfDay(zone)?.toInstant()).minOrNull()!!
            if (now().isBefore(inserted) || !now().isBefore(deadline)) { f.delete(); null }
            else Cached(if (p.getProperty("tag") == "NONE") DisabilityLookupResult.NotFound else
                DisabilityLookupResult.Found(DisabilityTagType.valueOf(p.getProperty("tag"))),
                if (p.getProperty("vehicle") == "NONE") VehicleLookupResult.NotFound else
                    VehicleLookupResult.Found(VehicleRecord(plate, until, null, null, null, null, null)), checked, inserted)
        }
    } catch (_: Exception) { file(plate).delete(); null }

    @Synchronized fun put(plate: PlateNumber, tag: DisabilityLookupResult?, vehicle: VehicleLookupResult?, checkedAt: Instant) {
        // Never persist errors, incomplete lookups or ambiguous failures as negative answers.
        if (tag !is DisabilityLookupResult.Found && tag !is DisabilityLookupResult.NotFound) return
        if (vehicle !is VehicleLookupResult.Found && vehicle !is VehicleLookupResult.NotFound) return
        val until = (vehicle as? VehicleLookupResult.Found)?.record?.licenceValidUntil
        val inserted = get(plate)?.insertedAt ?: now()
        val deadline = until?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
        if (deadline != null && !now().isBefore(deadline)) { file(plate).delete(); return }
        directory.mkdirs()
        val p = Properties().apply {
            setProperty("inserted", inserted.toString()); setProperty("checked", checkedAt.toString())
            setProperty("tag", (tag as? DisabilityLookupResult.Found)?.type?.name ?: "NONE")
            setProperty("vehicle", if (vehicle is VehicleLookupResult.Found) "FOUND" else "NONE")
            setProperty("until", until?.toString() ?: "")
        }
        val target = file(plate); val tmp = File(directory, target.name + ".tmp")
        try {
            tmp.outputStream().use { p.store(it, null) }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
        directory.listFiles().orEmpty().filter { it.extension == "properties" }.sortedBy { it.lastModified() }
            .dropLast(128).forEach { it.delete() }
    }
}
