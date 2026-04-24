# 🇮🇩 IndoChannel Cloudstream Repository

Selamat datang di repositori **IndoChannel** untuk [Cloudstream](https://github.com/recloudstream/cloudstream)! 
Repositori ini menyediakan berbagai ekstensi (plugin) untuk layanan streaming film dan anime berbahasa Indonesia.

## 📦 Daftar Provider (Ekstensi)

Saat ini, IndoChannel mendukung berbagai sumber streaming lokal terpopuler, antara lain:

### 🎬 Movie & TV Series
- **Rebahin**
- **LayarKaca (LK21)**
- **Gomov**
- **Ngefilm**
- **Pusatfilm**
- **Dutamovie**
- **Idlix**
- **JuraganFilm**
- **Cgvindo**

### 🌸 Anime
- **Otakudesu**
- **Samehadaku**
- **Anoboy**
- **Kuronime**

> **Catatan:** Semua sumber yang ada di sini adalah layanan web streaming gratis yang dikurasi. Harap maklum jika ada situs yang kadang offline atau berubah domain (kami selalu berusaha melakukan update secara berkala).

---

## 🛠️ Cara Pemasangan (Instalasi)

Untuk memasukkan semua provider dari IndoChannel ke aplikasi Cloudstream Anda, ikuti langkah-langkah mudah berikut:

### Metode 1: Penggunaan Shortcode (Direkomendasikan)
1. Buka aplikasi **Cloudstream**.
2. Masuk ke tab **Settings** (Pengaturan) -> **Extensions** (Ekstensi).
3. Klik tombol **+ Add Repository**.
4. Pada kolom yang muncul, isi URL dengan link berikut ini:
   ```text
   https://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json
   ```
5. Isi nama repositori dengan `IndoChannel` (opsional).
6. Klik **Download** dan tunggu hingga daftar ekstensi muncul, lalu tekan lambang Unduh (panah ke bawah) untuk setiap plugin yang ingin digunakan.

### Metode 2: Sekali Klik (Bila didukung)
Jika Anda membuka halaman ini di browser HP Anda yang sudah terinstal Cloudstream, cukup salin tautan instalasi otomatis berikut lalu buka di browser Anda:

`cloudstreamrepo://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json`

---

## 💻 Untuk Pengembang (Developer)

Ingin ikut berkontribusi atau memperbaiki scraper yang bermasalah?
Pastikan Anda sudah menginstal **JDK 17+** dan menggunakan **Android Studio** atau Command Line.

### Kompilasi & Build Lokal:
- Windows: `.\gradlew.bat IndoProvider:make` atau `.\gradlew.bat IndoProvider:deployWithAdb`
- Linux & Mac: `./gradlew IndoProvider:make` atau `./gradlew IndoProvider:deployWithAdb`

### Catatan Penting
Semua struktur plugin dan CSS Selector berpedoman pada dokumentasi `cloudstream3-plugin`. Mohon jangan menggabungkan dua domain berbeda jika kelas elemen web mereka (*DOM class*) tidak sama.

---

## ⚖️ Lisensi dan Disclaimer

- Proyek ini dirilis ke dalam domain publik (Public Domain).
- **DISCLAIMER:** Developer tidak meng-host file video apa pun di server sendiri. Repositori ini murni hanya sebuah *scraper* (pengumpul tautan) dan menautkan konten ke provider aslinya.
