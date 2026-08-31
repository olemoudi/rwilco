package dev.rwilco.ui.editor.sheets

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.rwilco.R
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.theme.LocalDarkTheme
import dev.rwilco.ui.theme.familyColor
import dev.rwilco.ui.theme.hereBlue
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File
import kotlin.math.cos
import kotlin.math.ln

/**
 * OpenStreetMap tiles with one pin and its radius. Long-press moves the pin. The View lives in
 * a wrapper that keeps the enclosing sheet from stealing its drags, and its tile cache lives in
 * the app's own cache dir (osmdroid's default is external storage, which scoped storage
 * refuses). Offline, the tiles simply stay on the theme's ground: the pin still works.
 *
 * **And it says where the phone is standing** ([here]): the blue dot everybody already knows,
 * with a slow, faint wave going out of it. Aiming a circle at a doorway is a question about the
 * distance between two things — the pin and you — and until now only one of them was on the
 * map, so the answer had to be guessed from the street names. The fix has never been the pin
 * (it is written into `lat`/`lng` and becomes the place); this is the other one, kept apart.
 */
@Composable
fun OsmMap(
    center: GeoPoint?,
    radiusM: Int,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
    /** Where the phone last said it was, drawn as the blue dot; null while nothing has answered. */
    here: GeoPoint? = null,
    /** How tall the map is, in dp: the zoom that fits a circle is worked out from it. */
    heightDp: Float = MAP_HEIGHT_DP,
) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val pinColor = familyColor(TriggerFamily.PLACE, dark)
    val lifecycleOwner = LocalLifecycleOwner.current

    val holder = remember {
        MapHolder(
            context,
            dark,
            scheme.surfaceContainerHigh.toArgb(),
            scheme.outlineVariant.toArgb(),
            pinColor.toArgb(),
            hereBlue(dark).toArgb(),
            scheme.surfaceContainerLowest.toArgb(),
            onLongPress,
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.map.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.map.onDetach()
        }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        // Its own height, because the zoom is worked out from it: a viewport whose size only
        // the caller knows cannot be asked to fit anything — so the caller says the height.
        modifier = modifier.height(heightDp.dp),
    ) {
        AndroidView(
            factory = { holder.wrapper },
            update = { holder.show(center, here, radiusM, heightDp) },
        )
    }
}

/** The osmdroid objects, created once and mutated on each `update`. */
private class MapHolder(
    context: Context,
    dark: Boolean,
    loadingColor: Int,
    loadingLineColor: Int,
    pinColor: Int,
    hereColor: Int,
    hereRimColor: Int,
    onLongPress: (GeoPoint) -> Unit,
) {
    val map: MapView
    val wrapper: FrameLayout
    private val marker: Marker
    private val circle: Polygon
    private val here: HereOverlay
    private var shownCenter: GeoPoint? = null

    init {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        map = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            controller.setZoom(3.0)
            controller.setCenter(GeoPoint(40.0, -3.0))
            // The colour filter below inverts the loading tile too, so on the dark scheme the
            // "not yet loaded" ground is painted pre-inverted to come out as the theme's own.
            overlayManager.tilesOverlay.setLoadingBackgroundColor(if (dark) inverted(loadingColor) else loadingColor)
            overlayManager.tilesOverlay.setLoadingLineColor(if (dark) inverted(loadingLineColor) else loadingLineColor)
            if (dark) {
                // Invert and desaturate the tiles: a bright beige map on the dark scheme is a
                // torch in a dark room.
                val matrix = ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                )
                matrix.postConcat(ColorMatrix().apply { setSaturation(0.2f) })
                overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(matrix))
            }
        }
        val pin = ContextCompat.getDrawable(context, R.drawable.ic_map_pin)!!.mutate()
        DrawableCompat.setTint(pin, pinColor)
        marker = Marker(map).apply {
            icon = pin
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
        }
        circle = Polygon(map).apply {
            fillPaint.color = (pinColor and 0x00FFFFFF) or (0x2E shl 24)
            outlinePaint.color = pinColor
            outlinePaint.strokeWidth = 3f
            setInfoWindow(null)
        }
        map.overlays.add(
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false
                override fun longPressHelper(p: GeoPoint): Boolean {
                    onLongPress(p)
                    return true
                }
            }),
        )
        // In the list from the start and never taken out, so it can never end up drawn *over*
        // the pin: the thing being aimed at is what has to be on top. With no position it draws
        // nothing at all, which is also what stops the pulse.
        here = HereOverlay(hereColor, hereRimColor, context.resources.displayMetrics.density)
        map.overlays.add(here)
        wrapper = TouchOwningFrame(context).apply {
            addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    fun show(center: GeoPoint?, here: GeoPoint?, radiusM: Int, heightDp: Float) {
        this.here.position = here
        if (center == null) {
            map.overlays.remove(marker)
            map.overlays.remove(circle)
            map.invalidate()
            return
        }
        marker.position = center
        circle.points = Polygon.pointsAsCircle(center, radiusM.toDouble())
        if (circle !in map.overlays) map.overlays.add(circle)
        if (marker !in map.overlays) map.overlays.add(marker)
        // Fly there only when the pin actually moved (a fix, a long-press), not on every
        // recomposition — a person panning around must not be yanked back to the pin.
        if (shownCenter == null || shownCenter!!.distanceToAsDouble(center) > 1.0) {
            // The first pin is the sheet opening, and it opens no tighter than a 300-metre
            // circle needs: the radius starts at 200 and the first thing anybody does is drag
            // it, so a view with no room to grow into means zooming out before you can answer.
            // After that the map fits the circle it actually has, which is what makes a
            // fifty-metre doorway worth aiming at.
            val fitted = if (shownCenter == null) maxOf(radiusM, OPENING_RADIUS_M) else radiusM
            shownCenter = center
            map.controller.setZoom(zoomFittingCircle(fitted, center.latitude, heightDp))
            map.controller.animateTo(center)
        }
        map.invalidate()
    }

    private fun inverted(argb: Int): Int = (argb and 0xFF000000.toInt()) or (argb.inv() and 0x00FFFFFF)
}

