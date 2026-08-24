package dev.rwilco.ui.editor.sheets

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.MAX_LABEL_LENGTH
import dev.rwilco.model.MAX_RADIUS_M
import dev.rwilco.model.MIN_RADIUS_M
import dev.rwilco.model.Transition
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.format.currentLocale
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
    /** Null while nothing has gone wrong; otherwise which of the two things went wrong. */
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoundPlace>>(emptyList()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var searched by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = Tokens.haptics
    val locale = currentLocale()
    val focusManager = LocalFocusManager.current

    fun runSearch() {
        val text = query.trim()
        if (text.isEmpty() || searching) return
        focusManager.clearFocus()
        searching = true
        scope.launch {
            results = searchPlaces(context, text, locale)
            searching = false
            searched = true
        }
    }

    fun locate() {
        locating = true
        failure = null
        scope.launch {
            when (val fix = currentLocation(context)) {
                is LocationFix.Found -> {
                    lat = fix.location.latitude
                    lng = fix.location.longitude
                    haptics.perform(HapticFeedbackType.Confirm)
                }
                LocationFix.NoPermission -> failure = FAILURE_PERMISSION
                LocationFix.NoFix -> failure = FAILURE_NO_FIX
            }
            locating = false
        }
    }
    // Both, not just the precise one: a person who answers "approximate" has said yes, and the
    // old code read that as a refusal and gave up with the phone perfectly able to answer.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else failure = FAILURE_PERMISSION
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
        // Typing an address is the way in for a place you are not standing in; the map and the
        // crosshair are for the two you are.
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                searched = false
            },
            label = { Text(stringResource(R.string.place_search)) },
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (searching) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else if (query.isNotBlank()) {
                    IconButton(onClick = { runSearch() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = stringResource(R.string.place_search))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (results.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.xs)) {
                for (place in results) {
                    ResultRow(place) {
                        lat = place.lat
                        lng = place.lng
                        failure = null
                        if (label.isBlank()) label = place.label.take(MAX_LABEL_LENGTH)
                        results = emptyList()
                        haptics.perform(HapticFeedbackType.Confirm)
                    }
                }
            }
        } else if (searched && !searching) {
            Text(
                text = stringResource(R.string.place_search_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OsmMap(
                    center = if (known) GeoPoint(lat!!, lng!!) else null,
                    radiusM = radius,
                    onLongPress = { point ->
                        lat = point.latitude
                        lng = point.longitude
                        failure = null
                        haptics.perform(HapticFeedbackType.Confirm)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                )
                FilledIconButton(
                    onClick = {
                        if (hasAnyLocationPermission(context)) {
                            locate()
                        } else {
                            permission.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                            )
                        }
                    },
                    enabled = !locating,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(Tokens.spacing.md)
                        .size(Tokens.sizes.control),
                ) {
                    if (locating) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(Icons.Outlined.MyLocation, contentDescription = stringResource(R.string.place_my_location))
                    }
                }
            }
            Text(
                text = when {
                    known -> String.format(Locale.ROOT, "%.5f, %.5f", lat, lng) + " · " + stringResource(R.string.place_map_hint)
                    failure == FAILURE_PERMISSION -> stringResource(R.string.place_no_permission)
                    failure == FAILURE_NO_FIX -> stringResource(R.string.place_no_fix)
                    else -> stringResource(R.string.place_map_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failure != null && !known) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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

/** One geocoder hit: what it is called, and where, so two "Calle Mayor 3" can be told apart. */
@Composable
private fun ResultRow(place: FoundPlace, onPick: () -> Unit) {
    RwilcoCard(onClick = onPick, shape = MaterialTheme.shapes.small) {
        Column(modifier = Modifier.padding(horizontal = Tokens.spacing.md, vertical = Tokens.spacing.sm)) {
            Text(place.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (place.detail != null) {
                Text(
                    text = place.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Saveable across a rotation, which an enum entry is not without a custom saver. */
private const val FAILURE_PERMISSION = "permission"
private const val FAILURE_NO_FIX = "no_fix"
