package dev.rwilco.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.ui.format.TimeText
import dev.rwilco.ui.format.currentLocale
import dev.rwilco.ui.format.rememberIs24h
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import java.time.LocalTime

/** Lets the tour find a time field; a Surface with a mono number has no other handle. */
const val TIME_FIELD_TAG = "timeField"

/** A time as a big mono reading you tap to change; the wheels open at the bottom of the screen. */
@Composable
fun TimeField(
    time: LocalTime,
    onChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val locale = currentLocale()
    val is24h = rememberIs24h()
    Surface(
        onClick = { picking = true },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            // The frame is the reading plus its padding, no taller: a control-height box around
            // a single line leaves the number sitting up against the top edge, which is what a
            // time in Settings looked like. Still a thumb's worth of target.
            .heightIn(min = Tokens.sizes.touch)
            .testTag(TIME_FIELD_TAG),
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = Tokens.spacing.lg, vertical = Tokens.spacing.sm),
        ) {
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(TimeText.time(time, is24h, locale), style = MonoStyles.time)
        }
    }
    if (picking) {
        TimePickerDialog(
            initial = time,
            onDismiss = { picking = false },
            onConfirm = { picked ->
                picking = false
                onChange(picked)
            },
        )
    }
}
