package io.github.reihlinstinct.reporter.registry

/** Owner-confirmed registry mapping. Keep numeric interpretation in one place. */
enum class DisabilityTagType {
    TRIANGLE, WHEELCHAIR, UNKNOWN;

    companion object {
        private val REGISTRY_TYPES = mapOf(1 to TRIANGLE, 2 to WHEELCHAIR)
        fun fromRegistry(code: Int?): DisabilityTagType = REGISTRY_TYPES[code] ?: UNKNOWN
    }
}
