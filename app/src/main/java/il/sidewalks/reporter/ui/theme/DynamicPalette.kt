package il.sidewalks.reporter.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/** Non-secret appearance preference. Opt-in only, never part of reporter credentials. */
class DynamicPaletteStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("dynamic_palette", false)
    fun setEnabled(enabled: Boolean) { prefs.edit().putBoolean("dynamic_palette", enabled).apply() }
    fun listen(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)
    fun stop(listener: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

@Composable
fun rememberDynamicPalette(): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current
    val store = remember(context) { DynamicPaletteStore(context) }
    var enabled by remember(store) { mutableStateOf(store.enabled()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> enabled = store.enabled() }
        store.listen(listener)
        onDispose { store.stop(listener) }
    }
    return enabled to { store.setEnabled(it); enabled = it }
}

fun supportsDynamicPalette(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= 31

/** Fail closed to municipal colors when a device-supplied scheme fails text AA. */
fun accessibleDynamicScheme(candidate: ColorScheme, fallback: ColorScheme): ColorScheme =
    if (dynamicTextContrastPasses(candidate)) candidate else fallback

fun dynamicTextContrastPasses(s: ColorScheme): Boolean {
    val pairs = listOf(s.primary to s.onPrimary, s.primaryContainer to s.onPrimaryContainer,
        s.secondary to s.onSecondary, s.secondaryContainer to s.onSecondaryContainer,
        s.tertiary to s.onTertiary, s.tertiaryContainer to s.onTertiaryContainer,
        s.background to s.onBackground, s.surface to s.onSurface,
        s.surfaceVariant to s.onSurfaceVariant, s.error to s.onError,
        s.errorContainer to s.onErrorContainer, s.inverseSurface to s.inverseOnSurface,
        s.surface to s.primary, s.background to s.primary)
    return pairs.all { (bg, fg) -> Palette.contrast(bg.toArgb() and 0xFFFFFF, fg.toArgb() and 0xFFFFFF) >= 4.5 }
}
