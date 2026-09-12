# AGENTS.md — WeatherInMyHand (掌中天气)

## Project structure

- `backend/` — Spring Boot 2.7 + JPA + H2 (Java 17, Maven)
- `android/` — Android Java app (compileSdk/targetSdk 34, minSdk 21, gradle wrapper included)

## Backend — commands

```powershell
cd backend
mvn spring-boot:run          # dev server on :8080
mvn clean package            # produce fat jar
mvn test                     # unit tests (rate-limit security rules + upstream-JSON tolerance)
```

Tests **do** exist (added 2026-09-12) — this line used to say the opposite:
`backend` → `mvn test` (JUnit 5 + AssertJ via `spring-boot-starter-test`, test scope, not in the fat jar);
`android` → `./gradlew :app:testDebugUnitTest` (plain JUnit 4 JVM tests, no emulator). A `mvn test` / `gradlew test` is the safety net for the invariants below, which are otherwise only visible by reading the code.

- Production runs the fat jar under systemd (`smog.service`); it binds **`127.0.0.1:8080`** via `Environment=SERVER_ADDRESS=127.0.0.1` (public plaintext 8080 is closed) and is fronted by nginx **443 TLS** (ZeroSSL IP cert) on the Aliyun ECS. Deploy files live on the server, not in this repo — see `HTTPS-DEPLOY.md`.
- H2 is **file-backed** (`jdbc:h2:file:./data/smogdb`, `ddl-auto=update`) — data survives restarts. DB files land in `backend/data/` (gitignored).
- Errors from all `/api/*` endpoints are centralized in `GlobalExceptionHandler` (`com.smog.exception`): **HTTP 200 + `{success:false, message}`**, so the Android client's `response.isSuccessful()` + `success`-bool contract keeps showing real error text.

## API entrypoints (backend)

| Method | Endpoint | Notes |
|---|---|---|
| `GET` | `/api/weather/info?city=北京` | weather + air quality (composite) |
| `GET` | `/api/weather/info?lat=39.9&lon=116.4` | same, reverse-geocodes lat/lon to a city name |

**That is the only endpoint.** Three others were deleted on 2026-09-12 rather than kept "for later", because each had no consumer and no way to become correct:

- `GET /api/location/local` — returned the single globally-newest row, no owner key, so it could only ever answer "what city did the last user look up". Per-device "last city" lives in the client's `WeatherCache`; a server-side city list would need an account/owner identity first.
- `GET /api/weather/air` — the client never called it, and `/info` does not route through its cache, so that cache stayed empty in production: a dead path propped up only by feedback. (If a second consumer ever needs air-only data, re-add it *and* have the composite path backfill the same cache.)
- `POST /api/location/save` — the city cache self-fills via `getOrFetchLocation`, so the app gained nothing from this POST (one wasted request + rate-limit slot per search). Deleting it also removed the "write by raw lat/lon" branch, which is the one that used to persist caller coordinates.

Note on coordinates: `locations` rows always hold the **geocoded city's** coordinates (city centre), never a caller's raw lat/lon — the raw values are used only for that one reverse-geocode call and are not persisted. Location is 敏感个人信息 under PIPL, and weather only needs city-level granularity, so this is enforced **server-side** (there is now no write endpoint at all) rather than trusting each client version to behave.

`API-DOC.md` is the front-end-facing contract and **must be updated whenever an endpoint changes** — it had silently kept documenting the deleted `/api/location/local`, including a 404 branch that no longer exists.

## Auth for QWeather API

Backend uses **Ed25519 JWT** to authenticate with QWeather API host `https://nx4nmurq3h.re.qweatherapi.com`.

