package dev.kortex.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Root of the Hilt dependency graph (modules in `di/`). Registered as android:name in the manifest. */
@HiltAndroidApp
class KortexApp : Application()
