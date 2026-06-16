package tr.maya

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Mayagram sosyal akış gönderisi.
 * Her gönderi bir karaktere ait; LLM tarafından üretilmiş caption ve
 * (isteğe bağlı) Dream API tarafından üretilmiş görüntü içerir.
 */
@Entity(tableName = "mayagram_posts")
data class MayagramPost(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val characterId: String,          // MayaCharacter.id
    val characterName: String,        // Denormalized — karakter silinse de gösterilir
    val characterEmoji: String,
    val characterAvatarUri: String?,
    val caption: String,              // LLM tarafından üretilen metin
    val imagePath: String?,           // Dream API çıktısı (null → yalnızca metin post)
    val dreamPrompt: String?,         // Görüntü üretiminde kullanılan prompt
    val timestamp: Long = System.currentTimeMillis(),
    val likeCount: Int = 0,
    val isLikedByUser: Boolean = false
)

/**
 * Mayagram yorum.
 */
@Entity(tableName = "mayagram_comments")
data class MayagramComment(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val postId: String,
    val authorId: String,             // "user" veya karakter ID
    val authorName: String,
    val authorEmoji: String,
    val authorAvatarUri: String?,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
