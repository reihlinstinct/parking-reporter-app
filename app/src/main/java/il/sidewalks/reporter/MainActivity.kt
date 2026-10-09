package il.sidewalks.reporter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.WindowManager
import android.app.KeyguardManager
import android.app.Activity
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import il.sidewalks.reporter.ui.AppShell
import il.sidewalks.reporter.security.SecureSettings
import il.sidewalks.reporter.security.ReporterSettings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var authenticatedAction: (() -> Unit)? = null
    private var cameraAction: (() -> Unit)? = null
    private val authentication = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = authenticatedAction
        authenticatedAction = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = cameraAction
        cameraAction = null
        if (granted) action?.invoke()
    }
    private fun authenticate(action: () -> Unit) {
        val manager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        @Suppress("DEPRECATION")
        val intent = manager.createConfirmDeviceCredentialIntent("הגדרות פרטיות", "אמת את נעילת המכשיר כדי לגשת לפרטים") ?: return
        authenticatedAction = action
        authentication.launch(intent)
    }
    private fun camera(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) action()
        else { cameraAction = action; cameraPermission.launch(Manifest.permission.CAMERA) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { MaterialTheme {
            AppShell(
                authenticate = ::authenticate,
                requestCamera = ::camera,
                readSettings = { SecureSettings(this).read() },
                saveSettings = { settings -> SecureSettings(this).save(settings) },
            )
        } }
    }
}
