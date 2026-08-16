package com.laviavi.adsbandroid.di

import android.content.Context
import androidx.room.Room
import com.laviavi.adsbandroid.data.AppDatabase
import com.laviavi.adsbandroid.data.AircraftEventLogDao
import com.laviavi.adsbandroid.data.AircraftHistoryDao
import com.laviavi.adsbandroid.data.AircraftMetaCacheDao
import com.laviavi.adsbandroid.data.AircraftSeenDao
import com.laviavi.adsbandroid.data.AircraftVisitDao
import com.laviavi.adsbandroid.data.BestRangeDao
import com.laviavi.adsbandroid.data.CoverageSampleDao
import com.laviavi.adsbandroid.data.EnrichmentCacheDao
import com.laviavi.adsbandroid.data.GlobalAircraftDao
import com.laviavi.adsbandroid.data.GlobalAircraftImportDao
import com.laviavi.adsbandroid.data.MIGRATION_1_2
import com.laviavi.adsbandroid.data.MIGRATION_2_3
import com.laviavi.adsbandroid.data.MIGRATION_3_4
import com.laviavi.adsbandroid.data.MIGRATION_4_5
import com.laviavi.adsbandroid.data.MIGRATION_5_6
import com.laviavi.adsbandroid.data.MIGRATION_6_7
import com.laviavi.adsbandroid.data.MIGRATION_7_8
import com.laviavi.adsbandroid.data.MIGRATION_8_9
import com.laviavi.adsbandroid.offline.AndroidLocationNamer
import com.laviavi.adsbandroid.offline.AndroidNetworkEligibility
import com.laviavi.adsbandroid.offline.LocationNamer
import com.laviavi.adsbandroid.offline.MapLibreOfflineRepository
import com.laviavi.adsbandroid.offline.NetworkEligibility
import com.laviavi.adsbandroid.pipeline.AppConfig
import com.laviavi.adsbandroid.pipeline.AppConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun provideAppConfig(): AppConfig = AppConfig()
    @Provides @Singleton fun provideAppConfigStore(@ApplicationContext ctx: Context): AppConfigStore =
        AppConfigStore(ctx)
    @Provides @Singleton fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "adsb.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            // Only a genuine downgrade (e.g. switching to an older debug build)
            // falls back to a wipe now — every historical upgrade path (v1-v3)
            // is covered by an explicit migration above.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    @Provides fun provideHistoryDao(db: AppDatabase): AircraftHistoryDao = db.aircraftHistoryDao()
    @Provides fun provideSeenDao(db: AppDatabase): AircraftSeenDao = db.aircraftSeenDao()
    @Provides fun provideEnrichmentDao(db: AppDatabase): EnrichmentCacheDao = db.enrichmentCacheDao()
    @Provides fun provideAircraftMetaCacheDao(db: AppDatabase): AircraftMetaCacheDao = db.aircraftMetaCacheDao()
    @Provides fun provideCoverageSampleDao(db: AppDatabase): CoverageSampleDao = db.coverageSampleDao()
    @Provides fun provideBestRangeDao(db: AppDatabase): BestRangeDao = db.bestRangeDao()
    @Provides fun provideAircraftVisitDao(db: AppDatabase): AircraftVisitDao = db.aircraftVisitDao()
    @Provides fun provideAircraftEventLogDao(db: AppDatabase): AircraftEventLogDao = db.aircraftEventLogDao()
    @Provides fun provideGlobalAircraftDao(db: AppDatabase): GlobalAircraftDao = db.globalAircraftDao()
    @Provides fun provideGlobalAircraftImportDao(db: AppDatabase): GlobalAircraftImportDao = db.globalAircraftImportDao()

    // ── Offline maps ──────────────────────────────────────────────────────────

    @Provides @Singleton fun provideNetworkEligibility(@ApplicationContext ctx: Context): NetworkEligibility =
        AndroidNetworkEligibility(ctx)

    @Provides @Singleton fun provideLocationNamer(@ApplicationContext ctx: Context): LocationNamer =
        AndroidLocationNamer(ctx)

    @Provides @Singleton fun provideMapLibreOfflineRepository(@ApplicationContext ctx: Context): MapLibreOfflineRepository =
        MapLibreOfflineRepository(ctx)
}