JWT config (hardcoded in `JwtUtil.java`):
- kid = `T7WKCV7R8P`, projectId = `2N8569G9JK`
- **Do NOT add an `iss` claim** — QWeather reserves `iss` and rejects (HTTP 401) any JWT that carries it, regardless of key/credential. Payload must only contain `sub`(projectId)/`iat`/`exp`. (Regression happened once; keep it that way.)
- Private key path is **configurable**, never hardcoded:
  1. env var `PRIVATE_PEM_PATH` (or an `.env` file — spring-dotenv is on the classpath, `.env` is gitignored), else
  2. `application.properties` → `private.pem.path` (default `${PRIVATE_PEM_PATH:ed25519-private.pem}`)
  3. Relative paths are resolved against the working dir and `working-dir/backend/` (covers running from repo root or `backend/`).

**These PEM files are NOT in git anymore** (untracked, gitignored). After a fresh clone place `ed25519-private.pem` under `backend/` (or point the env var at it).

> Security: the key was previously committed to history. If this repo is or was shared/remote, **rotate the JWT credential in the QWeather console** (new kid/projectId/private key) rather than just relying on the untrack above.

## Config notes

- `application.properties` used to carry inert placeholder keys `hefeng.weather.key` / `baidu.map.key`; those are **deleted now** (no code ever read them). QWeather auth is done exclusively via the JWT above.
- Baidu Maps is **not used** anywhere; reverse geocoding (GPS → city) is done through QWeather `geo/v2/city/lookup` with `location=lon,lat`.

## Android dev notes

- UI is a **single Activity** (`MainActivity`) + bottom `BottomNavigationView` with **3 tabs** (今天/空气质量/趋势). The three pages are `<include>` ScrollViews (`page_today`/`page_air`/`page_trend`) toggled by visibility — no Fragments. `WeatherDetailActivity` was **deleted**; its content moved into the tabs.
- **`MainActivity` was split on 2026-09-12** (1049 → 506 lines). Everything below is a plain class in `com.smog.weatherapp` — no architecture framework, no DI, constructed from the Activity **after** `setContentView` and doing their own `findViewById`. The Activity keeps only what genuinely needs it: view wiring + tab switching, the `uiApplySeq` arbitration, the three per-call-site fallback policies, the privacy gate, and the cache-warm paint. Where to look:
  - `net/WeatherApi` — OkHttp + the `{success,data}` / `{success,message}` contract (with sibling `stale` for degraded data). Its callback splits **`onTransportError()`** (never reached the server) from **`onFail(message)`** (answered, but unusable) on purpose: call sites word those two differently (search says "网络错误", refresh/GPS says "加载天气失败"). It deliberately knows nothing about `uiApplySeq`, the loading bar, or which cache fallback applies — those are UI semantics.
  - `net/NetworkStatus.isOnline(ctx)` — the connectivity check (renamed from `isNetworkAvailable` because the request path *and* the location path both need it, so it belongs to neither).
  - `LoadOverlay` — counting loading bar + error/retry panel. "Which request does Retry re-send" is injected per call site via `setRetryAction(...)`; the class only runs it.
  - `LocationHelper` — permission → listener (GPS, falling back to network) → 10s timeout. It carries the two fragile orderings: a repeat call clears the previous registration first, and a fix cancels the pending timeout (otherwise the timeout fires a moment later and falsely reports "定位不可用"). `onDestroy` just calls `stop()`. `onRequestPermissionsResult` stays on the Activity (framework callback) and forwards to `onPermissionResult`. Failure exits are three separate callbacks (`onLocation` / `onLocationAborted` / `onLocationTimeout`) rather than one boolean flag, because "only complain when there is no city yet" needs the Activity's state.
  - `PageRenderer` — JSON field → view mapping (the three pages + the data-time footer; owns its own `findViewById`s, with `weatherScene` passed in because tab switching also uses it). `UiFormat` — value → display text (`setDouble` / `setPoll` / `percentStr`, all static and pure). Split because they change for different reasons (decimal places vs. field mapping).
  - Views are intentionally **not** null-checked: the ids are aapt-verified, and `LoadOverlay`'s constructor already dereferences its buttons unconditionally, so a partial set of `!= null` guards would only hide a real "the loading bar never appears" bug.
