package com.marcopolo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.marcopolo.R
import com.marcopolo.service.LocationService
import kotlinx.coroutines.delay
import com.marcopolo.util.formatCountdown
import com.marcopolo.util.hapticClick
import com.marcopolo.viewmodel.MarcoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarcoScreen(
    onBack: () -> Unit,
    onFound: () -> Unit = {},
    viewModel: MarcoViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val mapState by viewModel.mapState.collectAsState()
    val useFootpath by viewModel.useFootpath.collectAsState()
    // Start location foreground service (requires location permission already granted)
    fun startLocationService() {
        val intent = Intent(context, LocationService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    // Location permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false) {
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
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        } else {
            startLocationService()
            viewModel.onPermissionsGranted()
        }
    }

    // ── Permission gate: app content only renders after permission granted ──
    if (!uiState.permissionsReady) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1C1C1C)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.perm_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.perm_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(28.dp))
                Button(
                    onClick = hapticClick {
                        locationPermissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                        )
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
                        stringResource(R.string.perm_grant),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    } else {
        // Map renders once session is active and at least one of: own GPS has a fix
        // OR partner data has arrived. This prevents being stuck waiting for GPS indoors.
        val hasAnyLocation = mapState.ownLat != null || mapState.hasPartnerLocation
        val mapReady = mapState.isActive && hasAnyLocation

        if (mapReady) {
            // ── Full-screen map (no Scaffold, no TopAppBar) ──
            Box(modifier = Modifier.fillMaxSize()) {
                MarcoMap(
                    modifier = Modifier.fillMaxSize(),
                    ownLat = mapState.ownLat,
                    ownLng = mapState.ownLng,
                    ownBearing = mapState.ownBearing,
                    partnerLat = mapState.partnerLat,
                    partnerLng = mapState.partnerLng,
                    partnerRole = stringResource(R.string.partner_role_polo),
                    routeLatLngs = mapState.routeLatLngs,
                    routeSteps = mapState.routeSteps,
                    distanceToTarget = mapState.distanceToTarget,
                    routeDistance = mapState.routeDistance,
                    compassAccuracy = mapState.compassAccuracy,
                    gpsAccuracy = mapState.gpsAccuracy,
                    showCheckmark = mapState.showCheckmark,
                    onCheckmarkClick = { viewModel.completeSession() }
                )

                // ── Status bar dark scrim ──
                // System status bar is transparent on modern Android; this provides
                // a dark background so white status bar icons are visible against
                // bright map tiles.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars)
                        .background(Color(0xDD000000))
                )

                // ── Persistent header overlay (dark card style, matches nav instruction) ──
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .background(Color(0xDD000000), RoundedCornerShape(12.dp))
                            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = hapticClick {
                            viewModel.cleanup()
                            onBack()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = Color.White
                            )
                        }
                        Text(
                            text = stringResource(R.string.title_marco),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        // ── Routing mode toggle (wider pill, animated thumb) ──
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val thumbOffset by animateDpAsState(
                                targetValue = if (useFootpath) 0.dp else 40.dp,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .width(80.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF88FF88).copy(alpha = 0.2f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { viewModel.setRoutingMode(!useFootpath) }
                            ) {
                                // Thumb (bottom layer — half-track pill slides behind icons)
                                Box(
                                    modifier = Modifier
                                        .offset(x = thumbOffset)
                                        .size(40.dp, 32.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Color(0xFF88FF88))
                                )
                                // Walk icon (top layer — always visible over thumb)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .padding(start = 6.dp)
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1C1C1C)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DirectionsWalk,
                                        contentDescription = stringResource(R.string.cd_footpath),
                                        tint = if (useFootpath) Color(0xFF88FF88) else Color(0xFF666666),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                // Car icon (top layer — always visible over thumb)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .padding(end = 6.dp)
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF1C1C1C)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DirectionsCar,
                                        contentDescription = stringResource(R.string.cd_street),
                                        tint = if (useFootpath) Color(0xFF666666) else Color(0xFF88FF88),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                        // Fixed-width container prevents timer digit changes from shifting the toggle
                        Box(
                            modifier = Modifier.width(80.dp).padding(end = 4.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Text(
                                text = formatCountdown(uiState.remainingSeconds),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF88FF88)
                            )
                        }
                    }
                }

                // ── Error panel (below header overlay) ──
                if (uiState.error != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(12.dp)
                    ) {
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
        } else {
            // ── Room / Loading states with Scaffold ──
            Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                    Text(
                        text = stringResource(R.string.title_marco)
                    )
                    },
                    navigationIcon = {
                        IconButton(onClick = hapticClick {
                            viewModel.cleanup()
                            onBack()
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.primary
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
                    val contentState = if (mapState.isActive) "loading" else "room"
                    Crossfade(
                        targetState = contentState,
                        animationSpec = tween(400)
                    ) { state ->
                        when (state) {
                            "loading" -> Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(48.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 4.dp
                                )
                                Spacer(modifier = Modifier.height(24.dp))
                                val waitingMessage = if (mapState.ownLat == null && !mapState.hasPartnerLocation) {
                                    stringResource(R.string.waiting_gps_and_polo)
                                } else if (mapState.ownLat == null) {
                                    stringResource(R.string.waiting_gps)
                                } else {
                                    stringResource(R.string.waiting_polo_location)
                                }
                                Text(
                                    text = waitingMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = uiState.roomCode ?: "",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
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

                                if (uiState.roomCode != null) {
                                    Text(
                                        text = stringResource(R.string.share_code),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = uiState.roomCode!!,
                                            textAlign = TextAlign.Center,
                                            fontSize = 56.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 8.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = hapticClick {
                                                val shareIntent = Intent().apply {
                                                    action = Intent.ACTION_SEND
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        context.getString(R.string.share_text, "https://marcopolo-relay.onrender.com/join/${uiState.roomCode}")
                                                    )
                                                    type = "text/plain"
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_invite)))
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = stringResource(R.string.cd_share_room),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(R.string.waiting_polo_connect),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 3.dp
                                    )
                                }
                            }
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
        // When distance ≤ 15m, wait 1.5s so both sides can sync their found state
        // via WebSocket. Then navigate home; the congratulation dialog renders on
        // top of the home screen via shared state in NavGraph.
        if (uiState.showFoundDialog) {
            LaunchedEffect(Unit) {
                delay(1500)
                onFound()
            }
        }

        // ── Disconnect overlay ──
        // Full-screen opaque overlay — completely hides the map behind a solid
        // background. Touch consumes any accidental taps on the covered map.
        if (uiState.showDisconnectDialog) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume touch — no dismiss on outside tap */ }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.Center)
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
                            stringResource(R.string.connection_lost),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.connection_lost_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(28.dp))
                        Button(
                            onClick = hapticClick {
                                onBack()
                                // cleanup happens in ViewModel.onCleared() when nav entry is popped
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
                            stringResource(R.string.btn_ok),
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


