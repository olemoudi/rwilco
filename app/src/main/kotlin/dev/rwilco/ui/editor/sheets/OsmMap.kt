package dev.rwilco.ui.editor.sheets

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.io.File

/**
 * OpenStreetMap tiles with one pin and its radius. Long-press moves the pin. The View lives in
 * a wrapper that keeps the enclosing sheet from stealing its drags, and its tile cache lives in
 * the app's own cache dir (osmdroid's default is external storage, which scoped storage
 * refuses). Offline, the tiles simply stay on the theme's ground: the pin still works.
 */
@Composable
fun OsmMap(
    center: GeoPoint?,
    radiusM: Int,
    onLongPress: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = LocalDarkTheme.current
    val scheme = MaterialTheme.colorScheme
    val pinColor = familyColor(TriggerFamily.PLACE, dark)
    val lifecycleOwner = LocalLifecycleOwner.current

    val holder = remember {
        MapHolder(context, dark, scheme.surfaceContainerHigh.toArgb(), scheme.outlineVariant.toArgb(), pinColor.toArgb(), onLongPress)
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
        modifier = modifier,
    ) {
        AndroidView(
            factory = { holder.wrapper },
            update = { holder.show(center, radiusM) },
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
    onLongPress: (GeoPoint) -> Unit,
) {
    val map: MapView
    val wrapper: FrameLayout
    private val marker: Marker
    private val circle: Polygon
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
        wrapper = TouchOwningFrame(context).apply {
            addView(map, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    fun show(center: GeoPoint?, radiusM: Int) {
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
            shownCenter = center
            map.controller.setZoom(zoomFor(radiusM))
            map.controller.animateTo(center)
        }
        map.invalidate()
    }

    private fun inverted(argb: Int): Int = (argb and 0xFF000000.toInt()) or (argb.inv() and 0x00FFFFFF)

    private fun zoomFor(radiusM: Int): Double = when {
        // A 50 m circle at 17 is a dot; close enough in and it is a doorway you can aim at.
        radiusM <= 75 -> 18.0
        radiusM <= 150 -> 17.0
        radiusM <= 400 -> 16.0
        else -> 15.0
    }
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
