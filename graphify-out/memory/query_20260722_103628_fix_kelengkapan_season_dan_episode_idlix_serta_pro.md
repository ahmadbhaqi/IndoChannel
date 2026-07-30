---
type: "codebase"
date: "2026-07-22T10:36:28.718065+00:00"
question: "Fix kelengkapan season dan episode IDLIX serta provider lain, gabungkan stream dengan subtitle Indonesia saja, dan rename IDLIX menjadi Idlix."
contributor: "graphify"
outcome: "useful"
source_nodes: ["IdlixProvider", "IdlixParser", "FilmapikProvider", "OtakudesuProvider", "NgefilmProvider", "PusatfilmProvider", "DutamoviePlayerParser"]
---

# Q: Fix kelengkapan season dan episode IDLIX serta provider lain, gabungkan stream dengan subtitle Indonesia saja, dan rename IDLIX menjadi Idlix.

## Answer

Idlix kini memuat semua season melalui endpoint season tervalidasi dengan fallback aman, hasil pencarian multipage dibatasi budget total 30 detik, dan subtitle yang diteruskan hanya Bahasa Indonesia pada load stream yang sama. Young Sheldon terverifikasi 7 season dan 141 episode dengan rentang episode unik lengkap. Filmapik pagination, Otakudesu episode-list, serta mapping season/episode Dutamovie-Ngefilm-Pusatfilm diperbaiki. Verifikasi akhir: 342 unit tests, 0 failure/error; assembleDebug sukses; dua live test Young Sheldon sukses.

## Outcome

- Signal: useful

## Source Nodes

- IdlixProvider
- IdlixParser
- FilmapikProvider
- OtakudesuProvider
- NgefilmProvider
- PusatfilmProvider
- DutamoviePlayerParser