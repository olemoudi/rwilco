package dev.rwilco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.rwilco.R
import androidx.compose.ui.res.stringResource
import dev.rwilco.ui.theme.Tokens

/**
 * What a list looks like before the first row has arrived: [count] card shapes, faded, each with
 * two blank lines where the words will be. Static on purpose — Home allows nothing that moves
 * on its own — and worn instead of an empty screen, because a blank Home for two seconds reads
 * as "there is nothing", which is the one thing it must not say before it knows.
 */
@Composable
fun ListPlaceholder(modifier: Modifier = Modifier, count: Int = 3) {
    val spacing = Tokens.spacing
    val line = MaterialTheme.colorScheme.surfaceContainerHighest
    val description = stringResource(R.string.common_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(PLACEHOLDER_ALPHA)
            .semantics { contentDescription = description },
    ) {
        repeat(count) { index ->
            RwilcoCard {
                Column(Modifier.padding(spacing.lg)) {
                    Box(
                        Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.7f else 0.5f)
                            .height(Tokens.sizes.keycap / 2)
                            .background(line, MaterialTheme.shapes.small),
                    )
                    Spacer(Modifier.height(spacing.md))
                    Box(
                        Modifier
                            .fillMaxWidth(0.35f)
                            .height(Tokens.sizes.keycap / 3)
                            .background(line, MaterialTheme.shapes.small),
                    )
                }
            }
            if (index < count - 1) Spacer(Modifier.height(spacing.md))
        }
    }
}

private const val PLACEHOLDER_ALPHA = 0.6f
