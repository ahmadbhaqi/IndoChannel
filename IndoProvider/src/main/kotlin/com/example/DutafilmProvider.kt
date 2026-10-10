package com.example

/** Dutafilm currently uses the same movie catalog and player markup as Dutamovie. */
class DutafilmProvider : DutamovieProvider() {
    override val legacyHosts: Set<String> = emptySet()

    init {
        mainUrl = "http://178.128.161.40"
        name = "Dutafilm"
    }
}