- Data flow: after the city is resolved (GPS via `LocationManager`, or search), call `/api/weather/info?city=` **once**, cache the whole `data` JSONObject in `lastData`, then render all three pages from it (`PageRenderer.renderToday`/`renderAir`/`renderTrend`).
- In-flight-request guards (easy to break when adding new calls): `uiApplySeq` makes the **last-issued** request win — search / GPS / refresh all bump it, and a stale callback must still call `overlay.endLoad()` before returning or the loading bar sticks. `WeatherApi.handleJson` reports read errors via `onFail` (never swallows them). `onDestroy` calls `location.stop()` (unregisters the listener + drops the 10s timeout runnable) and `weatherApi.cancelAll()`.
- Cold start resolves the city from **local state only**: the per-device snapshot (`WeatherCache` → previous city + data) followed by a fresh GPS fix. The client does **not** call any "my location" endpoint (there is none — see the API notes above); before 2026-09-12 it used `GET /api/location/local`, which returned the single most-recently-saved row **globally**, so on a fresh install with no local cache and no GPS it showed whatever city the *last* user had looked up. With no cache and no fix the header stays at `header_city_unavailable` ("未获取到位置"), matching the "只信实时定位" policy.
- The GPS fix is **never uploaded**: `onLocationChanged` goes straight to `loadWeatherDataByLocation(lat, lon)` (which calls `/api/weather/info?lat=…&lon=…` and gets the city name back in the response). The server's city cache still fills, because the weather pipeline calls `getOrFetchLocation(cityName)` on its way through. There is no coordinates POST to re-add — the server has none either (see the API notes above).
- **Don't lower `WeatherApi`'s read timeout.** It is set to 30s on purpose: on a cache miss `/info` serially makes up to 4 QWeather calls (geo → current → air → hourly), each with a 5s connect + 15s read budget on the server side, so the server's worst case is far beyond OkHttp's 10s default. With the default the client hangs up while the server is still working → the UI says "网络错误" for a request that then succeeds, and the refresh is wasted. If you make the backend parallel or raise its cache hit rate, the timeout can come back down — but only after redoing that arithmetic.
- 5 user-selectable themes (天蓝·晴 / Night / Forest / Sunset / 星幕·沉浸), chosen via a palette button in the header → `AlertDialog`; applied through `ThemeHelper` (`SharedPreferences` → `setTheme` before `setContentView` → `recreate()`). All colors come from custom attrs (`?attr/pageBackground|cardBackground|textPrimary|...`), never hardcoded hex.
- Trend tab renders the **real 24h `hourlyForecast`** from `/api/weather/info` (temperature + humidity) with AnyChart; the `AnyChartView` is re-set each time the tab is opened, because a `GONE` page is not rendered until shown.
- Helpers: `WeatherFormat` (emoji + AQI colors are code; everything textual — wind-dir, pollutant label, AQI level/assessment/health-advice — takes a `Resources` and reads `strings.xml` / `arrays.xml`), `ThemeHelper` (theme index persistence). Custom launcher icon = vector adaptive (`mipmap-anydpi-v26` + fg/bg drawables) + legacy `mipmap-*dpi` PNGs.
- Backend base URL is set as `BuildConfig.BACK_HOST_API` in `android/app/build.gradle` (`buildConfigField`) — currently `https://118.178.147.156/api/` (Aliyun trial, HTTPS via ZeroSSL IP cert + nginx), NOT `10.0.2.2`.
- Manifest sets `android:usesCleartextTraffic="false"` (cleartext off) and `android:networkSecurityConfig`; `res/xml/network_security_config.xml` trusts **system + the bundled Sectigo R46 public root** (`res/raw/sectigo_r46.pem`), so devices whose system CA store predates the 2023 R46 root can still complete TLS to the IP-cert backend (this was the real cause of a "网络错误" on Android 13).
- Privacy compliance: first-launch non-cancelable consent gate (`PrivacyStore` SharedPreferences); About/Privacy page (`PrivacyActivity`) shows version, data-source (QWeather) note and reset-consent; header About entry + Today-page data-source footer.
- **Release builds run R8** (`minifyEnabled true` + `shrinkResources true`; rules in `app/proguard-rules.pro`). Two things not to undo: (1) `gradle.properties` needs `org.gradle.jvmargs=-Xmx2048m` or R8 dies with "JVM garbage collector is thrashing"; (2) the AnyChart `-keep` is deliberately **narrow** (only the `@JavascriptInterface` bridge — its chart API is JS-string based, so obfuscation is safe) — re-adding a blanket `-keep class com.anychart.**` costs ~1.1 MB. Because R8 only breaks at *runtime*, a release APK must be smoke-tested on a device (especially the Trend tab chart and the HTTP paths); `com.smog.weatherapp.WeatherCache`/`PrivacyStore`/`WeatherFormat` showing up as `R8$$REMOVED$$CLASS$$` in `mapping.txt` is normal — they're fully inlined, not dropped.
- Build with Android Studio; gradle wrapper is committed.

