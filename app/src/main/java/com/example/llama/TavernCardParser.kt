package tr.maya

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32

/**
 * Tavern / SillyTavern karakter kartı (PNG tEXt chunk) okuma ve yazma.
 *
 * Format: PNG dosyasının metadata bölümünde "tEXt" chunk'ı içinde,
 * keyword genellikle "chara" olur, içerik base64 ile encode edilmiş JSON'dur.
 *
 * V1: name, description, personality, scenario, first_mes, mes_example
 * V2/V3 ("chara_card_v2" / "chara_card_v3"): yukarıdakiler "data" objesi içinde,
 *       ayrıca creator_notes, tags, alternate_greetings, system_prompt vb.
 *
 * Bu parser hem okumayı (import) hem PNG'ye geri yazmayı (export) destekler.
 * Sadece java.util.zip (CRC32) ve org.json kullanır — ek bağımlılık YOK.
 */
object TavernCardParser {

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    data class TavernCardData(
        val name: String,
        val description: String,
        val personality: String,
        val scenario: String,
        val firstMessage: String,
        val mesExample: String,
        val creatorNotes: String,
        val systemPromptRaw: String,  // V2/V3'te ayrı "system_prompt" alanı varsa
        val tags: List<String>
    )

    class InvalidCardException(message: String) : Exception(message)

    // ── Import: PNG dosyasından karakter verisini oku ────────────────────────

    /**
     * Verilen PNG dosyasından tavern karakter JSON'unu çıkarır.
     * @throws InvalidCardException PNG değilse veya "chara" tEXt chunk'ı bulunamazsa
     */
    fun readCardFromPng(file: File): TavernCardData {
        val bytes = file.readBytes()
        return readCardFromBytes(bytes)
    }

    fun readCardFromBytes(bytes: ByteArray): TavernCardData {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) {
            throw InvalidCardException("Bu dosya geçerli bir PNG değil")
        }

        var pos = 8
        var base64Json: String? = null

        while (pos + 8 <= bytes.size) {
            val length = readBE32(bytes, pos)
            val typeBytes = bytes.copyOfRange(pos + 4, pos + 8)
            val type = String(typeBytes, Charsets.US_ASCII)

            if (type == "tEXt" || type == "iTXt") {
                val dataStart = pos + 8
                val dataEnd = dataStart + length
                if (dataEnd > bytes.size) break
                val chunkData = bytes.copyOfRange(dataStart, dataEnd)

                // tEXt: keyword\0text  (Latin-1)
                // iTXt: keyword\0 compFlag\0 compMethod\0 langTag\0 translatedKeyword\0 text
                val nullIdx = chunkData.indexOf(0)
                if (nullIdx > 0) {
                    val keyword = String(chunkData.copyOfRange(0, nullIdx), Charsets.US_ASCII)
                    if (keyword.equals("chara", ignoreCase = true)) {
                        val textBytes = if (type == "tEXt") {
                            chunkData.copyOfRange(nullIdx + 1, chunkData.size)
                        } else {
                            // iTXt: keyword\0 0 \0 \0 \0 \0 text  — basit durumda 4 ek null var
                            var p2 = nullIdx + 1
                            var nullsSkipped = 0
                            while (p2 < chunkData.size && nullsSkipped < 4) {
                                if (chunkData[p2] == 0.toByte()) nullsSkipped++
                                p2++
                            }
                            chunkData.copyOfRange(p2, chunkData.size)
                        }
                        base64Json = String(textBytes, Charsets.ISO_8859_1)
                    }
                }
            }

            if (type == "IEND") break
            pos += 8 + length + 4 // length + type + data + CRC
        }

        val b64 = base64Json
            ?: throw InvalidCardException("PNG içinde \"chara\" karakter verisi bulunamadı")

        val jsonStr = try {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            throw InvalidCardException("Karakter verisi base64 olarak çözülemedi: ${e.message}")
        }

        val root = try { JSONObject(jsonStr) } catch (e: Exception) {
            throw InvalidCardException("Karakter verisi geçerli JSON değil: ${e.message}")
        }

        // V2/V3: kök seviyede "data" objesi var. V1: alanlar doğrudan kökte.
        val obj = root.optJSONObject("data") ?: root

        val tagsArr = obj.optJSONArray("tags")
        val tags = if (tagsArr != null)
            (0 until tagsArr.length()).map { tagsArr.optString(it, "") }.filter { it.isNotBlank() }
        else emptyList()

