package dev.kortex.finance.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kortex.finance.agent.FinanceAgent
import dev.kortex.finance.data.RoomFinanceRepository
import dev.kortex.finance.data.RoomSmsInboxRepository
import dev.kortex.finance.data.local.FinanceDao
import dev.kortex.finance.data.local.FinanceDatabase
import dev.kortex.finance.data.local.FinanceSyncDao
import dev.kortex.finance.data.secure.KeystoreSecretBox
import dev.kortex.finance.domain.port.FinanceKeySource
import dev.kortex.finance.domain.port.SecretBox
import dev.kortex.finance.domain.usecase.RevealNumber
import dev.kortex.finance.domain.port.Clock
import dev.kortex.finance.domain.repository.FinanceRepository
import dev.kortex.finance.domain.repository.SmsInboxRepository
import dev.kortex.finance.domain.usecase.AddAccount
import dev.kortex.finance.domain.usecase.AddCategory
import dev.kortex.finance.domain.usecase.AddTransaction
import dev.kortex.finance.domain.usecase.ClearBudget
import dev.kortex.finance.domain.usecase.DeleteAccount
import dev.kortex.finance.domain.usecase.DeleteCategory
import dev.kortex.finance.domain.usecase.DeleteTransaction
import dev.kortex.finance.domain.usecase.ObserveFinance
import dev.kortex.finance.domain.usecase.UpdateAccount
import dev.kortex.finance.domain.usecase.UpdateCategory
import dev.kortex.finance.domain.usecase.DeleteRecurring
import dev.kortex.finance.domain.usecase.MarkPaid
import dev.kortex.finance.domain.usecase.PayCardBill
import dev.kortex.finance.domain.usecase.RunFinanceEngine
import dev.kortex.finance.domain.usecase.SaveRecurring
import dev.kortex.finance.domain.usecase.SetBudget
import dev.kortex.finance.domain.usecase.SkipOccurrence
import dev.kortex.finance.domain.usecase.UndoOccurrence
import dev.kortex.finance.sms.BankSmsStore
import dev.kortex.finance.domain.usecase.AttachReceipt
import dev.kortex.finance.domain.usecase.ReadReceipt
import dev.kortex.finance.domain.usecase.ImportSms
import dev.kortex.finance.domain.usecase.PrepareSmsEntry
import dev.kortex.finance.domain.usecase.ProcessSmsInbox
import dev.kortex.finance.domain.usecase.ReadSms
import dev.kortex.finance.domain.usecase.ReceiveSms
import dev.kortex.finance.domain.usecase.ResolveInboxSms
import dev.kortex.finance.domain.usecase.SuggestCategory
import dev.kortex.finance.domain.usecase.SuggestMerchant
import dev.kortex.finance.domain.read.FinanceDecider
import dev.kortex.finance.domain.read.FinanceReader
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
            .addMigrations(
                FinanceDatabase.MIGRATION_1_2,
                FinanceDatabase.MIGRATION_2_3,
                FinanceDatabase.MIGRATION_3_4,
                FinanceDatabase.MIGRATION_4_5,
            )
            .build()

    @Provides
    fun provideFinanceDao(database: FinanceDatabase): FinanceDao = database.financeDao()

    /** For sync in `:sync`, which pushes dirty rows and applies pulled ones. */
    @Provides
    fun provideFinanceSyncDao(database: FinanceDatabase): FinanceSyncDao = database.financeSyncDao()

    @Provides
    fun provideClock(): Clock = Clock.System

    /** Settings › "Add bank SMS automatically"; one instance, read by the SMS receiver and the settings screen. */
    @Provides
    @Singleton
    fun provideBankSmsStore(@ApplicationContext context: Context): BankSmsStore = BankSmsStore(context)

    @Provides
    @Singleton
    fun provideSmsInbox(database: FinanceDatabase): SmsInboxRepository = RoomSmsInboxRepository(database.smsInboxDao())

    @Provides
    @Singleton
    fun provideRepository(dao: FinanceDao, clock: Clock): FinanceRepository = RoomFinanceRepository(dao, clock)

    @Provides
    fun provideObserveFinance(repository: FinanceRepository) = ObserveFinance(repository)

    /** FinanceKeySource (the `financeKey` function) is bound by the app, from `:sync`. */
    @Provides
    @Singleton
    fun provideSecretBox(@ApplicationContext context: Context, keys: FinanceKeySource): SecretBox = KeystoreSecretBox(context, keys)

    @Provides
    fun provideRevealNumber(repository: FinanceRepository, secrets: SecretBox) = RevealNumber(repository, secrets)

    @Provides
    fun provideAddAccount(repository: FinanceRepository, clock: Clock, secrets: SecretBox) = AddAccount(repository, clock, secrets)

    @Provides
    fun provideAddTransaction(repository: FinanceRepository, clock: Clock) = AddTransaction(repository, clock)

    @Provides
    fun provideAddCategory(repository: FinanceRepository) = AddCategory(repository)

    @Provides
    fun provideUpdateCategory(repository: FinanceRepository) = UpdateCategory(repository)

    @Provides
    fun provideDeleteCategory(repository: FinanceRepository) = DeleteCategory(repository)

    @Provides
    fun provideSetBudget(repository: FinanceRepository) = SetBudget(repository)

    @Provides
    fun provideClearBudget(repository: FinanceRepository) = ClearBudget(repository)

    @Provides
    fun provideUpdateAccount(repository: FinanceRepository, secrets: SecretBox) = UpdateAccount(repository, secrets)

    @Provides
    fun provideDeleteAccount(repository: FinanceRepository) = DeleteAccount(repository)

    @Provides
    fun provideDeleteTransaction(repository: FinanceRepository) = DeleteTransaction(repository)

    @Provides
    fun provideSaveRecurring(repository: FinanceRepository, clock: Clock) = SaveRecurring(repository, clock)

    @Provides
    fun provideDeleteRecurring(repository: FinanceRepository) = DeleteRecurring(repository)

    @Provides
    fun provideMarkPaid(repository: FinanceRepository, addTransaction: AddTransaction, clock: Clock) =
        MarkPaid(repository, addTransaction, clock)

    @Provides
    fun provideSkipOccurrence(repository: FinanceRepository) = SkipOccurrence(repository)

    @Provides
    fun provideUndoOccurrence(repository: FinanceRepository) = UndoOccurrence(repository)

    @Provides
    fun providePayCardBill(repository: FinanceRepository, addTransaction: AddTransaction, clock: Clock) =
        PayCardBill(repository, addTransaction, clock)

    /** One instance, so two screens opening at once don't run the engine side by side. */
    @Provides
    @Singleton
    fun provideRunFinanceEngine(repository: FinanceRepository, markPaid: MarkPaid, clock: Clock) =
        RunFinanceEngine(repository, markPaid, clock)

    // FinanceReader (the agent's model) and FinanceDecider (the decision model) are bound by the
    // app, which owns the model settings and keys; so is BackgroundSync, since the app owns sync.

    @Provides
    fun provideReadSms(reader: FinanceReader, clock: Clock, decider: FinanceDecider) = ReadSms(reader, clock, decider)

    @Provides
    fun provideReadReceipt(reader: FinanceReader, clock: Clock) = ReadReceipt(reader, clock)

    @Provides
    fun provideSuggestMerchant(repository: FinanceRepository, reader: FinanceReader, decider: FinanceDecider) =
        SuggestMerchant(repository, reader, decider)

    @Provides
    fun providePrepareSmsEntry(suggestMerchant: SuggestMerchant, clock: Clock) = PrepareSmsEntry(suggestMerchant, clock)

    @Provides
    fun provideReceiveSms(inbox: SmsInboxRepository) = ReceiveSms(inbox)

    @Provides
    fun provideImportSms(inbox: SmsInboxRepository) = ImportSms(inbox)

    @Provides
    fun provideResolveInboxSms(inbox: SmsInboxRepository, deleteTransaction: DeleteTransaction) = ResolveInboxSms(inbox, deleteTransaction)

    @Provides
    fun provideProcessSmsInbox(
        inbox: SmsInboxRepository,
        readSms: ReadSms,
        prepareSmsEntry: PrepareSmsEntry,
        addTransaction: AddTransaction,
        observeFinance: ObserveFinance,
        clock: Clock,
    ) = ProcessSmsInbox(inbox, readSms, prepareSmsEntry, addTransaction, observeFinance, clock)

    @Provides
    fun provideSuggestCategory(repository: FinanceRepository, decider: FinanceDecider) = SuggestCategory(repository, decider)

    @Provides
    fun provideAttachReceipt(repository: FinanceRepository) = AttachReceipt(repository)

    /** What the agent's finance tools do; the tools themselves are defined in the app. */
    @Provides
    fun provideFinanceAgent(observeFinance: ObserveFinance, addTransaction: AddTransaction, markPaid: MarkPaid, clock: Clock) =
        FinanceAgent(observeFinance, addTransaction, markPaid, clock)
}
