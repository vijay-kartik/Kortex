package dev.kortex.app.ui.anim

import androidx.compose.animation.core.CubicBezierEasing

/** Material 3 easing curves named by Kortex motion specs. */
val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
