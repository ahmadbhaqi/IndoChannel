# Provider URL Additions Design

## Context

IndoChannel registers Cloudstream providers from `IndoProvider/src/main/kotlin/com/example/IndoPlugin.kt`. Several current providers reuse an existing scraper by subclassing a compatible provider and overriding `mainUrl` plus `name`, such as `IdlixProvider`, `JuraganFilmProvider`, and `CgvindoProvider`.

The requested addition is to include these providers:

- Movie & TV Series: Indoxxi (`https://comblank.com`), Filmapik (`https://filmapik.to`), Indofilm (`https://yuhhaber.com`)
- Anime: Animeindo (`https://anime-indo.lol`), Oploverz (`https://plus.oploverz.ltd`), Zoronime (`https://zoronime.live`), Miranime (`https://miranime.net`)

## Goal

Add the requested URLs as first-class providers while keeping the implementation aligned with the scraper patterns already present in the project.

## Approach

Use small provider classes for each requested source. Each new class will reuse an existing provider implementation where the site appears to share a compatible WordPress/anime layout, then override only provider identity and base URL unless a small category adjustment is necessary.

For providers that need to be subclassed, existing final Kotlin provider classes may be changed to `open class` only when required. No broad scraper refactor is planned.

## Registration

`IndoPlugin.kt` will register the three Movie & TV Series providers in the existing movie block and the four Anime providers in the existing anime block.

## Tests

Extend the existing domain-oriented tests to assert:

- Each new provider source file contains the requested `mainUrl`.
- `IndoPlugin.kt` registers all seven new providers.

This keeps the requested provider list protected from accidental removal or domain drift.

## Out of Scope

- Writing a fully custom scraper per new domain.
- Verifying live playback for every site and extractor.
- Refactoring all existing providers into shared base classes.

## Risks

Some domains may not share enough DOM structure with the chosen base scraper. Under the selected scope, the implementation will follow current project patterns and minimize changes; deeper per-site scraping can be handled as a follow-up if a provider needs custom selectors.
