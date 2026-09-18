package dev.kortex.myinfo.topics.di

import javax.inject.Qualifier

/**
 * App-lifetime scope for Topics work that must outlive the screen that started it — tidying up a
 * file a dismissed capture sheet kept, say. The host app has a scope of its own, but `:topics`
 * doesn't depend on it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TopicsScope
