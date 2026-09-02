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
import androidx.compose.material.icons.outlined.OpenInFull
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
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
import dev.rwilco.ui.components.PermissionFixRow
import dev.rwilco.ui.components.PresetChip
import dev.rwilco.ui.settings.appDetailsIntent
import dev.rwilco.ui.settings.openSettingsPage
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
    /**
     * Where a place made here can be kept by name. Null where that makes no sense (Settings,
     * which *is* the list); given, the sheet offers a switch once there is a pin and a name.
     */
    onKeepPlace: ((SavedPlace) -> Unit)? = null,
) {
    var label by rememberSaveable { mutableStateOf(initial?.label ?: "") }
    var presence by rememberSaveable { mutableStateOf((initial?.presence ?: Presence.INSIDE).name) }
    // A place being *added* is asked for as a doorway: "al llegar a casa" is the sentence people
    // write, and the state reading — "mientras esté en casa" — is a tap away on the same switch.
    // The editor's opening answer, not the model's: [Trigger.Location.onCrossing] stays false on
    // disk, where it is what every place trigger written before the field existed decodes to.
    var onCrossing by rememberSaveable { mutableStateOf(initial?.onCrossing ?: true) }
    var radius by rememberSaveable { mutableIntStateOf(initial?.radiusM ?: 200) }
    var lat by rememberSaveable { mutableStateOf(initial?.lat) }
    var lng by rememberSaveable { mutableStateOf(initial?.lng) }
    var keep by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Plain remember, on purpose: the work behind these runs in the sheet's own scope, which a
    // rotation cancels, so a saved "busy" would be a spinner with nothing behind it for ever.
    var locating by remember { mutableStateOf(false) }
    // Where the phone said it was, for the map's own blue dot. Not saved across a rotation:
    // a position is a fact about this second, and the sheet asks again when it needs one.
    var here by remember { mutableStateOf<GeoPoint?>(null) }
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

    /**
     * [asked] is somebody pressing the crosshair; the sheet also asks on its own when it opens
     * ([initial] null and the permission already given), and that one has to be quieter. It
     * loses to a pin chosen while it was in flight — a saved place, a search result — and it
     * says nothing when it fails: an error in red under a map nobody has asked anything of is
     * noise, and the crosshair is still there to ask properly.
     */
    fun locate(asked: Boolean = true) {
        locating = true
        failure = null
        scope.launch {
            val fix = currentLocation(context)
            // **The dot first, and whatever happens to the pin afterwards.** Where the phone is
            // standing is true either way — the quiet fix on opening loses the *pin* to
            // anything chosen while it was in flight, and it should still be drawn — and the
            // two are the same only for the first second of a place that opens on you. The
            // question the map is asked, how far is this circle from me, needs both.
            if (fix is LocationFix.Found) here = GeoPoint(fix.location.latitude, fix.location.longitude)
            if (!asked && lat != null) {
                locating = false
                return@launch
            }
            when (fix) {
                is LocationFix.Found -> {
                    lat = fix.location.latitude
                    lng = fix.location.longitude
                    if (asked) haptics.perform(HapticFeedbackType.Confirm)
                }
                LocationFix.NoPermission -> if (asked) failure = FAILURE_PERMISSION
                LocationFix.NoFix -> if (asked) failure = FAILURE_NO_FIX
            }
            locating = false
        }
    }

    // A place being added is nearly always the one the phone is standing in, so it opens on it
    // rather than on the whole of Spain waiting for a long-press. Only when the permission is
    // already given: a sheet that opens with a system dialog on top of it is a worse first
    // second than a map you have to press the crosshair on.
    LaunchedEffect(Unit) {
        if (initial == null && lat == null && hasAnyLocationPermission(context)) locate(asked = false)
    }
    // Both, not just the precise one: a person who answers "approximate" has said yes, and the
    // old code read that as a refusal and gave up with the phone perfectly able to answer.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else failure = FAILURE_PERMISSION
    }

    val known = lat != null && lng != null
    // What the sheet opened with, so back and the scrim can tell work from nothing.
    val untouched = remember { listOf(lat, lng, radius, label, presence, onCrossing) }
    // A share of the window, never less than the old fixed height: on a tall phone 260dp was a
    // letterbox the circle had to be aimed through.
    val sizes = Tokens.sizes
    val mapHeightDp = maxOf(LocalConfiguration.current.screenHeightDp * sizes.mapShare, sizes.mapMinHeight.value)
    // Nothing to keep when the pin and the name are already a saved place: the switch would be
    // offering to write down what is written.
    val keepOffered = onKeepPlace != null && known && label.isNotBlank() &&
        savedPlaces.none { it.label.equals(label.trim(), ignoreCase = true) && it.lat == lat && it.lng == lng }
    if (expanded) {
        FullScreenMap(
            center = if (known) GeoPoint(lat!!, lng!!) else null,
            here = here,
            radiusM = radius,
            onLongPress = { point ->
                lat = point.latitude
                lng = point.longitude
                failure = null
                haptics.perform(HapticFeedbackType.Confirm)
            },
            onRadius = { radius = it },
            onDismiss = { expanded = false },
        )
    }
    SheetScaffold(
        title = title,
        onDismiss = onDismiss,
        onConfirm = {
            if (keep && keepOffered) onKeepPlace?.invoke(SavedPlace(label.trim(), lat!!, lng!!, radius))
            onConfirm(Trigger.Location(lat!!, lng!!, radius, Presence.valueOf(presence), label.trim(), onCrossing))
        },
        confirmLabel = stringResource(if (initial == null) R.string.sheet_add else R.string.sheet_done),
        confirmEnabled = known && label.isNotBlank(),
        dirty = listOf(lat, lng, radius, label, presence, onCrossing) != untouched,
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
        // The pin is down and "Añadir" is grey: the one thing missing is said, where the field is.
        if (known && label.isBlank()) {
            Text(
                text = stringResource(R.string.place_label_required),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
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
                    here = here,
                    radiusM = radius,
                    onLongPress = { point ->
                        lat = point.latitude
                        lng = point.longitude
                        failure = null
                        haptics.perform(HapticFeedbackType.Confirm)
                    },
                    heightDp = mapHeightDp,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The whole window, for looking rather than aiming: panning out along a street
                // to find the corner is what a map this size cannot do.
                FilledIconButton(
                    onClick = { expanded = true },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Tokens.spacing.md)
                        .size(Tokens.sizes.touch),
                ) {
                    Icon(Icons.Outlined.OpenInFull, contentDescription = stringResource(R.string.place_expand))
                }
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
                    failure == FAILURE_NO_FIX -> stringResource(R.string.place_no_fix)
                    else -> stringResource(R.string.place_map_hint)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (failure == FAILURE_NO_FIX && !known) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // A permission refused twice is one the system will not ask for again, so the
            // crosshair used to spin and stop with nowhere to go — and with a pin already down
            // the refusal was not even said, because the coordinates took its line. Its own
            // row, with the door to the app's page, whether or not there is a pin (0.67.0).
            if (failure == FAILURE_PERMISSION) {
                PermissionFixRow(
                    text = stringResource(R.string.place_no_permission),
                    action = stringResource(R.string.home_settings),
                    onFix = { context.openSettingsPage(context.appDetailsIntent()) },
                )
            }
        }
        RadiusControl(radius = radius, onChange = { radius = it })
        if (keepOffered) {
            KeepPlaceRow(keep = keep, onChange = { keep = it })
        }
    }
}

