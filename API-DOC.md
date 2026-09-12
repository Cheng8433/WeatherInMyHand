# WeatherInMyHand 后端 API 接口文档

> 供前端开发者查阅。后端基地址在 Android 端由 `BuildConfig.BACK_HOST_API` 决定（见 `android/app/build.gradle`），当前为 `https://118.178.147.156/api/`（阿里云试用实例，nginx TLS 反向代理，纯 IP 证书）；本机调试可改为 `http://10.0.2.2:8080/api/`（模拟器 → 宿主机）。
>
> 本文档与代码同步于 **1.0.5**。接口有增删时请一并更新这里，否则它会静默漂移（曾经就漏掉过 `/api/location/local` 的删除）。

---

## 〇、接口约定

- 成功响应统一为 HTTP 200 + `{ "success": true, "data": ... }`。
- 业务/上游错误统一由 `GlobalExceptionHandler` 处理，返回 **HTTP 200** + `{ "success": false, "message": "原因" }`（返回 200 是为了让 Android 端 `response.isSuccessful()` 为真，从而把真实错误文案透传给用户）。**没有任何接口返回 4xx/5xx 作为业务错误**。
- 降级标记：若本次上游（和风天气）请求失败、后端改用内存里的最近一次成功快照应答，`data` 的同级会多一个 `"stale": true`（`data` 本身仍完整，契约不变）。客户端据此在页脚明示「离线缓存」。
- 限流：`/api/weather/**` 挂有内存固定窗口限流（默认按客户端 IP 30 次/分、全局 120 次/分，见 `application.properties` 的 `rate.limit.*`）。超限**仍返回 HTTP 200** + `{ "success": false, "message": "请求过于频繁，请稍后再试" }`。被单 IP 拦下的请求不消耗全局额度。
- 响应的 `data` 是一个**响应模型**（`com.smog.dto.Weather`），不是数据库实体——服务端自 2026-09-12 起不再持久化天气数据，字段见下表。

---

## 一、数据结构

### 1.1 响应模型 `Weather` — 天气与空气质量（**不落库**）

由 `com.smog.dto.Weather` 直接序列化为响应里的 `data`。它没有 JPA 注解、也没有主键
（`id` 字段已于 2026-09-12 随持久化层一起删除）。下表列出的是**响应字段名**（JSON 为小驼峰）：

| 字段（JSON） | 类型 | 说明 |
|---|---|---|
| `cityName` | `String` | 城市名称 |
| `updateTime` | `Long` | 本次取数的时间戳（毫秒） |
| `weather` | `String` | 天气状况，如"多云" |
| `temperature` | `Double` | 温度（℃） |
| `feelsLike` | `Double` | 体感温度（℃） |
| `humidity` | `Double` | 相对湿度（%） |
| `windDir` | `String` | 风向，如"东南风" |
| `windScale` | `String` | 风力等级，如"1" |
| `windSpeed` | `Double` | 风速（km/h） |
| `precip` | `Double` | 降水量（mm） |
| `pressure` | `Double` | 大气压力（hPa） |
| `vis` | `Double` | 能见度（km） |
| `cloud` | `String` | 云量（%） |
| `dew` | `Double` | 露点温度（℃） |
| `aqi` | `Integer` | 兼容旧字段，同 `aqiUs` |
| `aqiUs` | `Integer` | 美国标准 AQI（us-epa） |
| `aqiCN` | `Integer` | 中国标准 AQI（cn-mee） |
| `aqiQa` | `BigDecimal` | QAQI 指数（和风自研）。**当前无写入方，恒为 null** |
| `airQuality` | `String` | 空气质量类别，如"Good" |
| `primaryPollutant` | `String` | 首要污染物代码，如"pm2p5" |
| `pm25` | `String` | PM2.5 浓度（μg/m³），字符串兼容字段 |
| `pm10` | `String` | PM10 浓度（μg/m³），字符串兼容字段 |
| `pm25Value` | `Double` | PM2.5 数值 |
| `pm10Value` | `Double` | PM10 数值 |
| `no2` | `Double` | 二氧化氮（ppb） |
| `o3` | `Double` | 臭氧（ppb） |
| `co` | `Double` | 一氧化碳（ppm） |
| `so2` | `Double` | 二氧化硫（ppb） |
| `hourlyForecast` | `Array` | 24h 温湿度趋势，结构为 `[{ "fxTime": "2026-09-05T14:00+08:00", "temp": "25", "humidity": "60" }, ...]`。**只有 `/info` 会带它**；逐小时预报失败时可能缺失 |

> 天气数据**不落库**（历史上曾有一张 `weather_data` 表按 `city_name` upsert，已于 2026-09-12 连同持久化层删除）：
> 服务端每次取数都在内存里新建这个对象，缓存（10 分钟 TTL）与降级快照也都在内存里，重启即清空。
> 因此「上游本次没返回的字段」会是 `null`，**不会**沿用上一次的值——客户端应按「逐字段独立降级显示」处理。

