package dev.rwilco.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.rwilco.R
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.Tokens
import dev.rwilco.voice.GeminiCheck
import dev.rwilco.voice.GeminiCheckState
import dev.rwilco.voice.GeminiSetup
import dev.rwilco.voice.geminiKeyOf

/**
 * Settings › Voice (0.178.0): where dictation's words are recognised, and Gemini as the fallback
 * for the sentences the app's own grammar cannot read — off until switched on, the person's own
 * key, and the card says plainly what leaves the phone when it is on.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceCard(
    setup: GeminiSetup,
    onEnabled: (Boolean) -> Unit,
    onKey: (String) -> Unit,
    onClear: () -> Unit,
    /** The last check of a key ("Probar la clave"), and the key it was for. */
    check: GeminiCheckState?,
    onCheck: (String) -> Unit,
) {
    val context = LocalContext.current
    val spacing = Tokens.spacing
    val scheme = MaterialTheme.colorScheme
    // Asked once: whether this phone can recognise speech on itself, which is what keeps the
    // audio on it.
    val onDevice = remember { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
    RwilcoCard {
        Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.lg)) {
            Column {
                SettingTitle(stringResource(R.string.voice_recognition_title))
                Text(
                    text = stringResource(if (onDevice) R.string.voice_recognition_on_device else R.string.voice_recognition_service),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            SettingSwitchRow(
                title = stringResource(R.string.voice_gemini_title),
                info = stringResource(R.string.voice_gemini_info),
                checked = setup.enabled,
                onCheckedChange = onEnabled,
            )
            Text(stringResource(R.string.voice_gemini_body), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (setup.enabled) {
                // The field's own draft, saved on purpose: drawn from the store it would lose the
                // letters typed while each write was on its way, and a key garbled that way is one
                // Gemini refuses with nobody knowing why. Keyed on the stored key, so the draft is
                // what was saved whenever that changes (the first read, a save, a delete).
                var draft by rememberSaveable(setup.key) { mutableStateOf(setup.key) }
                SecretField(
                    value = draft,
                    onChange = { draft = it },
                    label = stringResource(R.string.voice_gemini_key),
                    hint = stringResource(if (setup.key.isBlank()) R.string.voice_gemini_key_missing else R.string.voice_gemini_key_hint),
                )
                val cleaned = remember(draft) { geminiKeyOf(draft) }
                val keyboard = LocalSoftwareKeyboardController.current
                val focus = LocalFocusManager.current
                val showAnswer = remember { BringIntoViewRequester() }
                // A test is about the key it was made with: one edited since shows nothing.
                val tested = check?.takeIf { it.key == cleaned }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    // Always there, so nothing jumps as the field fills: off while empty or asking.
                    TextButton(
                        // The keyboard away first: the answer's line is drawn where it would cover it.
                        onClick = {
                            keyboard?.hide()
                            focus.clearFocus()
                            onCheck(draft)
                        },
                        enabled = cleaned.isNotEmpty() && (tested == null || tested.result != null),
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                    ) {
                        Text(stringResource(R.string.voice_gemini_check))
                    }
                    if (cleaned != setup.key && cleaned.isNotEmpty()) {
                        TextButton(onClick = { onKey(draft) }, modifier = Modifier.heightIn(min = Tokens.sizes.touch)) {
                            Text(stringResource(R.string.voice_gemini_save_key))
                        }
                    }
                    TextButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                        modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                    ) {
                        Text(stringResource(R.string.voice_gemini_get_key))
                    }
                    if (setup.key.isNotBlank()) {
                        TextButton(
                            onClick = onClear,
                            colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
                            modifier = Modifier.heightIn(min = Tokens.sizes.touch),
                        ) {
                            Text(stringResource(R.string.voice_gemini_clear))
                        }
                    }
                }
                val motion = Tokens.motion
                // Each new line into view once it has grown (the size change below runs for
                // `medium`): measured any earlier, the request leaves its lower half off screen.
                LaunchedEffect(tested) {
                    if (tested != null) {
                        delay((motion.medium + motion.fast).toLong())
                        showAnswer.bringIntoView()
                    }
                }
                AnimatedContent(
                    targetState = tested,
                    modifier = Modifier.bringIntoViewRequester(showAnswer),
                    transitionSpec = {
                        (fadeIn(tween(motion.fast)) togetherWith fadeOut(tween(motion.fast)))
                            .using(SizeTransform { _, _ -> tween(motion.medium, easing = motion.emphasized) })
                    },
                    label = "gemini-test",
                ) { state -> if (state != null) TestLine(state.result) }
            }
        }
    }
}

/** What testing the key came to (null: still asking), in a line: neutral when it works, the error ink when it does not. */
@Composable
private fun TestLine(result: GeminiCheck?) {
    val scheme = MaterialTheme.colorScheme
    val failed = result != null && result != GeminiCheck.WORKS
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(
            imageVector = when (result) {
                null -> Icons.Outlined.HourglassEmpty
                GeminiCheck.WORKS -> Icons.Outlined.CheckCircle
                else -> Icons.Outlined.ErrorOutline
            },
            // The line beside it says the same; merged, a description would be read twice.
            contentDescription = null,
            tint = if (failed) scheme.error else scheme.onSurfaceVariant,
            modifier = Modifier.size(Tokens.sizes.glyphMedium),
        )
        Spacer(Modifier.width(Tokens.spacing.sm))
        Text(
            text = stringResource(
                when (result) {
                    null -> R.string.voice_gemini_checking
                    GeminiCheck.WORKS -> R.string.voice_gemini_check_works
                    GeminiCheck.KEY_REFUSED -> R.string.voice_gemini_check_refused
                    GeminiCheck.NO_ANSWER -> R.string.voice_gemini_check_no_answer
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) scheme.error else scheme.onSurface,
        )
    }
}

/** Where a free Gemini key is made. */
private const val KEY_PAGE = "https://aistudio.google.com/apikey"
