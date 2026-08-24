package dev.rwilco.ui.editor.sheets

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.MAX_LABEL_LENGTH
import dev.rwilco.model.MAX_RADIUS_M
import dev.rwilco.model.MIN_RADIUS_M
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import java.util.Locale

/**
 * A place, a radius, and whether arriving or leaving matters. The pin comes from the phone's
 * own location or a long-press on the map; the radius is drawn around it as it changes.
 */
@Composable
fun LocationSheet(
    initial: Trigger.Location?,
    onConfirm: (Trigger.Location) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "") }
    var transition by rememberSaveable { mutableStateOf((initial?.transition ?: Transition.ENTER).name) }
    var radius by rememberSaveable { mutableIntStateOf(initial?.radiusM ?: 200) }
    var lat by rememberSaveable { mutableStateOf(initial?.lat) }
    var lng by rememberSaveable { mutableStateOf(initial?.lng) }
    var locating by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = Tokens.haptics

    fun locate() {
        locating = true
        failed = false
        scope.launch {
            val fix = currentLocation(context)
            locating = false
            if (fix == null) {
                failed = true
            } else {
                lat = fix.latitude
                lng = fix.longitude
                haptics.perform(HapticFeedbackType.Confirm)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else failed = true
    }

    val known = lat != null && lng != null
    SheetScaffold(
        title = stringResource(R.string.kind_place),
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.Location(lat!!, lng!!, radius, Transition.valueOf(transition), label.trim())) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = known && label.isNotBlank(),
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(MAX_LABEL_LENGTH) },
            label = { Text(stringResource(R.string.place_label)) },
            placeholder = { Text(stringResource(R.string.place_label_hint)) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedChoice(
            options = listOf(stringResource(R.string.trigger_arriving), stringResource(R.string.trigger_leaving)),
            selectedIndex = if (transition == Transition.ENTER.name) 0 else 1,
            onSelect = { transition = if (it == 0) Transition.ENTER.name else Transition.EXIT.name },
        )
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            OsmMap(
                center = if (known) GeoPoint(lat!!, lng!!) else null,
                radiusM = radius,
                onLongPress = { point ->
                    lat = point.latitude
                    lng = point.longitude
                    failed = false
                    haptics.perform(HapticFeedbackType.Confirm)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
            Text(
                text = stringResource(R.string.place_map_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { permission.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                enabled = !locating,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.sizes.control),
            ) {
                Icon(Icons.Outlined.MyLocation, contentDescription = null)
                Spacer(Modifier.width(Tokens.spacing.sm))
                Text(stringResource(if (locating) R.string.place_locating else R.string.place_use_location), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = when {
                    known -> String.format(Locale.ROOT, "%.5f, %.5f", lat, lng)
                    failed -> stringResource(R.string.place_no_fix)
                    else -> stringResource(R.string.place_no_location)
                },
                style = if (known) MonoStyles.label else MaterialTheme.typography.bodyMedium,
                color = if (failed && !known) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.place_radius), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.place_metres, radius), style = MonoStyles.label)
            }
            Slider(
                value = radius.toFloat(),
                onValueChange = { radius = (it / 50).toInt() * 50 },
                valueRange = MIN_RADIUS_M.toFloat()..MAX_RADIUS_M.toFloat(),
                steps = (MAX_RADIUS_M - MIN_RADIUS_M) / 50 - 1,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            )
        }
    }
}
