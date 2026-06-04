package com.marcopolo.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.marcopolo.network.RouteStep
import com.marcopolo.util.hapticClick
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
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



// ── Navigation instruction model ─────────────────────────────────────────────

/** Walking guidance: either turn-by-turn step or cardinal direction + distance. */
private data class NavInstruction(
    val arrow: String,      // "←", "↗", "↑", "📍", etc.
    val primary: String,    // "Turn left onto Main St" or "NE"
    val distance: String    // "50 m" or "0.4 km"
)

/** ── Step-finding engine ────────────────────────────────────────────── */

private const val OFF_ROUTE_THRESHOLD_M = 60.0  // fallback to cardinal beyond this
private const val STEP_ADVANCE_MARGIN_M = 8.0   // next step must be this much closer (avoids premature advance at boundaries)

/** Find which route step the user is closest to.
 *  Returns null if user is too far from route (off-route fallback).
 *
 *  Uses hysteresis at step boundaries: prefers current/earlier step when
 *  distances are within GPS noise margin (STEP_ADVANCE_MARGIN_M). Without this,
 *  GPS jitter (±8m typical) makes the next step's geometry appear closer,
 *  causing the instruction to show the next street name (Polo's street)
 *  before the user has progressed past the corner. */
private fun findCurrentStep(
    userLat: Double, userLng: Double,
    steps: List<RouteStep>
): Int? {
    if (steps.isEmpty()) return null
    if (steps.size == 1) return 0

    var bestIdx = 0
    var bestDist = Double.MAX_VALUE

    // Phase 1: Find geometrically closest step
    for (i in steps.indices) {
        val dist = pointToPolylineDistance(userLat, userLng, steps[i].geometry)
        if (dist < bestDist) {
            bestDist = dist
            bestIdx = i
        }
    }

    // Off-route: return null so caller falls back to cardinal direction
    if (bestDist > OFF_ROUTE_THRESHOLD_M) return null

    // Phase 2: Hysteresis — prefer earlier step when next step isn't clearly closer.
    // GPS noise (±8m) can make a later step appear closer even when the user
    // hasn't actually reached that segment. This backward check combined with
    // the forward advancement below creates a natural hysteresis band.
    if (bestIdx > 0) {
        val prevDist = pointToPolylineDistance(userLat, userLng, steps[bestIdx - 1].geometry)
        if (prevDist < bestDist + STEP_ADVANCE_MARGIN_M) {
            bestDist = prevDist
            bestIdx -= 1
        }
    }

    // Phase 3: Advance to next step when significantly closer
    if (bestIdx < steps.size - 1) {
        val nextDist = pointToPolylineDistance(userLat, userLng, steps[bestIdx + 1].geometry)
        if (nextDist + STEP_ADVANCE_MARGIN_M < bestDist) bestIdx = bestIdx + 1
    }

    return bestIdx
}

/** Minimum distance from (px,py) to the polyline (list of [lat, lng] pairs). */
private fun pointToPolylineDistance(px: Double, py: Double, polyline: List<List<Double>>): Double {
    var minDist = Double.MAX_VALUE
    for (i in 0 until polyline.size - 1) {
        val ax = polyline[i][0]; val ay = polyline[i][1]  // [lat, lng]
        val bx = polyline[i + 1][0]; val by = polyline[i + 1][1]
        val dist = pointToSegmentDistance(px, py, ax, ay, bx, by)
        if (dist < minDist) minDist = dist
    }
    return minDist
}

/** Minimum distance from point to line segment AB. */
private fun pointToSegmentDistance(
    px: Double, py: Double,
    ax: Double, ay: Double,
    bx: Double, by: Double
): Double {
    val dx = bx - ax; val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0.0) {
        val ex = px - ax; val ey = py - ay
        return sqrt(ex * ex + ey * ey)
    }
    val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
    val nx = ax + t * dx; val ny = ay + t * dy
    val ex = px - nx; val ey = py - ny
    return sqrt(ex * ex + ey * ey)
}

