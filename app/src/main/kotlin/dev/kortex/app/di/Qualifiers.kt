package dev.kortex.app.di

import javax.inject.Qualifier

/** App-lifetime [kotlinx.coroutines.CoroutineScope] for work that must outlive any activity or ViewModel. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Shared `MutableStateFlow<Set<String>>` of MCP server names that failed with an auth error at startup. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class McpAuthFailures
