# STOCKS: US equities

## Architecture and scope

STOCKS is an opt-in US integration in the existing `api` and `rest` modules.
`/api/quote/**` and Forex retain their previous contracts, defaults and providers.

- `AssetMarket.US` validates canonical tickers without adding or removing share-class punctuation.
- `StocksQuery` composes quotes, optional historical data and optional corporate actions.
- `TwelveDataQuoteProvider` implements the existing provider interface using Java HTTP and Jackson.
- A qualified US `ProviderOrchestrator` reuses Caffeine, Resilience4j, provider health tracking and single-flight infrastructure.
- B3 remains the primary Spring orchestrator and the default library holder.
- US caches use `US:` keys; the legacy B3 cache aliases remain unchanged.
- Quote response copies prevent history/dividend options from modifying cached base quotes.

The initial universe is USD-denominated common stocks, preferred stocks and REIT equities
on supported US listed venues, resolved through the provider catalog. ETFs, funds, ADR types,
OTC, options and conversion to BRL are not included. Catalog absence is a confirmed 404;
catalog unavailability is not.

Only Twelve Data is configured remotely. Failures use the US stale quote cache, never B3,
brapi, TradingView or an unlicensed Yahoo fallback. Additional licensed US implementations
of `StockQuoteProvider` can participate in the same US chain. Constructors reject a mixed-market chain.

## Enable and configure

Set `COTATRACK_STOCKS_ENABLED=true` and `TWELVE_DATA_API_KEY` before starting the existing REST application.
Enabling without a key fails configuration explicitly. No credentials or production demo key are committed.
Requests authenticate with an HTTP header rather than URL parameters.

| Spring property | Default | Meaning |
|---|---|---|
| `cotatrack.stocks.enabled` | `false` | Opt-in integration |
| `cotatrack.stocks.api-key` | `${TWELVE_DATA_API_KEY:}` | Provider API key |
| `cotatrack.stocks.timeout` | `PT3S` | ISO-8601 timeout per HTTP request |
| `cotatrack.stocks.credits-per-minute` | `8` | Shared weighted budget for all US endpoints |
| `cotatrack.stocks.credits-per-day` | `800` | Shared daily budget, reset at UTC midnight |
| `cotatrack.stocks.fundamentals-enabled` | `false` | Fetch profile, statistics and logo |
| `cotatrack.stocks.credits.stocks` | `1` | Catalog request weight |
| `cotatrack.stocks.credits.quote` | `1` | Per-symbol quote request weight |
| `cotatrack.stocks.credits.time_series` | `1` | Historical window request weight |
| `cotatrack.stocks.credits.profile` | `10` | Profile request weight |
| `cotatrack.stocks.credits.statistics` | `50` | Statistics request weight |
| `cotatrack.stocks.credits.logo` | `1` | Logo request weight |
| `cotatrack.stocks.credits.dividends` | `20` | Dividend request weight |
| `cotatrack.stocks.credits.splits` | `20` | Split request weight |

Verify weights and access against the contracted provider plan; weights are configurable because
they are not exchange-independent guarantees. A request whose weight exceeds the configured
minute budget is rejected locally without sending HTTP. Paid access and increased local budgets
are necessary for expensive endpoints; changing local limits does not upgrade provider access.

The daily counter and caches are process-local, as in the existing infrastructure. Multiple replicas,
other applications sharing the API key and process restarts must be included in the operational budget.
HTTP errors, including 429, are surfaced without tight retry loops.

Existing cache TTLs apply: quote 5 minutes, history 12 hours, corporate actions 24 hours,
catalog 7 days (registration/logo TTL), stale quote 24 hours. Optional fundamentals have a
bounded, provider-namespaced cache with the historical TTL. Catalog data is reused by quote validation.

## REST contracts

### Quotes and composition

```http
GET /api/stocks/AAPL,MSFT
GET /api/stocks/BRK.B?range=1y&interval=1d
GET /api/stocks/AAPL?from=2026-01-01&to=2026-06-30&interval=1d
GET /api/stocks/AAPL?dividends=true
GET /api/stocks/AAPL?includeDividends=true
```

- 1 to 50 input symbols; whitespace and case normalized, duplicates removed.
- The payload preserves `results`, `elapsedTime` and `requestedAt`.
- `metadata` is keyed by canonical ticker and includes `market`, `provider`, `feed`, `mode`,
  `asOf` when available, `fromCache`, `stale` and optional `historicalFeed`.
- `errors` lists `{symbol, operation, status, message}` for missing quotes or unavailable supplements.
- A valid quote with unavailable history/dividends returns 200 with an explicit error for that operation.
- All confirmed missing quotes return 404. Total upstream failure or a disabled provider returns 503.
- Invalid input returns 400. Provider quota exhaustion is 503, not a claim that the caller exceeded an API limit.
- Unavailable fields stay null/omitted, never fabricated zero values.

`REALTIME_PARTIAL` identifies the provider's default US feed, not SIP/consolidated market activity.
After market hours its last known trade retains the upstream timestamp; `mode` describes the feed,
not whether the exchange is currently open. Stale recovery never replaces that timestamp with request time.

### Historical data

Supported public intervals: `1m`, `5m`, `15m`, `30m`, `60m`, `1h`, `1d`, `1wk`, `1mo`.
Other existing B3 interval values are explicitly unsupported for US.
Supported ranges: `1d`, `5d`, `1mo`, `3mo`, `6mo`, `1y`, `2y`, `5y`, `10y`, `ytd`.
`max` is rejected; provider retention and entitlements still apply.

