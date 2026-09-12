package dev.rwilco.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.rwilco.R
import dev.rwilco.ui.components.RwilcoCard
import dev.rwilco.ui.components.RwilcoTopBar
import dev.rwilco.ui.theme.Tokens

/**
 * How the app works, in seven short pieces.
 *
 * Nothing here teaches itself: the gestures were said once, in the empty state on Home, which is
 * gone the moment the first reminder is written — and nothing anywhere mentioned that routines,
 * keep-in-touch, presets, places or the backup exist at all. That is fine for the person who
 * wrote the app and no use to anybody he hands it to.
 *
 * **A screen you go to, not a tour that comes to you** (there is no onboarding, on purpose): it
 * is reached from Settings → Acerca de and from the empty Home, and it can be left and come back
 * to. Prose in cards, no pictures, no steps, no "next".
 */
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val spacing = Tokens.spacing
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { RwilcoTopBar(title = stringResource(R.string.guide_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screen)
                .padding(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            for ((title, body) in SECTIONS) {
                RwilcoCard {
                    Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Text(
                            text = stringResource(title),
                            style = MaterialTheme.typography.titleMedium,
                            // A screen of prose is walked by its headings, which is the one thing
                            // a reader needs here that the eye gets for free.
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            text = stringResource(body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * In the order somebody meets them: the cards they are looking at, then the three shapes a
 * reminder can take, then where a place and the copy live, and last the one thing that speaks
 * when nobody answered.
 */
private val SECTIONS = listOf(
    R.string.guide_cards_title to R.string.guide_cards_body,
    R.string.guide_routines_title to R.string.guide_routines_body,
    R.string.guide_contacts_title to R.string.guide_contacts_body,
    R.string.guide_presets_title to R.string.guide_presets_body,
    R.string.guide_places_title to R.string.guide_places_body,
    R.string.guide_backup_title to R.string.guide_backup_body,
    R.string.guide_net_title to R.string.guide_net_body,
)
