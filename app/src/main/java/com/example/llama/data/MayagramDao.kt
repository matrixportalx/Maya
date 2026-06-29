package tr.maya.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import tr.maya.MayagramComment
import tr.maya.MayagramPost
import tr.maya.MayagramPostLike

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

    @Query("SELECT * FROM mayagram_posts WHERE id = :postId LIMIT 1")
    suspend fun getPostById(postId: String): MayagramPost?

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

    /** v6.5: Tek bir yorumu ID'siyle bul — yanıt zincirinde "bu yoruma kim yanıt verecek" tespiti için */
    @Query("SELECT * FROM mayagram_comments WHERE id = :commentId LIMIT 1")
    suspend fun getCommentById(commentId: String): MayagramComment?

    @Query("SELECT COUNT(*) FROM mayagram_comments WHERE postId = :postId")
    suspend fun commentCount(postId: String): Int

    @Query("DELETE FROM mayagram_comments WHERE postId = :postId")
    suspend fun deleteCommentsForPost(postId: String)

    // ── v6.10: Karakter beğenileri ───────────────────────────────────────────

    /** Aynı karakter aynı postu iki kez beğenemesin diye IGNORE — (postId, characterId) zaten primary key. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCharacterLike(like: MayagramPostLike)

    @Query("SELECT * FROM mayagram_post_likes WHERE postId = :postId ORDER BY timestamp ASC")
    suspend fun getCharacterLikesForPost(postId: String): List<MayagramPostLike>

    @Query("SELECT COUNT(*) FROM mayagram_post_likes WHERE postId = :postId")
    suspend fun characterLikeCount(postId: String): Int

    @Query("SELECT COUNT(*) FROM mayagram_post_likes WHERE postId = :postId AND characterId = :characterId")
    suspend fun hasCharacterLiked(postId: String, characterId: String): Int

    @Query("DELETE FROM mayagram_post_likes WHERE postId = :postId")
    suspend fun deleteCharacterLikesForPost(postId: String)
}
