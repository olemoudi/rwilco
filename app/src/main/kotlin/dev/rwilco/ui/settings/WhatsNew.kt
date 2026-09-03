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

/**
 * Newest first. Empty until there is a release worth a word; the sheet then never appears.
 *
 * **The head of this list is the build.** It stopped at 0.20.0 once and nobody noticed for
 * forty-five releases, because a sheet with nothing to say says nothing; `WhatsNewTest` now
 * refuses a build whose code is not the first entry here, so a release cannot be cut without
 * its line. The thirty versions that went unannounced are one entry, keyed to the build that
 * brought the notes back, so a phone that last saw 0.20.0 is told once what happened since.
 */
val RELEASES: List<Release> = listOf(
    Release(versionCode = 127, name = "0.76.0", bulletsRes = R.array.whats_new_0_76_0),
    Release(versionCode = 126, name = "0.75.0", bulletsRes = R.array.whats_new_0_75_0),
    Release(versionCode = 125, name = "0.74.0", bulletsRes = R.array.whats_new_0_74_0),
    Release(versionCode = 124, name = "0.73.0", bulletsRes = R.array.whats_new_0_73_0),
    Release(versionCode = 123, name = "0.72.0", bulletsRes = R.array.whats_new_0_72_0),
    Release(versionCode = 122, name = "0.71.0", bulletsRes = R.array.whats_new_0_71_0),
    Release(versionCode = 121, name = "0.70.0", bulletsRes = R.array.whats_new_0_70_0),
    Release(versionCode = 120, name = "0.69.0", bulletsRes = R.array.whats_new_0_69_0),
    Release(versionCode = 119, name = "0.68.0", bulletsRes = R.array.whats_new_0_68_0),
    Release(versionCode = 118, name = "0.67.0", bulletsRes = R.array.whats_new_0_67_0),
    Release(versionCode = 117, name = "0.66.2", bulletsRes = R.array.whats_new_0_66_2),
    Release(versionCode = 116, name = "0.66.1", bulletsRes = R.array.whats_new_0_66_1),
    Release(versionCode = 115, name = "0.66.0", bulletsRes = R.array.whats_new_0_66_0),
    Release(versionCode = 114, name = "0.65.3", bulletsRes = R.array.whats_new_0_65_3),
    Release(versionCode = 113, name = "0.65.2", bulletsRes = R.array.whats_new_0_65_2),
    Release(versionCode = 112, name = "0.65.1", bulletsRes = R.array.whats_new_0_65_1),
    Release(versionCode = 111, name = "0.65.0", bulletsRes = R.array.whats_new_0_65_0),
    Release(versionCode = 110, name = "0.64.0", bulletsRes = R.array.whats_new_0_64_0),
    Release(versionCode = 109, name = "0.63.0", bulletsRes = R.array.whats_new_0_63_0),
    Release(versionCode = 108, name = "0.62.0", bulletsRes = R.array.whats_new_0_62_0),
    Release(versionCode = 107, name = "0.61.1", bulletsRes = R.array.whats_new_0_61_1),
    Release(versionCode = 106, name = "0.61.0", bulletsRes = R.array.whats_new_0_61_0),
    Release(versionCode = 105, name = "0.60.0", bulletsRes = R.array.whats_new_0_60_0),
    Release(versionCode = 104, name = "0.59.0", bulletsRes = R.array.whats_new_0_59_0),
    Release(versionCode = 103, name = "0.58.0", bulletsRes = R.array.whats_new_0_58_0),
    Release(versionCode = 102, name = "0.57.0", bulletsRes = R.array.whats_new_0_57_0),
    Release(versionCode = 101, name = "0.56.0", bulletsRes = R.array.whats_new_0_56_0),
    Release(versionCode = 100, name = "0.55.0", bulletsRes = R.array.whats_new_0_55_0),
    Release(versionCode = 99, name = "0.54.0", bulletsRes = R.array.whats_new_0_54_0),
    Release(versionCode = 98, name = "0.53.0", bulletsRes = R.array.whats_new_0_53_0),
    Release(versionCode = 97, name = "0.52.0", bulletsRes = R.array.whats_new_0_52_0),
    Release(versionCode = 96, name = "0.51.0", bulletsRes = R.array.whats_new_0_51_0),
    Release(versionCode = 95, name = "0.21 – 0.50", bulletsRes = R.array.whats_new_0_21_to_0_50),
    Release(versionCode = 50, name = "0.20.0", bulletsRes = R.array.whats_new_0_20_0),
    Release(versionCode = 49, name = "0.19.3", bulletsRes = R.array.whats_new_0_19_3),
    Release(versionCode = 48, name = "0.19.2", bulletsRes = R.array.whats_new_0_19_2),
    Release(versionCode = 47, name = "0.19.1", bulletsRes = R.array.whats_new_0_19_1),
    Release(versionCode = 46, name = "0.19.0", bulletsRes = R.array.whats_new_0_19_0),
    Release(versionCode = 45, name = "0.18.2", bulletsRes = R.array.whats_new_0_18_2),
    Release(versionCode = 44, name = "0.18.1", bulletsRes = R.array.whats_new_0_18_1),
    Release(versionCode = 43, name = "0.18.0", bulletsRes = R.array.whats_new_0_18_0),
    Release(versionCode = 42, name = "0.17.1", bulletsRes = R.array.whats_new_0_17_1),
    Release(versionCode = 41, name = "0.17.0", bulletsRes = R.array.whats_new_0_17_0),
    Release(versionCode = 40, name = "0.16.0", bulletsRes = R.array.whats_new_0_16_0),
    Release(versionCode = 39, name = "0.15.2", bulletsRes = R.array.whats_new_0_15_2),
    Release(versionCode = 38, name = "0.15.1", bulletsRes = R.array.whats_new_0_15_1),
    Release(versionCode = 37, name = "0.15.0", bulletsRes = R.array.whats_new_0_15_0),
    Release(versionCode = 36, name = "0.14.0", bulletsRes = R.array.whats_new_0_14_0),
    Release(versionCode = 35, name = "0.13.0", bulletsRes = R.array.whats_new_0_13_0),
    Release(versionCode = 34, name = "0.12.0", bulletsRes = R.array.whats_new_0_12_0),
    Release(versionCode = 33, name = "0.11.4", bulletsRes = R.array.whats_new_0_11_4),
    Release(versionCode = 32, name = "0.11.3", bulletsRes = R.array.whats_new_0_11_3),
    Release(versionCode = 31, name = "0.11.2", bulletsRes = R.array.whats_new_0_11_2),
    Release(versionCode = 30, name = "0.11.1", bulletsRes = R.array.whats_new_0_11_1),
    Release(versionCode = 29, name = "0.11.0", bulletsRes = R.array.whats_new_0_11_0),
    Release(versionCode = 28, name = "0.10.0", bulletsRes = R.array.whats_new_0_10_0),
    Release(versionCode = 27, name = "0.9.0", bulletsRes = R.array.whats_new_0_9_0),
    Release(versionCode = 26, name = "0.8.0", bulletsRes = R.array.whats_new_0_8_0),
    Release(versionCode = 25, name = "0.7.7", bulletsRes = R.array.whats_new_0_7_7),
    Release(versionCode = 24, name = "0.7.6", bulletsRes = R.array.whats_new_0_7_6),
    Release(versionCode = 23, name = "0.7.5", bulletsRes = R.array.whats_new_0_7_5),
    Release(versionCode = 22, name = "0.7.4", bulletsRes = R.array.whats_new_0_7_4),
    Release(versionCode = 21, name = "0.7.3", bulletsRes = R.array.whats_new_0_7_3),
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

/** How many releases Settings offers to look back over. Past five is a history, not a change log. */
const val RECENT_RELEASES = 5

@Composable
fun WhatsNewSheet(lastSeenVersionCode: Int, onSeen: (Int) -> Unit) {
    val current = BuildConfig.VERSION_CODE
    val entries = remember(lastSeenVersionCode) { entriesFor(lastSeenVersionCode, current) }
    if (entries.isEmpty()) {
        // Nothing to say (or a fresh install): record the build so the next one is announced.
        LaunchedEffect(lastSeenVersionCode) { if (lastSeenVersionCode < current) onSeen(current) }
        return
    }
    ReleaseNotesSheet(
        entries = entries,
        title = stringResource(R.string.whats_new_title),
        // Dismissing IS having seen it: there is no other way out, and a sheet that comes back
        // because somebody swiped instead of tapping is a sheet nobody reads the second time.
        onDismiss = { onSeen(current) },
    )
}

/**
 * The release notes, whoever asked for them.
 *
 * Two callers with different bookkeeping and one appearance: the sheet that arrives after an
 * update and marks itself seen, and the one somebody opens from Settings to look back, which
 * marks nothing because looking is not the same as being told.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesSheet(entries: List<Release>, title: String, onDismiss: () -> Unit) {
    val spacing = Tokens.spacing
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(modifier = Modifier.padding(horizontal = spacing.screen)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
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
                            text = "\u00b7 $bullet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = spacing.xs),
                        )
                    }
                    Spacer(Modifier.height(spacing.lg))
                }
            }
            Button(
                onClick = onDismiss,
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
