# Movie Track — Android (1. aşama: indirici + tarayıcı + kütüphane + oynatıcı)

Movie Track'in mobil uygulaması. İlk aşamada 1DM benzeri bir indirme yöneticisi içerir; Movie Track'in
izleme listesi ve dizi takibi 2. aşamada aynı uygulamaya eklenecek.

## Özellikler (1. aşama)

**Tarayıcı (Keşfet)**
- Çoklu sekme, gizli sekme, yer imleri, geçmiş, masaüstü sitesi modu
- Reklam/izleyici ve pop-up engelleme
- Sayfadaki videoları otomatik bulma: M3U8 (HLS), MPD (DASH), MP4/WebM/MKV, sesler, altyazılar;
  gizlenmiş (.txt/.php, text/plain) oynatma listeleri de yakalanır. Sağ alttaki indirme düğmesi bulunanları gösterir.
- Sitenin indirme bağlantılarını devralma, uzun basma ile bağlantı/resim indirme, tam ekran video

**İndirme yöneticisi**
- Dosya başına 16 parçaya kadar çok bağlantılı indirme, duraklat/devam (uygulama kapansa bile kaldığı yerden)
- Kuyruk, aynı anda en fazla N indirme, yalnızca Wi-Fi, arka planda indirme (bildirimde ilerleme)
- Hata yönetimi ve yeniden deneme; süresi dolan bağlantıyı yenileme ("Bağlantıyı yenile")
- Kopyalanan bağlantıyı algılama, "Paylaş → Movie Track", TXT dosyasından toplu içe aktarma
- HLS/DASH: kalite seçimi, ses dilleri (`aud:tr`, `aud:en`), altyazılar (`subtitle:xx` → .srt), AES-128 çözme,
  reklam aralarını atlama, **yeniden kodlamadan TS → MP4** birleştirme (ses izlerine dil etiketi) veya ayrı dosyalar
- Dosyalar doğrudan son konumuna yazılır (İndirilenler/MovieTrack/… veya seçtiğin klasör); dizi bölümleri
  `Diziler/<Dizi>/Sezon N/<Dizi> - S01E05.mp4` olarak adlandırılır

**Kütüphane ve oynatıcı**
- Türe göre kategoriler (Video, Müzik, Belge, Resim, Arşiv, Uygulama), arama, sıralama, yeniden adlandırma, silme, paylaşma
- Dosya adından dizi/sezon/bölüm tanıma ve dizi görünümü
- Dahili oynatıcı (Media3): MP4/MKV/WebM/TS, harici altyazı (SRT/VTT/ASS), ses/altyazı izi seçimi, hız,
  döngü, en-boy oranı, Resim içinde Resim, ekran kilidi, parlaklık/ses hareketleri, çift dokunuşla ±10 sn, kaldığın yerden devam

DRM korumalı içerikler (Widevine vb.) indirilemez.

## APK

`main`'e her gönderimde GitHub Actions APK'yı derler ve **Releases → son-surum** altına `MovieTrack.apk` olarak koyar.
Pull request'ler de derlenir ve testten geçer.

Bu kod daha önce `movie-track-` reposunun `mobil-android` dalındaydı (`mobile/`); geçmişiyle birlikte buraya taşındı.
indirici uygulaması artık kendi reposunda (`indirici-mobil`).

## Yerel derleme

```bash
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # birim testleri
```

## Yol haritası

| Aşama | İçerik |
|---|---|
| 1 (bu sürüm) | Tarayıcı + video bulma + indirme yöneticisi + kütüphane + oynatıcı |
| 2 | Movie Track hesabı (Supabase): izleme listesi, dizi/bölüm takibi, indirilen bölümlerin otomatik eşleşmesi |
| 3 | TMDB ile otomatik film/dizi tanıma ve posterler, yeni bölüm bildirimleri |
| 4 | Zamanlayıcı, website grabber, kullanıcı scriptleri, sayfa çevirisi, özel DNS |
| 5 | Bulut yedekleme ve cihazlar arası senkronizasyon |
| 6 | Torrent/magnet, şifre yöneticisi, AI önerileri |
