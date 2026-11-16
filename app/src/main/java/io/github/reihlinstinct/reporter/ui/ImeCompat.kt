package io.github.reihlinstinct.reporter.ui

import android.graphics.Rect
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keyboard padding that also works before Android 11 (#471). From API 30 the
 * system reports the keyboard as a window inset and [imePadding] is enough.
 * Before that, an edge-to-edge window is panned instead of resized and the
 * inset never arrives, so the bottom action stayed under the keyboard. There
 * the keyboard height is measured from the visible display frame. Apply it
 * after systemBarsPadding(): the navigation bar height is already padded and
 * is subtracted here.
 */
fun Modifier.imePaddingCompat(): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) imePadding() else composed {
        val view = LocalView.current
        val density = LocalDensity.current
        var extraPx by remember { mutableIntStateOf(0) }
        DisposableEffect(view) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                val frame = Rect()
                view.getWindowVisibleDisplayFrame(frame)
                val covered = (view.rootView.height - frame.bottom).coerceAtLeast(0)
                val navBottom = ViewCompat.getRootWindowInsets(view)
                    ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
                // Ignore small gaps (display cutouts, rounding): a keyboard is far taller.
                val keyboard = if (covered > view.rootView.height / 6) covered else 0
                extraPx = (keyboard - navBottom).coerceAtLeast(0)
            }
            view.viewTreeObserver.addOnGlobalLayoutListener(listener)
            onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
        }
        padding(bottom = with(density) { extraPx.toDp() })
    }