## Security & git

- Root `.gitignore` **exists**; ignores `*.pem`, `backend/data/`, build artifacts, `.idea/`, logs, IDE files.
- `ed25519-private.pem` / `ed25519-public.pem`, legacy Eclipse files (`backend/.project`, `.classpath`, `.settings/*`) and accidentally-committed build-log `.txt` files were all **removed from tracking** (gitignore covers `*.pem`, IDE files, `*_output.txt`/`*_result.txt`). Git history still contains old copies of these — the working tree is clean of secrets/IDE junk.

## What's missing / known gaps

- No global HTTP-error statuses (intentional, see contract note above). Tests exist but are **thin and unit-level only** — no instrumentation/UI tests, so the Activity and the new `LocationHelper`/`LoadOverlay`/`PageRenderer` wiring is still only verified by compiling and smoke-testing on a device.
- **Weather data is not persisted at all** (removed 2026-09-12). `weather_data`, `WeatherRepository`, and the JPA annotations + auto-increment `id` on what is now `com.smog.dto.Weather` were all deleted: nothing ever read the table to serve a response (the two `findTopByCityName…` reads were only the upsert base, and the `stale` snapshot comes from the in-memory `ResultCache`, not the DB), so it cost 3 full-row writes per cache miss and nothing else. Consequences to keep in mind: `data` no longer carries an `id`; a field the upstream omits is now `null` rather than silently inherited from the previous row (that inheritance was *less* honest — an old value masquerading as this fetch's data); the 10-minute cache and the degraded snapshot both live in memory, so **nothing survives a restart and there is no server-side weather history**. The old "upsert race / deferred unique index on `city_name`" gap is closed *by deletion* — don't reintroduce a table without bringing the index question back with it. One bonus find: `mergeAirQuality` never copied `aqiCN` (it copies `aqi`/`aqiUs`/`aqiQa` and every pollutant), so `aqiCN` used to be populated *only* by the DB row happening to hold it from a previous request — i.e. it was always null for a city's first-ever query, and the persistence layer was masking the bug. Fixed in the same change; don't remove that line again.
- `locations` **is** the only persisted table, upserted by city name (each city keeps one row), so its size is bounded by distinct cities — old duplicates from before the upsert change remain but can be wiped safely.
- **Pre-existing `locations` rows still hold raw user coordinates** written before 2026-09-12 (the fix only changes what gets written from now on). They are the *nearest-city* fixes of past sessions, and each city keeps just one row, so this is a bounded leftover rather than a growing log — but if you want a clean slate, wiping `locations` is safe: it is a pure city-name→coords cache that refills on demand.
- `saveLocationFromApi`/`fetchLocationFromApi` duplicate most of `reverseGeocode` — two near-identical calls to the same QWeather endpoint. Not worth churning before there is a second consumer.
