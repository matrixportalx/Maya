package tr.maya.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tr.maya.MayagramComment
import tr.maya.MayagramPost

@Database(
    entities = [
        Conversation::class,
        DbMessage::class,
        MayagramPost::class,
        MayagramComment::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun mayagramDao(): MayagramDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // Versiyon 1 → 2: messages tablosuna tps kolonu eklendi
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN tps REAL")
            }
        }

        // Versiyon 2 → 3: messages tablosuna imagePath kolonu eklendi (v4.8 multimodal)
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT")
            }
        }

        // Versiyon 3 → 4: Mayagram tabloları eklendi
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mayagram_posts (
                        id TEXT NOT NULL PRIMARY KEY,
                        characterId TEXT NOT NULL,
                        characterName TEXT NOT NULL,
                        characterEmoji TEXT NOT NULL,
                        characterAvatarUri TEXT,
                        caption TEXT NOT NULL,
                        imagePath TEXT,
                        dreamPrompt TEXT,
                        timestamp INTEGER NOT NULL,
                        likeCount INTEGER NOT NULL DEFAULT 0,
                        isLikedByUser INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mayagram_comments (
                        id TEXT NOT NULL PRIMARY KEY,
                        postId TEXT NOT NULL,
                        authorId TEXT NOT NULL,
                        authorName TEXT NOT NULL,
                        authorEmoji TEXT NOT NULL,
                        authorAvatarUri TEXT,
                        content TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "llama_chat.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { INSTANCE = it }
            }
    }
}
