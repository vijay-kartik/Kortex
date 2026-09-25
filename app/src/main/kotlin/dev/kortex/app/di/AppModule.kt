package dev.kortex.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.app.BuildConfig
import dev.kortex.app.data.auth.GmailAuthManager
import dev.kortex.app.data.auth.McpOAuthManager
import dev.kortex.app.data.security.AppLockStore
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.app.domain.security.AppLock
import dev.kortex.links.data.LinkSyncDao
import dev.kortex.links.images.LinkImageStore
import dev.kortex.sync.CloudAccount
import dev.kortex.sync.CloudSync
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** App-wide infrastructure: coroutine scope, settings persistence and auth managers. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Written by ChatViewModel's startup MCP connect pass, read by SettingsViewModel. */
    @Provides
    @Singleton
    @McpAuthFailures
    fun provideMcpAuthFailures(): MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    // MCP settings persistence (user-added servers + disabled tool names).
    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext context: Context): SettingsStore = SettingsStore(context)

    /** The signed-in Google account; Kortex can't be used without one. */
    @Provides
    @Singleton
    fun provideCloudAccount(@ApplicationContext context: Context): CloudAccount =
        CloudAccount(context, BuildConfig.FIREBASE_WEB_CLIENT_ID)

    /** Manual cloud sync of links, from Settings › Cloud sync. */
    @Provides
    @Singleton
    fun provideCloudSync(
        @ApplicationContext context: Context,
        account: CloudAccount,
        linkSyncDao: LinkSyncDao,
        linkImages: LinkImageStore,
    ): CloudSync = CloudSync(context, account, linkSyncDao, linkImages)

    /** Biometric app lock: one lock state shared by every activity. */
    @Provides
    @Singleton
    fun provideAppLock(@ApplicationContext context: Context): AppLock = AppLock(AppLockStore(context))

    // Gmail OAuth2 token management (uses device's Google accounts).
    @Provides
    @Singleton
    fun provideGmailAuthManager(@ApplicationContext context: Context): GmailAuthManager = GmailAuthManager(context)

    @Provides
    @Singleton
    fun provideMcpOAuthManager(
        @ApplicationContext context: Context,
        settingsStore: SettingsStore,
        @ApplicationScope appScope: CoroutineScope,
    ): McpOAuthManager = McpOAuthManager(
        context = context,
        settingsStore = settingsStore,
        appScope = appScope,
    )
}