### 1.2 表 `locations` — 城市位置（服务端**唯一**的持久化表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` (PK, 自增) | 主键 |
| `city_name` | `String` (NOT NULL) | 城市名称 |
| `latitude` | `Double` | **该城市中心的**纬度 |
| `longitude` | `Double` | **该城市中心的**经度 |
| `update_time` | `Long` | 更新时间戳（毫秒） |

> 这张表是纯「城市名 → 坐标」缓存，由后端 `getOrFetchLocation` 在需要时自填，没有对外的写接口。
> 坐标一律是逆地理编码出的**城市中心**，从不落库调用方上传的原始定位：位置属敏感个人信息，天气只需城市级粒度（详见 AGENTS.md）。

---

## 二、API 端点

### 2.1 `GET /api/weather/info` — 综合天气 + 空气质量（唯一供 App 使用的端点）

**请求参数**（city 与 lat/lon 二选一）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | `String` | 否 | 城市名称，如"北京" |
| `lat` | `Double` | 否 | 纬度（需与 lon 同时传） |
| `lon` | `Double` | 否 | 经度（需与 lat 同时传） |

- 传 `city` 时直接按城市名取数。
- 传 `lat`/`lon` 时后端先逆地理编码出城市名，再按城市名走同一条取数链路；响应体里的 `cityName` 是解析出的标准城市名。
- 结果按城市名做 10 分钟内存缓存（同一城市并发首请求只打一次和风）；上游失败则降级返回该城最近一次成功快照 + `stale: true`。

**成功响应 200**
```json
{
  "success": true,
  "data": {
    "cityName": "北京",
    "updateTime": 1714192000000,
    "weather": "多云",
    "temperature": 22.5,
    "feelsLike": 20.1,
    "humidity": 45.0,
    "windDir": "东南风",
    "windScale": "1",
    "windSpeed": 5.0,
    "precip": 0.0,
    "pressure": 1013.0,
    "vis": 10.0,
    "cloud": "30",
    "dew": 8.5,
    "aqi": 55,
    "aqiUs": 55,
    "aqiCN": 42,
    "aqiQa": null,
    "airQuality": "Good",
    "primaryPollutant": "pm2p5",
    "pm25": "15.0",
    "pm10": "30.0",
    "pm25Value": 15.0,
    "pm10Value": 30.0,
    "no2": 20.0,
    "o3": 80.0,
    "co": 0.5,
    "so2": 5.0,
    "hourlyForecast": [
      { "fxTime": "2026-09-05T14:00+08:00", "temp": "25", "humidity": "60" }
    ]
  }
}
```

**降级响应 200**（上游失败，但内存缓存里还有该城最近一次成功快照）
```json
{ "success": true, "stale": true, "data": { "cityName": "北京", "updateTime": 1714191000000, "...": "..." } }
```

**错误响应（HTTP 200）**
```json
{ "success": false, "message": "缺少城市名或经纬度参数" }
```

> 三个上游数据源的失败是分级处理的：实时天气是必需项（失败即走上面的降级/报错），
> 空气质量失败同样触发降级，而**逐小时预报失败只打日志、不影响本次响应**（`hourlyForecast` 会是 null 或缺失）。
> 这意味着「空气质量字段为 null」是可能出现的正常状态，客户端应对每个字段独立降级显示。

---

## 三、端点汇总

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/weather/info` | 天气 + 空气质量综合（city 或 lat/lon） |

> **只有这一个端点。** 已删除的端点不再保留，避免留下没有消费者的死路径：
> - `GET /api/location/local`（2026-09-12 删除）——它无主人标识，"全库最新一条"只可能答出「上一个人查过的城市」，多设备共用等于串城且泄露他人位置。每台设备自己的「上次城市」由客户端本地缓存负责。
> - `GET /api/weather/air`（2026-09-12 删除）——客户端从不调用，且其缓存与 `/info` 的缓存互不相通（`/info` 不经过 air 缓存），实际是条恒空的死路径。需要单独取空气质量时再按需重建。
> - `POST /api/location/save`（2026-09-12 删除）——城市缓存已由后端 `getOrFetchLocation` 按需自填，这个写接口对 App 自身毫无必要；连它一起删掉的还有「按经纬度写库」的分支，落库坐标全由服务端按城市名决定。
>
> 同一天还删掉了**持久化层**（不只是端点）：`WeatherRepository`、`Weather` 上的 JPA 注解与自增主键、
> 以及表 `weather_data`。它对响应没有任何贡献（快照与降级都在内存里），却让每次缓存未命中多出 3 次全行写。
> 随之消失的还有「upsert 非并发安全、唯一索引因存量重复行暂缓」这个缺口。
