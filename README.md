# IndoChannel

IndoChannel adalah repositori ekstensi komunitas untuk CloudStream yang menghadirkan katalog film, serial, dan anime dari berbagai provider berbahasa Indonesia melalui satu ekstensi, `IndoProvider`. Ekstensi ini tidak menyimpan atau meng-host video, melainkan memproses katalog dan tautan yang disediakan oleh situs sumber pihak ketiga.

## Provider

| Film & Serial    | Anime      |
| ---------------- | ---------- |
| LayarKaca (LK21) | Otakudesu  |
| Ngefilm          | Samehadaku |
| Dutamovie        | Anoboy     |
| KitaNonton       | Kuronime   |
| IndoXXI          | Animeindo  |
| Filmapik         | Oploverz   |
| IDLIX            | Zoronime   |
| Pusatfilm        |            |
| keBioskop21      |            |

## Instalasi

[![Install Repository in CloudStream](https://img.shields.io/badge/Install%20Repository-CloudStream-5c6bc0?style=for-the-badge&logo=android&logoColor=white)](https://self-similarity.github.io/http-protocol-redirector?r=cloudstreamrepo://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json)

Atau tambahkan repositori secara manual melalui **Settings → Extensions → Add Repository**, lalu masukkan URL berikut:

```
https://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json
```

Setelah repositori ditambahkan, buka **IndoChannel** dan instal ekstensi **IndoProvider**.

## Pengembangan

Membutuhkan **JDK 17** dan **Android SDK**.

```bash
./gradlew :IndoProvider:testDebugUnitTest   # unit test
./gradlew :IndoProvider:make                # build plugin
```

Pada Windows, gunakan `gradlew.bat` sebagai gantinya.

## Kontribusi

Issue dan pull request untuk perbaikan parser, provider, pemutaran, subtitle, pengujian, atau dokumentasi sangat diterima. Saat melaporkan masalah, sertakan nama provider, judul, episode, server yang dipilih, dan langkah reproduksi. Jangan menyertakan cookie, token, kredensial akun, atau data pribadi apa pun.

## Disclaimer

IndoChannel adalah proyek komunitas independen dan tidak berafiliasi dengan CloudStream maupun situs provider yang tercantum. Pengembang tidak meng-host atau mengendalikan konten yang disediakan pihak ketiga. Pengguna bertanggung jawab mematuhi hukum, ketentuan layanan, dan hak cipta yang berlaku di wilayah masing-masing.