/**
 * The blue dot: where the phone is, and a slow wave going out of it.
 *
 * **It paces itself.** The obvious way to animate this is a Compose `rememberInfiniteTransition`
 * read in the `AndroidView`'s `update` — and that runs the whole update lambda sixty times a
 * second, which here means re-deciding the pin, rebuilding the radius circle
 * (`Polygon.pointsAsCircle`) and re-asking whether to fly the camera, every frame, for a dot.
 * So the phase is read off the clock inside [draw] and the next frame is asked for from there.
 * The loop is self-terminating in both directions that matter: nothing is drawn and nothing is
 * scheduled without a [position], and a view that has stopped drawing (the sheet gone, the map
 * paused) never reaches this at all.
 *
 * The wave is deliberately faint and slow — this is a thing to notice out of the corner of the
 * eye while aiming at something else, not a beacon. The rim is the lowest surface rather than
 * white, because the dark scheme's map is an inverted tile and a white ring on it is a hole.
 */
private class HereOverlay(dotColor: Int, rimColor: Int, private val density: Float) : Overlay() {

    /** Null until something has answered; drawing nothing is also what stops the pulse. */
    var position: GeoPoint? = null

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dotColor
        style = Paint.Style.FILL
    }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = rimColor
        style = Paint.Style.STROKE
        strokeWidth = RIM_DP * density
    }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dotColor
        style = Paint.Style.FILL
    }
    private val at = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val where = position ?: return
        mapView.projection.toPixels(where, at)
        val x = at.x.toFloat()
        val y = at.y.toFloat()

        // One beat: the wave grows from the dot's own edge outwards and fades as it goes, so
        // what the eye catches is the movement rather than any one frame of it.
        val phase = (SystemClock.uptimeMillis() % BEAT_MS) / BEAT_MS.toFloat()
        val reach = DOT_DP + (WAVE_DP - DOT_DP) * phase
        wave.alpha = (WAVE_ALPHA * (1f - phase)).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, reach * density, wave)

        canvas.drawCircle(x, y, DOT_DP * density, dot)
        canvas.drawCircle(x, y, DOT_DP * density, rim)

        // The next frame, asked for only while there is something to draw.
        mapView.postInvalidateOnAnimation()
    }

    private companion object {
        /** Small: it is where you are, not what you are aiming at. */
        const val DOT_DP = 5f
        const val RIM_DP = 2f
        const val WAVE_DP = 20f
        /** Out of 255 at the start of a beat, gone by the end of it. */
        const val WAVE_ALPHA = 70f
        /** Slow enough to read as breathing rather than as something asking to be tapped. */
        const val BEAT_MS = 2_400L
    }
}

/** The map's height when nobody says otherwise, in dp; the zoom below is worked out from it. */
internal const val MAP_HEIGHT_DP = 260f

/** The circle the map opens wide enough for, whatever the radius starts at. */
private const val OPENING_RADIUS_M = 300

/** A tenth of the view left as air, so the circle is inside the map rather than on its edge. */
private const val CIRCLE_MARGIN = 1.15

/**
 * Metres per dp at zoom 0. The tiles are scaled to the screen's density
 * (`isTilesScaledToDpi`), so one tile is 256 **dp** on every phone and the ground a map of a
 * given size covers is the same everywhere — which is what makes this answerable at all.
 */
private const val METRES_PER_DP_AT_ZOOM_0 = 156543.03392

/** Below 3 the whole world is a thumbnail; MAPNIK has no tiles past 19. */
private const val MIN_ZOOM = 3.0
private const val MAX_ZOOM = 19.0

/**
 * The zoom at which a circle of [radiusM] around [latitude] fits in a map [heightDp] tall.
 *
 * A table of four buckets used to answer this, and it was too close in at every one of them: a
 * 400-metre circle at zoom 16 is 800 metres of circle in 476 metres of view, so the thing the
 * sheet is *for* — seeing how far the circle reaches — ran off the top and bottom of the map.
 * Height is the tight dimension of a 260dp map, so it is the one that decides.
 */
internal fun zoomFittingCircle(radiusM: Int, latitude: Double, heightDp: Float = MAP_HEIGHT_DP): Double {
    // Mercator: a degree of longitude shrinks with the cosine of the latitude, and so does the
    // ground a pixel covers. Floored so the poles cannot divide by zero.
    val metresPerDp = METRES_PER_DP_AT_ZOOM_0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
    val wanted = 2.0 * radiusM * CIRCLE_MARGIN
    return (ln(heightDp * metresPerDp / wanted) / ln(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
}

/** Keeps the bottom sheet from interpreting a pan on the map as a drag to dismiss. */
private class TouchOwningFrame(context: Context) : FrameLayout(context) {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return super.dispatchTouchEvent(event)
    }
}
