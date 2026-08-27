package dev.rwilco.ui.editor.sheets

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.MAX_LABEL_LENGTH
import dev.rwilco.model.MAX_RADIUS_M
import dev.rwilco.model.MIN_RADIUS_M
import dev.rwilco.model.Presence
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.Trigger
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.SegmentedChoice
import dev.rwilco.ui.components.SheetScaffold
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * A place, a radius, and whether arriving or leaving matters. The pin comes from a saved place,
 * the phone's own location, an address, or a long-press on the map; the radius is drawn
 * around it as it changes. Settings hosts the same sheet to keep a place by name — without
 * the arriving/leaving choice, which belongs to a rule, not a place.
 */
@Composable
fun LocationSheet(
    initial: Trigger.Location?,
    onConfirm: (Trigger.Location) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.kind_place),
    pickTransition: Boolean = true,
    savedPlaces: List<SavedPlace> = emptyList(),
) {
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "") }
    var presence by rememberSaveable { mutableStateOf((initial?.presence ?: Presence.INSIDE).name) }
    var onCrossing by rememberSaveable { mutableStateOf(initial?.onCrossing ?: false) }
    var radius by rememberSaveable { mutableIntStateOf(initial?.radiusM ?: 200) }
    var lat by rememberSaveable { mutableStateOf(initial?.lat) }
    var lng by rememberSaveable { mutableStateOf(initial?.lng) }
    // Plain remember, on purpose: the work behind these runs in the sheet's own scope, which a
    // rotation cancels, so a saved "busy" would be a spinner with nothing behind it for ever.
    var locating by remember { mutableStateOf(false) }
    /** Null while nothing has gone wrong; otherwise which of the two things went wrong. */
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FoundPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
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
        title = title,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(Trigger.Location(lat!!, lng!!, radius, Presence.valueOf(presence), label.trim(), onCrossing)) },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = known && label.isNotBlank(),
    ) {
        // The places kept by name, one tap each: name, pin and radius at once. The one that
        // matches the pin is inverted, so a rule built from "Casa" says so.
        if (savedPlaces.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
                Text(
                    text = stringResource(R.string.place_saved_pick),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.spacing.sm),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    for (place in savedPlaces) {
                        PresetChip(
                            label = place.label,
                            selected = lat == place.lat && lng == place.lng,
                            onClick = {
                                label = place.label
                                lat = place.lat
                                lng = place.lng
                                radius = place.radiusM
                                failure = null
                                results = emptyList()
                            },
                        )
                    }
                }
            }
        }
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
        if (pickTransition) {
            PresenceChoice(
                presence = Presence.valueOf(presence),
                onCrossing = onCrossing,
                onPresence = { presence = it.name },
                onCrossingChange = { onCrossing = it },
            )
        }
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
                // Rounded, not truncated: a tick the slider snaps to can arrive a hair under
                // its own value, and truncation then lands fifty metres short of it.
                onValueChange = { radius = (it / 50).roundToInt() * 50 },
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

/**
 * Which side of the line, and whether the phone has to be seen crossing it.
 *
 * Two controls and not four buttons, because it is two questions — and the switch **relabels**
 * the segments rather than sitting apart from them, so what somebody reads is always one of the
 * four things a place rule can be: "mientras estoy", "mientras no estoy", "al llegar", "al
 * salir". A place used to be a doorway and only a doorway, and the commonest thing anybody means
 * — "cuando esté en casa" — could not be written at all: a reminder made at home waited for you
 * to leave first. The line underneath says what the choice costs, because that is the half of it
 * nobody can guess.
 */
@Composable
private fun PresenceChoice(
    presence: Presence,
    onCrossing: Boolean,
    onPresence: (Presence) -> Unit,
    onCrossingChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = Tokens.haptics
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.spacing.sm)) {
        SegmentedChoice(
            options = listOf(
                stringResource(if (onCrossing) R.string.place_side_arriving else R.string.place_side_inside),
                stringResource(if (onCrossing) R.string.place_side_leaving else R.string.place_side_outside),
            ),
            selectedIndex = if (presence == Presence.INSIDE) 0 else 1,
            onSelect = { onPresence(if (it == 0) Presence.INSIDE else Presence.OUTSIDE) },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = onCrossing,
                    role = Role.Switch,
                    onValueChange = { on ->
                        haptics.perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        onCrossingChange(on)
                    },
                )
                .heightIn(min = Tokens.sizes.touch),
        ) {
            Text(
                text = stringResource(R.string.place_needs_crossing),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Tokens.spacing.md))
            Switch(
                checked = onCrossing,
                // The row owns the gesture; the switch is the picture of the state.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(checkedThumbColor = scheme.surface, checkedTrackColor = scheme.onSurface),
            )
        }
        Text(
            text = stringResource(
                when {
                    presence == Presence.INSIDE && onCrossing -> R.string.place_means_arriving
                    presence == Presence.INSIDE -> R.string.place_means_inside
                    onCrossing -> R.string.place_means_leaving
                    else -> R.string.place_means_outside
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