        return TavernCardData(
            name            = obj.optString("name", "İçe Aktarılan Karakter"),
            description     = obj.optString("description", ""),
            personality     = obj.optString("personality", ""),
            scenario        = obj.optString("scenario", ""),
            firstMessage    = obj.optString("first_mes", ""),
            mesExample      = obj.optString("mes_example", ""),
            creatorNotes    = obj.optString("creator_notes", ""),
            systemPromptRaw = obj.optString("system_prompt", ""),
            tags            = tags
        )
    }

    /** TavernCardData'dan Maya'nın tek-blok sistem promptunu oluşturur. */
    fun buildSystemPrompt(card: TavernCardData): String {
        return buildString {
            if (card.systemPromptRaw.isNotBlank()) {
                append(card.systemPromptRaw.trim())
                appendLine(); appendLine()
            }
            if (card.description.isNotBlank()) {
                append(card.description.trim())
                appendLine(); appendLine()
            }
            if (card.personality.isNotBlank()) {
                append("Kişilik: ")
                append(card.personality.trim())
                appendLine(); appendLine()
            }
            if (card.scenario.isNotBlank()) {
                append("Senaryo: ")
                append(card.scenario.trim())
                appendLine(); appendLine()
            }
            if (card.mesExample.isNotBlank()) {
                append("Örnek konuşma stili:\n")
                append(card.mesExample.trim())
            }
        }.trim().ifEmpty {
            "{{date}} {{time}}. Senin adın {{char}}. Sen yararlı, zeki ve eğlenceli bir yapay zeka asistansın ve {{user}}'ın sadık bir dostusun."
        }
    }

    // ── Export: Maya karakterini tavern PNG kartına yaz ──────────────────────

    /**
     * Verilen kaynak PNG bayt dizisine (avatar resmi) "chara" tEXt chunk'ı ekleyerek
     * yeni bir tavern kartı PNG'si üretir.
     *
     * Not: Maya'nın tek sistem promptu alanı V1 formatındaki "description" alanına
     * tam olarak yazılır; "personality", "scenario" boş bırakılır (Maya bunları
     * ayrı tutmuyor). Bu, kartın başka tavern-uyumlu uygulamalarda da okunabilmesini sağlar.
     */
    fun buildCardPng(
        sourcePngBytes: ByteArray,
        characterName: String,
        userName: String,
        systemPrompt: String
    ): ByteArray {
        if (sourcePngBytes.size < 8 || !sourcePngBytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) {
            throw InvalidCardException("Kaynak avatar geçerli bir PNG değil")
        }

        val v2Json = JSONObject().apply {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", JSONObject().apply {
                put("name", characterName)
                put("description", systemPrompt)
                put("personality", "")
                put("scenario", "")
                put("first_mes", "")
                put("mes_example", "")
                put("creator_notes", "Maya uygulamasından dışa aktarıldı")
                put("system_prompt", "")
                put("post_history_instructions", "")
                put("tags", org.json.JSONArray())
                put("creator", "")
                put("character_version", "")
                put("alternate_greetings", org.json.JSONArray())
                put("extensions", JSONObject())
            })
        }

        val base64 = android.util.Base64.encodeToString(
            v2Json.toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        val keyword = "chara".toByteArray(Charsets.US_ASCII)
        val textBytes = base64.toByteArray(Charsets.ISO_8859_1)
        val chunkData = keyword + byteArrayOf(0) + textBytes
        val newChunk = buildChunk("tEXt", chunkData)

        // Yeni tEXt chunk'ı IHDR'dan sonra, IDAT'tan önce ekle (PNG spec'e uygun yer).
        val out = ByteArrayOutputStream()
        out.write(sourcePngBytes, 0, 8) // PNG magic

        var pos = 8
        var inserted = false
        while (pos + 8 <= sourcePngBytes.size) {
            val length = readBE32(sourcePngBytes, pos)
            val type = String(sourcePngBytes.copyOfRange(pos + 4, pos + 8), Charsets.US_ASCII)
            val chunkTotalLen = 8 + length + 4

            if (!inserted && (type == "IDAT" || type == "IEND")) {
                out.write(newChunk)
                inserted = true
            }

            if (pos + chunkTotalLen > sourcePngBytes.size) break
            out.write(sourcePngBytes, pos, chunkTotalLen)
            pos += chunkTotalLen

            if (type == "IEND") break
        }
        if (!inserted) out.write(newChunk)

        return out.toByteArray()
    }

    // ── PNG yardımcıları ───────────────────────────────────────────────────────

    private fun readBE32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

    private fun writeBE32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    private fun buildChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = writeBE32(crc.value.toInt())

        val out = ByteArrayOutputStream()
        out.write(writeBE32(data.size))
        out.write(typeBytes)
        out.write(data)
        out.write(crcBytes)
        return out.toByteArray()
    }

    private fun ByteArray.indexOf(byte: Byte): Int {
        for (i in indices) if (this[i] == byte) return i
        return -1
    }
}
