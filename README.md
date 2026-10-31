# Dijital İkiz (Android)

Telefonundan sesle yönetebildiğin kişisel asistan: takvim/toplantı, hatırlatma, not, SMS, WhatsApp ve e-posta taslağı, arama. Konuşmayı Türkçe anlar (Android'in ses tanıması), komutları **Gemini API** ile yorumlar ve telefondaki araçları kendisi çağırır.

> **Önemli:** Bu kod yazıldığı ortamda derlenemedi (Android SDK'ya erişim yoktu). Derleme ilk kez GitHub'da çalışacak. Hata verirse Actions günlüğünü kopyalayıp gönder, düzeltiriz.

## APK'yı alma (GitHub Actions, bilgisayara kurulum yok)

1. github.com'da yeni bir depo aç (private olabilir).
2. Bu klasörün **tüm içeriğini** depoya yükle. Terminal ile en kolayı:
   ```
   git init
   git add .
   git commit -m "ilk sürüm"
   git branch -M main
   git remote add origin https://github.com/KULLANICI/DEPO.git
   git push -u origin main
   ```
   Web arayüzüyle yüklüyorsan `.github/workflows/build.yml` dosyası gizli klasörde olduğu için sürüklemede atlanabilir; depoda **Add file → Create new file** deyip adına `.github/workflows/build.yml` yaz ve içeriği yapıştır.
3. Depoda **Actions** sekmesine gir; "APK derle" otomatik başlar (yaklaşık 5-8 dk). Elle başlatmak için **Run workflow**.
4. Bitince depo ana sayfasında sağdaki **Releases → Dijital İkiz - son derleme** altından `dijital-ikiz.apk` dosyasını indir. (Private depoda telefon tarayıcında GitHub'a giriş yapmış olman gerekir.)
5. Telefonda APK'ya dokun, "bilinmeyen kaynaklardan yüklemeye izin ver" uyarısını onayla ve yükle.

Android Studio tercih edersen: klasörü aç, Build → Build APK(s) de olur.

## İlk kullanım

1. Açılışta izinleri ver (mikrofon, takvim, kişiler, SMS, bildirim).
2. Ayarlar penceresine (sağ üst ⚙) **Gemini API anahtarını** yaz (aistudio.google.com). Model varsayılan `gemini-2.5-flash`; istersen model adını ve kendin hakkında kısa bilgi de gir.
3. Mikrofona dokun ve konuş. "Sürekli sohbet" açıksa yanıttan sonra kendiliğinden tekrar dinler.

Örnekler:
- "Yarın saat on bire Ahmet'le toplantı ekle, yer ofis."
- "Bu hafta neler var?"
- "Akşam altıda annemi aramamı hatırlat."
- "Not al: market listesi süt, yumurta, ekmek."
- "Mehmet'e SMS at, geç kalacağım." (göndermeden önce onay penceresi çıkar)
- "Ayşe'ye WhatsApp'tan toplantının ertelendiğini yaz."
- "Ali'ye konusu bütçe olan bir e-posta taslağı hazırla."
- "Hafızana yaz: kahveyi sütsüz içerim."

## Kartvizit

Ana ekranda sağ üstteki **Kartvizit** düğmesi. Burada:

- **📷 Fotoğraf çek / 🖼 Galeriden:** kartın fotoğrafı Gemini ile okunur; firma, kişi, ünvan, telefon, e-posta, web ve adres forma dolar. **Kaydetmeden önce kontrol et ve düzelt**, otomatik okuma hata yapabilir.
- **Not:** formda "🎤 Notu sesle yaz" ile dikte edilir.
- Karta dokununca fotoğraf ve bilgiler açılır; **Ara, E-posta, Web, Düzenle, Sil** düğmeleri vardır.
- Üstteki arama kutusu firma, kişi, telefon ve notlarda arar (🎤 ile sesle de).

Ana ekrandan sesli komutlar:
- "Corvis'teki kişinin telefonu ne?" / "Notlarında fiyat teklifi geçen müşteriler hangileri?"
- "Ahmet Yılmaz kartına not ekle: salıya teklif gönderilecek."
- "Ahmet Yılmaz'ın mailini düzelt: ahmet@firma.com"
- "Ahmet Yılmaz kartını sil." (onay penceresi çıkar)
- "Ahmet'i ara", "Ahmet'e mail yaz": rehberde yoksa kartvizitlerden bulunur.

Kartvizit verisi ve fotoğrafları yalnızca telefonda tutulur. Fotoğraf okunurken görüntü Google'ın Gemini API'sine gönderilir.

## Sınırlar (dürüst liste)

- **WhatsApp ve e-posta kendiliğinden gönderilmez.** Uygulama hazır taslağı açar, Gönder'e senin basman gerekir. Arama için de tuşa basman gerekir.
- **Gelen e-posta ve mesajları okuyamaz.** Bunun için Gmail API ve OAuth girişi eklenmesi gerekir; şu an yok.
- **SMS:** doğrudan gönderim SMS iznine bağlı. Bazı Android sürümleri APK ile yüklenen uygulamalarda bu izni kısıtlar (Uygulama bilgisi → ⋮ → "Kısıtlanmış ayarlara izin ver"). İzin yoksa uygulama mesaj ekranını hazır metinle açar.
- **Ses tanıma** Google'ın servisini kullanır; Türkçe için telefonda Google uygulaması güncel olmalı.
- **Ses çıkışı** için telefonda Türkçe metin okuma sesi kurulu olmalı.
- Her komut Gemini API'ye gittiği için internet gerekir; kullanım kotası/ücreti anahtarının planına bağlıdır.

## Gizlilik

- API anahtarı, notlar, hatırlatmalar ve hafıza yalnızca bu telefonda, uygulamaya özel alanda saklanır (şifrelenmemiş).
- Söylediklerin, ilgili takvim/rehber/not sonuçlarıyla birlikte Google'ın Gemini API'sine gönderilir.

## Yapı

- `MainActivity.kt`: arayüz, ses tanıma, sesli yanıt, onay penceresi
- `Agent.kt`: Gemini API ve araç (function calling) döngüsü
- `Tools.kt`: takvim, hatırlatma, not, rehber, SMS/WhatsApp/e-posta, arama araçları
- `Store.kt`: ayarlar, notlar, hatırlatmalar, hafıza
- `ReminderReceiver.kt`, `BootReceiver.kt`: hatırlatma bildirimleri (yeniden başlatmadan sonra da çalışır)
