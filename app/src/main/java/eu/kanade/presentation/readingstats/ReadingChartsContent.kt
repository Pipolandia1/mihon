package eu.kanade.presentation.readingstats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding

/** One bar: its value, or null when it is unknown (not recorded), and an optional label under it. */
@Immutable
data class ChartBar(val value: Long?, val label: String?, val highlighted: Boolean = false)

private const val MIN_BAR_FRACTION = 0.04f
private const val BAR_WIDTH_FRACTION = 0.7f
private const val UNKNOWN_ALPHA = 0.25f
private const val EMPTY_ALPHA = 0.15f
private const val DIM_ALPHA = 0.7f

/**
 * Bars as tall as their share of the largest. An unknown bar is an outline, so "not recorded"
 * never looks like "nothing read". [description] is what a screen reader says for the whole chart.
 */
@Composable
fun BarChart(
    bars: List<ChartBar>,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
) {
    val largest = bars.maxOfOrNull { it.value ?: 0 }?.coerceAtLeast(1) ?: 1
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(modifier = modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { bar ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val value = bar.value
                    when {
                        value == null -> Box(
                            modifier = Modifier
                                .fillMaxWidth(BAR_WIDTH_FRACTION)
                                .fillMaxHeight(MIN_BAR_FRACTION * 2)
                                .border(1.dp, onSurface.copy(alpha = UNKNOWN_ALPHA), RoundedCornerShape(2.dp)),
                        )
                        value == 0L -> Box(
                            modifier = Modifier
                                .fillMaxWidth(BAR_WIDTH_FRACTION)
                                .height(2.dp)
                                .background(onSurface.copy(alpha = EMPTY_ALPHA)),
                        )
                        else -> Box(
                            modifier = Modifier
                                .fillMaxWidth(BAR_WIDTH_FRACTION)
                                .fillMaxHeight((value.toFloat() / largest).coerceAtLeast(MIN_BAR_FRACTION))
                                .background(
                                    if (bar.highlighted) primary else primary.copy(alpha = DIM_ALPHA),
                                    RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                                ),
                        )
                    }
                }
            }
        }
        if (bars.any { it.label != null }) {
            Spacer(Modifier.height(MaterialTheme.padding.extraSmall))
            Row(modifier = Modifier.fillMaxWidth()) {
                bars.forEach { bar ->
                    Text(
                        text = bar.label.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurface.copy(alpha = SECONDARY_ALPHA),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** A ranked value: its name, a figure, and a bar for its share. */
@Composable
fun ShareRow(label: String, figure: String, fraction: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.extraSmall),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = figure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                modifier = Modifier.padding(start = MaterialTheme.padding.small),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = EMPTY_ALPHA), RoundedCornerShape(2.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
        }
    }
}
