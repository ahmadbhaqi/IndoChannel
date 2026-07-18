# IndoChannel

IndoChannel adalah repositori ekstensi komunitas untuk [CloudStream](https://github.com/recloudstream/cloudstream). Satu ekstensi `IndoProvider` menghadirkan katalog film, serial, dan anime dari berbagai provider berbahasa Indonesia langsung di dalam CloudStream.

> IndoChannel bukan aplikasi streaming mandiri dan tidak menyimpan atau meng-host video. Ekstensi hanya membaca katalog serta tautan yang disediakan oleh situs sumber.

## Instalasi yang direkomendasikan

Gunakan tombol berikut dari perangkat Android yang sudah memiliki CloudStream. Tautan akan membuka CloudStream dan menawarkan penambahan repositori IndoChannel secara otomatis.

[![Pasang IndoChannel di CloudStream](https://img.shields.io/badge/Pasang%20di%20CloudStream-IndoChannel-5c6bc0?style=for-the-badge&logo=android&logoColor=white)](cloudstreamrepo://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json)

Tautan instalasi langsung:

[`cloudstreamrepo://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json`](cloudstreamrepo://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json)

Setelah CloudStream terbuka:

1. Setujui penambahan repositori `IndoChannel`.
2. Buka **Pengaturan / Settings > Extensions**.
3. Pilih repositori **IndoChannel**, lalu pasang atau perbarui ekstensi **IndoProvider**.

### Jika tombol tidak membuka CloudStream

Tambahkan repositori secara manual melalui **Settings > Extensions > Add repository**, lalu masukkan:

```text
https://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json
```

[Buka file repo.json](https://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json)

Nama repositori dapat diisi `IndoChannel`. Letak dan nama menu bisa sedikit berbeda pada setiap versi atau fork CloudStream.

## Provider yang tersedia

Provider berikut terdaftar di dalam ekstensi `IndoProvider` saat ini:

| Film & serial | Anime |
| --- | --- |
| LayarKaca (LK21) | Otakudesu |
| Ngefilm | Samehadaku |
| Dutamovie | Anoboy |
| KitaNonton | Kuronime |
| IndoXXI | Animeindo |
| Filmapik | Oploverz |
| Rebahin | Zoronime |
| IDLIX |  |

Fitur yang muncul dapat berbeda antarprovider, tetapi umumnya mencakup katalog, pencarian, detail judul, episode, metadata, subtitle, dan pilihan server jika disediakan oleh sumber.

## Catatan ketersediaan

Situs sumber dapat mengganti domain, struktur halaman, proteksi anti-bot, server pemutar, atau menghapus sebuah judul tanpa pemberitahuan. Karena itu, provider maupun tautan tertentu dapat berhenti berfungsi sementara walaupun ekstensi sudah versi terbaru. Ketersediaan juga dapat dipengaruhi jaringan, DNS, wilayah, dan kondisi mirror untuk judul tersebut.

Jika muncul pesan **tidak ada tautan yang ditemukan**:

1. Perbarui CloudStream dan ekstensi IndoProvider.
2. Coba server atau provider lain untuk judul yang sama.
3. Pastikan situs sumber dapat dijangkau dari jaringan Anda.
4. Jika masalah berulang, laporkan nama provider, judul, episode, server, versi ekstensi, dan log yang relevan agar masalah dapat direproduksi.

## Untuk pengembang

Prasyarat utama adalah JDK 17 dan Android SDK. Dari direktori utama proyek, jalankan:

```powershell
# Unit test
.\gradlew.bat :IndoProvider:testDebugUnitTest

# Paket plugin CloudStream
.\gradlew.bat :IndoProvider:make

# AAR debug
.\gradlew.bat :IndoProvider:assembleDebug
```

Di Linux atau macOS, ganti `.\gradlew.bat` dengan `./gradlew`.

Live test sengaja dilewati pada pengujian biasa karena mengakses situs eksternal. Untuk menjalankan rangkaian live test utama di PowerShell:

```powershell
$env:RUN_LIVE_PROVIDER_TESTS='1'
.\gradlew.bat :IndoProvider:testDebugUnitTest
```

Audit pipeline film yang lebih luas dapat dijalankan dengan `RUN_LIVE_MOVIE_PROVIDER_TESTS=1`. Hasil live test tidak selalu stabil karena bergantung pada layanan pihak ketiga.

Build otomatis menghasilkan paket plugin, `plugins.json`, dan `repo.json` pada branch `builds`.

## Kontribusi dan laporan bug

Perbaikan parser, pengujian regresi, dan laporan provider yang rusak sangat diterima. Sertakan contoh URL atau judul yang masih tersedia secara publik serta langkah reproduksi; jangan menyertakan cookie, token, akun, atau data pribadi.

## Disclaimer

Proyek ini tidak berafiliasi dengan CloudStream maupun situs provider yang tercantum. Pengembang tidak meng-host konten video dan tidak mengendalikan materi yang disediakan pihak ketiga. Pengguna bertanggung jawab mematuhi hukum, ketentuan layanan, dan hak cipta yang berlaku di wilayah masing-masing.
