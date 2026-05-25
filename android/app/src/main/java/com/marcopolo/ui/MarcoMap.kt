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
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.marcopolo.util.hapticClick
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs
import kotlin.math.sqrt

// Detailed tile source with building outlines, street names, and POI labels.
// CartoDB Voyager — free for small-scale use with attribution.
private val HIGH_QUALITY_TILES: ITileSource = XYTileSource(
    "CartoDB-Voyager", 3, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
        "https://c.basemaps.cartocdn.com/rastertiles/voyager/"
    )
)


// Re-zoom when bounding box diagonal changes by > ~30m (in degrees)
private const val MIN_BBOX_CHANGE = 0.0003

/**
 * osmdroid MapView wrapped as a Compose component.
 * Shows own + partner markers, route polyline, zoom controls,
 * and a follow-me toggle. The map rotates smoothly around the
 * own GPS position.
 */
@Suppress("ClickableViewAccessibility")
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
    distanceToTarget: Double? = null,   // straight-line meters to partner
    showCheckmark: Boolean = false,     // green ✓ button when partner ≤30m
    onCheckmarkClick: (() -> Unit)? = null
) {
    val mapView = remember { mutableStateOf<MapView?>(null) }
    var youMarker by remember { mutableStateOf<Marker?>(null) }
    var partnerMarker by remember { mutableStateOf<Marker?>(null) }
    var polylineBg by remember { mutableStateOf<Polyline?>(null) }   // glow
    var polylineFg by remember { mutableStateOf<Polyline?>(null) }   // bright line

    var youBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var partnerBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // Cached drawables — avoid BitmapDrawable allocation on every update lambda run
    var youDrawable by remember { mutableStateOf<BitmapDrawable?>(null) }
    var partnerDrawable by remember { mutableStateOf<BitmapDrawable?>(null) }

    // ── Route path fade-in/out alpha (0 → 1 over 600ms) ──
    // Animates when partner reveal state changes; stays at 1 when both locations known.
    val routeAlpha by animateFloatAsState(
        targetValue = if (partnerLat != null && ownLat != null) 1f else 0f,
        animationSpec = tween(600)
    )

    // ── Change-detection state (suppress redundant map operations) ──
    var prevOwnLat by remember { mutableDoubleStateOf(Double.NaN) }
    var prevOwnLng by remember { mutableDoubleStateOf(Double.NaN) }
    var prevOwnBearing by remember { mutableFloatStateOf(java.lang.Float.NaN) }
    var prevPartnerLat by remember { mutableDoubleStateOf(Double.NaN) }
    var prevPartnerLng by remember { mutableDoubleStateOf(Double.NaN) }
    var prevOrientation by remember { mutableFloatStateOf(-1f) }
    var prevRouteKey by remember { mutableStateOf("") }
    var bboxDone by remember { mutableStateOf(false) }
    // Dynamic bbox re-zoom — re-adjust when follow-me is on and distance changes significantly
    var prevBboxDiagonal by remember { mutableDoubleStateOf(0.0) }
    // Trigger to zoom from a LaunchedEffect (post-layout) instead of from the update lambda
    var bboxZoomTrigger by remember { mutableIntStateOf(0) }

    // ── Follow-me toggle: auto-center on own position ──
    var followMe by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // ── Smooth map orientation animation ──
    val mapOrientation = remember { Animatable(0f) }
    // Round bearing to nearest 5° to damp compass noise — reduces
    // LaunchedEffect restarts from ~16Hz to ~1-2Hz on foot.
    val targetOrientation = remember(ownBearing) {
        val b = ownBearing ?: 0f
        -(Math.round(b / 5f) * 5f)
    }
    // Delay orientation animation until map tiles settle (~2s).
    // Prevents startup CPU burst: compass + map init + tile loading
    // all competing on Honor 20's Mali-G76.
    var orientationSettled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2000L)
        orientationSettled = true
    }
    LaunchedEffect(targetOrientation, orientationSettled) {
        if (orientationSettled) {
            mapOrientation.animateTo(
                targetOrientation,
                animationSpec = tween(durationMillis = 350, easing = LinearEasing)
            )
        } else {
            // Before settling, snap to latest bearing (no animation surge)
            mapOrientation.snapTo(targetOrientation)
        }
    }

    // Show spinner until map tiles start loading
    var mapReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(800L)
        mapReady = true
    }

    // ── Zoom-to-fit: runs AFTER layout (not in update lambda) so MapView has proper dimensions ──
    var scrollableAreaSet by remember { mutableStateOf(false) }
    LaunchedEffect(bboxZoomTrigger) {
        if (bboxZoomTrigger > 0) {
            val mv = mapView.value ?: return@LaunchedEffect
            // Skip if MapView hasn't been laid out yet — prevents
            // invalid zoom calculation on slow devices (Honor 20).
            if (mv.width <= 0 || mv.height <= 0) return@LaunchedEffect
            val oLat = ownLat ?: return@LaunchedEffect
            val oLng = ownLng ?: return@LaunchedEffect
            val pLat = partnerLat ?: return@LaunchedEffect
            val pLng = partnerLng ?: return@LaunchedEffect
            // Constrain viewport on first zoom so osmdroid doesn't load tiles
            // for the whole planet — only the meeting area matters.
            if (!scrollableAreaSet) {
                scrollableAreaSet = true
                val margin = 0.5  // ~55km padding — room to pan
                mv.setScrollableAreaLimitDouble(
                    org.osmdroid.util.BoundingBox(
                        maxOf(oLat, pLat) + margin,
                        maxOf(oLng, pLng) + margin,
                        minOf(oLat, pLat) - margin,
                        minOf(oLng, pLng) - margin
                    )
                )
            }
            val hasRoute = routeLatLngs != null && routeLatLngs.size >= 2
            val pad = if (hasRoute) 0.0003 else 0.001
            val north = maxOf(oLat, pLat)
            val south = minOf(oLat, pLat)
            val east = maxOf(oLng, pLng)
            val west = minOf(oLng, pLng)
            try {
                // Non-animated zoom — animated zoom on osmdroid can crash
                // on Mali-G76 when the view is still initializing.
                mv.zoomToBoundingBox(
                    org.osmdroid.util.BoundingBox(
                        north + pad, east + pad,
                        south - pad, west - pad
                    ),
                    false, 0
                )
            } catch (_: Exception) {
                // Non-critical — route polyline still visible at default zoom.
                // Catch to prevent crash on slow GPU / initializing MapView.
            }
        }
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
                    // Force software rendering to avoid Mali GPU driver hangs
                    // on Honor 20 / other devices with GPU issues in Canvas 2D rendering.
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    minZoomLevel = 15.0  // ~4–5km view — gentle starting zoom
                    maxZoomLevel = 19.0  // matching tile source (CartoDB Voyager max 19)
                    controller.setZoom(16.0)  // moderate initial zoom, smoothed by bbox animation when data arrives

                    // Scale tiles for retina/high-DPI screens (crisp text & details)
                    val density = ctx.resources.displayMetrics.density
                    setTilesScaleFactor(density.coerceIn(1.0f, 2.0f))
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    mapView.value = this
                    onResume()

                    youBitmap = createYouBitmap(ctx)
                    partnerBitmap = createPartnerBitmap(ctx, partnerRole)
                    youDrawable = youBitmap?.let { BitmapDrawable(ctx.resources, it) }
                    partnerDrawable = partnerBitmap?.let { BitmapDrawable(ctx.resources, it) }

                    // ── Polyline background (glow) ──
                    polylineBg = Polyline().apply {
                        outlinePaint.color = Color.argb(60, 0, 200, 100)
                        outlinePaint.strokeWidth = 22f
                        outlinePaint.isAntiAlias = true
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.BEVEL
                    }
                    overlays.add(polylineBg)

                    // ── Polyline foreground ──
                    polylineFg = Polyline().apply {
                        outlinePaint.color = Color.parseColor("#FF22DD66")
                        outlinePaint.strokeWidth = 10f
                        outlinePaint.isAntiAlias = true
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.BEVEL
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
            update = { _ ->
                val mv = mapView.value
                var dirty = false

                // ── Update own marker at GPS position (rotates with bearing) ──
                if (ownLat != null && ownLng != null) {
                    val posChanged = ownLat != prevOwnLat || ownLng != prevOwnLng
                    val bearing = ownBearing ?: 0f
                    val bearingChanged = bearing != prevOwnBearing
                    if (posChanged || bearingChanged) {
                        youMarker?.apply {
                            position = GeoPoint(ownLat, ownLng)
                            isEnabled = true
                            icon = youDrawable
                            rotation = -bearing
                        }
                        prevOwnLat = ownLat
                        prevOwnLng = ownLng
                        prevOwnBearing = bearing
                        dirty = true
                        if (posChanged && followMe && mv != null) {
                            mv.controller.setCenter(GeoPoint(ownLat, ownLng))
                        }
                    }
                } else {
                    if (prevOwnLat.isNaN().not()) {
                        youMarker?.isEnabled = false
                        prevOwnLat = Double.NaN
                        prevOwnLng = Double.NaN
                        prevOwnBearing = java.lang.Float.NaN
                        dirty = true
                    }
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

                // ── Update partner marker only when position changes ──
                if (partnerLat != null && partnerLng != null) {
                    if (partnerLat != prevPartnerLat || partnerLng != prevPartnerLng) {
                        partnerMarker?.apply {
                            position = GeoPoint(partnerLat, partnerLng)
                            isEnabled = true
                            title = partnerRole
                            icon = partnerDrawable
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
                    dirty = true
                    if (haveBoth) {
                        val oLat = ownLat
                        val oLng = ownLng
                        val pLat = partnerLat
                        val pLng = partnerLng
                        val points = if (routeLatLngs != null && routeLatLngs.size >= 2) {
                            routeLatLngs.map { GeoPoint(it[0], it[1]) }
                        } else {
                            listOf(GeoPoint(oLat, oLng), GeoPoint(pLat, pLng))
                        }
                        polylineBg?.apply { setPoints(points); isVisible = true }
                        polylineFg?.apply { setPoints(points); isVisible = true }

                        // Dynamic zoom-to-fit. Re-zooms when follow-me is on and
                        // the bounding box diagonal changes significantly.
                        // Capped by minZoomLevel (15.0) — never zooms out too far.
                        val north = maxOf(oLat, pLat)
                        val south = minOf(oLat, pLat)
                        val east = maxOf(oLng, pLng)
                        val west = minOf(oLng, pLng)
                        val diagLat = north - south
                        val diagLng = east - west
                        val diagonal = sqrt(diagLat * diagLat + diagLng * diagLng)
                        val shouldReZoom = if (followMe) {
                            !bboxDone || abs(diagonal - prevBboxDiagonal) > MIN_BBOX_CHANGE
                        } else {
                            false
                        }
                        if (shouldReZoom) {
                            prevBboxDiagonal = diagonal
                            bboxDone = true
                            bboxZoomTrigger++  // trigger post-layout zoom via LaunchedEffect
                        }
                    } else {
                        polylineBg?.isVisible = false
                        polylineFg?.isVisible = false
                        bboxDone = false
                    }
                }

                // ── Route alpha on every update — smooth fade-in/out ──
                val alphaBg = (routeAlpha * 60).toInt().coerceIn(0, 255)
                val alphaFg = (routeAlpha * 255).toInt().coerceIn(0, 255)
                if (alphaBg != (polylineBg?.outlinePaint?.alpha ?: 0) ||
                    alphaFg != (polylineFg?.outlinePaint?.alpha ?: 0)) {
                    polylineBg?.outlinePaint?.alpha = alphaBg
                    polylineFg?.outlinePaint?.alpha = alphaFg
                    dirty = true
                }

                // Only request redraw if something actually changed
                if (dirty) {
                    try { mv?.invalidate() } catch (_: Exception) {}
                }
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
            // Green checkmark — manual found when partner ≤30m
            if (showCheckmark) {
                FloatingActionButton(
                    onClick = hapticClick { onCheckmarkClick?.invoke() },
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    containerColor = ComposeColor(0xFF4CAF50),
                    contentColor = ComposeColor.White,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Mark as found",
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Center-me / follow-me button
            FloatingActionButton(
                onClick = hapticClick {
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
                onClick = hapticClick { mapView.value?.controller?.zoomIn() },
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
                onClick = hapticClick { mapView.value?.controller?.zoomOut() },
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

private const val MARKER_SIZE_DP = 36

/** Blue circle with white direction arrow for "You" marker (rotated by heading). */
private fun createYouBitmap(context: android.content.Context): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (MARKER_SIZE_DP * density).toInt().coerceAtLeast(20)
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

private fun createPartnerBitmap(context: android.content.Context, role: String): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val size = (MARKER_SIZE_DP * density).toInt().coerceAtLeast(20)
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


