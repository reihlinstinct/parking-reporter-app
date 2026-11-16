package io.github.reihlinstinct.reporter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.reihlinstinct.reporter.i18n.LocalStrings
import io.github.reihlinstinct.reporter.i18n.ProvideStringsIfMissing

/**
 * Full-screen confirmation shown after the municipality accepted a report.
 * [receiptText] is the server confirmation line and is passed only by debug
 * builds (null in release): it is shown small at the bottom and never
 * written anywhere else.
 */
@Composable
fun SubmittedScreen(receiptText: String?, onDone: () -> Unit) = ProvideStringsIfMissing {
    val strings = LocalStrings.current
    val ok = MaterialTheme.colorScheme.primary
    val onOk = MaterialTheme.colorScheme.onPrimary
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("submitted-screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Canvas(Modifier.size(112.dp).testTag("submitted-check")) {
            drawCircle(ok)
            val w = size.width
            val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val a = Offset(w * 0.27f, w * 0.53f)
            val b = Offset(w * 0.44f, w * 0.69f)
            val c = Offset(w * 0.74f, w * 0.34f)
            drawLine(onOk, a, b, stroke.width, stroke.cap)
            drawLine(onOk, b, c, stroke.width, stroke.cap)
        }
        Text(strings.t("submitted.screen.title"), style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(strings.t("submitted.screen.body"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(strings.t("submitted.screen.back")) }
        Spacer(Modifier.heightIn(min = 24.dp))
        if (receiptText != null) {
            Text(receiptText, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("submission-note"))
        }
    }
}