/** Map OSRM maneuver modifier to a visual arrow. */
private fun arrowForStep(modifier: String?): String = when (modifier) {
    "left" -> "←"; "right" -> "→"
    "sharp left" -> "↰"; "sharp right" -> "↱"
    "slight left" -> "↖"; "slight right" -> "↗"
    "straight", null -> "↑"; "uturn" -> "↓"
    else -> "↑"
}

/** Compute polygon points approximating a circle of [radiusMeters] centered at [lat],[lng]. */
private fun circlePoints(lat: Double, lng: Double, radiusMeters: Double, numPoints: Int = 48): List<GeoPoint> {
    val latRad = Math.toRadians(lat)
    val metersPerDegLat = 111320.0
    val metersPerDegLng = 111320.0 * Math.cos(latRad)
    return (0 until numPoints).map { i ->
        val angle = 2.0 * Math.PI * i / numPoints
        val dLat = radiusMeters * Math.cos(angle) / metersPerDegLat
        val dLng = radiusMeters * Math.sin(angle) / metersPerDegLng
        GeoPoint(lat + dLat, lng + dLng)
    }
}

/** Distance in compact format. */
private fun formatDistance(meters: Double): String =
    if (meters < 1000.0) "${meters.toInt()} m" else "%.1f km".format(meters / 1000.0)

/** Compute walking guidance: either turn-by-turn (when on-route with steps)
 *  or relative-to-heading direction fallback (when off-route or steps unavailable).
 *  The arrow shows direction relative to the user's compass heading — ↑ means
 *  "walk forward (the way you're facing)", → means "turn right", etc. */
