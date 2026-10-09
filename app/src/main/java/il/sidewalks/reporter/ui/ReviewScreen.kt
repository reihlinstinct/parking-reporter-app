package il.sidewalks.reporter.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import il.sidewalks.reporter.core.ReviewDraft

@Composable
fun ReviewScreen(initial: ReviewDraft, image: ImageBitmap?, onSave: (ReviewDraft) -> Unit, onBack: () -> Unit, onOutgoingPreview: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var plates by remember(initial.evidence.stableId) { mutableStateOf<List<String>>(emptyList()) }
    var recognizing by remember { mutableStateOf(false) }
    var ocrStatus by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<il.sidewalks.reporter.evidence.AddressSuggestion>>(emptyList()) }
    var geocoding by remember { mutableStateOf(false) }
    var geocodeStatus by remember { mutableStateOf("") }
    var draft by remember(initial.evidence.stableId) { mutableStateOf(initial) }
    var time by remember(initial.evidence.stableId) { mutableStateOf(initial.confirmedTime.orEmpty()) }
    var latitude by remember(initial.evidence.stableId) { mutableStateOf(initial.confirmedLatitude?.toString().orEmpty()) }
    var longitude by remember(initial.evidence.stableId) { mutableStateOf(initial.confirmedLongitude?.toString().orEmpty()) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("בדיקת דיווח - ללא שליחה", style = MaterialTheme.typography.titleLarge)
        image?.let { Image(it, "צילום ראיה", modifier = Modifier.fillMaxWidth().height(220.dp)) }
        Text("זמן הראיה המקורי")
        Text("\u2066${HebrewPresentation.evidenceTime(draft.evidence.capturedAtIso)}\u2069", modifier = Modifier.fillMaxWidth(), style = TextStyle(textDirection = TextDirection.Ltr))
        Text("GPS: ${if (draft.evidence.latitude == null) "חסר" else "נקרא מהראיה"}")
        Text("זיהוי לוחית מתבצע במכשיר בלבד. תוצאה היא הצעה, ולא אישור שהלוחית נכונה.")
        Button(enabled = image != null && !recognizing, onClick = {
            recognizing = true
            scope.launch {
                plates = withContext(Dispatchers.Default) {
                    try { il.sidewalks.reporter.recognition.OfflinePlateOcr(context).recognize(image!!.asAndroidBitmap()) }
                    catch (_: Exception) { emptyList() }
                }
                recognizing = false
                ocrStatus = if (plates.isEmpty()) "לא זוהתה לוחית. הזן ידנית מתוך התמונה" else "בדוק כל ספרה בתמונה לפני בחירה"
            }
        }) { Text(if (recognizing) "מזהה במכשיר..." else "הצע לוחית מתוך הצילום") }
        Text(ocrStatus)
        plates.forEach { plate -> TextButton(onClick = { draft = draft.edit(plate = plate) }) { Text("הצעה בלבד: \u2066$plate\u2069") } }
        OutlinedTextField(draft.plate, { draft = draft.edit(plate = it) }, textStyle = TextStyle(textDirection = TextDirection.Ltr), label = { Text("לוחית ללא מקפים") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.address, { draft = draft.edit(address = it) }, label = { Text("רחוב ומספר בית שאישרת") }, modifier = Modifier.fillMaxWidth())
        Text("חיפוש כתובת שולח קואורדינטות בלבד לספק המיקום של המכשיר. מספרי בית הם הצעות בלבד.")
        Button(enabled = !geocoding && latitude.toDoubleOrNull() != null && longitude.toDoubleOrNull() != null, onClick = {
            geocoding = true
            scope.launch {
                suggestions = withContext(Dispatchers.IO) {
                    try { il.sidewalks.reporter.evidence.AddressResolver(context).suggest(latitude.toDouble(), longitude.toDouble()) }
                    catch (_: Exception) { emptyList() }
                }
                geocoding = false
                geocodeStatus = if (suggestions.isEmpty()) "אין הצעות, הזן כתובת שבדקת" else "בדוק ואשר את מספר הבית בעצמך"
            }
        }) { Text("חפש הצעות כתובת לפי המיקום") }
        Text(geocodeStatus)
        suggestions.forEach { suggestion ->
            TextButton(onClick = { draft = draft.edit(address = suggestion.display).copy(unresolved = setOf("כתובת דורשת אישור")) }) {
                Text("הצעה בלבד: ${suggestion.display}")
            }
        }
        OutlinedTextField(HebrewPresentation.category(draft.subject), {}, readOnly = true, label = { Text("קטגוריה") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.exactHebrewText, { draft = draft.edit(text = it) }, label = { Text("הטקסט המדויק לבדיקה") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
        Text("מלא ואשר זמן עם אזור זמן וקואורדינטות ידועות לך. אין לנחש מידע חסר. הערכים המקוריים נשמרים בנפרד.")
        Text("\u20662026-01-01T12:00:00+02:00\u2069", modifier = Modifier.fillMaxWidth())
        OutlinedTextField(time, { time = it; draft = draft.copy(unresolved = setOf("זמן דורש אישור"), approvedDigest = null) }, textStyle = TextStyle(textDirection = TextDirection.Ltr), label = { Text("זמן מאושר עם אזור זמן (ISO)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(latitude, { latitude = it; draft = draft.copy(unresolved = setOf("מיקום דורש אישור"), approvedDigest = null) }, label = { Text("קו רוחב") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(longitude, { longitude = it; draft = draft.copy(unresolved = setOf("מיקום דורש אישור"), approvedDigest = null) }, label = { Text("קו אורך") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { draft = draft.confirmEvidence(time, latitude.toDoubleOrNull(), longitude.toDoubleOrNull()) }) { Text("אשר זמן, מיקום וכתובת שבדקת") }
        if (draft.unresolved.isNotEmpty()) Text("דרוש בירור: ${draft.unresolved.joinToString()}")
        Text("כל שינוי מבטל אישור. האישור כאן אינו שולח לעירייה או לרשת חברתית.")
        Button(enabled = draft.isReady(), onClick = { draft = draft.approve(); onSave(draft) }) { Text("אשר את התוכן לבדיקה") }
        Text(if (draft.isApproved()) "התוכן אושר מקומית, לא נשלח" else "התוכן לא אושר")
        Button(onClick = { onSave(draft) }) { Text("שמור טיוטה") }
        onOutgoingPreview?.let { action -> Button(onClick = action) { Text("בדוק תמונה נפרדת ללא נתוני צילום") } }
        TextButton(onClick = onBack) { Text("חזור") }
    }
    }
}
