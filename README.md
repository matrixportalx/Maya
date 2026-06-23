# ![Maya](screenshots/Maya.png)

**Maya**, Android cihazlar için tamamen çevrimdışı çalışan, llama.cpp tabanlı bir yerel LLM sohbet uygulamasıdır. Hiçbir bulut bağlantısı, sunucu veya veri paylaşımı yoktur. Model, doğrudan telefonunuzun işlemcisinde çalışır.

> 🌐 Uygulama arayüzü Türkçedir.

---

## 📸 Ekran Görüntüleri

![Ana ekran](screenshots/Screenshot_20260318_035811_com_kova_chat_MainActivity.jpg)

![Sohbet](screenshots/Screenshot_20260318_035758_com_kova_chat_MainActivity.jpg)

![Model Listesi](screenshots/Screenshot_20260318_035819_com_kova_chat_MainActivity.jpg)

![Menü](screenshots/Screenshot_20260318_035824_com_kova_chat_MainActivity.jpg)

![Ayarlar1](screenshots/Screenshot_20260318_035829_com_kova_chat_MainActivity.jpg)

![Ayarlar2](screenshots/Screenshot_20260318_035829_com_kova_chat_MainActivity.jpg)

![Ayrlar3](screenshots/Screenshot_20260318_035836_com_kova_chat_MainActivity.jpg)

![Ayarlar4](screenshots/Screenshot_20260318_035853_com_kova_chat_MainActivity.jpg)

![Ayarlar5](screenshots/Screenshot_20260318_035902_com_kova_chat_MainActivity.jpg)

![Ayarlar6](screenshots/Screenshot_20260318_035916_com_kova_chat_MainActivity.jpg)

![Karakterler](screenshots/Screenshot_20260318_040159_com_kova_chat_MainActivity.jpg)

---

## ✨ Özellikler

### 🤖 Model Yönetimi
- GGUF formatındaki modelleri yerel depolamadan yükle
- Birden fazla model ekle, listeden seç, kaldır
- mmap / mlock desteği (RAM kullanım kontrolü)
- Flash Attention modu: Kapalı / Otomatik / Açık (zorla)
- Son modeli otomatik yükleme
- Model bilgisi paneli: context boyutu, şablon, hız (t/s), vision durumu
- GGUF metadata okuyucu (mimari, tokenizer, rope, expert bilgisi vb. — streaming parser, dosya tamamen yüklenmeden okunur)

### 💬 Sohbet
- Çoklu bağımsız sohbet geçmişi (Room veritabanı)
- Otomatik sohbet başlığı üretimi (modelin kendisiyle)
- Markdown render (Markwon + tablo desteği)
- Kod bloğu kopyalama (fenced code block tespiti)
- Mesaj arama: aktif sohbette veya tüm sohbetlerde, eşleşen kelime vurgulu
- Mesaj düzenleme ve yeniden oluşturma (regenerate)
- Sohbeti dışa aktarma (.txt / .md)
- **Bypass Context Length** — her turda KV cache sıfırlanıp son mesajlar yeniden encode edilir; teorik olarak sınırsız sohbet
- Qwen3 / Qwen3.5 `/no_think` desteği
- Arka planda yanıt üretimi: Foreground Service ile bildirim üzerinden takip

### 🎭 Karakter Sistemi
- Özel karakter profilleri: emoji veya özel fotoğraf avatarı, karakter adı, kullanıcı adı
- Ayrı **description / personality / scenario / first message** alanları (Tavern kart formatına uyumlu)
- `{{char}}` / `{{user}}` yer tutucu desteği
- `{{date}}` / `{{time}}` dinamik sistem promptu ekleme
- **Tavern / SillyTavern karakter kartı içe & dışa aktarma** — PNG `tEXt` chunk içine gömülü base64 JSON, V1/V2/V3 destekli
- Varsayılan "Maya" karakteri gömülü avatar ile birlikte gelir

### 📸 Mayagram
- Maya karakterlerinin kendi aralarında paylaşım yaptığı Instagram/Reddit tarzı sosyal akış
- Karakter, konu seçimine göre LLM tarafından üretilen caption + (varsa) görüntü
- Diğer karakterlerden otomatik yorum üretimi, `@karakter` etiketleme ile yönlendirilmiş yanıtlar
- Beğeni, yorum, gönderi silme
- Görüntülere pinch-to-zoom tam ekran önizleme, galeriye kaydetme ve paylaşma
- Gemma 4 thinking token temizliği caption/yorum üretiminde otomatik uygulanır

