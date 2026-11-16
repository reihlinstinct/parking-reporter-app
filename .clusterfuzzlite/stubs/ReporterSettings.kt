package io.github.reihlinstinct.reporter.security

/** Fuzz-build stand-in for the Android-only settings class: only the fields EncryptedPackage reads. */
data class ReporterSettings(
    val firstName: String, val lastName: String, val phone: String, val idNumber: String, val email: String,
)
