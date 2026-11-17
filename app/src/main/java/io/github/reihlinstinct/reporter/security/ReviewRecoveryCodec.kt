package io.github.reihlinstinct.reporter.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Inactive fixed-order v1 schema. No reflection, keys, paths, auth, approval or submission state.
 * Encode only into the encrypted recovery envelope, never a plaintext file or Bundle.
 * JVM streams/Strings keep copies that cannot be reliably erased; caller still clears returned byte arrays.
 * Caller must separately bind/re-read the initial ledger and evidence; decoding grants no authority. */
object ReviewRecoveryCodec {
    const val TEXT_BYTES = 32 * 1024
    const val ADDRESS_BYTES = 4096
    const val RAW_BYTES = 512
    const val PLATE_BYTES = 64
    const val TEMPLATE_COUNT = 32
    private const val VERSION = 1
    private val warnings = setOf("missing_gps", "missing_time", "timezone_needs_confirmation",
        "address_needs_confirmation", "suggested_address_needs_confirmation",
        "time_needs_confirmation", "location_needs_confirmation")

    // Own transport model: evidence comes from the matching saved ledger, never this payload.
    class Snapshot(
        val plate: String, val plateClass: String, val plateRequired: Boolean?,
        val address: String, val subject: String, val text: String,
        unresolved: Set<String>, val confirmedTime: String?,
        val confirmedLatitude: Double?, val confirmedLongitude: Double?,
        val plateRaw: String, val plateEdits: Int, val latitudeRaw: String, val longitudeRaw: String,
        val manualStreet: String, val manualHouse: String, val manualCross: String,
        val savedManualStreet: String, val savedManualHouse: String,
        val ocrPrefilledPlate: String?, val gpsFilled: Boolean,
        val textTyped: Boolean, val editing: Boolean, val confirmExit: Boolean,
        val templateTag: String?, templateRendered: List<Sentence>, val templateFrozen: Boolean,
        val addressBaseline: String, pendingAddressWarnings: Set<String>,
    ) {
        private val warningValues = unresolved.toSet()
        private val renderedValues = templateRendered.toList()
        private val pendingValues = pendingAddressWarnings.toSet()
        val unresolved: Set<String> get() = warningValues.toSet()
        val templateRendered: List<Sentence> get() = renderedValues.toList()
        val pendingAddressWarnings: Set<String> get() = pendingValues.toSet()
        override fun toString() = "ReviewRecoverySnapshot(redacted)"
    }
    class Sentence(val id: String, val text: String) {
        override fun toString() = "ReviewRecoverySentence(redacted)"
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun validate(s: Snapshot) {
        require(s.plateClass in setOf("regular", "police", "cd", "cc")) { "Invalid recovery plate class" }
        require(s.plateRaw.matches(Regex("[0-9A-Za-z\\u05DE \\t\\u00A0\\u2007\\u202F\\u05BE\\u2010-\\u2015\\u2212-]*"))) { "Invalid recovery raw plate" }
        require(s.plateEdits >= 0) { "Invalid recovery provenance" }
        require(s.unresolved.all { it in warnings } && s.pendingAddressWarnings.all {
            it == "address_needs_confirmation" || it == "suggested_address_needs_confirmation"
        }) { "Invalid recovery warning" }
        require(s.confirmedLatitude == null || s.confirmedLatitude.isFinite() && s.confirmedLatitude in -90.0..90.0) { "Invalid recovery latitude" }
        require(s.confirmedLongitude == null || s.confirmedLongitude.isFinite() && s.confirmedLongitude in -180.0..180.0) { "Invalid recovery longitude" }
        // Incomplete raw strings are edits, not coordinates. Numeric nonfinite/out-of-range forms reject.
        for ((raw, limit) in listOf(s.latitudeRaw to 90.0, s.longitudeRaw to 180.0)) {
            val number = raw.toDoubleOrNull()
            require(number == null || number.isFinite() && number in -limit..limit) { "Invalid recovery raw coordinate" }
        }
        require(!s.gpsFilled || (s.confirmedLatitude != null && s.confirmedLongitude != null &&
            s.latitudeRaw.toDoubleOrNull() == s.confirmedLatitude && s.longitudeRaw.toDoubleOrNull() == s.confirmedLongitude)) { "Invalid recovery GPS provenance" }
        require(s.templateRendered.size <= TEMPLATE_COUNT &&
            s.templateRendered.map { it.id }.toSet().size == s.templateRendered.size &&
            s.templateRendered.all { it.id.matches(Regex("[A-Za-z0-9_.-]{1,128}")) } &&
            (s.templateTag == null || s.templateTag.matches(Regex("[A-Za-z0-9_.-]{1,128}")))) { "Invalid recovery template" }
    }

