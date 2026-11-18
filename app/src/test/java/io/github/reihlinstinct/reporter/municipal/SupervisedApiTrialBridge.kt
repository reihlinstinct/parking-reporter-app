package io.github.reihlinstinct.reporter.municipal

import java.io.File

/** Isolated supervised GPS-only experiment, not an app or batch activation.
 * Unknown municipal acceptance: never invent a street/house to satisfy validation.
 */
class SupervisedApiTrialBridge(private val client: MunicipalClient,
    private val contact: LinkedHashMap<String, Any?>, private val root: File) {
    private var token: String? = null
    private var prepared: Map<String, Any?>? = null
    private var created = false
    private var attached = false
    private var createdCase: Map<String, Any?>? = null

    @Suppress("UNCHECKED_CAST")
    fun call(request: Map<String, Any?>): Map<String, Any?> {
        return when (request["operation"]) {
            "prepare" -> {
                check(token == null && !created)
                val raw = request["report"] as Map<String, Any?>
                payload(raw)
                val photos = raw["photos"] as List<Map<String, Any?>>
                require(photos.size == 1)
                photo(photos.single())
                prepared = raw
                mapOf("success" to true)
            }
            "login" -> {
                check(prepared != null && token == null)
                val t = client.login()
                val subject = prepared!!["subjectCode"] as String
                require(client.subjectCategories(t).any { it.code == subject && it.plateRequired != true })
                token = t
                mapOf("success" to true)
            }
            "create" -> {
                check(!created && token != null)
                val raw = request["report"] as Map<String, Any?>
                require(raw == prepared)
                created = true // Burn before the first contact/case request, no in-process retry.
                val t = token!!
                val c = LinkedHashMap(contact).apply { put("Token", t) }
                val registered = client.upsertContact(t, c)
                val id = registered["ContactIdInCRM"] as? String
                require(!id.isNullOrBlank())
                c["ContactIdInCRM"] = id
                val result = client.createCase(t, c, payload(raw))
                createdCase = linkedMapOf(
                    "CaseIdInCRM" to result["CaseIdInCRM"], "WebCaseIdInCRM" to result["WebCaseIdInCRM"],
                    "CaseNumberInCRM" to result["CaseNumberInCRM"])
                val idInCrm = createdCase!!["CaseIdInCRM"] as? String
                require(!idInCrm.isNullOrBlank() && idInCrm.trim('0', '-', ' ').isNotEmpty())
                mapOf("success" to true, "case" to createdCase)
            }
            "attach" -> {
                check(created && !attached && token != null)
                val approved = (prepared!!["photos"] as List<Map<String, Any?>>).single()
                require(request["photo"] == approved)
                val case = request["case"] as Map<String, Any?>
                require(case == createdCase)
                val caseId = case["CaseIdInCRM"] as? String
                require(!caseId.isNullOrBlank() && caseId.trim('0', '-', ' ').isNotEmpty())
                val data = photo(approved)
                attached = true
                client.attachPhoto(token!!, caseId, case["WebCaseIdInCRM"] as? String ?: "", "evidence.jpg", data)
                mapOf("success" to true)
            }
            else -> error("unsupported supervised operation")
        }
    }

    private fun photo(raw: Map<String, Any?>): ByteArray {
        require(raw.keys == setOf("path", "sha256") && raw["path"] == "photos/approved.jpg")
        val file = File(root, "photos/approved.jpg")
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        require(file.isFile && file.length() in 1..1_100_000)
        val bytes = file.readBytes()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash == raw["sha256"])
        javax.imageio.stream.MemoryCacheImageInputStream(java.io.ByteArrayInputStream(bytes)).use { input ->
            val readers = javax.imageio.ImageIO.getImageReaders(input)
            require(readers.hasNext())
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                require(reader.formatName.equals("jpeg", ignoreCase = true))
                val w = reader.getWidth(0); val h = reader.getHeight(0)
                require(w in 1..2560 && h in 1..2560 && w.toLong() * h <= 4_000_000)
                val image = reader.read(0)
                require(image != null && image.width == w && image.height == h)
                image.flush()
            } finally { reader.dispose() }
        }
        return bytes
    }

    companion object {
        fun payload(raw: Map<String, Any?>): LinkedHashMap<String, Any?> {
            require(raw.keys == setOf("reportId", "text", "subjectCode", "latitude", "longitude", "locationDescription", "photos"))
            val text = raw["text"] as String
            val location = raw["locationDescription"] as String
            MunicipalPayloads.checkText(text, "Trial text"); MunicipalPayloads.checkText(location, "Trial location")
            require(text.length <= 10000 && location.length <= 1000)
            val subject = raw["subjectCode"] as String
            require(Regex("[0-9]{1,10}").matches(subject))
            val lat = (raw["latitude"] as Number).toDouble(); val lon = (raw["longitude"] as Number).toDouble()
            require(lat.isFinite() && lon.isFinite() && lat in 31.65..31.95 && lon in 35.05..35.35)
            val (x, y) = Itm.toItm(lat, lon)
            return linkedMapOf("CaseDescription" to text, "CaseSubjectCode" to subject,
                "CaseStreetCode" to "", "CaseStreetName" to "", "CaseHouseNumber" to "",
                "CaseAddressText" to location, "CoordinateX" to x, "CoordinateY" to y,
                "Language" to 1, "ApplicationCaseNumber" to "ApplicationCaseNumber_VAL")
        }

        @JvmStatic fun main(args: Array<String>) {
            try {
                fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("missing private configuration")
                require(args.isEmpty() && env("SUPERVISED_API_TRIAL") == "GPS_ONLY_ONCE")
                val bridge = SupervisedApiTrialBridge(OkHttpMunicipalClient(env("J106_SUBSCRIPTION_KEY"),
                    env("J106_APP_USERNAME"), env("J106_APP_PASSWORD"), diagnostic = { response ->
                        File(env("BATCH_PRIVATE_ROOT"), "response-private.jsonl").appendText(PythonJson.dumps(linkedMapOf(
                            "endpoint" to response.endpoint, "status" to response.status, "body" to response.body,
                            "failure" to response.failure)) + "\n")
                    }),
                    MunicipalPayloads.buildContact(MunicipalPayloads.Reporter(env("REPORTER_FIRST_NAME"), env("REPORTER_LAST_NAME"),
                        env("REPORTER_PHONE"), env("REPORTER_ID_NUMBER"), env("REPORTER_EMAIL"))), File(env("BATCH_PRIVATE_ROOT")))
                val reader = System.`in`.bufferedReader()
                while (true) {
                    val line = StringBuilder()
                    while (true) {
                        val c = reader.read()
                        if (c == -1 || c == 10) break
                        require(line.length < 1048576); line.append(c.toChar())
                    }
                    if (line.isEmpty()) break
                    try {
                        @Suppress("UNCHECKED_CAST") val request = PythonJson.loads(line.toString()) as Map<String, Any?>
                        println(PythonJson.dumps(bridge.call(request))); System.out.flush()
                    } catch (_: Throwable) {
                        println("{\"success\":false,\"error\":\"SUPERVISED_TRIAL_UNKNOWN_OR_REFUSED\"}")
                        System.out.flush(); return
                    }
                }
            } catch (_: Throwable) {
                println("{\"success\":false,\"error\":\"SUPERVISED_TRIAL_UNAVAILABLE\"}")
                System.out.flush()
            }
        }
    }
}
