package com.example

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun mainViewController(): UIViewController = ComposeUIViewController {
    SpaceDodgerGame()
}
