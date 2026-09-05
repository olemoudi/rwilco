package dev.rwilco.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.rwilco.R

/**
 * Two readings on one line, joined the way the locale wants them: "hoy · 17:56", "Alerta ·
 * fuerte". The separator lived as a literal in a dozen files and once as a resource with a
 * helper beside it, in Settings (0.94.0). This is that helper, for everyone.
 */
@Composable
fun join(first: String, second: String): String = stringResource(R.string.common_join, first, second)

fun Words.join(first: String, second: String): String = get(R.string.common_join, first, second)

/** Several readings on one line — a card's tags, a rule's fences — with the same separator. */
@Composable
fun joinAll(parts: List<String>): String = parts.joinToString(stringResource(R.string.common_separator))
