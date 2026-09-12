package com.example.rinklnote.navigation

/**
 * 快捷记账当前展示的唯一界面。
 *
 * 抽屉与输入法通过两个过渡态串行动画，过渡期间两者都不显示，避免旧实现里
 * 抽屉仍留在底层、输入法同时盖住页面的现象。
 */
internal enum class QuickAddSurface(
    val drawerVisible: Boolean,
    val keypadVisible: Boolean
) {
    Closed(drawerVisible = false, keypadVisible = false),
    Drawer(drawerVisible = true, keypadVisible = false),
    DrawerToKeypad(drawerVisible = false, keypadVisible = false),
    Keypad(drawerVisible = false, keypadVisible = true),
    KeypadToDrawer(drawerVisible = false, keypadVisible = false);

    fun openDrawer(): QuickAddSurface = Drawer

    fun onAmountTap(): QuickAddSurface =
        if (this == Drawer) DrawerToKeypad else this

    fun onKeypadDismissed(): QuickAddSurface =
        if (this == Keypad) KeypadToDrawer else this

    fun onTransitionFinished(): QuickAddSurface = when (this) {
        DrawerToKeypad -> Keypad
        KeypadToDrawer -> Drawer
        else -> this
    }

    fun close(): QuickAddSurface = Closed
}
