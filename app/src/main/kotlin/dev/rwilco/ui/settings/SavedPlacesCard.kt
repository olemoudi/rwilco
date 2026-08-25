package dev.rwilco.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rwilco.R
import dev.rwilco.model.SavedPlace
import dev.rwilco.model.TriggerFamily
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.TriggerKeycap
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.ui.theme.edge
import dev.rwilco.ui.theme.wash

/**
 * The places kept by name: home, work, the gym. Each row is a place trigger's worth of
 * answer — name, pin, radius — offered whole in the place sheet. Tap a row to move it, the
 * bin to forget it.
 */
@Composable
fun SavedPlacesCard(
    places: List<SavedPlace>,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val spacing = Tokens.spacing
    val family = TriggerFamily.PLACE
    RwilcoCard {
        Column(Modifier.padding(spacing.lg)) {
            Text(
                text = stringResource(R.string.settings_places_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.md))
            if (places.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_places_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.md))
            }
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                places.forEachIndexed { index, place ->
                    // The same wash and edge as a place rule in the editor: it is the same thing.
                    Surface(
                        onClick = { onEdit(index) },
                        shape = MaterialTheme.shapes.medium,
                        color = family.wash(),
                        border = BorderStroke(Tokens.strokes.control, family.edge()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = spacing.md, top = spacing.sm, bottom = spacing.sm),
                        ) {
                            TriggerKeycap(family = family, icon = Icons.Outlined.Place, contentDescription = null)
                            Spacer(Modifier.width(spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = place.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.place_metres, place.radiusM),
                                    style = MonoStyles.date,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { onRemove(index) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.settings_remove_place),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (places.isNotEmpty()) Spacer(Modifier.height(spacing.md))
            OutlinedButton(
                onClick = onAdd,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(Tokens.strokes.control, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = PaddingValues(horizontal = spacing.lg),
                modifier = Modifier.heightIn(min = Tokens.sizes.touch),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(spacing.sm))
                Text(stringResource(R.string.settings_add_place), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
