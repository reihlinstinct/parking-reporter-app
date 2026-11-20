package io.github.reihlinstinct.reporter.municipal

import java.io.File
import io.github.reihlinstinct.reporter.core.ReportCategory

/** Isolated supervised GPS-only experiment, not an app or batch activation.
 * Unknown municipal acceptance: never invent a street/house to satisfy validation.
 */
class TwoCarCiBridge(private val client: MunicipalClient,
    private val contact: LinkedHashMap<String, Any?>, private val root: File,
    private val cableHazard: Boolean = false) {
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
                payload(raw, cableHazard)
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
                val policy = ReportCategory.SIDEWALK_PARKING
                require(if (cableHazard) subject == "5164" else subject == policy.municipalSubjectCode && policy.needsPlate)
                val live = client.subjectCategories(t)
                if (cableHazard) require(live.any { it.code == "5164" && it.name == "אחר" && it.path == CABLE_PATH })
                else require(live.any { it.code == subject })
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
                val result = client.createCase(t, c, payload(raw, cableHazard))
                createdCase = linkedMapOf(
                    "CaseIdInCRM" to result["CaseIdInCRM"], "WebCaseIdInCRM" to result["WebCaseIdInCRM"],
                    "CaseNumberInCRM" to result["CaseNumberInCRM"])
                MunicipalCaseReference.requireAttachable(createdCase!!)
                mapOf("success" to true, "case" to createdCase)
            }
            "attach" -> {
                check(created && !attached && token != null)
                val approved = (prepared!!["photos"] as List<Map<String, Any?>>).single()
                require(request["photo"] == approved)
                val case = request["case"] as Map<String, Any?>
                require(case == createdCase)
                val (caseId, webId) = MunicipalCaseReference.requireAttachable(case)
                val data = photo(approved)
                attached = true
                client.attachPhoto(token!!, caseId, webId, "evidence.jpg", data)
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
        val CABLE_PATH = listOf("כבישים מדרכות", "אחר")
        fun payload(raw: Map<String, Any?>, cableHazard: Boolean = false): LinkedHashMap<String, Any?> {
            val baseKeys = setOf("reportId", "text", "subjectCode", "streetCode", "streetName", "houseNumber", "coordinateX", "coordinateY", "plate", "photos")
            require(raw.keys == baseKeys + if (cableHazard) setOf("eventType", "subjectPath") else emptySet())
            if (cableHazard) {
                require(raw["subjectCode"] == "5164" && raw["eventType"] == "charging_cable_sidewalk_hazard" && raw["subjectPath"] == CABLE_PATH)
            } else require(raw["subjectCode"] == ReportCategory.SIDEWALK_PARKING.municipalSubjectCode)
            val plate = raw["plate"] as String
            val text = raw["text"] as String
            require(Regex("[0-9]{7,8}").matches(plate) && (if (cableHazard) text.replace("-", "") else text).contains(plate))
            val x = (raw["coordinateX"] as Number).toInt(); val y = (raw["coordinateY"] as Number).toInt()
            require(x in 200000..240000 && y in 610000..650000)
            return MunicipalPayloads.buildReport(MunicipalPayloads.ReportFields(
                raw["text"] as String, raw["subjectCode"] as String, raw["streetCode"] as String,
                raw["streetName"] as String, raw["houseNumber"] as String, x, y))
        }

        @JvmStatic fun main(args: Array<String>) {
            try {
                fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("missing private configuration")
                val mode = env("CAR_CI_AUTHORIZED")
                require(args.isEmpty() && mode in setOf("TWO_CAR_ONCE", "CABLE_HAZARD_ONCE"))
                val bridge = TwoCarCiBridge(OkHttpMunicipalClient(env("J106_SUBSCRIPTION_KEY"),
                    env("J106_APP_USERNAME"), env("J106_APP_PASSWORD"), diagnostic = { response ->
                        File(env("BATCH_PRIVATE_ROOT"), "response-private.jsonl").appendText(PythonJson.dumps(linkedMapOf(
                            "endpoint" to response.endpoint, "status" to response.status, "body" to response.body,
                            "failure" to response.failure)) + "\n")
                    }),
                    MunicipalPayloads.buildContact(MunicipalPayloads.Reporter(env("REPORTER_FIRST_NAME"), env("REPORTER_LAST_NAME"),
                        env("REPORTER_PHONE"), env("REPORTER_ID_NUMBER"), env("REPORTER_EMAIL"))), File(env("BATCH_PRIVATE_ROOT")), mode == "CABLE_HAZARD_ONCE")
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
