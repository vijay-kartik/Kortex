package dev.kortex.app.di

import javax.inject.Qualifier

/** App-lifetime [kotlinx.coroutines.CoroutineScope] for work that must outlive any activity or ViewModel. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
