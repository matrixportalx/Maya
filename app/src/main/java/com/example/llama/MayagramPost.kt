package tr.maya

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Mayagram sosyal akış gönderisi.
 * Karakterler VEYA kullanıcı tarafından paylaşılabilir (authorIsUser ile ayırt edilir).
 * Karakter gönderilerinde caption LLM tarafından üretilir.
 * Kullanıcı gönderilerinde caption kullanıcı tarafından yazılır; görüntü galeriden
 * seçilebilir veya Dream API ile (kullanıcının metninden türetilen İngilizce prompt'la) üretilebilir.
 */
@Entity(tableName = "mayagram_posts")
data class MayagramPost(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val characterId: String,          // MayaCharacter.id — kullanıcı gönderisinde "user" sabit değeri
    val characterName: String,        // Denormalized — karakter silinse de gösterilir
    val characterEmoji: String,
    val characterAvatarUri: String?,
    val caption: String,              // LLM tarafından üretilen METİN veya kullanıcının yazdığı metin
    val imagePath: String?,           // Dream API çıktısı veya kullanıcının seçtiği galeri görüntüsü (null → yalnızca metin post)
    val dreamPrompt: String?,         // Görüntü üretiminde kullanılan prompt (varsa)
    val timestamp: Long = System.currentTimeMillis(),
    val likeCount: Int = 0,
    val isLikedByUser: Boolean = false,
    val authorIsUser: Boolean = false // v6.5: true ise bu gönderiyi kullanıcı paylaştı (karakter değil)
)

/**
 * Mayagram yorum.
 * v6.5: parentCommentId ile yorum zincirleri (reply) desteklenir.
 *   null  → gönderiye doğrudan yapılan yorum
 *   dolu  → başka bir yoruma verilen yanıt; o yorumu yazana otomatik karakter yanıtı tetiklenir
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
    val timestamp: Long = System.currentTimeMillis(),
    val parentCommentId: String? = null, // v6.5: yanıt verilen yorumun ID'si (null = post'a direkt yorum)
    val authorIsUser: Boolean = false    // v6.5: bu yorumu kullanıcı mı yazdı
)
