package com.marcopolo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.marcopolo.ui.HomeScreen
import com.marcopolo.ui.MarcoScreen
import com.marcopolo.ui.PoloConfigScreen
import com.marcopolo.ui.PoloMapScreen
import com.marcopolo.ui.theme.MarcoPoloTheme

class MainActivity : ComponentActivity() {

    // Snapshot state shared between Activity and composables.
    // Updated from deep links, consumed by the nav graph.
    private val _deepLinkCode = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge: draw behind system bars so the map fills the entire screen.
        // Set dark translucent status bar (matching header overlay 0xDD000000) so
        // white status bar icons are visible against bright map tiles.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.argb(221, 0, 0, 0)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // Initialize osmdroid configuration (conservative for low-end devices)
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = packageName
            // Small cache = less disk I/O + less memory pressure on low-RAM devices.
            tileFileSystemCacheMaxBytes = 25L * 1024 * 1024   // 25MB — covers full session + cross-session reuse
            tileFileSystemCacheTrimBytes = 20L * 1024 * 1024  // trim to 20MB when limit hit
            // Fewer download threads = less CPU contention on single-core devices.
            tileDownloadThreads = 2                            // was 4
        }

        // Check if launched from a deep link (cold start)
        _deepLinkCode.value = parseDeepLink(intent)

        setContent {
            MarcoPoloTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MarcoPoloNavGraph(deepLinkCode = _deepLinkCode.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Update the snapshot state — Compose recomposes automatically
        _deepLinkCode.value = parseDeepLink(intent)
    }

    /** Extract room code from deep links:
     *  - marcopolo://join/{CODE}
     *  - https://marcopolo-relay.onrender.com/join/{CODE}
     */
    private fun parseDeepLink(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val uri = intent.data ?: return null
        val path = uri.path ?: return null

        // Only allow /join/{CODE} paths
        val match = Regex("^/join/([0-9]{4})$").find(path)
        return match?.groupValues?.getOrNull(1)
    }
}

@Composable
fun MarcoPoloNavGraph(deepLinkCode: String? = null) {
    val navController = rememberNavController()

    // Shared found-dialog state: persists across navigation so the popup
    // can render on top of the home screen after a found event.
    var foundDialogShown by remember { mutableStateOf(false) }

    // Called by MarcoScreen/PoloMapScreen when the found condition is met.
    // Sets the shared flag and navigates home; the ViewModel's onCleared() handles cleanup.
    val onFound: () -> Unit = {
        foundDialogShown = true
        navController.popBackStack("home", false)
    }

    // Navigate directly to Polo when a deep link arrives
    LaunchedEffect(deepLinkCode) {
        val code = deepLinkCode ?: return@LaunchedEffect
        if (navController.currentDestination?.route == "home") {
            navController.navigate("polo_map/$code") {
                popUpTo("home") { inclusive = false }
            }
        }
    }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onMarcoClick = { navController.navigate("marco") },
                onPoloClick = { navController.navigate("polo_config") },
                showFoundDialog = foundDialogShown,
                onDismissFound = { foundDialogShown = false }
            )
        }
        composable("marco") {
            MarcoScreen(
                onBack = { navController.popBackStack("home", false) },
                onFound = onFound
            )
        }
        composable("polo_config") {
            PoloConfigScreen(
                onStartSession = { code ->
                    navController.navigate("polo_map/$code")
                },
                onBack = { navController.popBackStack("home", false) }
            )
        }
        composable("polo_map/{code}") { backStackEntry ->
            val code = backStackEntry.arguments?.getString("code") ?: return@composable
            PoloMapScreen(
                roomCode = code,
                onBack = { navController.popBackStack("home", false) },
                onFound = onFound
            )
        }
    }
}