- `range` and explicit dates are mutually exclusive.
- `from` and `to` are inclusive exchange-local dates; future end dates are rejected.
- Default history is daily over the last calendar month.
- A maximum of 10 years per daily/weekly/monthly request and 30 calendar days intraday is enforced.
- Relative day ranges use calendar days, not a promise of N trading sessions.
- All timestamps use `America/New_York` with DST, returned as epoch seconds.
- Requests use bounded windows (5 calendar days intraday, 5 years otherwise), sorted and deduplicated.
- A full 5000-row window is rejected as potentially truncated, rather than returned as a complete series.
- Prices are explicitly unadjusted (`adjust=none`, regular session); `adjClose` is null.
- No automatic total-return or split adjustment is inferred.

Completed historical/EOD data and current intraday data have different coverage. The default realtime
feed represents only part of US trades; finalized US history/EOD is consolidated according to the
provider and becomes available after its publication cutoff. Current-session bars can be incomplete.
`historicalFeed` identifies the history product separately from quote feed metadata.

Only recent, completed **daily** candles enrich current indicators. SMA200 needs 200 valid sessions,
volume averages need 10/63 sessions with known volumes, and 52-week extrema need at least 252 sessions.
Short histories, old date windows and intraday/weekly/monthly series do not invent daily indicators.
Derived indicators use the raw-price policy; they are not split-adjusted analytics.

### Corporate actions and fundamentals

`dividends=true` or its alias requests both dividend and split history, bounded by provider availability.
The configured plan must authorize both endpoints. An upstream failure does not become an empty event list.

- Cash `amount` maps to `rate` as a decimal string.
- `ex_date` maps to the additive `exDate`, never to B3's `lastDatePrior`.
- Currency is retained. Payment/declaration dates are populated only if supplied.
- B3-specific identifiers/classifications remain null when no equivalent is supplied.
- Splits and reverse splits retain the original named `fromFactor`/`toFactor` and description.
  `eventType=SPLIT` is the generic split family; no B3 percentage `factor` is fabricated.
- New event fields are omitted when null, preserving the legacy B3 JSON shape and constructors.
- Forward and trailing dividend metrics stay separate; incomplete event histories do not synthesize annual yields.

Optional fundamentals map sector, industry, logo, market capitalization, SMA200 and forward/trailing
dividend metrics. Yield fractions from statistics are converted to percentage units.
The existing volume-average convention is 10/63 daily sessions; vendor 90-day values are not silently
substituted for a 63-session average. Other `*Percent` fields retain existing CotaTrack units.

### Catalog/listing

```http
GET /api/stocks/list?search=apple&type=stock&sortBy=name&sortOrder=asc&limit=20&page=0
```

- Search matches ticker and company name, case-insensitively.
- Types: `stock`, `common`, `preferred`; comma-separated values accepted.
- Global sorting: `name` (ticker), `longName` (company name), `exchange`; `asc` or `desc`.
- Price/volume/change sorting is rejected: sorting only a paginated subset would be incorrect.
- Input pages are zero-based; `currentPage` is one-based, matching the existing REST convention.
- Limits are 1..50. Catalog filtering/sorting precedes pagination.
- Only symbols on the selected page are quoted. Catalog rows remain useful when quotes fail;
  unavailable prices/volumes are omitted and failures are included in `errors`.
- `name`, `open`, `close`, `change_abs` reuse list field names. `volume` is a nullable Long.
- Responses preserve pagination fields and add `errors`.

## Diagnostics and library usage

`/api/health` and `/api/diagnostics` include `TWELVE_DATA` with `market=US`, independent circuit state,
health counters and disabled/degraded status. Additional cache sizes are `usStockCatalogs`,
`usIntradayQuotes`, `usHistoricalQuotes`, `usDividends`, `usStockFundamentals` and `usStaleQuotes`.

Library users construct a US orchestrator explicitly and pass it to `StocksQuery`; no Spring dependency
or change to the B3 holder is required:

```java
var cache = new CotaTrackCacheManager();
var provider = new TwelveDataQuoteProvider(HttpClient.newHttpClient(),
        System.getenv("TWELVE_DATA_API_KEY"), true, cache);
var orchestrator = new ProviderOrchestrator(AssetMarket.US, List.of(provider),
        cache, new BatchQuoteCoalescer());
var result = StocksQuery.builder().orchestrator(orchestrator)
        .symbols(List.of("AAPL", "BRK.B")).range(Range.ONE_YEAR).build().fetch();
```

Use `StocksQuery` for US supplements. Calling legacy B3/Yahoo fetch methods on a US quote fails
explicitly instead of dispatching the US symbol to B3.

## Validation

Deterministic tests cover provider payloads, HTTP errors/timeouts, auth redaction, credit limits,
history windows/timezones, dividends/splits, partial results, market isolation, coalescing,
circuit breakers, fallback/stale cache, REST contracts, pagination and Spring bean wiring.

```powershell
mvn -P with-rest '-Dtest=*,!StockQueryControllerIntegrationTest,!CotaTrackApplicationTests' test
```

The two excluded existing classes perform live startup/integration calls. `StocksApplicationTest`
validates Spring wiring without external startup calls. Bruno examples require the configured provider
key, a compatible plan and sufficient credits; they are not offline fixtures.

## Data rights

Default realtime coverage is not consolidated. External distribution, including API redistribution
and white labeling, requires the appropriate rights/add-ons. Enabling the feature does not grant them.
Do not enable production distribution until the intended use is authorized.

- [US equities coverage and licensing](https://support.twelvedata.com/en/articles/9935903-us-equities-market-data)
- [Provider documentation](https://twelvedata.com/docs)
- [Provider plans](https://twelvedata.com/pricing)
- [Business plans](https://twelvedata.com/pricing-business)
