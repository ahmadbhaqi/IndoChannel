# IndoChannel

Repositori ekstensi komunitas untuk [CloudStream](https://github.com/recloudstream/cloudstream). Ekstensi `IndoProvider` menghadirkan katalog film, serial, dan anime dari berbagai provider berbahasa Indonesia langsung di dalam CloudStream.

> **Catatan:** IndoChannel bukan aplikasi streaming mandiri dan tidak menyimpan atau meng-host video. Ekstensi ini hanya membaca katalog serta tautan yang disediakan oleh situs sumber pihak ketiga.

## Daftar Isi

- [Instalasi yang Direkomendasikan](#instalasi-yang-direkomendasikan)
- [Provider yang Tersedia](#provider-yang-tersedia)
- [Catatan Ketersediaan](#catatan-ketersediaan)
- [Untuk Pengembang](#untuk-pengembang)
- [Kontribusi dan Laporan Bug](#kontribusi-dan-laporan-bug)
- [Disclaimer](#disclaimer)

## Instalasi yang Direkomendasikan

Gunakan tombol berikut dari perangkat Android yang sudah memiliki CloudStream terpasang. Tombol ini akan membuka CloudStream dan menawarkan penambahan repositori IndoChannel secara otomatis.

[![Pasang IndoChannel di CloudStream](https://img.shields.io/badge/Pasang%20di%20CloudStream-IndoChannel-5c6bc0?style=for-the-badge&logo=android&logoColor=white)](cloudstreamrepo://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json)

Atau gunakan tautan instalasi langsung:

```
cloudstreamrepo://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json
```

Setelah CloudStream terbuka:

1. Setujui penambahan repositori **IndoChannel**.
2. Buka **Pengaturan / Settings > Extensions**.
3. Pilih repositori **IndoChannel**, lalu pasang atau perbarui ekstensi **IndoProvider**.

### Jika Tombol Tidak Membuka CloudStream

Tambahkan repositori secara manual melalui **Settings > Extensions > Add repository**, lalu masukkan:

```
https://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json
```

Nama repositori dapat diisi bebas, misalnya `IndoChannel`. Letak dan nama menu bisa sedikit berbeda pada tiap versi atau fork CloudStream.

## Provider yang Tersedia

Provider berikut terdaftar di dalam ekstensi `IndoProvider` saat ini:

| Film & serial | Anime |
| --- | --- |
| LayarKaca (LK21) | Otakudesu |
| Ngefilm | Samehadaku |
| Dutamovie | Anoboy |
| KitaNonton | Kuronime |
| IndoXXI | Animeindo |
| Filmapik | Oploverz |
| IDLIX | Zoronime |
| Pusatfilm |  |
| keBioskop21 |  |

Rebahin tidak didaftarkan bersamaan dengan KitaNonton karena keduanya dikelola oleh tim yang sama; KitaNonton dipertahankan sebagai provider aktif.

Fitur yang tersedia dapat berbeda antarprovider, tetapi umumnya mencakup katalog, pencarian, detail judul, episode, metadata, subtitle, dan pilihan server jika disediakan oleh situs sumber.

## Catatan Ketersediaan

Situs sumber dapat mengganti domain, struktur halaman, proteksi anti-bot, atau server pemutar, maupun menghapus sebuah judul tanpa pemberitahuan. Karena itu, provider atau tautan tertentu dapat berhenti berfungsi sementara meskipun ekstensi sudah versi terbaru. Ketersediaan juga dapat dipengaruhi oleh jaringan, DNS, wilayah, dan kondisi mirror untuk judul tersebut.

Jika muncul pesan **"tidak ada tautan yang ditemukan"**:

1. Perbarui CloudStream dan ekstensi IndoProvider ke versi terbaru.
2. Coba server atau provider lain untuk judul yang sama.
3. Pastikan situs sumber dapat dijangkau dari jaringan Anda.
4. Jika masalah berulang, laporkan nama provider, judul, episode, server, versi ekstensi, dan log yang relevan agar masalah dapat direproduksi.

## Untuk Pengembang

Prasyarat: **JDK 17** dan **Android SDK**. Dari direktori utama proyek, jalankan:

```bash
# Unit test
./gradlew :IndoProvider:testDebugUnitTest

# Paket plugin CloudStream
./gradlew :IndoProvider:make

# AAR debug
./gradlew :IndoProvider:assembleDebug
```

> Di Windows, ganti `./gradlew` dengan `.\gradlew.bat`.

Live test sengaja dilewati pada pengujian biasa karena mengakses situs eksternal. Untuk menjalankan rangkaian live test utama (PowerShell):

```powershell
$env:RUN_LIVE_PROVIDER_TESTS='1'
.\gradlew.bat :IndoProvider:testDebugUnitTest
```

Audit pipeline film yang lebih luas dapat dijalankan dengan variabel `RUN_LIVE_MOVIE_PROVIDER_TESTS=1`. Hasil live test tidak selalu stabil karena bergantung pada layanan pihak ketiga.

Build otomatis menghasilkan paket plugin, `plugins.json`, dan `repo.json` pada branch `builds`.

## Kontribusi dan Laporan Bug

Perbaikan parser, pengujian regresi, dan laporan provider yang rusak sangat diterima. Sertakan contoh URL atau judul yang masih tersedia secara publik serta langkah reproduksi. Jangan menyertakan cookie, token, akun, atau data pribadi apa pun pada laporan.

## Disclaimer

Proyek ini tidak berafiliasi dengan CloudStream maupun situs provider yang tercantum. Pengembang tidak meng-host konten video dan tidak mengendalikan materi yang disediakan pihak ketiga. Pengguna bertanggung jawab mematuhi hukum, ketentuan layanan, dan hak cipta yang berlaku di wilayah masing-masing.
