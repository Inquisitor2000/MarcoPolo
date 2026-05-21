package com.marcopolo.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

// Clean, high-contrast tile source with crisp label rendering.
// CartoDB Positron (light) — free for small-scale use with attribution.
private val HIGH_QUALITY_TILES: ITileSource = XYTileSource(
    "CartoDB-Positron", 3, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/light_all/",
        "https://b.basemaps.cartocdn.com/light_all/",
        "https://c.basemaps.cartocdn.com/light_all/"
    )
)


/**
 * osmdroid MapView wrapped as a Compose component.
 * Shows own + partner markers, route polyline, zoom controls,
 * and a follow-me toggle. The map rotates smoothly around the
 * own GPS position.
 */
@Composable
fun MarcoMap(
    modifier: Modifier = Modifier,
    ownLat: Double?,
    ownLng: Double?,
    ownBearing: Float? = null,          // degrees, 0 = north
    partnerLat: Double?,
    partnerLng: Double?,
    partnerRole: String = "Partner",
    routeLatLngs: List<List<Double>>? = null,
    distanceToTarget: Double? = null     // straight-line meters to partner
) {
    val mapView = remember { mutableStateOf<MapView?>(null) }
    var youMarker by remember { mutableStateOf<Marker?>(null) }
    var partnerMarker by remember { mutableStateOf<Marker?>(null) }
    var polylineBg by remember { mutableStateOf<Polyline?>(null) }   // glow
    var polylineFg by remember { mutableStateOf<Polyline?>(null) }   // bright line

    var youBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var partnerBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // ── Change-detection state (suppress redundant map operations) ──
    var prevOwnLat by remember { mutableStateOf(Double.NaN) }
    var prevOwnLng by remember { mutableStateOf(Double.NaN) }
    var prevPartnerLat by remember { mutableStateOf(Double.NaN) }
    var prevPartnerLng by remember { mutableStateOf(Double.NaN) }
    var prevOrientation by remember { mutableStateOf(-1f) }
    var prevRouteKey by remember { mutableStateOf("") }
    var bboxDone by remember { mutableStateOf(false) }

    // ── Follow-me toggle: auto-center on own position ──
    var followMe by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // ── Smooth map orientation animation ──
    val mapOrientation = remember { Animatable(0f) }
    // Round bearing to whole degrees to damp noisy GPS heading
    val targetOrientation = remember(ownBearing) {
        -(Math.round(ownBearing ?: 0f)).toFloat()
    }
    LaunchedEffect(targetOrientation) {
        mapOrientation.animateTo(
            targetOrientation,
            animationSpec = tween(durationMillis = 350, easing = LinearEasing)
        )
    }

    // Show spinner until map tiles start loading
    var mapReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(800L)
        mapReady = true
    }

    // Manage osmdroid lifecycle
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.value?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            mapView.value?.onDetach()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = modifier) {
        // ── osmdroid map layer ──
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(HIGH_QUALITY_TILES)
                    setMultiTouchControls(true)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    minZoomLevel = 15.0  // ~4–5km view — allows twice the current zoom-out
                    maxZoomLevel = 19.0
                    controller.setZoom(16.0)

                    // Scale tiles for retina/high-DPI screens (crisp text & details)
                    val density = ctx.resources.displayMetrics.density
                    setTilesScaleFactor(density.coerceIn(1.0f, 2.0f))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    mapView.value = this
                    onResume()

                    youBitmap = createYouBitmap(ctx, 28)
                    partnerBitmap = createPartnerBitmap(ctx, 24, partnerRole)

                    // ── Polyline background (glow) ──
                    polylineBg = Polyline().apply {
                        outlinePaint.color = Color.argb(60, 0, 200, 100)
                        outlinePaint.strokeWidth = 14f
                        outlinePaint.isAntiAlias = true
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                    }
                    overlays.add(polylineBg)

                    // ── Polyline foreground ──
                    polylineFg = Polyline().apply {
                        outlinePaint.color = Color.parseColor("#FF22DD66")
                        outlinePaint.strokeWidth = 6f
                        outlinePaint.isAntiAlias = true
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                    }
                    overlays.add(polylineFg)

                    // ── You marker (at GPS position, rotates with bearing) ──
                    youMarker = Marker(this).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = "You"
                        setFlat(true)
                    }
                    overlays.add(youMarker)

                    // ── Partner marker ──
                    partnerMarker = Marker(this).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = partnerRole
                    }
                    overlays.add(partnerMarker)

                    // ── Detect user pan to disengage follow-me ──
                    var lastTouchX = 0f
                    var lastTouchY = 0f
                    setOnTouchListener { _, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                lastTouchX = event.x
                                lastTouchY = event.y
                                false
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (event.pointerCount == 1 && followMe) {
                                    val dx = event.x - lastTouchX
                                    val dy = event.y - lastTouchY
                                    if (dx * dx + dy * dy > 400f) {
                                        followMe = false
                                    }
                                }
                                false
                            }
                            else -> false
                        }
                    }
                }
            },
            update = { ctx ->
                val mv = mapView.value
                var dirty = false

                // ── Update own marker at GPS position (rotates with bearing) ──
                if (ownLat != null && ownLng != null) {
                    youMarker?.apply {
                        position = GeoPoint(ownLat, ownLng)
                        isEnabled = true
                        icon = youBitmap?.let { BitmapDrawable(ctx.resources, it) }
                        rotation = -(ownBearing ?: 0f)
                    }
                } else {
                    youMarker?.isEnabled = false
                }

                // ── Rotate map only in follow-me mode — panning freely disengages it ──
                if (followMe) {
                    val orient = mapOrientation.value
                    if (abs(orient - prevOrientation) > 0.5f && mv != null) {
                        mv.setMapOrientation(orient)
                        prevOrientation = orient
                        dirty = true
                    }
                }

                // ── Center on own location (follow-me enabled by default) ──
                if (ownLat != null && ownLng != null && mv != null) {
                    if (ownLat != prevOwnLat || ownLng != prevOwnLng) {
                        prevOwnLat = ownLat
                        prevOwnLng = ownLng
                        if (followMe) {
                            mv.controller.setCenter(GeoPoint(ownLat, ownLng))
                            dirty = true
                        }
                    }
                }

                // ── Update partner marker only when position changes ──
                if (partnerLat != null && partnerLng != null) {
                    if (partnerLat != prevPartnerLat || partnerLng != prevPartnerLng) {
                        partnerMarker?.apply {
                            position = GeoPoint(partnerLat, partnerLng)
                            isEnabled = true
                            title = partnerRole
                            icon = partnerBitmap?.let { BitmapDrawable(ctx.resources, it) }
                        }
                        prevPartnerLat = partnerLat
                        prevPartnerLng = partnerLng
                        dirty = true
                    }
                } else {
                    if (prevPartnerLat.isNaN().not()) {
                        partnerMarker?.isEnabled = false
                        prevPartnerLat = Double.NaN
                        prevPartnerLng = Double.NaN
                        dirty = true
                    }
                }

                // ── Update polylines only when route or positions change ──
                val haveBoth = ownLat != null && ownLng != null && partnerLat != null && partnerLng != null
                val routeKey = if (haveBoth) {
                    "${ownLat},${ownLng},${partnerLat},${partnerLng},${routeLatLngs?.size}"
                } else ""

                if (routeKey != prevRouteKey && mv != null) {
                    prevRouteKey = routeKey
                    if (haveBoth) {
                        val oLat = ownLat!!
                        val oLng = ownLng!!
                        val pLat = partnerLat!!
                        val pLng = partnerLng!!
                        val points = if (routeLatLngs != null && routeLatLngs.size >= 2) {
                            routeLatLngs.map { GeoPoint(it[0], it[1]) }
                        } else {
                            listOf(GeoPoint(oLat, oLng), GeoPoint(pLat, pLng))
                        }
                        polylineBg?.apply { setPoints(points); isVisible = true }
                        polylineFg?.apply { setPoints(points); isVisible = true }

                        // Zoom to fit both — only once, never again (avoids fighting user pans)
                        if (!bboxDone) {
                            bboxDone = true
                            val north = maxOf(oLat, pLat)
                            val south = minOf(oLat, pLat)
                            val east = maxOf(oLng, pLng)
                            val west = minOf(oLng, pLng)
                            mv.zoomToBoundingBox(
                                org.osmdroid.util.BoundingBox(
                                    north + 0.001, east + 0.001,
                                    south - 0.001, west - 0.001
                                ),
                                true, 80
                            )
                        }
                    } else {
                        polylineBg?.isVisible = false
                        polylineFg?.isVisible = false
                        bboxDone = false
                    }
                    dirty = true
                }

                if (dirty) mv?.invalidate()
            }
        )

        // ── Distance box at bottom-center ──
        if (distanceToTarget != null) {
            val distText = if (distanceToTarget < 1000) {
                "%.0f m".format(distanceToTarget)
            } else {
                "%.2f km".format(distanceToTarget / 1000)
            }
            Text(
                text = distText,
                color = ComposeColor.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .background(
                        color = ComposeColor(0xCC000000),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }

        // ── Zoom + follow-me controls (bottom-right) ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Center-me / follow-me button (top)
            FloatingActionButton(
                onClick = {
                    followMe = !followMe
                    if (followMe) {
                        // Snap map rotation to current bearing immediately
                        val bearing = -(ownBearing ?: 0f)
                        prevOrientation = bearing
                        scope.launch { mapOrientation.snapTo(bearing) }
                        mapView.value?.setMapOrientation(bearing)
                        // Re-center on own position
                        if (ownLat != null && ownLng != null) {
                            mapView.value?.controller?.animateTo(GeoPoint(ownLat, ownLng))
                        }
                    }
                },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                containerColor = ComposeColor(0xCCFFFFFF),
                contentColor = ComposeColor(0xFF333333),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
            ) {
                // Circle-dot icon (accent color when engaged, gray when idle)
                val accent = ComposeColor(0xFF2979FF)
                val gray = ComposeColor(0xFF888888)
                val ringColor = if (followMe) accent else gray
                val dotColor = if (followMe) accent else gray
                Box(
                    modifier = Modifier.size(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer ring
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(ringColor, CircleShape)
                            .padding(2.dp)
                            .background(ComposeColor.White, CircleShape)
                    )
                    // Inner dot
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(dotColor, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom in
            FloatingActionButton(
                onClick = { mapView.value?.controller?.zoomIn() },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                containerColor = ComposeColor(0xCCFFFFFF),
                contentColor = ComposeColor(0xFF333333),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
            ) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom out
            FloatingActionButton(
                onClick = { mapView.value?.controller?.zoomOut() },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                containerColor = ComposeColor(0xCCFFFFFF),
                contentColor = ComposeColor(0xFF333333),
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
            ) {
                Text("−", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Centered loading spinner
        if (!mapReady) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.12f),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp
            )
        }
    }
}

// ── Bitmap helpers (own marker, partner marker) ──────────────────────────────

/** Blue circle with white direction arrow for "You" marker (rotated by heading). */
private fun createYouBitmap(context: android.content.Context, sizeDp: Int): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (sizeDp * density).toInt().coerceAtLeast(20)
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f

    // Outer blue circle
    val circlePaint = Paint().apply {
        color = Color.parseColor("#FF2979FF")
        isAntiAlias = true
    }
    canvas.drawCircle(cx, cy, size / 2f, circlePaint)

    // White border
    val borderPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    canvas.drawCircle(cx, cy, size / 2f - 1.5f * density, borderPaint)

    // White direction arrow (chevron pointing up)
    val arrowPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    val path = Path().apply {
        val s = size * 0.22f
        moveTo(cx, cy - s * 0.5f)                  // top tip
        lineTo(cx + s * 0.6f, cy + s * 0.4f)       // bottom-right
        lineTo(cx, cy + s * 0.1f)                   // center indent
        lineTo(cx - s * 0.6f, cy + s * 0.4f)       // bottom-left
        close()
    }
    canvas.drawPath(path, arrowPaint)
    return bitmap
}

private fun createPartnerBitmap(context: android.content.Context, sizeDp: Int, role: String): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (sizeDp * density).toInt().coerceAtLeast(20)
    val bitmap = android.graphics.Bitmap.createBitmap(size, (size * 1.3f).toInt(), android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val circleR = size / 2f

    val pinColor = if (role == "Polo") Color.parseColor("#FF00BFA5") else Color.parseColor("#FFE040E0")

    val fillPaint = Paint().apply {
        color = pinColor
        isAntiAlias = true
    }
    canvas.drawCircle(cx, circleR, circleR - 1f * density, fillPaint)

    val borderPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    canvas.drawCircle(cx, circleR, circleR - 1.5f * density, borderPaint)

    val letter = role.first().toString()
    val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = circleR * 1.1f
        isFakeBoldText = true
    }
    val yOffset = (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(letter, cx, circleR - yOffset, textPaint)
    return bitmap
}


