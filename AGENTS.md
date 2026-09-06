# AGENTS.md — WeatherInMyHand (掌中天气)

## Project structure

- `backend/` — Spring Boot 2.7 + JPA + H2 (Java 17, Maven)
- `android/` — Android Java app (compileSdk/targetSdk 34, minSdk 21, gradle wrapper included)

## Backend — commands

```powershell
cd backend
mvn spring-boot:run          # dev server on :8080
mvn clean package            # produce fat jar
```

No tests exist in this repo.

- H2 is **file-backed** (`jdbc:h2:file:./data/smogdb`, `ddl-auto=update`) — data survives restarts. DB files land in `backend/data/` (gitignored).
- Errors from all `/api/*` endpoints are centralized in `GlobalExceptionHandler` (`com.smog.exception`): **HTTP 200 + `{success:false, message}`**, so the Android client's `response.isSuccessful()` + `success`-bool contract keeps showing real error text.

## API entrypoints (backend)

| Method | Endpoint | Notes |
|---|---|---|
| `GET` | `/api/weather/info?city=北京` | weather + air quality (composite) |
| `GET` | `/api/weather/info?lat=39.9&lon=116.4` | same, reverse-geocodes lat/lon to a city name |
| `GET` | `/api/weather/air?city=北京` | air quality only |
| `POST` | `/api/location/save` | body `{latitude, longitude}` → reverse geocode; or `{cityName}` → geo lookup; saves to DB |
| `GET` | `/api/location/local` | no params; returns latest saved location (`{success,data}`) or 404 `{success:false}` |

Note: `GET /api/location/local` is **not** a per-city DB lookup despite the old API docs — it returns the single most-recently-saved location row.

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
- Data flow: after the city is resolved (GPS via `LocationManager`, or search), call `/api/weather/info?city=` **once**, cache the whole `data` JSONObject in `lastWeatherData`, then render all three pages from it (`renderToday`/`renderAir`/`renderTrend`).
- 4 user-selectable themes (天蓝·晴 / Night / Forest / Sunset), chosen via a palette button in the header → `AlertDialog`; applied through `ThemeHelper` (`SharedPreferences` → `setTheme` before `setContentView` → `recreate()`). All colors come from custom attrs (`?attr/pageBackground|cardBackground|textPrimary|...`), never hardcoded hex.
- Trend tab renders the **real 24h `hourlyForecast`** from `/api/weather/info` (temperature + humidity) with AnyChart; the `AnyChartView` is re-set each time the tab is opened, because a `GONE` page is not rendered until shown.
- Helpers: `WeatherFormat` (emoji / wind-dir / pollutant label+unit / AQI color+level), `ThemeHelper` (theme index persistence). Custom launcher icon = vector adaptive (`mipmap-anydpi-v26` + fg/bg drawables) + legacy `mipmap-*dpi` PNGs.
- Backend base URL is set as `BuildConfig.BACK_HOST_API` in `android/app/build.gradle` (`buildConfigField`) — currently `http://118.178.147.156:8080/api/` (Aliyun 3-month trial), NOT `10.0.2.2`.
- `android:usesCleartextTraffic="true"` in manifest (plain HTTP allowed).
- Build with Android Studio; gradle wrapper is committed.

## Security & git

- Root `.gitignore` **exists**; ignores `*.pem`, `backend/data/`, build artifacts, `.idea/`, logs, IDE files.
- `ed25519-private.pem` / `ed25519-public.pem`, legacy Eclipse files (`backend/.project`, `.classpath`, `.settings/*`) and accidentally-committed build-log `.txt` files were all **removed from tracking** (gitignore covers `*.pem`, IDE files, `*_output.txt`/`*_result.txt`). Git history still contains old copies of these — the working tree is clean of secrets/IDE junk.

## What's missing / known gaps

- No tests; no global HTTP-error statuses (intentional, see contract note above).
- `weather_data`/`locations` are upserted by city (each city keeps one latest row), so size is bounded by distinct cities — old duplicates from before the upsert change remain but can be wiped safely.
- 24h hourly forecast is `@Transient` — never persisted, so "history" beyond the current snapshot doesn't exist server-side.
