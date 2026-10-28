package io.github.strongsand.lshell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Static tonal slots, deliberately without a fake plot, fake values or shimmer. */
@Composable
internal fun ChartLoadingPlaceholder(title: String, message: String) {
    Surface(Modifier.fillMaxWidth(), shape = DashDesign.hero,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Surface(Modifier.fillMaxWidth().heightIn(min = 180.dp).clearAndSetSemantics {},
                shape = DashDesign.section, color = MaterialTheme.colorScheme.surfaceContainerHigh) {}
        }
    }
}
