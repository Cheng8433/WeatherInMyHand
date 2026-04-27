# AGENTS.md — WeatherInMyHand (雾霾探测系统)

## Project structure

- `backend/` — Spring Boot 2.7 + JPA + H2 (Java 17, Maven)
- `android/` — Android Java app (compileSdk 30, minSdk 21, no Gradle wrapper)

## Backend — commands

```powershell
cd backend
mvn spring-boot:run          # dev server on :8080
mvn clean package            # produce fat jar
```

No tests exist in this repo.

## API entrypoints (backend)

| Endpoint | Notes |
|---|---|
| `GET /api/weather/info?city=北京` | weather + air quality (composite) |
| `GET /api/weather/air?city=北京` | air quality only |
| `GET /api/location/search?city=北京` | geo lookup via QWeather API |
| `GET /api/location/local?city=北京` | DB-only lookup |
| `GET /api/location/fetch?city=北京` | force API fetch, no save |

## Auth for QWeather API

Backend uses **Ed25519 JWT** to authenticate with QWeather API host `https://nx4nmurq3h.re.qweatherapi.com`.

JWT config (hardcoded in `JwtUtil.java`):
- kid = `K95D3VE8WH`, projectId = `2N8569G9JK`
- Private key: `backend/ed25519-private.pem`

These PEM files are **committed to git** — treat as sensitive.

## Config keys to replace before running

Search for these placeholders:
- `application.properties`: `hefeng.weather.key`, `baidu.map.key`
- `MainActivity.java`: `YOUR_BAIDU_AK`

## Android dev notes

- Backend URL hardcoded to `http://10.0.2.2:8080/api/` (emulator → host)
- `android:usesCleartextTraffic="true"` in manifest (HTTP allowed)
- Chart in `WeatherDetailActivity` uses **random mock data**, not real history
- Baidu Maps API used for reverse geocoding (GPS → city name)
- Build with Android Studio (no `gradlew` in repo)

## Security & git

- Root `.gitignore` is missing — `ed25519-private.pem`, `ed25519-public.pem`, and IDE files under `.idea/` are tracked
- `.idea/` directory is committed

## What's missing / known gaps

Deduplicated from README — README has no additional actionable info beyond what is above.
