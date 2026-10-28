package io.github.strongsand.lshell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Direction is iconography; accessibility reads the metric name and exact value. */
@Composable
internal fun DirectionalMetric(download: Boolean, value: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val label = stringResource(if (download) R.string.traffic_download else R.string.traffic_upload)
    Row(modifier.clearAndSetSemantics { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (download) OrbitSymbols.Download else OrbitSymbols.Upload, null, Modifier.size(18.dp))
        Text(value, style = style)
    }
}
