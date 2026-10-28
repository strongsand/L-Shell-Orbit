package io.github.strongsand.lshell

/** Route family determines selection, regardless of menu, back or explicit deep link. */
internal object BottomNavigationPresentation {
    fun index(tab: MainTab): Int = when (tab) {
        MainTab.HOME, MainTab.DOWNLOAD, MainTab.UPLOAD, MainTab.LATENCY, MainTab.SIGNAL, MainTab.POWER -> 0
        MainTab.DISH -> 1
        MainTab.NETWORK -> 2
        MainTab.SKY -> -1
        else -> 3
    }
}
