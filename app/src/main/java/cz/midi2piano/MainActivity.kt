package cz.midi2piano

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun M2PTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val cs = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9CC9FF), secondary = Color(0xFFFFB871), tertiary = Color(0xFFB5C9FF))
        else -> lightColorScheme(primary = Color(0xFF1B6BB5), secondary = Color(0xFF9A5B00), tertiary = Color(0xFF4F5F9B))
    }
    val base = Typography()
    val typo = base.copy(
        displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(32.dp), extraLarge = RoundedCornerShape(44.dp),
    )
    MaterialTheme(colorScheme = cs, typography = typo, shapes = shapes, content = content)
}

class MainActivity : ComponentActivity() {
    private val vm: AppVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            M2PTheme {
                val pickMidi = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.load(u) }
                val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
                    if (u != null) {
                        try {
                            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        } catch (_: Exception) {}
                        vm.setOutDir(u)
                    }
                }
                AppRoot(vm, onPickMidi = { pickMidi.launch(arrayOf("*/*")) }, onPickDir = { pickDir.launch(null) })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW && i.data != null) vm.load(i.data!!)
    }
}
