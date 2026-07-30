---
type: "query"
date: "2026-07-22T08:12:26.310759+00:00"
question: "idlix sekarang dapat berjalan dengan lancar, walaupun belum sepenuhnya, karna saat saya mencari yang tidak ada di rekomendasi dashboard atau katalog, statusnya tautan tidak ditemukan, its more likely all of them cannot played well but a few can, dan juga tidak peduli movie manapun, tetap tidak ada subtitle karena mungkin memang dipisah antara movie dengan subtitle, saya harap kamu bisa fix"
contributor: "graphify"
outcome: "useful"
source_nodes: ["IdlixProvider", "load()", "loadLinks()", "IdlixParser"]
---

# Q: idlix sekarang dapat berjalan dengan lancar, walaupun belum sepenuhnya, karna saat saya mencari yang tidak ada di rekomendasi dashboard atau katalog, statusnya tautan tidak ditemukan, its more likely all of them cannot played well but a few can, dan juga tidak peduli movie manapun, tetap tidak ada subtitle karena mungkin memang dipisah antara movie dengan subtitle, saya harap kamu bisa fix

## Answer

Expanded from original query via graph vocab: [idlix, search, catalog, load, links, playable, stream, movie, episode, api]. Fixed IDLIX search-only playback and subtitles: support direct and gate/redeem responses; accept current compact and legacy signed master layouts; resolve same-video subtitle and media siblings with authoritative t/pm tokens; emit subtitle headers; normalize and strictly bound gate polling; preserve series cards whose video lives on episodes; reject unavailable movie cards and invalid signed URLs. Verified with 327 unit tests (0 failures/errors), debug assembly, and a non-skipped live Inception flow that fetched search detail, signed media playlists/segments, and a subtitle URL.

## Outcome

- Signal: useful

## Source Nodes

- IdlixProvider
- load()
- loadLinks()
- IdlixParser