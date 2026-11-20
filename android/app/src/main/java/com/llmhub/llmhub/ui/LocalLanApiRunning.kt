package com.llmhub.llmhub.ui

import androidx.compose.runtime.compositionLocalOf

/** True while the local HTTP API is running; used to dim Chat and lock phone agent tools. */
val LocalLanApiRunning = compositionLocalOf { false }
