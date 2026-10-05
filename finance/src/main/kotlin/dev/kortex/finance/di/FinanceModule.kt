package dev.kortex.finance.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.finance.data.RoomFinanceRepository
import dev.kortex.finance.data.local.FinanceDao
import dev.kortex.finance.data.local.FinanceDatabase
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.FinanceRepository
import dev.kortex.finance.domain.usecase.AddAccount
import dev.kortex.finance.domain.usecase.AddCategory
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.DeleteAccount
import dev.kortex.finance.domain.usecase.DeleteCategory
import dev.kortex.finance.domain.usecase.DeleteTransaction
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.UpdateAccount
import dev.kortex.finance.domain.usecase.UpdateCategory
import javax.inject.Singleton

/** Finance storage and use cases. Domain and data classes carry no DI annotations; they are built here. */
@Module
@InstallIn(SingletonComponent::class)
object FinanceModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FinanceDatabase =
        Room.databaseBuilder(context, FinanceDatabase::class.java, "finance.db")
            .addCallback(FinanceDatabase.CALLBACK)
            .build()

    @Provides
    fun provideFinanceDao(database: FinanceDatabase): FinanceDao = database.financeDao()

    @Provides
    fun provideClock(): Clock = Clock.System

    @Provides
    @Singleton
    fun provideRepository(dao: FinanceDao, clock: Clock): FinanceRepository = RoomFinanceRepository(dao, clock)

    @Provides
    fun provideObserveFinance(repository: FinanceRepository) = ObserveFinance(repository)

    @Provides
    fun provideAddAccount(repository: FinanceRepository, clock: Clock) = AddAccount(repository, clock)

    @Provides
    fun provideAddTransaction(repository: FinanceRepository, clock: Clock) = AddTransaction(repository, clock)

    @Provides
    fun provideAddCategory(repository: FinanceRepository) = AddCategory(repository)

    @Provides
    fun provideUpdateCategory(repository: FinanceRepository) = UpdateCategory(repository)

    @Provides
    fun provideDeleteCategory(repository: FinanceRepository) = DeleteCategory(repository)

    @Provides
    fun provideUpdateAccount(repository: FinanceRepository) = UpdateAccount(repository)

    @Provides
    fun provideDeleteAccount(repository: FinanceRepository) = DeleteAccount(repository)

    @Provides
    fun provideDeleteTransaction(repository: FinanceRepository) = DeleteTransaction(repository)
}
