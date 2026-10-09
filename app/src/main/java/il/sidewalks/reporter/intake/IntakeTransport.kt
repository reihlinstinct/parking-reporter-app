package il.sidewalks.reporter.intake

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Never configure this client for a municipality or a private repository. */
interface IntakeWritePort {
    fun create(title: String, manifest: String): Long
    fun comment(issue: Long, body: String)
}
enum class UploadPhase { PREPARED, CREATING, ISSUE_CREATED, CHUNKING, COMMITTING, UPLOADED, UNCERTAIN }
data class UploadCheckpoint(val packageId: String, val digest: String, val phase: UploadPhase,
                            val issue: Long? = null, val nextChunk: Int = 0)
interface UploadJournal {
    /** Must atomically reserve unique ID/digest; duplicate or previously uncertain entries fail. */
    fun reserve(upload: SealedUpload): UploadCheckpoint
    /** Must durably persist BEFORE a network write. Failure stops writes. */
    fun store(checkpoint: UploadCheckpoint)
}
class IntakeUploader(private val port: IntakeWritePort, private val journal: UploadJournal) {
    @Synchronized fun send(upload: SealedUpload, approvedDigest: String): UploadCheckpoint {
        require(approvedDigest == upload.approvalDigest)
        var state = journal.reserve(upload)
        require(state.phase == UploadPhase.PREPARED && state.digest == upload.digest && state.packageId == upload.id)
        var mayHaveWritten = false
        try {
            state = state.copy(phase = UploadPhase.CREATING); journal.store(state)
            mayHaveWritten = true
            val issue = port.create("package-${upload.id}", upload.manifest())
            require(issue > 0)
            state = state.copy(phase = UploadPhase.ISSUE_CREATED, issue = issue); journal.store(state)
            for (index in 0 until upload.count) {
                state = state.copy(phase = UploadPhase.CHUNKING, nextChunk = index); journal.store(state)
                port.comment(issue, upload.chunk(index, issue))
                state = state.copy(nextChunk = index + 1); journal.store(state)
            }
            state = state.copy(phase = UploadPhase.COMMITTING); journal.store(state)
            port.comment(issue, upload.completion(issue))
            state = state.copy(phase = UploadPhase.UPLOADED); journal.store(state)
            return state
        } catch (failure: Exception) {
            if (mayHaveWritten) {
                try { journal.store(state.copy(phase = UploadPhase.UNCERTAIN)) } catch (_: Exception) { /* previous pre-write checkpoint remains */ }
            }
            throw IntakeUploadFailure(mayHaveWritten)
        }
    }
}
class IntakeUploadFailure(val uncertain: Boolean) : Exception("Intake upload stopped; do not retry automatically")

/** No device flow is launched and no token is supplied by the preview UI. */
class GitHubIntakePort(private val bearer: () -> String) : IntakeWritePort {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
        .followSslRedirects(false).callTimeout(45, TimeUnit.SECONDS).build()
    private val endpoint = "https://api.github.com/repos/reihlinstinct/parking-report-intake/issues"
    private fun post(url: String, json: JSONObject): JSONObject {
        val token = bearer(); require(token.isNotBlank())
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            require(response.code == 201)
            val stream = response.body?.byteStream() ?: error("Missing response")
            val bytes = stream.readBytesBounded(65_536)
            return JSONObject(String(bytes, Charsets.UTF_8))
        }
    }
    override fun create(title: String, manifest: String): Long = post(endpoint, JSONObject().put("title", title).put("body", manifest)).getLong("number")
    override fun comment(issue: Long, body: String) {
        require(issue > 0 && body.length <= 48_000)
        post("$endpoint/$issue/comments", JSONObject().put("body", body))
    }
    private fun java.io.InputStream.readBytesBounded(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= max); out.write(buffer, 0, n) }
        return out.toByteArray()
    }
}
