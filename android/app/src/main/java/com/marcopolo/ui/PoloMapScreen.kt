package com.marcopolo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marcopolo.service.LocationService
import kotlinx.coroutines.delay
import com.marcopolo.util.DebugOverlay
import com.marcopolo.util.formatCountdown
import com.marcopolo.util.hapticClick
import com.marcopolo.viewmodel.PoloViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoloMapScreen(
    roomCode: String,
    onBack: () -> Unit,
    onFound: () -> Unit = {},
    viewModel: PoloViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val mapState by viewModel.mapState.collectAsState()
    var showDebug by remember { mutableStateOf(false) }

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
            val permissionsToRequest = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            locationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
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
                Button(onClick = hapticClick {
                    val permissionsToRequest2 = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        permissionsToRequest2.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    locationPermissionLauncher.launch(permissionsToRequest2.toTypedArray())
                }) {
                    Text("Grant Permission")
                }
            }
        }
    } else {
        // Map renders once session is active and at least one of: own GPS has a fix
        // OR partner data has arrived. This prevents being stuck waiting for GPS indoors.
        val hasAnyLocation = mapState.ownLat != null || mapState.hasPartnerLocation
        val mapReady = mapState.isActive && hasAnyLocation

        Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (mapReady) "Polo" else roomCode,
                        modifier = Modifier.clickable { showDebug = !showDebug }
                    )
                },
                actions = {
                    if (mapReady) {
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
                    IconButton(onClick = hapticClick {
                        viewModel.cleanup()
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.secondary
                        )
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
            // ── Debug overlay (togglable via title tap) ──
            DebugOverlay(
                show = showDebug,
                onToggle = { showDebug = false },
                    lines = listOf(
                        "isActive" to "${uiState.isActive}",
                        "ownLat" to "${uiState.ownLat ?: "null"}",
                        "ownLng" to "${uiState.ownLng ?: "null"}",
                        "partnerLat" to "${uiState.partnerLat ?: "null"}",
                        "partnerRevealed" to "${uiState.partnerRevealed}",
                        "hasPartnerLoc" to "${uiState.hasPartnerLocation}",
                        "rawPartnerLat" to "${uiState.rawPartnerLat ?: "null"}",
                        "permReady" to "${uiState.permissionsReady}",
                        "locReady" to "${uiState.locationReady}",
                        "sentCount" to "${uiState.sentCount}",
                        "roomCode" to "${uiState.roomCode ?: "null"}",
                        "error" to "${uiState.error ?: "none"}"
                    )
            )

            // ── Content crossfade: room → loading → map ──
                val contentState = when {
                    mapReady -> "map"
                    mapState.isActive -> "loading"
                    else -> "room"
                }
            Crossfade(
                targetState = contentState,
                animationSpec = tween(400)
            ) { state ->
                when (state) {
                    "map" -> Box(Modifier.fillMaxSize()) {
                            MarcoMap(
                                modifier = Modifier.fillMaxSize(),
                                ownLat = mapState.ownLat,
                                ownLng = mapState.ownLng,
                                ownBearing = mapState.ownBearing,
                                partnerLat = mapState.partnerLat,
                                partnerLng = mapState.partnerLng,
                                partnerRole = "Marco",
                                routeLatLngs = mapState.routeLatLngs,
                                routeSteps = mapState.routeSteps,
                                distanceToTarget = mapState.distanceToTarget,
                                showCheckmark = mapState.showCheckmark,
                                onCheckmarkClick = { viewModel.completeSession() }
                            )

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
                        }
                    "loading" -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.secondary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        val waitingMessage = if (mapState.ownLat == null && !mapState.hasPartnerLocation) {
                            "Acquiring GPS and waiting for Marco..."
                        } else if (mapState.ownLat == null) {
                            "Acquiring GPS..."
                        } else {
                            "Waiting for Marco's location..."
                        }
                        Text(
                            text = waitingMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Room $roomCode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    "room" -> Column(
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

                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.secondary,
                            strokeWidth = 4.dp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Waiting for Marco to connect...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Room $roomCode",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            }
        }

        // ── Distance threshold haptic feedback ──
        // Light tap when crossing closer-distance milestones
        val thresholdView = LocalView.current
        val triggeredThresholds = remember { mutableSetOf<Int>() }
        LaunchedEffect(uiState.partnerDistance) {
            val dist = uiState.partnerDistance ?: return@LaunchedEffect
            for (t in listOf(1000, 500, 250, 125, 75, 50)) {
                if (dist <= t && t !in triggeredThresholds) {
                    triggeredThresholds.add(t)
                    thresholdView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
        }

        // ── Found: navigate home, dialog renders on top of home screen ──
        if (uiState.showFoundDialog) {
            LaunchedEffect(Unit) {
                delay(1500)
                onFound()
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
                            onClick = hapticClick {
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


