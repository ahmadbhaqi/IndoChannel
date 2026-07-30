dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation(kotlin("test-junit"))
    // Cloudstream's extractor registry references these compile-only runtime
    // helpers when the real network resolver is exercised from JVM tests.
    testImplementation("org.mozilla:rhino:1.8.1")
    testImplementation("me.xdrop:fuzzywuzzy:1.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
    testImplementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")
}

configurations.named("testImplementation") {
    extendsFrom(configurations.getByName("compileOnly"))
}

version = 21

cloudstream {
    description = "Film, serial, dan anime dari berbagai provider Indonesia dalam satu ekstensi CloudStream."
    authors = listOf("Ahmad")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
    requiresResources = false
    language = "id"
    iconUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/ic_launcher-playstore.png"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