### 🎨 Dream API (Görüntü Oluşturma)
- [Local Dream](https://github.com/xororz/local-dream) uygulamasıyla SSE tabanlı entegrasyon (`127.0.0.1:8081`)
- Prompt, negatif prompt, boyut, adım sayısı, CFG scale, seed, OpenCL hızlandırma ayarları
- Üretim ilerlemesi canlı gösterilir; tamamlanan görüntü galeriye kaydedilebilir veya paylaşılabilir
- Mayagram gönderileriyle otomatik entegre

### 🖼️ Görüntü Tanıma (Vision / Multimodal)
- mmproj (vision projector) GGUF dosyası yükleyerek görüntülü sohbet
- llama.cpp `mtmd` API'si ile görüntü gömme (embed)
- Galeriden görüntü seçip modele gönderme

### 🌐 İnternet Araması
- DuckDuckGo (gizlilik odaklı, API anahtarı gerektirmez)
- Brave Search API
- SearXNG (öz barındırmalı instance desteği)
- Üç mod: Kapalı / Tetikleyici (anahtar kelimeyle) / Her zaman
- Akıllı veya basit sorgu çıkarma
- Sayfa içeriği otomatik okuma (arama sonucundaki ilk sayfalar)
- Mesajdaki URL'leri arama ayarından bağımsız otomatik algılama ve okuma

### 📋 Günlük Rapor
- AlarmManager ile zamanlı arka plan özeti (Doze-dayanıklı, exact alarm)
- **Birden fazla rapor profili** — her biri kendi saatinde, kendi konu/RSS listesiyle çalışır
- RSS besleme + web arama konu desteği, isteğe bağlı tam makale çekme
- Profile özel özet prompt'u tanımlama
- Ayrı "Rapor Modeli" ayarı (ana sohbet modelinden bağımsız, küçük/hızlı model seçilebilir)
- Bildirim → sohbete ekleme akışı, ham veri görüntüleme

### 📝 Sohbet Şablonları
- Otomatik (GGUF Jinja şablonundan)
- Aya / Command-R, ChatML, Gemma 3, Llama 3, Granite, **Gemma 4** (thinking destekli)
- Tam özel şablon CRUD (UUID tabanlı, hazır ön ayarlardan başlayıp düzenleme)
- Thinking/düşünme bloğu otomatik ayrıştırma ve katlanabilir gösterim (Qwen3 `<think>`, Gemma 4 `<|channel>`)

### 💾 Yedekleme & Geri Yükleme
- Sohbetler ve/veya ayarları ayrı ayrı yedekle
- Şifreli (AES-256-GCM, PBKDF2) veya şifresiz JSON
- Karakterler, rapor profilleri, özel şablonlar yedeklemeye dahil
- Geri yüklemede birleştir (merge) veya üzerine yaz (overwrite) seçeneği

### 🆕 Otomatik Güncelleme
- GitHub Releases API üzerinden sürüm kontrolü
- Cihaz ABI'sine uygun APK otomatik seçimi (arm64-v8a / universal)
- Ayarlanabilir kontrol aralığı (1 saat – haftalık)
- İndirme ilerlemesi bildirimi, indirilince otomatik kurulum ekranı

### 🌓 Diğer
- Karanlık / Aydınlık / Sistem teması
- Karakter ve kullanıcı için özel fotoğraf avatarı
- Model & geçici dosya önbellek yönetimi

---

## 📦 Kurulum / APK İndirme

Hazır APK dosyasını [Releases](../../releases) bölümünden indirebilirsiniz.

**Gereksinimler:**
- Android 13+ (API 33)
- arm64-v8a işlemci (Snapdragon, Dimensity vb.)
- Önerilen: 8 GB+ RAM

**Model nereden bulunur?**
[Hugging Face](https://huggingface.co/models?library=gguf) üzerinde GGUF formatında yüzlerce model mevcuttur. 1B–8B arası modeller çoğu telefonda çalışır.

---

## 🧩 Desteklenen Modeller

| Model Ailesi | Durum |
|---|---|
| Qwen3 / Qwen3.5 | ✅ Çalışıyor (`/no_think` destekli) |
| Gemma 3 / Gemma 3n (resmi) | ✅ Çalışıyor |
| Gemma 4 (E4B) | ✅ Çalışıyor (thinking destekli) |
| Llama 3 | ✅ Çalışıyor |
| Tiny Aya / Aya / Command-R | ✅ Çalışıyor |
| IBM Granite | ✅ Çalışıyor |
| LFM (Liquid) | ✅ Çalışıyor |
| Gemma 3n (heretic/topluluk) | ⚠️ Kararsız |
| Görsel tanıma (vision/mmproj) | ✅ Çalışıyor (mtmd API) |

Genel kural: llama.cpp'nin desteklediği tüm GGUF modeller, gerekirse özel şablon oluşturularak çalışır.

---

## 🏗️ Mimari & Teknik Detaylar

```
maya/
  app/          → Android Kotlin katmanı
  lib/          → JNI köprüsü (Kotlin + C++)
  llama.cpp/    → Motor (git submodule)
```

| Katman | Teknoloji |
|---|---|
| UI | Kotlin, Android Views, Material3 |
| Çıkarım motoru | llama.cpp (C++) |
| JNI köprüsü | `ai_chat.cpp` + `InferenceEngineImpl.kt` |
| Veritabanı | Room (sohbet geçmişi, Mayagram gönderi/yorumları) |
| Arka plan görevleri | WorkManager (günlük rapor) + AlarmManager (zamanlama) + Foreground Service (üretim bildirimi) |
| Multimodal altyapı | `mtmd.h` / `mtmd-helper.h` (`tools/mtmd`) |
| Görüntü oluşturma | Local Dream SSE API entegrasyonu |
| Build | GitHub Actions, Android NDK (arm64-v8a + x86_64) |

**Kod Mimarisi:** Kotlin tarafı, `MainActivity` etrafında çok sayıda extension function dosyasına bölünmüştür (Chat, UI, Settings, Character, Mayagram, DreamApi, WebSearch, Templates, Search, Backup, DailyReport vb.) — tek dosyada büyük bir sınıf yerine sorumluluk bazlı ayrım.

---

## 📄 Lisans

Bu proje [llama.cpp](https://github.com/ggerganov/llama.cpp) üzerine inşa edilmiştir (MIT lisansı).

---

*Maya • matrixportalx*
