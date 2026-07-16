# Movie Provider Server Resolution Design

**Date:** 2026-07-16

## Problem

Cloudstream reports "tidak ada server yang ditemukan" whenever a provider's `loadLinks()` returns without emitting an `ExtractorLink`. The repository currently has several independent causes for that outcome:

- `loadResolvedExtractorWithResult()` only has custom handling for PlaySobat and otherwise delegates to Cloudstream's registered extractors.
- Active movie pages can return direct HLS/MP4 media or AsiaStream embeds that are not covered by the upstream extractor registry.
- Ngefilm, Dutamovie, and Pusatfilm still use the older extractor path instead of the shared resolver.
- Some providers return block pages, Cloudflare challenges, unrelated redirects, empty article pages, trailer-only pages, or upstream player errors.
- Per-provider implementations duplicate traversal logic and sometimes stop without trying every candidate.

The user requires every provider to remain registered and visible. Providers whose upstream sites contain no playable media therefore remain best-effort, but resolvable media must be recovered consistently.

## Goals

- Keep every current provider registration in `IndoPlugin`.
- Centralize candidate resolution for movie providers while preserving anime providers that already use the shared path.
- Emit direct HLS and MP4 sources without requiring a host-specific upstream extractor.
- Add a deterministic AsiaStream adapter and retain PlaySobat support.
- Continue through remaining candidates when one candidate fails.
- Prevent recursive iframe loops and duplicate emitted links.
- Detect non-content/interstitial responses before treating them as player pages.
- Cover the shared behavior with fixture-based regression tests.

## Non-Goals

- Bypassing ISP filtering, Cloudflare challenges, or access controls.
- Inventing media URLs when the upstream page contains no playable source.
- Removing or hiding unhealthy providers.
- Building a general-purpose JavaScript browser or unlimited recursive scraper.
- Refactoring unrelated provider catalog, search, or metadata code.

## Chosen Approach

Use a layered resolver in `ProviderLinkLoader` and migrate every remaining provider from the legacy loader to it. This is preferred over provider-specific patches because host behavior is shared, and preferred over unrestricted recursive scraping because the latter is slow, unsafe, and difficult to test.

## Architecture

### Candidate collection

Each provider remains responsible for collecting candidate URLs from its own page structure, including:

- iframe and media metadata sources;
- player tab pages;
- Muvipro AJAX responses;
- API-returned player URLs; and
- inline data already parsed by provider-specific code.

Providers pass each raw candidate and the correct referer into the shared resolver. They do not implement host-specific resolution locally.

### Shared resolution pipeline

Each provider creates one `LinkResolutionSession` for a `loadLinks()` invocation and submits all candidates through that session. `ProviderLinkLoader` resolves a candidate in this order:

1. Normalize the candidate URL and reject blank, script, trailer, and already-visited URLs.
2. Emit direct `.m3u8` and `.mp4` URLs with the appropriate media type and referer headers. Classification uses the case-insensitive URI path so query strings do not hide the extension.
3. Apply known adapters:
   - PlaySobat: fetch and decrypt its nested source list using the existing parser.
   - AsiaStream: fetch the watch page, parse its `sniff(...)` configuration, construct the `master.txt` HLS URL, and emit it with the watch-page referer.
4. Run Cloudstream's registered extractor and track whether its callback emits a link.
5. If nothing was emitted, fetch the candidate page once and inspect its iframe/media sources for nested candidates.
6. Resolve nested candidates with a maximum depth of one from the original provider candidate.

`LinkResolutionSession` owns visited candidate URLs, emitted media URLs, the depth limit, callbacks, and the aggregate loaded state for one `loadLinks()` request. This prevents loops and duplicate servers across every candidate collected by that provider.

### Pure parsing helpers

Pure, deterministic parsing remains separate from network orchestration:

- `ProviderHtmlParser` identifies iframe/media candidates and non-content interstitial pages.
- `InlineDataParser` parses AsiaStream `sniff(...)` arguments and continues to parse PlaySobat payloads.
- URL classification distinguishes HLS, MP4, trailers, player pages, and invalid candidates.

Network calls and callback emission remain in `ProviderLinkLoader`.

### Provider migration

Ngefilm, Dutamovie, and Pusatfilm move from `loadExtractorWithResult()` to a shared `LinkResolutionSession`. Existing providers already using the shared resolver retain their page-specific candidate collection but use a session where multiple candidates must share deduplication state. All registrations in `IndoPlugin` remain unchanged.

## Data Flow

1. Cloudstream calls a provider's `loadLinks(data, ...)`.
2. The provider loads its content page or API response and gathers candidate URLs.
3. Each unique candidate enters the shared resolver with its referer.
4. The resolver either emits a direct link, expands a known adapter, delegates to an upstream extractor, or scans one nested player page.
5. Failures are isolated per candidate and the next candidate is attempted.
6. `loadLinks()` returns `true` if at least one link was emitted; otherwise it returns `false` after exhausting every candidate.

## Error Handling

- Network, parsing, and unsupported-host failures are handled per candidate and do not abort the remaining candidates.
- Coroutine cancellation is never swallowed and is rethrown immediately.
- Recursion is bounded by depth and a visited set.
- Internet Positif pages, Cloudflare challenge pages, obvious database failure pages, and empty HTML are classified as non-content.
- YouTube trailer URLs remain excluded from playable server results.
- An upstream extractor's Boolean return is not trusted as proof of success; actual callback emission determines whether a link was loaded.
- Providers remain visible even when their current upstream domain is unhealthy.

## Testing

Tests are fixture-based and do not depend on live provider availability:

- URL classification for HLS, MP4, relative URLs, trailers, and invalid schemes.
- AsiaStream `sniff(...)` parsing and construction of its HLS master URL.
- Existing PlaySobat parsing remains covered.
- Interstitial and upstream-error page detection.
- Candidate and emitted-link deduplication.
- Maximum nested iframe depth and cycle prevention.
- Failure of one candidate followed by success of a later candidate.
- Regression checks that Ngefilm, Dutamovie, and Pusatfilm use the shared resolver.
- Registration tests confirming all providers remain in `IndoPlugin`.

Verification includes the full `:IndoProvider:testDebugUnitTest` task and a debug compilation/build task with the local Android SDK configured for the command.

## Success Criteria

- The reproducible AsiaStream page produces at least one HLS `ExtractorLink` through the shared pipeline.
- Direct `.m3u8` and `.mp4` candidates emit links without a host-specific extractor.
- A failed/unsupported server does not prevent later valid candidates from loading.
- All current providers remain registered.
- The full unit test suite and debug build pass.
- Truly empty, blocked, or down upstream sources fail cleanly only after all available candidates have been attempted.