/** The radius, read in metres and dragged in fifty-metre ticks: the sheet's and the full map's. */
@Composable
internal fun RadiusControl(radius: Int, onChange: (Int) -> Unit) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.place_radius), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.place_metres, radius), style = MonoStyles.label)
            }
            Slider(
                value = radius.toFloat(),
                // Rounded, not truncated: a tick the slider snaps to can arrive a hair under
                // its own value, and truncation then lands fifty metres short of it.
                onValueChange = { onChange((it / 50).roundToInt() * 50) },
                // Inset from the sheet's own margin: at either end the thumb sat within a
                // thumb's width of the edge of the screen, where a finger arrives half on the
                // bezel and the gesture is as likely to be read as a back swipe. Twelve dp
                // cleared the bezel; twenty-four clears the *gesture* inset as well, which is
                // what the system actually reserves at each side, and leaves the track visibly
                // narrower than the text above it — a control sitting inside its row rather
                // than spanning it.
                modifier = Modifier.padding(horizontal = Tokens.spacing.xl),
                valueRange = MIN_RADIUS_M.toFloat()..MAX_RADIUS_M.toFloat(),
                steps = (MAX_RADIUS_M - MIN_RADIUS_M) / 50 - 1,
                // The ticks as well as the tracks. Left to the defaults they come out amber, and
                // amber in this app means one thing — what fires next — so a row of it under a
                // radius control is the colour saying something it does not mean. Each tick is
                // the mark of the track it sits on, and nothing else.
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onSurface,
                    activeTrackColor = MaterialTheme.colorScheme.onSurface,
                    activeTickColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
}

/**
 * "Guardar como lugar": the place made for this rule kept by name, so the next rule that means
 * it picks it off a chip. A saved place could only ever be made in Settings, and the condition
 * sheet — which offers nothing but saved places — was unreachable until somebody went there.
 */
@Composable
private fun KeepPlaceRow(keep: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = Tokens.haptics
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = keep,
                role = Role.Switch,
                onValueChange = { on ->
                    haptics.perform(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    onChange(on)
                },
            )
            .heightIn(min = Tokens.sizes.touch),
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = stringResource(R.string.place_keep), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.place_keep_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Tokens.spacing.md))
        Switch(
            checked = keep,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = scheme.surface, checkedTrackColor = scheme.onSurface),
        )
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
