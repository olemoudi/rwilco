package dev.rwilco.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.R
import dev.rwilco.ui.components.UpdateReadyBadge
import dev.rwilco.ui.settings.SettingsGroup
import dev.rwilco.ui.theme.RwilcoTheme
import dev.rwilco.ui.theme.Tokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The green arrow of a downloaded update, on the cog and on the folded Updates row, on the dark
 * scheme — each once without it and once with it, so the mark is the only difference on the screen.
 *
 * Rendered from the pieces rather than reached through the app, because a real waiting update
 * needs a newer APK of this very package in the cache; what cannot be judged from the code is how
 * an 18dp disc sits on a 32dp cog and a 28dp square, and that is what this puts in a picture.
 */
@RunWith(AndroidJUnit4::class)
class UpdateReadyBadgeTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theArrowMarksTheCogAndTheFoldedUpdatesRow() {
        rule.setContent {
            RwilcoTheme(darkTheme = true) {
                Surface {
                    Column(Modifier.padding(16.dp)) {
                        for (ready in listOf(false, true)) {
                            // As the cog stands beside the wordmark on Home.
                            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(8.dp))
                                Box {
                                    Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(Tokens.sizes.cog))
                                    UpdateReadyBadge(
                                        visible = ready,
                                        ground = MaterialTheme.colorScheme.background,
                                        modifier = Modifier.align(Alignment.TopEnd),
                                    )
                                }
                            }
                            SettingsGroup(
                                icon = Icons.Outlined.SystemUpdate,
                                title = context.getString(R.string.settings_updates),
                                summary = context.getString(R.string.settings_summary_wifi_only),
                                expanded = false,
                                onToggle = {},
                                corner = { ground -> UpdateReadyBadge(visible = ready, ground = ground) },
                            ) {}
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val marks = rule.onAllNodesWithContentDescription(context.getString(R.string.update_ready_badge), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertEquals("one on the cog and one on the row, and only where an update is waiting", 2, marks.size)
        val bitmap: Bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        File(dir, "update-ready-badge.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
