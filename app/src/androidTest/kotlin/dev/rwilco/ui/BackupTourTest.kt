package dev.rwilco.ui

import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.rwilco.BuildConfig
import dev.rwilco.MainActivity
import dev.rwilco.R
import dev.rwilco.RwilcoApplication
import dev.rwilco.model.ThemeMode
import dev.rwilco.vault.VaultOutcome
import dev.rwilco.vault.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks Settings → Backup through Compose semantics and captures the screen in each of its
 * states — off, on, stopped by a conflict — on the dark scheme. No network is touched: the
 * states are written straight into the vault's store, which is what the screen reads.
 */
@RunWith(AndroidJUnit4::class)
class BackupTourTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun useSpanish() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
        }
    }

    /**
     * Handed over rather than asked for: the app asks for notifications on its first resume, and
     * a system dialog over the screen is a tap that lands nowhere and a screenshot of the
     * permission controller.
     */
    @get:Rule(order = 0)
    val notifications: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app get() = context.applicationContext as RwilcoApplication

    private fun s(id: Int): String = rule.activity.getString(id)

    @Before
    fun darkAndOff() = runBlocking {
        app.settingsStore.update { it.copy(lastSeenVersionCode = BuildConfig.VERSION_CODE, theme = ThemeMode.DARK) }
        app.vaultStore.clear()
    }

    @After
    fun off() = runBlocking { app.vaultStore.clear() }

    @Test
    fun theBackupScreenInEachOfItsStates() {
        rule.onNodeWithContentDescription(s(R.string.home_settings)).performClick()
        rule.waitUntilShown(s(R.string.vault_card_title))
        rule.onNodeWithText(s(R.string.vault_card_title), useUnmergedTree = true).performScrollTo().performClick()
        rule.waitUntilShown(s(R.string.vault_enable))
        shot("backup-off")

        runBlocking {
            app.vaultStore.update {
                VaultState(enabled = true, owner = "olemoudi", repo = "rwilco-vault", pat = "x", key = "a2V5", salt = "c2FsdA==", deviceId = "tour")
            }
        }
        rule.waitUntilShown(s(R.string.vault_backup_now))
        shot("backup-on")

        runBlocking { app.vaultStore.update { it.copy(lastOutcome = VaultOutcome.CONFLICT, lastOutcomeAt = app.clock.instant()) } }
        rule.waitUntilShown(s(R.string.vault_conflict_keep_phone))
        shot("backup-conflict")

    }

    @Test
    fun theHomeBadgeCountsWhatIsWaiting() {
        // Nothing is navigated: the rule starts at Home, and turning the vault on from under it
        // is what the badge is for — it appears because this vault has never copied anything.
        rule.waitUntilShown(s(R.string.app_name))
        runBlocking {
            app.vaultStore.update {
                it.copy(enabled = true, owner = "olemoudi", repo = "rwilco-vault", pat = "x", key = "a2V5", salt = "c2FsdA==", deviceId = "tour")
            }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithContentDescription(waitingLabel(), substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("home-badge")
    }

    /** The badge says what it is for; the number depends on what this phone happens to hold. */
    private fun waitingLabel(): String = s(R.string.vault_card_title).take(0) + "copiarse"

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.waitUntilShown(text: String) {
        // Case-insensitively: the wordmark on Home is the app's name uppercased, which is what
        // the badge test waits for, and an exact "Rwilco" has not matched it since the header
        // was rewritten.
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(text, ignoreCase = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(1_500)
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: rule.onRoot().captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
