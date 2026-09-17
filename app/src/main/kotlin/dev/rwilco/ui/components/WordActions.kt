package dev.rwilco.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import dev.rwilco.R
import dev.rwilco.model.Actionable
import dev.rwilco.model.actionablesIn

/**
 * Hands what was found in a reminder's words to the phone (0.135.0): the dialer **with the number
 * in it and the call not made** — `ACTION_DIAL` needs no permission and rings nobody — or the
 * browser. False when nothing on the phone would take it, which the caller says out loud.
 *
 * Tried and caught rather than asked about first: the manifest declares no `<queries>`, so
 * `resolveActivity` would answer "nothing" for a dialer that is plainly there.
 */
fun Context.open(actionable: Actionable): Boolean = runCatching {
    val intent = when (actionable) {
        is Actionable.Phone -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", actionable.dial, null))
        is Actionable.Link -> Intent(Intent.ACTION_VIEW, Uri.parse(actionable.url))
    }
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess

/** What a card's menu can do with the words themselves: see [rememberWordActions]. */
class WordActions(val found: List<Actionable>, val open: (Actionable) -> Unit, val copy: () -> Unit)

/**
 * The rows a menu offers about a reminder's own words: ring the number in them, open the link in
 * them, copy them. One place for the two lists that have the menu (Home, the routines), so the
 * clipboard, the snackbar and the refusal are said the same way in both. [then] is the menu
 * closing, which every one of them does first.
 */
@Composable
fun rememberWordActions(text: String, then: () -> Unit): WordActions {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current
    val nothingOpensIt = stringResource(R.string.menu_nothing_opens_it)
    val copied = stringResource(R.string.menu_text_copied)
    val found = remember(text) { actionablesIn(text) }
    return WordActions(
        found = found,
        open = { actionable ->
            then()
            if (!context.open(actionable)) snackbar.show(nothingOpensIt)
        },
        copy = {
            then()
            clipboard.setText(AnnotatedString(text))
            // From Android 13 the system says so itself, over whatever is on screen; a second
            // voice under it is the app talking over the phone.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.show(copied)
        },
    )
}
