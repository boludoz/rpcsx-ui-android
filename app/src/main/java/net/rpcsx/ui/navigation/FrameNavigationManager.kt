package net.rpcsx.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class NavigationFrame {
    HEADER,
    GAMES_GRID,
    BOTTOM_DOCK
}

enum class FocusLevel {
    FRAME_LEVEL,  // Outer frame container is highlighted in blue
    ITEM_LEVEL    // Individual items inside the frame are highlighted in blue
}

data class NavResult(val consumed: Boolean, val frameChanged: Boolean = false)

object FrameNavigationManager {
    var activeFrame by mutableStateOf(NavigationFrame.GAMES_GRID)
    var focusLevel by mutableStateOf(FocusLevel.FRAME_LEVEL)
    var isGamepadInputActive by mutableStateOf(false)
    var activeDockIndex by mutableIntStateOf(0) // 0: Games, 1: Controls, 2: Add, 3: Directories, 4: Settings
    var activeGameIndex by mutableIntStateOf(0)
    var totalGamesCount by mutableIntStateOf(1)

    // Callbacks registered by UI components
    var onPerformDockAction: ((Int) -> Unit)? = null
    var onPerformGameBoot: ((Int) -> Unit)? = null

    fun onCrossPressed(): Boolean {
        isGamepadInputActive = true
        if (focusLevel == FocusLevel.FRAME_LEVEL) {
            focusLevel = FocusLevel.ITEM_LEVEL
            return true
        }
        // At ITEM_LEVEL: execute item click!
        if (activeFrame == NavigationFrame.BOTTOM_DOCK) {
            onPerformDockAction?.invoke(activeDockIndex)
            return true
        }
        if (activeFrame == NavigationFrame.GAMES_GRID) {
            onPerformGameBoot?.invoke(activeGameIndex)
            return true
        }
        return false
    }

    fun onCirclePressed(): Boolean {
        isGamepadInputActive = true
        if (focusLevel == FocusLevel.ITEM_LEVEL) {
            focusLevel = FocusLevel.FRAME_LEVEL
            return true
        }
        return false
    }

    fun onDpadUp(): NavResult {
        isGamepadInputActive = true
        if (focusLevel == FocusLevel.FRAME_LEVEL) {
            val oldFrame = activeFrame
            when (activeFrame) {
                NavigationFrame.BOTTOM_DOCK -> activeFrame = NavigationFrame.GAMES_GRID
                NavigationFrame.GAMES_GRID -> activeFrame = NavigationFrame.HEADER
                NavigationFrame.HEADER -> {}
            }
            return NavResult(consumed = true, frameChanged = oldFrame != activeFrame)
        }
        if (activeFrame == NavigationFrame.GAMES_GRID) {
            if (activeGameIndex - 2 >= 0) {
                activeGameIndex -= 2
                return NavResult(consumed = true, frameChanged = false)
            } else {
                focusLevel = FocusLevel.FRAME_LEVEL
                return NavResult(consumed = true, frameChanged = true)
            }
        }
        return NavResult(consumed = false)
    }

    fun onDpadDown(): NavResult {
        isGamepadInputActive = true
        if (focusLevel == FocusLevel.FRAME_LEVEL) {
            val oldFrame = activeFrame
            when (activeFrame) {
                NavigationFrame.HEADER -> activeFrame = NavigationFrame.GAMES_GRID
                NavigationFrame.GAMES_GRID -> activeFrame = NavigationFrame.BOTTOM_DOCK
                NavigationFrame.BOTTOM_DOCK -> {}
            }
            return NavResult(consumed = true, frameChanged = oldFrame != activeFrame)
        }
        if (activeFrame == NavigationFrame.GAMES_GRID) {
            if (activeGameIndex + 2 < totalGamesCount) {
                activeGameIndex += 2
                return NavResult(consumed = true, frameChanged = false)
            }
        }
        return NavResult(consumed = false)
    }

    fun onDpadLeft(): NavResult {
        isGamepadInputActive = true
        if (focusLevel == FocusLevel.FRAME_LEVEL) return NavResult(consumed = true)
        if (activeFrame == NavigationFrame.BOTTOM_DOCK) {
            activeDockIndex = (activeDockIndex - 1).coerceAtLeast(0)
            return NavResult(consumed = true)
        }
        if (activeFrame == NavigationFrame.GAMES_GRID) {
            activeGameIndex = (activeGameIndex - 1).coerceAtLeast(0)
            return NavResult(consumed = true)
        }
        return NavResult(consumed = false)
    }

    fun onDpadRight(): NavResult {
        isGamepadInputActive = true
        if (focusLevel == FocusLevel.FRAME_LEVEL) return NavResult(consumed = true)
        if (activeFrame == NavigationFrame.BOTTOM_DOCK) {
            activeDockIndex = (activeDockIndex + 1).coerceAtMost(4)
            return NavResult(consumed = true)
        }
        if (activeFrame == NavigationFrame.GAMES_GRID) {
            activeGameIndex = (activeGameIndex + 1).coerceAtMost((totalGamesCount - 1).coerceAtLeast(0))
            return NavResult(consumed = true)
        }
        return NavResult(consumed = false)
    }
}
