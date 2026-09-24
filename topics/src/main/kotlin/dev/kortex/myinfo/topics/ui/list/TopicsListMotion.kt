package dev.kortex.myinfo.topics.ui.list

// Same timing as the Links options tray.
internal const val PRESS_MS = 120
internal const val TRAY_MS = 250

/** How far the rest of the list fades while a card's options tray is open. */
internal const val DIMMED_ALPHA = 0.35f

/** Cross-fade between a topic card and its undo row. */
internal const val SWAP_MS = 200

// Same as the Links +: leaves with the top bar's first search phase, returns once the field has closed.
internal const val FAB_OUT_MS = 150
internal const val FAB_IN_MS = 200
internal const val FAB_IN_DELAY_MS = 150
