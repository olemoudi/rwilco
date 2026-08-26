package dev.rwilco.ui.settings

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.diag.collectDiagnostics
import dev.rwilco.diag.report
import dev.rwilco.ui.components.LocalSnackbar
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.theme.MonoStyles
import dev.rwilco.ui.theme.Tokens
import kotlinx.coroutines.launch

/**
 * What the app knows about itself, as one block of text to be copied and handed to whoever is
 * going to fix it.
 *
 * It is a diagnostic screen and reads as one: mono, dense, nothing explained. The words of the
 * reminders are deliberately not in it — a bug is in the moments and the decisions, never in
 * what somebody wrote — so this can be pasted into a conversation without pasting a life.
 */
@Composable
fun DiagnosticsScreen(app: RwilcoApplication, onBack: () -> Unit) {
    val spacing = Tokens.spacing
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val copied = stringResource(R.string.diag_copied)
    val cleared = stringResource(R.string.diag_cleared)

    // What the log says right now, watched rather than sampled: a report built the moment the
    // screen opened is a report that goes stale while somebody is reading it, and the reason to
    // be on this screen at all is usually that something is happening.
    val log by app.diagStore.log.collectAsStateWithLifecycle(initialValue = null)
    val newest = log?.notes?.firstOrNull()?.at
    val lines = log?.notes?.size ?: 0
    val report by produceState(initialValue = null as String?, reload, newest, lines) {
        // The old text stays on screen while the new one is built, so a note arriving does not
        // blank the screen somebody is in the middle of reading.
        value = app.collectDiagnostics().report()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = spacing.sm)
                        .heightIn(min = Tokens.sizes.control),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                    Text(
                        text = stringResource(R.string.diag_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = spacing.sm),
                    )
                    IconButton(onClick = {
                        scope.launch {
                            app.diagStore.clear()
                            reload++
                            snackbar.show(cleared)
                        }
                    }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.diag_clear))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = spacing.screen),
        ) {
            Text(
                text = stringResource(R.string.diag_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = spacing.sm),
            )
            val text = report
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(text.orEmpty()))
                        scope.launch { snackbar.show(copied) }
                    },
                    enabled = text != null,
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = Tokens.sizes.control),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(R.string.diag_copy))
                }
                OutlinedButton(
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, text.orEmpty())
                        context.startActivity(Intent.createChooser(send, null))
                    },
                    enabled = text != null,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f).heightIn(min = Tokens.sizes.control),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(Modifier.width(spacing.sm))
                    Text(stringResource(R.string.diag_share))
                }
            }
            Spacer(Modifier.height(spacing.md))
            RwilcoCard(modifier = Modifier.weight(1f)) {
                // Both ways: the report has long lines on purpose (one reminder is one line),
                // and wrapping them would make it unreadable on the screen it is read on.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(spacing.md),
                ) {
                    Text(
                        text = text ?: stringResource(R.string.diag_building),
                        style = MonoStyles.date,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                    )
                }
            }
            Spacer(Modifier.height(spacing.lg))
        }
    }
}
