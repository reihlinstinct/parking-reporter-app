package il.sidewalks.reporter.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.room.Room
import il.sidewalks.reporter.capture.CaptureScreen
import il.sidewalks.reporter.core.ReviewDraft
import il.sidewalks.reporter.evidence.EvidenceReader
import il.sidewalks.reporter.ledger.ReportDatabase
import il.sidewalks.reporter.ledger.ReportRecord
import il.sidewalks.reporter.security.ReporterSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

@Composable
fun AppShell(
    authenticate: (() -> Unit) -> Unit,
    requestCamera: (() -> Unit) -> Unit,
    readSettings: () -> ReporterSettings?,
    saveSettings: (ReporterSettings) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val database = remember { Room.databaseBuilder(context, ReportDatabase::class.java, "report-ledger.db").build() }
    DisposableEffect(Unit) { onDispose { database.close() } }
    var page by remember { mutableStateOf("home") }
    var profile by remember { mutableStateOf<ReporterSettings?>(null) }
    var draft by remember { mutableStateOf<ReviewDraft?>(null) }
    var original by remember { mutableStateOf<File?>(null) }
    var revision by remember { mutableLongStateOf(0) }
    var records by remember { mutableStateOf<List<ReportRecord>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    suspend fun reload() { records = withContext(Dispatchers.IO) { database.reports().all() } }
    LaunchedEffect(Unit) { reload() }
    var status by remember { mutableStateOf("") }
    fun saveDraft(value: ReviewDraft) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    database.reports().store(value.evidence.stableId, revision,
                        il.sidewalks.reporter.ledger.DraftCodec.encode(value),
                        value.digest().takeIf { value.isApproved() })
                }
                check(updated == 1)
                revision += 1
                draft = value; status = "נשמר מקומית, לא נשלח"
                reload()
            } catch (_: Exception) { status = "שמירה נכשלה, לא בוצעה שליחה" }
            finally { saving = false }
        }
    }
    when (page) {
        "camera" -> CaptureScreen(onCaptured = { file ->
            scope.launch {
                try {
                    val evidence = withContext(Dispatchers.IO) { EvidenceReader().read(file) }
                    val missing = buildSet {
                        if (evidence.latitude == null) add("GPS חסר")
                        if (evidence.capturedAtIso == null) add("זמן חסר") else add("אזור זמן דורש אישור")
                        add("כתובת ומספר בית דורשים אישור")
                    }
                    withContext(Dispatchers.IO) {
                        database.reports().reserve(ReportRecord(evidence.stableId, evidence.originalSha256,
                            "originals/${file.name}", 0,
                            il.sidewalks.reporter.ledger.DraftCodec.encode(ReviewDraft(evidence, "", "", "sidewalk_parking", "", missing)), null))
                    }
                    original = file; revision = 0
                    draft = ReviewDraft(evidence, "", "", "sidewalk_parking", "", missing)
                    reload(); page = "review"
                } catch (_: Exception) { status = "הראיה כפולה או שלא נשמרה, לא בוצעה שליחה"; page = "home" }
            }
        }, onCancel = { page = "home" })
        "review" -> draft?.let { value ->
            val image = remember(original) {
                original?.let { file -> BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }
            }
            ReviewScreen(value, image, ::saveDraft) { page = "home" }
        }
        "settings" -> SettingsScreen(profile, save = { value, cleared ->
            authenticate {
                try { saveSettings(value); profile = null; cleared(); status = "נשמר מוצפן" }
                catch (_: Exception) { status = "הצפנה או אימות נכשלו, לא נשמר מידע גלוי" }
            }
        }, back = { profile = null; page = "home" })
        else -> Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("מדרכות פנויות", style = MaterialTheme.typography.headlineMedium)
            Text("M1 בתהליך פיתוח - טיוטות מקומיות בלבד, אין הגשת דיווח")
            Button(onClick = { requestCamera { page = "camera" } }) { Text("צלם ראיה מקורית") }
            records.forEach { record ->
                TextButton(onClick = {
                    try {
                        draft = il.sidewalks.reporter.ledger.DraftCodec.decode(record.draftJson).copy(approvedDigest = record.reviewedDigest)
                        val file = File(context.noBackupFilesDir, record.originalRelativePath)
                        check(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/") && file.isFile)
                        check(EvidenceReader().read(file, record.stableId).originalSha256 == record.originalSha256)
                        original = file; revision = record.revision; page = "review"
                    } catch (_: Exception) { status = "לא ניתן לשחזר את הראיה; אין שליחה" }
                }) { Text("פתח טיוטה ${record.stableId.take(8)}") }
            }
            if (draft != null) Button(onClick = { page = "review" }) { Text("המשך בדיקת טיוטה") }
            Button(onClick = { authenticate {
                try { profile = readSettings(); page = "settings" }
                catch (_: Exception) { status = "לא ניתן לקרוא מידע מוצפן, נדרש בירור" }
            } }) { Text("הגדרות פרטיות") }
            Text("התחברות לשירות הדיווח עדיין אינה זמינה. פרטי גישה עירוניים אינם חלק מהאפליקציה.")
            Text(status)
            Text("ביטול הרשאת מצלמה או אימות משאיר את המסך ללא פעולה. אין שמירת פרטים גלויים כחלופה.")
        }
    }
}
