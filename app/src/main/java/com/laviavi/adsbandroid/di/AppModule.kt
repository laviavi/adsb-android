package com.laviavi.adsbandroid.di

import android.content.Context
import androidx.room.Room
import com.laviavi.adsbandroid.data.AppDatabase
import com.laviavi.adsbandroid.data.AircraftHistoryDao
import com.laviavi.adsbandroid.data.AircraftMetaCacheDao
import com.laviavi.adsbandroid.data.AircraftSeenDao
import com.laviavi.adsbandroid.data.AircraftVisitDao
import com.laviavi.adsbandroid.data.EnrichmentCacheDao
import com.laviavi.adsbandroid.data.MIGRATION_1_2
import com.laviavi.adsbandroid.data.MIGRATION_2_3
import com.laviavi.adsbandroid.data.MIGRATION_3_4
import com.laviavi.adsbandroid.data.MIGRATION_4_5
import com.laviavi.adsbandroid.data.MIGRATION_5_6
import com.laviavi.adsbandroid.offline.AndroidLocationNamer
import com.laviavi.adsbandroid.offline.AndroidNetworkEligibility
import com.laviavi.adsbandroid.offline.ConfigurableTileDownloader
import com.laviavi.adsbandroid.offline.LocalTileSource
import com.laviavi.adsbandroid.offline.OsmdroidCacheSource
import com.laviavi.adsbandroid.offline.FileManifestStore
import com.laviavi.adsbandroid.offline.FileTileStore
import com.laviavi.adsbandroid.offline.LocalOnlyTileDownloader
import com.laviavi.adsbandroid.offline.LocationNamer
import com.laviavi.adsbandroid.offline.LogcatOfflineLogger
import com.laviavi.adsbandroid.offline.ManifestStore
import com.laviavi.adsbandroid.offline.NetworkEligibility
import com.laviavi.adsbandroid.offline.OfflineMapManager
import com.laviavi.adsbandroid.offline.SystemOfflineClock
import com.laviavi.adsbandroid.offline.TileDownloader
import com.laviavi.adsbandroid.offline.TileStore
import com.laviavi.adsbandroid.offline.UuidGenerator
import com.laviavi.adsbandroid.pipeline.AppConfig
import com.laviavi.adsbandroid.pipeline.AppConfigStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun provideAppConfig(): AppConfig = AppConfig()
    @Provides @Singleton fun provideAppConfigStore(@ApplicationContext ctx: Context): AppConfigStore =
        AppConfigStore(ctx)
    @Provides @Singleton fun provideDatabase(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "adsb.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            // Only a genuine downgrade (e.g. switching to an older debug build)
            // falls back to a wipe now — every historical upgrade path (v1-v3)
            // is covered by an explicit migration above.
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()
    @Provides fun provideHistoryDao(db: AppDatabase): AircraftHistoryDao = db.aircraftHistoryDao()
    @Provides fun provideSeenDao(db: AppDatabase): AircraftSeenDao = db.aircraftSeenDao()
    @Provides fun provideEnrichmentDao(db: AppDatabase): EnrichmentCacheDao = db.enrichmentCacheDao()
    @Provides fun provideAircraftMetaCacheDao(db: AppDatabase): AircraftMetaCacheDao = db.aircraftMetaCacheDao()
    @Provides fun provideAircraftVisitDao(db: AppDatabase): AircraftVisitDao = db.aircraftVisitDao()

    // ── Offline maps ──────────────────────────────────────────────────────────

    @Provides @Singleton fun provideOfflineTileStore(@ApplicationContext ctx: Context): TileStore =
        FileTileStore(ctx.filesDir)

    @Provides @Singleton fun provideOfflineManifestStore(@ApplicationContext ctx: Context): ManifestStore =
        FileManifestStore(ctx.filesDir, LogcatOfflineLogger())

    @Provides @Singleton fun provideNetworkEligibility(@ApplicationContext ctx: Context): NetworkEligibility =
        AndroidNetworkEligibility(ctx)

    @Provides @Singleton fun provideLocationNamer(@ApplicationContext ctx: Context): LocationNamer =
        AndroidLocationNamer(ctx)

    /** osmdroid's own cache, the source for importing already-viewed coverage. */
    @Provides @Singleton fun provideOsmdroidCacheSource(@ApplicationContext ctx: Context): LocalTileSource =
        OsmdroidCacheSource(java.io.File(ctx.filesDir, "osmdroid/tiles"))

    /**
     * Downloads go to whatever endpoint the user configured, and nowhere otherwise.
     *
     * The template is read per fetch rather than captured at injection so changing it
     * in Settings takes effect without restarting the app. With no template the
     * downloader is inert — offline maps still work via import, which needs no
     * endpoint at all.
     */
    @Provides @Singleton fun provideTileDownloader(
        store: TileStore,
        configStore: AppConfigStore,
    ): TileDownloader = ConfigurableTileDownloader(
        fallback = LocalOnlyTileDownloader(store),
        templateProvider = {
            runBlocking { runCatching { configStore.load().effectiveTileUrlTemplate }.getOrDefault("") }
        },
    )

    @Provides @Singleton fun provideOfflineMapManager(
        manifestStore: ManifestStore,
        tileStore: TileStore,
        downloader: TileDownloader,
        eligibility: NetworkEligibility,
        namer: LocationNamer,
    ): OfflineMapManager = OfflineMapManager(
        store = manifestStore,
        tiles = tileStore,
        downloader = downloader,
        eligibility = eligibility,
        clock = SystemOfflineClock(),
        ids = UuidGenerator(),
        namer = namer,
        log = LogcatOfflineLogger(),
    )
}
