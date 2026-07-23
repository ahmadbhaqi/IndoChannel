IndoChannel

IndoChannel adalah repositori ekstensi komunitas untuk "CloudStream" (https://github.com/recloudstream/cloudstream) yang menghadirkan katalog film, serial, dan anime dari berbagai provider berbahasa Indonesia melalui satu ekstensi, "IndoProvider". Ekstensi ini tidak menyimpan atau meng-host video, tetapi memproses katalog dan tautan yang disediakan oleh situs sumber pihak ketiga.

Provider

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

Instalasi

""Pasang IndoChannel di CloudStream" (https://img.shields.io/badge/Pasang%20di%20CloudStream-IndoChannel-5c6bc0?style=for-the-badge&logo=android&logoColor=white)" (https://self-similarity.github.io/http-protocol-redirector?r=cloudstreamrepo://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json)

Atau tambahkan repositori melalui Settings → Extensions → Add Repository, kemudian masukkan URL berikut:

https://raw.githubusercontent.com/ahmadbhaqi/IndoChannel/builds/repo.json

Setelah repositori ditambahkan, buka IndoChannel dan instal ekstensi IndoProvider.

Pengembangan

Membutuhkan JDK 17 dan Android SDK. Jalankan ./gradlew :IndoProvider:testDebugUnitTest untuk unit test dan ./gradlew :IndoProvider:make untuk membangun plugin. Pada Windows, gunakan gradlew.bat.

Kontribusi

Issue dan pull request untuk perbaikan parser, provider, pemutaran, subtitle, pengujian, atau dokumentasi sangat diterima. Saat melaporkan masalah, sertakan nama provider, judul, episode, server yang dipilih, dan langkah reproduksi; jangan menyertakan cookie, token, kredensial akun, atau data pribadi.

Disclaimer

IndoChannel merupakan proyek komunitas independen dan tidak berafiliasi dengan CloudStream maupun situs provider yang tercantum. Pengembang tidak meng-host atau mengendalikan konten yang disediakan oleh pihak ketiga. Pengguna bertanggung jawab untuk mematuhi hukum, ketentuan layanan, dan hak cipta yang berlaku di wilayah masing-masing.
