package tr.maya

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Kullanıcı profili — {{user}} yerine geçecek isim, kısa biyografi ve avatar.
 * İleride karakterlere atanabilecek (her karakter farklı bir profil görebilecek);
 * şimdilik bağımsız bir liste olarak yönetilir.
 */
data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bio: String = "",           // isteğe bağlı kısa açıklama
    val avatarUri: String? = null   // "content://...", "file:..." — karakter avatarlarıyla aynı format
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("bio", bio)
        put("avatar_uri", avatarUri ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): UserProfile = UserProfile(
            id        = o.optString("id", UUID.randomUUID().toString()),
            name      = o.optString("name", "Kullanıcı"),
            bio       = o.optString("bio", ""),
            avatarUri = o.optString("avatar_uri", "").ifEmpty { null }
        )

        fun loadAll(context: Context): MutableList<UserProfile> {
            val prefs = context.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
            val json  = prefs.getString("user_profiles_json", null) ?: return mutableListOf()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.toMutableList()
            } catch (e: Exception) { mutableListOf() }
        }

        fun saveAll(context: Context, profiles: List<UserProfile>) {
            val arr = JSONArray().also { a -> profiles.forEach { a.put(it.toJson()) } }
            context.getSharedPreferences("llama_prefs", Context.MODE_PRIVATE)
                .edit().putString("user_profiles_json", arr.toString()).apply()
        }
    }
}
