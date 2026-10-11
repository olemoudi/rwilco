package dev.rwilco.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import dev.rwilco.voice.GeminiSetup

/**
 * Settings › Voice (0.178.0): where dictation's words are recognised, and Gemini as the fallback
 * for the sentences the app's own grammar cannot read — off until switched on, the person's own
 * key, and the card says plainly what leaves the phone when it is on.
 */
@Composable
fun VoiceCard(
    setup: GeminiSetup,
    onEnabled: (Boolean) -> Unit,
    onKey: (String) -> Unit,
    onClear: () -> Unit,
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
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    if (draft.trim() != setup.key && draft.isNotBlank()) {
                        TextButton(onClick = { onKey(draft.trim()) }, modifier = Modifier.heightIn(min = Tokens.sizes.touch)) {
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
            }
        }
    }
}

/** Where a free Gemini key is made. */
private const val KEY_PAGE = "https://aistudio.google.com/apikey"
