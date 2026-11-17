package io.github.reihlinstinct.reporter.municipal

import java.io.File

/** Inactive private stdio bridge. Reuses production payload/address/client code.
 * Stdout is PRIVATE IPC, never a public workflow log. All failures are constants.
 * Caller owns approved-byte validation and durable intent BEFORE write requests.
 */
class BatchMunicipalBridge(
    private val client: MunicipalClient,
    private val streets: StreetDirectory,
    private val contact: LinkedHashMap<String, Any?>,
    private val photoRoot: File,
) {
    private var token: String? = null
    private fun authenticated() = token ?: error("login required")

    @Suppress("UNCHECKED_CAST")
    fun call(request: Map<String, Any?>): Map<String, Any?> {
        return when (request["operation"]) {
            "login" -> {
                token = client.login()
                mapOf("success" to true)
            }
            "prepare" -> {
                val raw = request["report"] as Map<String, Any?>
                report(raw)
                @Suppress("UNCHECKED_CAST")
                val photos = raw["photos"] as? List<Map<String, Any?>> ?: emptyList()
                photos.forEach { photo(it) }
                mapOf("success" to true)
            }
            "create" -> {
                val report = report(request["report"] as Map<String, Any?>)
                val t = authenticated()
                val c = LinkedHashMap(contact).apply { put("Token", t) }
                val registered = client.upsertContact(t, c)
                val id = registered["ContactIdInCRM"] as? String
                require(!id.isNullOrBlank())
                c["ContactIdInCRM"] = id
                val result = client.createCase(t, c, report)
                val case = linkedMapOf<String, Any?>(
                    "CaseIdInCRM" to result["CaseIdInCRM"],
                    "WebCaseIdInCRM" to result["WebCaseIdInCRM"],
                    "CaseNumberInCRM" to result["CaseNumberInCRM"],
                )
                require(listOf("CaseIdInCRM", "WebCaseIdInCRM", "CaseNumberInCRM").any {
                    (case[it] as? String)?.trim('0', '-', ' ')?.isNotEmpty() == true
                })
                mapOf("success" to true, "case" to case)
            }
            "attach" -> {
                val case = request["case"] as Map<String, Any?>
                val caseId = case["CaseIdInCRM"] as? String
                require(!caseId.isNullOrBlank() && caseId.trim('0', '-', ' ').isNotEmpty())
                val photo = request["photo"] as Map<String, Any?>
                val bytes = photo(photo)
                val name = photo["path"] as String
                val filename = (photo["sha256"] as String) + if (name.endsWith(".png")) ".png" else ".jpg"
                client.attachPhoto(authenticated(), caseId,
                    case["WebCaseIdInCRM"] as? String ?: "", filename, bytes)
                mapOf("success" to true)
            }
            else -> error("unsupported operation")
        }
    }

    /** Validate full decode before any login/contact/case write, bounded before allocation. */
    private fun photo(raw: Map<String, Any?>): ByteArray {
        val name = raw["path"] as String
        require(Regex("photos/[A-Za-z0-9_./-]+\\.(jpg|jpeg|png)").matches(name))
        val file = File(photoRoot, name).canonicalFile
        require(file.toPath().startsWith(photoRoot.canonicalFile.toPath()))
        require(file.isFile && file.length() in 1..10_485_760)
        val bytes = file.readBytes()
        require(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == raw["sha256"])
        javax.imageio.ImageIO.createImageInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
            requireNotNull(input)
            val readers = javax.imageio.ImageIO.getImageReaders(input)
            require(readers.hasNext())
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val format = reader.formatName.lowercase(java.util.Locale.ROOT)
                require(format == if (name.endsWith(".png")) "png" else "jpeg")
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 4_000_000)
                val decoded = reader.read(0)
                require(decoded != null && decoded.width == width && decoded.height == height)
                decoded.flush()
            } finally { reader.dispose() }
        }
        return bytes
    }

    private fun report(raw: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val match = Regex("^(.*\\S)\\s+(" + HOUSE_NUMBER.pattern + ")$").matchEntire((raw["address"] as String).trim()) ?: error("exact address required")
        val address = match.groupValues[1] to match.groupValues[2]
        val resolved = streets.resolve(address.first, address.second,
            (raw["latitude"] as Number).toDouble() to (raw["longitude"] as Number).toDouble())
        return MunicipalPayloads.buildReport(MunicipalPayloads.ReportFields(
            description = raw["text"] as String, subjectCode = raw["subjectCode"] as String,
            streetCode = resolved.streetCode, streetName = resolved.streetName,
            houseNumber = resolved.house, coordinateX = resolved.x, coordinateY = resolved.y,
        ))
    }

    companion object {
        @JvmStatic fun main(args: Array<String>) {
            fun env(key: String) = System.getenv(key)?.takeIf { it.isNotBlank() } ?: error("missing private configuration")
            try {
                check(env("BATCH_BRIDGE_AUTHORIZED") == "1")
                val bridge = BatchMunicipalBridge(
                    OkHttpMunicipalClient(env("J106_SUBSCRIPTION_KEY"), env("J106_APP_USERNAME"), env("J106_APP_PASSWORD")),
                    StreetDirectory.load(File(env("BATCH_ADDRESS_PATH")).readText()),
                    MunicipalPayloads.buildContact(MunicipalPayloads.Reporter(
                        env("REPORTER_FIRST_NAME"), env("REPORTER_LAST_NAME"), env("REPORTER_PHONE"),
                        env("REPORTER_ID_NUMBER"), env("REPORTER_EMAIL"))),
                    File(env("BATCH_PRIVATE_ROOT")),
                )
                val reader = System.`in`.bufferedReader()
                while (true) {
                    // Bounded line reader: do not allocate arbitrary private input.
                    val line = StringBuilder()
                    while (true) {
                        val c = reader.read()
                        if (c == -1 || c == 10) break
                        check(line.length < 1_048_576)
                        line.append(c.toChar())
                    }
                    if (line.isEmpty()) break
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val request = PythonJson.loads(line.toString()) as Map<String, Any?>
                        println(PythonJson.dumps(bridge.call(request)))
                        System.out.flush()
                    } catch (_: Throwable) {
                        println("{\"success\": false, \"error\": \"BATCH_OPERATION_UNKNOWN_OR_FAILED\"}")
                        System.out.flush()
                        return // No further request after uncertainty.
                    }
                }
            } catch (_: Throwable) {
                println("{\"success\": false, \"error\": \"BATCH_BRIDGE_UNAVAILABLE\"}")
                System.out.flush()
            }
        }
    }
}
