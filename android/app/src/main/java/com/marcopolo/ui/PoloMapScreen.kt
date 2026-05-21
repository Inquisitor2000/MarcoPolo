package com.marcopolo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marcopolo.service.LocationService
import com.marcopolo.util.formatCountdown
import com.marcopolo.viewmodel.PoloUiState
import com.marcopolo.viewmodel.PoloViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoloMapScreen(
    roomCode: String,
    onBack: () -> Unit,
    viewModel: PoloViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Walking route received from Marco
    val activeRoute = uiState.walkRoute

    // Start location foreground service (requires location permission already granted)
    fun startLocationService() {
        val intent = Intent(context, LocationService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    // Initiate connection on compose
    LaunchedEffect(roomCode) {
        viewModel.joinRoom(roomCode)
    }

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted) {
            startLocationService()
            viewModel.onPermissionsGranted()
        }
    }

    // Check & request permissions on first composition
    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        } else {
            startLocationService()
            viewModel.onPermissionsGranted()
        }
    }

    // ── Permission gate: app content only renders after permission granted ──
    if (!uiState.permissionsReady) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Location permission required",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "This app needs location access to find your partner.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    )
                }) {
                    Text("Grant Permission")
                }
            }
        }
    } else {
        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isActive) "Polo" else "Polo — $roomCode")
                },
                actions = {
                    if (uiState.isActive) {
                        Text(
                            text = formatCountdown(uiState.remainingSeconds),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = {
                        viewModel.cleanup()
                        onBack()
                    }) {
                        Text("Leave")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Map always rendered (tiles cache in background) ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (uiState.isActive) 1f else 0f)
            ) {
                MarcoMap(
                    modifier = Modifier.fillMaxSize(),
                    ownLat = uiState.ownLat,
                    ownLng = uiState.ownLng,
                    ownBearing = uiState.ownBearing,
                    partnerLat = uiState.partnerLat,
                    partnerLng = uiState.partnerLng,
                    partnerRole = "Marco",
                    routeLatLngs = activeRoute?.geometry,
                    distanceToTarget = uiState.partnerDistance
                )
            }

            // ── Overlay content ──
            if (uiState.isActive) {
                // ── Info panel overlaid on map ──
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (uiState.error != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = uiState.error!!,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            } else {
                // ── Connecting state (map hidden beneath) ──
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (uiState.error != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = uiState.error!!,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connecting to room $roomCode...",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                    )
                }
            }

            // ── Debug overlay (top-right) ──
            DebugPanel(
                info = buildDebugInfo(uiState),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
            }
        }

        // ── Disconnect dialog ──
        if (uiState.showDisconnectDialog) {
            Dialog(
                onDismissRequest = {
                    viewModel.cleanup()
                    onBack()
                },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Card(
                    modifier = Modifier
                        .widthIn(min = 300.dp, max = 360.dp)
                        .padding(16.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Connection Lost",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Connection was interrupted by the other party.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = {
                                viewModel.cleanup()
                                onBack()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Text(
                                "OK",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Build debug key-value pairs from PoloUiState */
private fun buildDebugInfo(s: PoloUiState): List<Pair<String, String>> {
    val items = mutableListOf<Pair<String, String>>()
    items.add("Room" to (s.roomCode ?: "—"))
    items.add("Partner" to if (s.isActive) "✅ joined" else "⏳ waiting")
    items.add("Perms" to s.permissionsReady.toString())
    items.add("Locn" to s.locationReady.toString())
    items.add("Sent" to s.sentCount.toString())
    items.add("Me" to formatCoord(s.ownLat, s.ownLng))
    items.add("Them" to formatCoord(s.partnerLat, s.partnerLng))
    items.add("Dist" to formatDist(s.partnerDistance))
    items.add("Reveal" to if (s.partnerRevealed) "✅" else "🔒")
    items.add("Route" to formatRoute(s.walkRoute?.geometry, s.walkRoute?.distance, s.walkRoute?.duration))
    return items
}

private fun formatCoord(lat: Double?, lng: Double?): String {
    if (lat == null || lng == null) return "—"
    return "%.5f, %.5f".format(lat, lng)
}

private fun formatDist(m: Double?): String {
    if (m == null) return "—"
    return if (m < 1000) "%.0f m".format(m) else "%.2f km".format(m / 1000)
}

private fun formatRoute(
    geo: List<List<Double>>?,
    dist: Double?,
    dur: Double?
): String {
    if (geo == null) return "—"
    val pts = geo.size
    val d = dist?.let { if (it < 1000) "%.0f m".format(it) else "%.2f km".format(it / 1000) } ?: "?"
    val t = dur?.let { "${(it / 60).toInt()} min" } ?: "?"
    return "$pts pts · $d · $t"
}