    fun encode(s: Snapshot): ByteArray {
        validate(s)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            fun text(value: String, max: Int) {
                // Bound UTF-16 length before encoding too, so arbitrarily large inputs cannot allocate bytes.
                require(value.length <= max) { "Recovery text too large" }
                val b = value.toByteArray(Charsets.UTF_8)
                try {
                    require(b.size <= max && utf8(b) == value) { "Invalid recovery text" }
                    d.writeInt(b.size); d.write(b)
                } finally { b.fill(0) }
            }
            fun nullable(value: String?, max: Int) { d.writeBoolean(value != null); if (value != null) text(value, max) }
            fun number(value: Double?) { d.writeBoolean(value != null); if (value != null) d.writeDouble(value) }
            fun codes(values: Set<String>) { d.writeInt(values.size); values.sorted().forEach { text(it, 64) } }
            d.writeInt(VERSION)
            text(s.plate, PLATE_BYTES); text(s.plateClass, 16)
            d.writeByte(when(s.plateRequired) { null -> 0; false -> 1; true -> 2 })
            text(s.address, ADDRESS_BYTES); text(s.subject, RAW_BYTES); text(s.text, TEXT_BYTES)
            codes(s.unresolved); nullable(s.confirmedTime, 128)
            number(s.confirmedLatitude); number(s.confirmedLongitude)
            text(s.plateRaw, PLATE_BYTES); d.writeInt(s.plateEdits)
            text(s.latitudeRaw, 64); text(s.longitudeRaw, 64)
            text(s.manualStreet, ADDRESS_BYTES); text(s.manualHouse, RAW_BYTES); text(s.manualCross, ADDRESS_BYTES)
            text(s.savedManualStreet, ADDRESS_BYTES); text(s.savedManualHouse, RAW_BYTES)
            nullable(s.ocrPrefilledPlate, PLATE_BYTES); d.writeBoolean(s.gpsFilled)
            d.writeBoolean(s.textTyped); d.writeBoolean(s.editing); d.writeBoolean(s.confirmExit)
            nullable(s.templateTag, 128); d.writeInt(s.templateRendered.size)
            s.templateRendered.forEach { text(it.id, 128); text(it.text, ADDRESS_BYTES) }
            d.writeBoolean(s.templateFrozen); text(s.addressBaseline, ADDRESS_BYTES); codes(s.pendingAddressWarnings)
        }
        require(out.size() <= ReviewRecoveryEnvelope.MAX_PAYLOAD_BYTES) { "Recovery snapshot too large" }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Snapshot {
        require(bytes.size in 4..ReviewRecoveryEnvelope.MAX_PAYLOAD_BYTES) { "Invalid recovery snapshot size" }
        val input = ByteArrayInputStream(bytes)
        val d = DataInputStream(input)
        fun bool(): Boolean = when(d.readUnsignedByte()) {
            0 -> false; 1 -> true; else -> throw IllegalArgumentException("Invalid recovery boolean")
        }
        fun text(max: Int): String {
            val n = d.readInt()
            require(n in 0..max && n <= input.available()) { "Invalid recovery field size" }
            val b = ByteArray(n); d.readFully(b)
            return try { utf8(b) } finally { b.fill(0) }
        }
        fun nullable(max: Int): String? = if (bool()) text(max) else null
        fun number(): Double? = if (bool()) d.readDouble() else null
        fun codes(): Set<String> {
            val n = d.readInt(); require(n in 0..warnings.size) { "Invalid recovery warning count" }
            val list = List(n) { text(64) }
            require(list.toSet().size == n) { "Duplicate recovery warning" }
            return list.toSet()
        }
        require(d.readInt() == VERSION) { "Invalid recovery snapshot version" }
        val plate = text(PLATE_BYTES); val plateClass = text(16)
        val plateRequired = when(d.readUnsignedByte()) { 0 -> null; 1 -> false; 2 -> true
            else -> throw IllegalArgumentException("Invalid recovery plate policy") }
        val address = text(ADDRESS_BYTES); val subject = text(RAW_BYTES); val body = text(TEXT_BYTES)
        val unresolved = codes(); val time = nullable(128); val lat = number(); val lon = number()
        val plateRaw = text(PLATE_BYTES); val edits = d.readInt(); val rawLat = text(64); val rawLon = text(64)
        val street = text(ADDRESS_BYTES); val house = text(RAW_BYTES); val cross = text(ADDRESS_BYTES)
        val savedStreet = text(ADDRESS_BYTES); val savedHouse = text(RAW_BYTES)
        val ocr = nullable(PLATE_BYTES); val gps = bool(); val typed = bool(); val editing = bool(); val exit = bool()
        val tag = nullable(128); val count = d.readInt()
        require(count in 0..TEMPLATE_COUNT) { "Invalid recovery template count" }
        val rendered = List(count) { Sentence(text(128), text(ADDRESS_BYTES)) }
        val frozen = bool(); val baseline = text(ADDRESS_BYTES); val pending = codes()
        require(input.available() == 0) { "Unexpected recovery fields" }
        return Snapshot(plate, plateClass, plateRequired, address, subject, body, unresolved, time, lat, lon,
            plateRaw, edits, rawLat, rawLon, street, house, cross, savedStreet, savedHouse, ocr, gps,
            typed, editing, exit, tag, rendered, frozen, baseline, pending).also { validate(it) }
    }
}
