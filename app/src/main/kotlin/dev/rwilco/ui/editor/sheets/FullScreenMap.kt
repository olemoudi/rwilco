package dev.rwilco.ui.editor.sheets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens
import org.osmdroid.util.GeoPoint

/**
 * The map on its own, the whole window tall.
 *
 * The sheet's map is a share of the screen and that is enough to aim a pin; it is not enough
 * to *look* — to pan out along a street, find the right corner, and drop the circle on it.
 * This is the same map (the same pin, the same long-press, the same radius) given the window,
 * with the radius under it and one way back. Nothing is answered here that the sheet does not
 * hold: the pin and the radius are the sheet's state, and "Listo" only closes the door.
 */
@Composable
fun FullScreenMap(
    center: GeoPoint?,
    /** Where the phone is, for the blue dot; the same one the sheet's own map shows. */
    here: GeoPoint? = null,
    radiusM: Int,
    onLongPress: (GeoPoint) -> Unit,
    onRadius: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val spacing = Tokens.spacing
    val haptics = Tokens.haptics
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                // The map takes what the controls leave; its own height is what the zoom that
                // fits the circle is worked out from, so it is measured before it is drawn.
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    OsmMap(
                        center = center,
                        here = here,
                        radiusM = radiusM,
                        onLongPress = onLongPress,
                        heightDp = maxHeight.value,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledIconButton(
                        onClick = onDismiss,
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = scheme.surfaceContainerHighest,
                            contentColor = scheme.onSurface,
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(spacing.md)
                            .heightIn(min = Tokens.sizes.touch),
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.place_collapse))
                    }
                }
                Column(Modifier.padding(horizontal = spacing.screen)) {
                    Spacer(Modifier.height(spacing.md))
                    Text(
                        text = stringResource(R.string.place_map_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    RadiusControl(radius = radiusM, onChange = onRadius)
                    Spacer(Modifier.height(spacing.md))
                    Button(
                        onClick = {
                            haptics.perform(HapticFeedbackType.Confirm)
                            onDismiss()
                        },
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.onSurface, contentColor = scheme.surface),
                        // The gap under it is outside the button: with the padding inside,
                        // the bottom 12dp of what read as the button did nothing.
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Tokens.sizes.control),
                    ) {
                        Text(stringResource(R.string.common_done), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(spacing.md))
                }
            }
        }
    }
}
