package dev.rwilco.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens

/** Found by the instrumented test, which is the only thing that can prove it comes back. */
const val DISCLAIMER_TAG = "disclaimer"

/**
 * Whether the notice has already been read in this run of the process.
 *
 * "Every launch" is the point of it, so the memory has to be exactly one launch long: shorter
 * and it would come back on every rotation, longer and it would be the flag the second button
 * sets. A rotation, a trip to Settings and back, an alert on top of Home are all the same run;
 * the app being started again is not.
 */
object Disclaimer {
    var readThisRun by mutableStateOf(false)
}

/**
 * What this app is, said before it is trusted with anything.
 *
 * It is a reminders app written for one person and updated every few days from a repository —
 * which is a fine thing to write and a poor thing to assume. Somebody has to be told once,
 * plainly, that a reminder that matters should not be left only here.
 *
 * Two ways out, and they are not the same weight on purpose: "OK" is the filled one and the
 * easy one, and it only closes the notice — it comes back at the next launch. Turning it off
 * for good is the deliberate act, a text button that has to be looked for. An app that talks
 * itself down should make the acknowledgement cheap and the silencing considered, not the
 * other way round.
 */
@Composable
fun DisclaimerDialog(onRead: () -> Unit, onNeverAgain: () -> Unit) {
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onRead, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainer,
            border = BorderStroke(Tokens.strokes.edge, scheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .testTag(DISCLAIMER_TAG),
        ) {
            Column(modifier = Modifier.padding(spacing.lg)) {
                Text(
                    text = stringResource(R.string.disclaimer_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(spacing.lg))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = onNeverAgain,
                        colors = ButtonDefaults.textButtonColors(contentColor = scheme.onSurfaceVariant),
                        modifier = Modifier.heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.disclaimer_never_again)) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = onRead,
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.onSurface,
                            contentColor = scheme.surface,
                        ),
                        modifier = Modifier.heightIn(min = Tokens.sizes.control),
                    ) { Text(stringResource(R.string.disclaimer_ok), style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}
