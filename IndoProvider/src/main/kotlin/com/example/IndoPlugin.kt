package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IndoPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider ke dalam Cloudstream
        
        // Movie & TV Series
        // Provider tambahan diurutkan dari trafik publik tertinggi.
        registerMainAPI(MovieboxProvider())
        registerMainAPI(PencurimovieProvider())
        registerMainAPI(SarangfilmProvider())
        registerMainAPI(NomatProvider())
        registerMainAPI(IndomaxProvider())
        registerMainAPI(KawanfilmProvider())
        registerMainAPI(LayarKacaProvider())
        registerMainAPI(NgefilmProvider())
        registerMainAPI(DutamovieProvider())
        registerMainAPI(KitanontonProvider())
        registerMainAPI(IndoxxiProvider())
        registerMainAPI(FilmapikProvider())
        registerMainAPI(IdlixProvider())
        registerMainAPI(PusatfilmProvider())
        registerMainAPI(KeBioskopProvider())
        
        // Anime
        // Provider tambahan diurutkan dari trafik publik tertinggi.
        registerMainAPI(KuramanimeProvider())
        registerMainAPI(AnimasuProvider())
        registerMainAPI(OtakudesuProvider())
        registerMainAPI(SamehadakuProvider())
        registerMainAPI(AnoboyProvider())
        registerMainAPI(KuronimeProvider())
        registerMainAPI(AnimeindoProvider())
        registerMainAPI(OploverzProvider())
        registerMainAPI(ZoronimeProvider())
    }
}
