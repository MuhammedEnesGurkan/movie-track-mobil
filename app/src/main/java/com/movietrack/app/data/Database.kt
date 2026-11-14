package com.movietrack.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object Status {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val MERGING = "merging"
    const val DONE = "done"
    const val ERROR = "error"
    const val WAITING_WIFI = "wifi"
    val ACTIVE = setOf(QUEUED, RUNNING, MERGING, WAITING_WIFI)
}

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    var url: String,
    var type: String, // direct | hls | dash
    var fileName: String,
    var mime: String = "",
    var category: String = "",
    var status: String = Status.QUEUED,
    var totalBytes: Long = 0,
    var downloadedBytes: Long = 0,
    var outputUri: String? = null,
    var extraOutputs: String = "", // altyazı vb. ek dosyalar: "uri|ad" satırları
    var referer: String? = null,
    var userAgent: String? = null,
    var cookies: String? = null,
    var error: String? = null,
    var parts: String = "", // "başlangıç-bitiş-inen;..."
    var meta: String = "", // KV kodlu ek bilgi (HLS seçimi vb.)
    var pageUrl: String? = null,
    var title: String? = null,
    var labels: String = "", // "1080p aud:tr subtitle:en"
    var createdAt: Long = System.currentTimeMillis(),
    var finishedAt: Long = 0,
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val url: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "history")
data class HistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val url: String, val visitedAt: Long = System.currentTimeMillis())

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun list(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun byStatus(statuses: List<String>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE url = :url AND status != 'error' LIMIT 1")
    suspend fun findByUrl(url: String): DownloadEntity?

    @Insert
    suspend fun insert(e: DownloadEntity): Long

    @Update
    suspend fun update(e: DownloadEntity)

    /** Yalnızca ilerleme alanları (durum alanını ezmeden). */
    @Query("UPDATE downloads SET downloadedBytes = :bytes, totalBytes = :total, parts = :parts WHERE id = :id")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long, parts: String)

    @Delete
    suspend fun delete(e: DownloadEntity)
}

@Dao
interface BrowserDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun bookmarks(): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun addBookmark(b: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun removeBookmark(url: String)

    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 300")
    fun history(): Flow<List<HistoryEntity>>

    @Insert
    suspend fun addHistory(h: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clearHistory()
}

@Database(entities = [DownloadEntity::class, BookmarkEntity::class, HistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun browser(): BrowserDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "movietrack.db").fallbackToDestructiveMigration().build()
    }
}
