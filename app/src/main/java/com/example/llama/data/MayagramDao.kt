package tr.maya.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import tr.maya.MayagramComment
import tr.maya.MayagramPost

@Dao
interface MayagramDao {

    // ── Posts ─────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPost(post: MayagramPost)

    @Query("SELECT * FROM mayagram_posts ORDER BY timestamp DESC")
    fun getAllPosts(): Flow<List<MayagramPost>>

    @Query("SELECT * FROM mayagram_posts ORDER BY timestamp DESC")
    suspend fun getAllPostsList(): List<MayagramPost>

    @Query("SELECT * FROM mayagram_posts WHERE characterId = :charId ORDER BY timestamp DESC")
    suspend fun getPostsByCharacter(charId: String): List<MayagramPost>

    @Query("DELETE FROM mayagram_posts WHERE id = :postId")
    suspend fun deletePost(postId: String)

    @Query("UPDATE mayagram_posts SET likeCount = :count, isLikedByUser = :liked WHERE id = :postId")
    suspend fun updateLike(postId: String, count: Int, liked: Boolean)

    @Query("SELECT COUNT(*) FROM mayagram_posts")
    suspend fun postCount(): Int

    // ── Comments ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: MayagramComment)

    @Query("SELECT * FROM mayagram_comments WHERE postId = :postId ORDER BY timestamp ASC")
    suspend fun getComments(postId: String): List<MayagramComment>

    @Query("SELECT COUNT(*) FROM mayagram_comments WHERE postId = :postId")
    suspend fun commentCount(postId: String): Int

    @Query("DELETE FROM mayagram_comments WHERE postId = :postId")
    suspend fun deleteCommentsForPost(postId: String)
}
