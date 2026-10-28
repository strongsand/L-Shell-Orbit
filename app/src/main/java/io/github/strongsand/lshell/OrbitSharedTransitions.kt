@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package io.github.strongsand.lshell

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

val LocalOrbitShared = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalOrbitVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable
fun Modifier.orbitSharedBounds(key: String): Modifier {
    val shared = LocalOrbitShared.current ?: return this
    val visibility = LocalOrbitVisibility.current ?: return this
    if (!LocalOrbitMotion.current) return this
    return with(shared) {
        sharedBounds(rememberSharedContentState(key), animatedVisibilityScope = visibility)
    }
}
