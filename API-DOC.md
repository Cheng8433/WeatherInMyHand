# WeatherInMyHand 后端 API 接口文档

> 供前端开发者查阅。后端基地址在 Android 端由 `BuildConfig.BACK_HOST_API` 决定（见 `android/app/build.gradle`），当前为 `http://118.178.147.156:8080/api/`（阿里云试用实例）；本机调试可改为 `http://10.0.2.2:8080/api/`（模拟器 → 宿主机）。

---

## 〇、接口约定

- 成功响应统一为 HTTP 200 + `{ "success": true, "data": ... }`。
- 业务/上游错误统一由 `GlobalExceptionHandler` 处理，返回 **HTTP 200** + `{ "success": false, "message": "原因" }`（返回 200 是为了让 Android 端 `response.isSuccessful()` 为真，从而把真实错误文案透传给用户）。唯一例外：`GET /api/location/local` 无历史记录时返回 HTTP 404。
- 天气相关的综合/空气质量接口返回的是 `weather_data` 实体快照，字段见下表。

---

## 一、数据库表结构

### 1.1 表 `weather_data` — 天气与空气质量

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` (PK, 自增) | 主键 |
| `city_name` | `String` (NOT NULL) | 城市名称 |
| `update_time` | `Long` | 更新时间戳（毫秒） |
| `weather` | `String` | 天气状况，如"多云" |
| `temperature` | `Double` | 温度（℃） |
| `feels_like` | `Double` | 体感温度（℃） |
| `humidity` | `Double` | 相对湿度（%） |
| `wind_dir` | `String` | 风向，如"东南风" |
| `wind_scale` | `String` | 风力等级，如"1" |
| `wind_speed` | `Double` | 风速（km/h） |
| `precip` | `Double` | 降水量（mm） |
| `pressure` | `Double` | 大气压力（hPa） |
| `vis` | `Double` | 能见度（km） |
| `cloud` | `String` | 云量（%） |
| `dew` | `Double` | 露点温度（℃） |
| `aqi` | `Integer` | 兼容旧字段，同 aqiUs |
| `aqi_us` | `Integer` | 美国标准 AQI |
| `aqi_cn` | `Integer` | 中国标准 AQI |
| `aqi_qa` | `BigDecimal` | QAQI 指数（和风自研） |
| `air_quality` | `String` | 空气质量类别，如"Good" |
| `primary_pollutant` | `String` | 首要污染物代码，如"pm2p5" |
| `pm25` | `String` | PM2.5 浓度（μg/m³） |
| `pm10` | `String` | PM10 浓度（μg/m³） |
| `pm25_value` | `Double` | PM2.5 数值 |
| `pm10_value` | `Double` | PM10 数值 |
| `no2` | `Double` | 二氧化氮（ppb） |
| `o3` | `Double` | 臭氧（ppb） |
| `co` | `Double` | 一氧化碳（ppm） |
| `so2` | `Double` | 二氧化硫（ppb） |

> 接口响应的 `data` 还会额外带一个**不入库**的 `hourlyForecast` 数组（24h 温湿度趋势），结构为 `[{ "fxTime": "2026-09-05T14:00+08:00", "temp": "25", "humidity": "60" }, ...]`。

### 1.2 表 `locations` — 城市位置

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` (PK, 自增) | 主键 |
| `city_name` | `String` (NOT NULL) | 城市名称 |
| `latitude` | `Double` | 纬度 |
| `longitude` | `Double` | 经度 |
| `update_time` | `Long` | 更新时间戳（毫秒） |

---

## 二、API 端点

### 2.1 `GET /api/weather/info` — 综合天气 + 空气质量

**请求参数**（city 与 lat/lon 二选一）

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | `String` | 否 | 城市名称，如"北京" |
| `lat` | `Double` | 否 | 纬度（需与 lon 同时传） |
| `lon` | `Double` | 否 | 经度（需与 lat 同时传） |

**成功响应 200**
```json
{
  "success": true,
  "data": {
    "id": 1,
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

**错误响应（HTTP 200）**
```json
{ "success": false, "message": "缺少城市名或经纬度参数" }
```

---

### 2.2 `GET /api/weather/air` — 仅空气质量

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | `String` | 是 | 城市名称，如"北京" |

**成功响应 200**（天气相关字段均为 `null`，空气质量字段见 2.1 示例的空气质量部分）

```json
{
  "success": true,
  "data": {
    "id": 2,
    "cityName": "北京",
    "updateTime": 1714192000000,
    "weather": null,
    "aqi": 55,
    "aqiUs": 55,
    "aqiCN": 42,
    "airQuality": "Good",
    "primaryPollutant": "pm2p5",
    "pm25": "15.0",
    "pm10": "30.0",
    "pm25Value": 15.0,
    "pm10Value": 30.0,
    "no2": 20.0,
    "o3": 80.0,
    "co": 0.5,
    "so2": 5.0
  }
}
```

**错误响应（HTTP 200）**
```json
{ "success": false, "message": "未找到城市：北京" }
```

---

### 2.3 `POST /api/location/save` — 保存位置（客户端每次定位/搜索后调用）

**请求体**（两种方式）

- 定位场景：传经纬度（服务端用 QWeather 逆地理编码解析出城市名并保存）
  ```json
  { "latitude": 39.9042, "longitude": 116.4074 }
  ```
- 手动搜索场景：只传城市名（lat/lon 传 0 或省略）
  ```json
  { "cityName": "北京", "latitude": 0, "longitude": 0 }
  ```

**成功响应 200**（返回保存后的 `Location` 实体）

```json
{ "id": 1, "cityName": "北京", "latitude": 39.9042, "longitude": 116.4074, "updateTime": 1714192000000 }
```

**错误响应（HTTP 200）**
```json
{ "success": false, "message": "无效的请求参数" }
```

---

### 2.4 `GET /api/location/local` — 获取最近一次保存的位置（冷启动恢复用，无参数）

> 注意：此接口**不接收 city 参数**，返回的是全库时间戳最新的一条 `locations` 记录（跨城市）。

**成功响应 200**
```json
{
  "success": true,
  "data": { "cityName": "北京", "latitude": 39.9042, "longitude": 116.4074 }
}
```

**无历史记录 → HTTP 404**
```json
{ "success": false, "message": "没有历史位置" }
```

---

## 三、端点汇总

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/weather/info` | 天气 + 空气质量综合（city 或 lat/lon） |
| `GET` | `/api/weather/air?city=北京` | 仅空气质量 |
| `POST` | `/api/location/save` | 保存定位/搜索的城市（逆地理编码或城市名检索） |
| `GET` | `/api/location/local` | 获取最近一次保存的位置（无参数） |

> 注：`/api/weather/*` 标注了 `@CrossOrigin(origins = "*")`；`/api/location/*` 未标注（原生 App 无跨域限制，如接 Web 端需自行补充）。
