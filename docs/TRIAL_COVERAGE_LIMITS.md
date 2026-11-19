# Offline trial coverage and limits (#650)

`PYTHONPATH=scripts python -m unittest discover -s scripts -p "test_*.py"`
exercises synthetic controller, intake, journal, encrypted persistence, preparation,
and subprocess boundaries. Preparation explicitly retains legacy plaintext ZIP
compatibility; credentialed trial callers use the shared encrypted-only default.
No command here dispatches a workflow or calls the municipality.

`JAVA_HOME=/path/to/jdk17 sh gradlew -p api-bridge test` compiles the exact JVM
municipal sources and tests listed in api-bridge/build.gradle.kts. A green Python
mock test is not proof of HTTP behavior, Android lifecycle, emulator pixels,
real private repository CAS/create-only semantics, external writer fencing,
current municipal catalog policy, or activation authority. Android proof belongs
to the exact-head emulator checks and retained screenshots. Independent full-file
review is a separate merge requirement. Historical passing runs do not prove a
new head. None of these tests authorizes a live write.
