package dev.rwilco.ui.settings

import androidx.annotation.ArrayRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import dev.rwilco.BuildConfig
import dev.rwilco.R
import dev.rwilco.ui.theme.Tokens

/** One release worth announcing: its code, its name, and its bullets (a string-array resource). */
data class Release(val versionCode: Int, val name: String, @ArrayRes val bulletsRes: Int)

/** Newest first. Empty until there is a release worth a word; the sheet then never appears. */
val RELEASES: List<Release> = listOf(
    Release(versionCode = 20, name = "0.7.2", bulletsRes = R.array.whats_new_0_7_2),
    Release(versionCode = 19, name = "0.7.1", bulletsRes = R.array.whats_new_0_7_1),
    Release(versionCode = 18, name = "0.7.0", bulletsRes = R.array.whats_new_0_7_0),
    Release(versionCode = 17, name = "0.6.1", bulletsRes = R.array.whats_new_0_6_1),
    Release(versionCode = 16, name = "0.6.0", bulletsRes = R.array.whats_new_0_6_0),
    Release(versionCode = 15, name = "0.5.2", bulletsRes = R.array.whats_new_0_5_2),
    Release(versionCode = 14, name = "0.5.1", bulletsRes = R.array.whats_new_0_5_1),
    Release(versionCode = 13, name = "0.5.0", bulletsRes = R.array.whats_new_0_5_0),
    Release(versionCode = 12, name = "0.4.3", bulletsRes = R.array.whats_new_0_4_3),
    Release(versionCode = 11, name = "0.4.2", bulletsRes = R.array.whats_new_0_4_2),
    Release(versionCode = 10, name = "0.4.1", bulletsRes = R.array.whats_new_0_4_1),
    Release(versionCode = 9, name = "0.4.0", bulletsRes = R.array.whats_new_0_4_0),
    Release(versionCode = 8, name = "0.3.2", bulletsRes = R.array.whats_new_0_3_2),
    Release(versionCode = 7, name = "0.3.1", bulletsRes = R.array.whats_new_0_3_1),
    Release(versionCode = 6, name = "0.3.0", bulletsRes = R.array.whats_new_0_3_0),
    Release(versionCode = 5, name = "0.2.3", bulletsRes = R.array.whats_new_0_2_3),
    Release(versionCode = 4, name = "0.2.2", bulletsRes = R.array.whats_new_0_2_2),
    Release(versionCode = 2, name = "0.2.0", bulletsRes = R.array.whats_new_0_2_0),
)

/**
 * The releases to show somebody who last saw [lastSeenVersionCode] and now runs
 * [currentVersionCode]. A fresh install (0) is shown nothing: the whole changelog on first
 * launch is noise, and marking it seen is what keeps the next real update announced.
 */
fun entriesFor(lastSeenVersionCode: Int, currentVersionCode: Int, releases: List<Release> = RELEASES): List<Release> {
    if (lastSeenVersionCode <= 0) return emptyList()
    return releases
        .filter { it.versionCode in (lastSeenVersionCode + 1)..currentVersionCode }
        .sortedByDescending { it.versionCode }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(lastSeenVersionCode: Int, onSeen: (Int) -> Unit) {
    val current = BuildConfig.VERSION_CODE
    val entries = remember(lastSeenVersionCode) { entriesFor(lastSeenVersionCode, current) }
    if (entries.isEmpty()) {
        // Nothing to say (or a fresh install): record the build so the next one is announced.
        LaunchedEffect(lastSeenVersionCode) { if (lastSeenVersionCode < current) onSeen(current) }
        return
    }
    val spacing = Tokens.spacing
    ModalBottomSheet(
        onDismissRequest = { onSeen(current) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.screen)) {
            Text(stringResource(R.string.whats_new_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(spacing.lg))
            // The button sits OUTSIDE the scrolling part, so a long release can never push the
            // only way out below the fold.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (release in entries) {
                    Text(release.name, style = MaterialTheme.typography.titleMedium)
                    for (bullet in stringArrayResource(release.bulletsRes)) {
                        Text(
                            text = "· $bullet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.xs),
                        )
                    }
                    Spacer(Modifier.height(spacing.lg))
                }
            }
            Button(
                onClick = { onSeen(current) },
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = spacing.md)
                    .heightIn(min = Tokens.sizes.control),
            ) { Text(stringResource(R.string.whats_new_ok), style = MaterialTheme.typography.titleMedium) }
        }
    }
}