private fun computeNavInstruction(
    ownLat: Double?, ownLng: Double?,
    partnerLat: Double?, partnerLng: Double?,
    ownBearing: Float?,
    distanceM: Double?,
    routeSteps: List<RouteStep>
): NavInstruction? {
    val olat = ownLat ?: return null
    val olng = ownLng ?: return null
    val plat = partnerLat ?: return null
    val plng = partnerLng ?: return null
    val dist = distanceM ?: return null

    // Already found — no guidance needed
    if (dist <= 15f) return null

    // ── Always compute relative direction from device heading ──
    val from = android.location.Location("").also { it.latitude = olat; it.longitude = olng }
    val to   = android.location.Location("").also { it.latitude = plat; it.longitude = plng }
    val absBearing = from.bearingTo(to).let { if (it < 0f) it + 360f else it }
    val userFacing = ownBearing ?: 0f
    val relative = ((absBearing - userFacing) % 360f + 360f) % 360f
    val relativeArrows = arrayOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
    val relativeLabels = arrayOf("Straight", "Right ahead", "Right", "Right back", "Back", "Left back", "Left", "Left ahead")
    val idx = ((relative + 22.5f) % 360f / 45f).toInt()

    // ── Turn-by-turn from route steps (when on-route) ──
    // Arrow always shows relative direction from current heading;
    // primary text keeps the step instruction for context.
    if (routeSteps.isNotEmpty()) {
        val stepIdx = findCurrentStep(olat, olng, routeSteps)
        if (stepIdx != null) {
            val step = routeSteps[stepIdx]
            // Last step = "Arrive" / reaching destination
            if (stepIdx >= routeSteps.size - 1 && dist < 50.0) {
                val destArrow = if (routeSteps.size > 1) "📍" else relativeArrows[idx]
                return NavInstruction(destArrow, "Arrive", formatDistance(dist))
            }
            val instruction = step.instruction
            val stepDist = formatDistance(step.distance)
            return NavInstruction(relativeArrows[idx], instruction, stepDist)
        }
    }

    // ── Fallback: direction relative to user's facing ──
    return NavInstruction(relativeArrows[idx], relativeLabels[idx], formatDistance(dist))
}

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
    routeSteps: List<RouteStep> = emptyList(),
    distanceToTarget: Double? = null,   // straight-line meters to partner
    compassAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM,
    gpsAccuracy: Float? = null,         // meters, from location.accuracy
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
    // GPS accuracy polygon (approximates a circle on the map)
    var accuracyPolygon by remember { mutableStateOf<Polygon?>(null) }
    var prevGpsAccuracy by remember { mutableFloatStateOf(java.lang.Float.NaN) }
    var prevCompassAccuracy by remember { mutableIntStateOf(-1) }

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


    // ── Smooth map orientation via animateFloatAsState ──
    // Round bearing to nearest 5° to damp compass noise while keeping
    // rotation responsive during line-follow (~5-8° change at normal walking turn rate).
    // animateFloatAsState handles mid-animation retargeting gracefully — when
    // target value changes mid-animation it smoothly transitions from the current
    // (partially-animated) value to the new target, unlike LaunchedEffect+Animatable
    // which restarts from scratch on each change, causing the "chase" oscillation
    // that resulted in ~1s uncontrolled rotation then revert.
    //
    // Exponential moving average on the raw compass bearing. Strong filtering
    // (alpha=0.15) so brief sensor glitches (e.g., 0° spike during fast rotation)
    // only move the smoothed value by 15% of the error per frame — producing at
    // most one 5° orientation step instead of a full 45° snap to north.
    var smoothBearing by remember { mutableFloatStateOf(0f) }
    var hasSmoothBearing by remember { mutableStateOf(false) }
    // Synchronous EMA — no LaunchedEffect cancel/restart overhead per sensor frame.
    // Runs during composition whenever ownBearing or compassAccuracy changes.
    remember(ownBearing, compassAccuracy) {
        val b = ownBearing ?: return@remember
        val accuracyOk = compassAccuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        if (!hasSmoothBearing) {
            smoothBearing = b
            hasSmoothBearing = true
        } else if (accuracyOk) {
            // Shortest-angle diff, handling 0/360 wrap
            var diff = b - smoothBearing
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f
            smoothBearing = ((smoothBearing + diff * 0.15f) % 360f + 360f) % 360f
        }
        // else: compass LOW/UNRELIABLE → skip update → smoothBearing frozen.
        // When accuracy returns to MEDIUM+, EMA gradually catches up to real bearing.
    }
    // Continuous target orientation (de-cycled, no 0/360 wrap).
    // Target rounds to 5° then wraps to [-360, 0]. Without de-cycling,
    // crossing 359°→1° produces raw target 0→-360 → animateFloatAsState
    // animates +360° difference → visible full-screen spin.
    // Fix: maintain continuous numeric value via shortest-angle diffs.
    var targetOrientation by remember { mutableFloatStateOf(0f) }
    var prevRawTarget by remember { mutableFloatStateOf(0f) }
    remember(smoothBearing) {
        val raw = -(Math.round(smoothBearing / 5f) * 5f)
        val d = raw - prevRawTarget
        val diff = when {
            d > 180f -> d - 360f
            d < -180f -> d + 360f
            else -> d
        }
        targetOrientation += diff
        prevRawTarget = raw
    }
    // Delay orientation updates until map tiles settle (~2s).
    // Prevents startup CPU burst: compass + map init + tile loading
    // all competing on Honor 20's Mali-G76.
    var orientationReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2000L)
        orientationReady = true
    }
    val mapOrientation by animateFloatAsState(
        targetValue = if (orientationReady) targetOrientation else 0f,
        animationSpec = tween(durationMillis = 300, easing = LinearEasing)
    )

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

                    youBitmap = createYouBeamBitmap(ctx, SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM)
                    partnerBitmap = createPartnerBitmap(ctx, partnerRole)
                    youDrawable = youBitmap?.let { BitmapDrawable(ctx.resources, it) }
                    partnerDrawable = partnerBitmap?.let { BitmapDrawable(ctx.resources, it) }

                    // ── GPS accuracy polygon (circle approximation on map) ──
                    accuracyPolygon = Polygon().apply {
                        isVisible = false
                        // Translucent light blue fill
                        fillPaint.color = Color.argb(30, 41, 121, 255)
                        // Thin border
                        outlinePaint.color = Color.argb(60, 41, 121, 255)
                        outlinePaint.strokeWidth = 2f
                        outlinePaint.isAntiAlias = true
                    }
                    overlays.add(0, accuracyPolygon)  // add at index 0 so it's behind everything

                    // ── Polyline background (glow) ──
                    polylineBg = Polyline().apply {
                        outlinePaint.color = Color.argb(60, 0, 200, 100)
                        outlinePaint.strokeWidth = 22f
                        outlinePaint.isAntiAlias = true
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                    }
                    overlays.add(polylineBg)

                    // ── Polyline foreground ──
                    polylineFg = Polyline().apply {
                        outlinePaint.color = Color.parseColor("#FF22DD66")
                        outlinePaint.strokeWidth = 10f
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
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        title = partnerRole
                        setFlat(true)
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
                    // Normalize continuous target to [0, 360) for osmdroid.
                    // Continuous value avoids 0/360 animation spin; modulo here
                    // strips the accumulated cycles.
                    val orient = mapOrientation % 360f
                    val normOrient = if (orient < 0) orient + 360f else orient
                    if (abs(normOrient - prevOrientation) > 1.5f && mv != null) {
                        mv.setMapOrientation(normOrient)
                        prevOrientation = normOrient
                        // Don't set dirty — setMapOrientation triggers internal redraw,
                        // avoid redundant invalidate storm during rotation
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

                // ── Update GPS accuracy polygon + beam bitmap on accuracy change ──
                if (ownLat != null && ownLng != null) {
                    val gpsAcc = gpsAccuracy ?: 0f
                    val compAcc = compassAccuracy
                    val gpsChanged = gpsAcc != prevGpsAccuracy
                    val compChanged = compAcc != prevCompassAccuracy
                    if (gpsChanged || compChanged) {
                        // Update accuracy polygon (circle approximation on map)
                        val poly = accuracyPolygon
                        if (poly != null && gpsAcc > 0f) {
                            poly.points = circlePoints(ownLat, ownLng, gpsAcc.toDouble())
                            poly.isVisible = true
                        } else {
                            poly?.isVisible = false
                        }
                        prevGpsAccuracy = gpsAcc
                        // Regenerate beam bitmap when compass accuracy changes
                        if (compChanged) {
                            val mv = mapView.value
                            if (mv != null && mv.context != null) {
                                val newBitmap = createYouBeamBitmap(mv.context, compAcc)
                                youBitmap = newBitmap
                                youDrawable = newBitmap.let { BitmapDrawable(mv.context.resources, it) }
                                youMarker?.icon = youDrawable
                                prevCompassAccuracy = compAcc
                            }
                        }
                        dirty = true
                    }
                } else {
                    accuracyPolygon?.isVisible = false
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

        // ── Navigation instruction card (bottom-center overlay) ──
        // 3 lines: big arrow, "primary · distance", "Total: xxx"
        // Width-constrained to leave room for right-side zoom/control buttons.
        val navInstr = remember(ownLat, ownLng, partnerLat, partnerLng, ownBearing, distanceToTarget, routeSteps) {
            computeNavInstruction(ownLat, ownLng, partnerLat, partnerLng, ownBearing, distanceToTarget, routeSteps)
        }
        val config = LocalConfiguration.current
        val navCardMaxWidth = (config.screenWidthDp -






                0).dp.coerceAtLeast(120.dp)
        navInstr?.let { nav ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = navCardMaxWidth)
                    .background(ComposeColor(0xDD000000), RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Line 1: Big navigation arrow
                    Text(
                        nav.arrow,
                        fontSize = 28.sp,
                        color = ComposeColor.White,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // Line 2: Street/instruction (wraps if long, never cuts off distance)
                    Text(
                        nav.primary,
                        fontSize = 16.sp,
                        color = ComposeColor(0xFF88FF88),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    // Line 3: Step distance (always fully visible, independent line)
                    Text(
                        nav.distance,
                        fontSize = 16.sp,
                        color = ComposeColor(0xFF88FF88),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    // Line 4: Total distance
                    if (distanceToTarget != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Total: ${formatDistance(distanceToTarget)}",
                            fontSize = 14.sp,
                            color = ComposeColor(0xFF88FF88),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // ── Zoom + follow-me controls (bottom-right) ──
        val onCheckmark = hapticClick { onCheckmarkClick?.invoke() }
        val onFollowMe = hapticClick {
            followMe = !followMe
            if (followMe) {
                val bearing = -(ownBearing ?: 0f)
                prevOrientation = bearing
                mapView.value?.setMapOrientation(bearing)
                if (ownLat != null && ownLng != null) {
                    mapView.value?.controller?.animateTo(GeoPoint(ownLat, ownLng))
                }
            }
        }
        val onZoomIn = hapticClick { mapView.value?.controller?.zoomIn() }
        val onZoomOut = hapticClick { mapView.value?.controller?.zoomOut() }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Green checkmark — manual found when partner ≤30m
            if (showCheckmark) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(ComposeColor(0xFF4CAF50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onCheckmark() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Mark as found",
                        tint = ComposeColor.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Follow-me button — green accent when engaged
            val folGreen = ComposeColor(0xFF88FF88)
            val folDim = ComposeColor(0xFF888888)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xDD000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onFollowMe() },
                contentAlignment = Alignment.Center
            ) {
                // Circle-dot icon (green when engaged, gray when idle)
                val folColor = if (followMe) folGreen else folDim
                Box(
                    modifier = Modifier.size(22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(folColor, CircleShape)
                            .padding(3.dp)
                            .background(ComposeColor(0xDD000000), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(folColor, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom in
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xDD000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onZoomIn() },
                contentAlignment = Alignment.Center
            ) {
                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ComposeColor(0xFF88FF88))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Zoom out
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ComposeColor(0xDD000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onZoomOut() },
                contentAlignment = Alignment.Center
            ) {
                Text("−", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ComposeColor(0xFF88FF88))
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

/**
 * Google Maps-style blue dot with conical beam.
 * - Beam is a pie-slice sector pointing "up" in the bitmap;
 *   marker rotation handles alignment with bearing direction.
 * - Beam angle (width) varies with compass accuracy — narrow = well-calibrated.
 * - Blue dot with white border drawn on top, covering the beam origin.
 * - GPS accuracy circle is a separate osmdroid Polygon overlay (not drawn here).
 */
private fun createYouBeamBitmap(context: android.content.Context, compassAccuracy: Int): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val dotR = (14 * density).coerceAtLeast(10f)                     // dot radius
    val beamR = dotR * 2.5f                                          // beam extends 2.5x dot radius
    val totalSize = (beamR * 2 + 4 * density).toInt().coerceAtLeast(40)
    val bitmap = android.graphics.Bitmap.createBitmap(totalSize, totalSize, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = totalSize / 2f
    val cy = totalSize / 2f

    // ── Beam angle based on compass accuracy ──
    // Narrow beam = well-calibrated, wide beam = needs calibration
    val beamHalfAngle = when (compassAccuracy) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 18f
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 35f
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> 55f
        SensorManager.SENSOR_STATUS_UNRELIABLE -> 80f
        else -> 35f
    }
    // Primary beam — semi-transparent blue sector
    val beamPaint = Paint().apply {
        color = Color.argb(90, 41, 121, 255)
        isAntiAlias = true
    }
    val beamRect = RectF(cx - beamR, cy - beamR, cx + beamR, cy + beamR)
    canvas.drawArc(beamRect, 270f - beamHalfAngle, beamHalfAngle * 2f, true, beamPaint)

    // Secondary glow — wider, fainter layer for soft edge
    val glowPaint = Paint().apply {
        color = Color.argb(35, 60, 140, 255)
        isAntiAlias = true
    }
    canvas.drawArc(beamRect, 270f - beamHalfAngle * 1.3f, beamHalfAngle * 2.6f, true, glowPaint)

    // ── Blue dot ──
    val circlePaint = Paint().apply {
        color = Color.parseColor("#FF2979FF")
        isAntiAlias = true
    }
    canvas.drawCircle(cx, cy, dotR, circlePaint)

    // ── White border ring ──
    val borderPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    canvas.drawCircle(cx, cy, dotR - 1.5f * density, borderPaint)

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


