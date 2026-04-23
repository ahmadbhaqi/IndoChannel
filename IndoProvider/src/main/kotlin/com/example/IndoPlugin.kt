package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class IndoPlugin: Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider ke dalam Cloudstream
        
        // Movie & TV Series
        registerMainAPI(LayarKacaProvider())
        registerMainAPI(NgefilmProvider())
        registerMainAPI(PusatfilmProvider())
        registerMainAPI(DutamovieProvider())
        registerMainAPI(RebahinProvider())
        registerMainAPI(CgvindoProvider())
        registerMainAPI(KitanontonProvider())
        registerMainAPI(GomovProvider())
        
        // Anime
        registerMainAPI(OtakudesuProvider())
        registerMainAPI(SamehadakuProvider())
        registerMainAPI(AnoboyProvider())
        registerMainAPI(KuronimeProvider())
        registerMainAPI(AnimeSailProvider())
        registerMainAPI(AnimeIndoProvider())
    }
}
