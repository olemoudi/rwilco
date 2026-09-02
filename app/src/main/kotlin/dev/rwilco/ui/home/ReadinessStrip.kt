package dev.rwilco.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.Tokens

/**
 * "Puede que este móvil no suene": Home's word about a phone that cannot keep the app's promise.
 *
 * Every one of the ten things this is about fails silently — an exact-alarm grant revoked, a
 * channel muted by hand, Do Not Disturb on total silence — and until now the only place that
 * said so was a folded row in Settings that nobody opens until they have already missed
 * something. In the error colour and never amber (amber is what fires next; this is what
 * might not). "Arreglar" goes to Settings, which opens the broken group on arrival; "Ahora no"
 * remembers these problems, and only these (`stripShows`).
 */
@Composable
fun ReadinessStrip(
    problems: Int,
    onFix: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The worst of them in its own words — "las notificaciones están desactivadas" — and "y 3 más" (0.68.0). */
    worst: String? = null,
) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    RwilcoCard(modifier = modifier, color = scheme.errorContainer) {
        Column(Modifier.padding(spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NotificationsOff, contentDescription = null, tint = scheme.onErrorContainer)
                Spacer(Modifier.padding(horizontal = spacing.xs))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_readiness_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onErrorContainer,
                    )
                    Text(
                        // A count alone ("4 cosas por arreglar") named nothing; the worst one
                        // is said, and the rest counted.
                        text = when {
                            worst == null -> pluralStringResource(R.plurals.settings_summary_broken, problems, problems)
                            problems > 1 -> worst + " · " + pluralStringResource(R.plurals.home_readiness_more, problems - 1, problems - 1)
                            else -> worst
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = scheme.onErrorContainer),
                    modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                ) {
                    Text(stringResource(R.string.home_readiness_dismiss))
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onFix,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                    modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                ) {
                    Text(stringResource(R.string.home_readiness_fix), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}
