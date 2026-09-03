package com.wearable.inspection.mobile.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context
import com.wearable.inspection.mobile.data.dao.*
import com.wearable.inspection.mobile.data.entity.*

@Database(
    entities = [
        PartEntity::class,
        InspectionTemplateEntity::class,
        RoiDefinitionEntity::class,
        InspectionSessionEntity::class,
        RoiInspectionRecordEntity::class,
        CaptureBatchEntity::class,
        CapturedPhotoEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun partDao(): PartDao
    abstract fun templateDao(): TemplateDao
    abstract fun roiDao(): RoiDao
    abstract fun inspectionSessionDao(): InspectionSessionDao
    abstract fun roiRecordDao(): RoiRecordDao
    abstract fun captureBatchDao(): CaptureBatchDao
    abstract fun capturedPhotoDao(): CapturedPhotoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mobile_inspection_db"
                )
                    .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
