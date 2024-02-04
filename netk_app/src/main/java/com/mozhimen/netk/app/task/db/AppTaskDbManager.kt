package com.mozhimen.netk.app.task.db

import android.content.Context
import androidx.annotation.UiThread
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mozhimen.basick.lintk.optins.OApiInit_InApplication
import com.mozhimen.netk.app.task.cons.CNetKAppTaskState

/**
 * @ClassName DatabaseManager
 * @Description TODO
 * @Author Mozhimen
 * @Date 2023/11/7 16:01
 * @Version 1.0
 */
@OApiInit_InApplication
object AppTaskDbManager {
    private lateinit var _appTaskDb: AppTaskDb

    lateinit var appTaskDao: AppTaskDao
        private set

    /**
     * 用户数据库从version 1 升级到 version 2
     */
    private val APP_DATABASE_MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE netk_app_task ADD COLUMN task_state_init INTEGER NOT NULL DEFAULT ${CNetKAppTaskState.STATE_TASK_CREATE}")
//            database.execSQL("ALTER TABLE app_file_params ADD COLUMN need_checking INTEGER NOT NULL DEFAULT 0")
        }
    }

    @UiThread
    fun init(context: Context) {
        _appTaskDb = Room.databaseBuilder(context.applicationContext, AppTaskDb::class.java, "netk_app_task_db")
//            .fallbackToDestructiveMigration()//使用该方法会在数据库升级异常时重建数据库，但是所有数据会丢失
            .addMigrations(APP_DATABASE_MIGRATION_1_2)
            .build()
        appTaskDao = _appTaskDb.appTaskDao()
        AppTaskDaoManager.init()
    }
}