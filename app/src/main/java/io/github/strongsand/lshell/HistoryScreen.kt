package io.github.strongsand.lshell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun HistoryScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    OccurrenceHistoryScreen(modifier, onBack)
}
