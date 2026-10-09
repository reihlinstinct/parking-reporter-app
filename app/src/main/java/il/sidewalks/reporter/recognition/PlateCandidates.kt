package il.sidewalks.reporter.recognition

/** OCR numbers are suggestions only. Never substitute O/0 or join unrelated fragments. */
object PlateCandidates {
    fun fromText(text: String): List<String> = Regex("(?<![A-Za-z0-9])(?:[0-9]{7,8}|[0-9]{2}-[0-9]{3}-[0-9]{2}|[0-9]{3}-[0-9]{2}-[0-9]{3})(?![A-Za-z0-9])")
        .findAll(text.take(64_000)).map { it.value.replace("-", "") }.distinct().take(10).toList()
}
