# Graph Report - .  (2026-07-16)

## Corpus Check
- Corpus is ~12,738 words - fits in a single context window. You may not need a graph.

## Summary
- 410 nodes · 710 edges · 23 communities (21 shown, 2 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 44 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Idlix Parser
- Plugin Registration Aliases
- Otakudesu Provider
- Anoboy Provider
- Samehadaku Provider
- Inline Media Decryption
- Shared HTML Parsing
- Animeindo Kuronime Providers
- Gomov Provider
- Filmapik Provider
- Ngefilm Provider
- Dutamovie Provider
- Pusatfilm Provider
- Kitanonton Provider
- Miranime Provider
- Oploverz Provider
- Parser Regression Tests
- Repository Design Pipeline
- Provider Domain Tests
- Gradle Wrapper Script

## God Nodes (most connected - your core abstractions)
1. `ProviderHtmlParserTest` - 18 edges
2. `InlineDataParser` - 16 edges
3. `loadResolvedExtractorWithResult()` - 15 edges
4. `RebahinProvider` - 15 edges
5. `GomovProvider` - 14 edges
6. `IdlixApiParser` - 13 edges
7. `NgefilmProvider` - 13 edges
8. `DutamovieProvider` - 12 edges
9. `ProviderHtmlParser` - 12 edges
10. `FilmapikProvider` - 11 edges

## Surprising Connections (you probably didn't know these)
- `Generated Cloudstream repository manifest` --conceptually_related_to--> `Cloudstream repository installation`  [INFERRED]
  .github/workflows/build.yml → README.md
- `Build workflow` --references--> `JDK 17 and Gradle build toolchain`  [EXTRACTED]
  .github/workflows/build.yml → README.md
- `Gradle package publication workflow` --references--> `JDK 17 and Gradle build toolchain`  [EXTRACTED]
  .github/workflows/gradle-publish.yml → README.md
- `IndoChannel repository README` --references--> `Seven requested provider aliases`  [EXTRACTED]
  README.md → docs/superpowers/specs/2026-07-07-provider-url-additions-design.md
- `Provider URL additions implementation plan` --semantically_similar_to--> `Provider URL additions design`  [INFERRED] [semantically similar]
  docs/superpowers/plans/2026-07-07-provider-url-additions.md → docs/superpowers/specs/2026-07-07-provider-url-additions-design.md

## Import Cycles
- None detected.

## Communities (23 total, 2 thin omitted)

### Community 0 - "Idlix Parser"
Cohesion: 0.14
Nodes (16): IdlixApiParser, IdlixCatalogItem, IdlixEpisodeItem, IdlixProvider, IdlixSeasonItem, IdlixStreamData, Boolean, HomePageResponse (+8 more)

### Community 1 - "Plugin Registration Aliases"
Cohesion: 0.09
Nodes (18): Context, CgvindoProvider, IndofilmProvider, IndoPlugin, IndoxxiProvider, JuraganFilmProvider, LayarKacaProvider, Boolean (+10 more)

### Community 2 - "Otakudesu Provider"
Cohesion: 0.13
Nodes (17): getStatus(), getType(), AnimeSearchResponse, Boolean, HomePageResponse, Int, List, LoadResponse (+9 more)

### Community 3 - "Anoboy Provider"
Cohesion: 0.13
Nodes (17): AnoboyProvider, AnimeSearchResponse, Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI (+9 more)

### Community 4 - "Samehadaku Provider"
Cohesion: 0.13
Nodes (15): getStatus(), getType(), AnimeSearchResponse, Boolean, HomePageResponse, Int, List, LoadResponse (+7 more)

### Community 5 - "Inline Media Decryption"
Cohesion: 0.24
Nodes (6): ByteArray, InlineDataParser, Int, List, String, JsonNode

### Community 6 - "Shared HTML Parsing"
Cohesion: 0.20
Nodes (8): Document, Element, Boolean, List, String, MuviproAjaxRequest, ProviderHtmlParser, Map

### Community 7 - "Animeindo Kuronime Providers"
Cohesion: 0.11
Nodes (13): AnimeindoProvider, AnimeSearchResponse, Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI (+5 more)

### Community 8 - "Gomov Provider"
Cohesion: 0.18
Nodes (10): GomovProvider, Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI, MainPageRequest (+2 more)

### Community 9 - "Filmapik Provider"
Cohesion: 0.17
Nodes (11): FilmapikProvider, FilmapikSearchItem, Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI (+3 more)

### Community 10 - "Ngefilm Provider"
Cohesion: 0.18
Nodes (10): Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI, MainPageRequest, SearchResponse (+2 more)

### Community 11 - "Dutamovie Provider"
Cohesion: 0.19
Nodes (10): DutamovieProvider, Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI, MainPageRequest (+2 more)

### Community 12 - "Pusatfilm Provider"
Cohesion: 0.18
Nodes (10): Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI, MainPageRequest, SearchResponse (+2 more)

### Community 13 - "Kitanonton Provider"
Cohesion: 0.18
Nodes (10): KitanontonProvider, Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI, MainPageRequest (+2 more)

### Community 14 - "Miranime Provider"
Cohesion: 0.16
Nodes (11): AnimeSearchResponse, Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI, MainPageRequest (+3 more)

### Community 15 - "Oploverz Provider"
Cohesion: 0.16
Nodes (11): AnimeSearchResponse, Boolean, HomePageResponse, Int, List, LoadResponse, MainAPI, MainPageRequest (+3 more)

### Community 17 - "Repository Design Pipeline"
Cohesion: 0.22
Nodes (14): Automated plugin builds-branch pipeline, Cloudstream repository installation, Custom scraper work deferred because DOM compatibility is uncertain, Generated Cloudstream repository manifest, GitHub Packages release publication, Build workflow, Gradle package publication workflow, IndoChannel repository README (+6 more)

### Community 19 - "Gradle Wrapper Script"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **4 isolated node(s):** `FilmapikSearchItem`, `ResponseSources`, `ResponseData`, `GitHub Packages release publication`
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `IdlixProvider` connect `Idlix Parser` to `Plugin Registration Aliases`?**
  _High betweenness centrality (0.215) - this node is a cross-community bridge._
- **Why does `ProviderHtmlParserTest` connect `Parser Regression Tests` to `Idlix Parser`, `Shared HTML Parsing`?**
  _High betweenness centrality (0.146) - this node is a cross-community bridge._
- **Why does `OtakudesuProvider` connect `Otakudesu Provider` to `Plugin Registration Aliases`?**
  _High betweenness centrality (0.090) - this node is a cross-community bridge._
- **Are the 9 inferred relationships involving `loadResolvedExtractorWithResult()` (e.g. with `.loadLinks()` and `.loadLinks()`) actually correct?**
  _`loadResolvedExtractorWithResult()` has 9 INFERRED edges - model-reasoned connections that need verification._
- **What connects `FilmapikSearchItem`, `ResponseSources`, `ResponseData` to the rest of the system?**
  _4 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Idlix Parser` be split into smaller, more focused modules?**
  _Cohesion score 0.13663663663663664 - nodes in this community are weakly interconnected._
- **Should `Plugin Registration Aliases` be split into smaller, more focused modules?**
  _Cohesion score 0.09090909090909091 - nodes in this community are weakly interconnected._