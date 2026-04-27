# WeatherInMyHand 后端 API 接口文档

> 供前端开发者查阅。后端基地址：`http://10.0.2.2:8080`（Android 模拟器 → 宿主机）

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

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | `String` | 是 | 城市名称，如"北京" |

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
    "so2": 5.0
  }
}
```

**错误响应 200**
```json
{
  "success": false,
  "message": "获取城市位置失败：北京"
}
```

---

### 2.2 `GET /api/weather/air` — 仅空气质量

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | `String` | 是 | 城市名称，如"北京" |

**成功响应 200**（天气相关字段均为 `null`）
```json
{
  "success": true,
  "data": {
    "id": 1,
    "cityName": "北京",
    "updateTime": 1714192000000,
    "weather": null,
    "temperature": null,
    "feelsLike": null,
    "humidity": null,
    "windDir": null,
    "windScale": null,
    "windSpeed": null,
    "precip": null,
    "pressure": null,
    "vis": null,
    "cloud": null,
    "dew": null,
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
    "so2": 5.0
  }
}
```

**错误响应 200**
```json
{
  "success": false,
  "message": "获取城市位置失败：北京"
}
```

---

### 2.3 `GET /api/location/search` — 查城市经纬度（数据库 + API 回退）

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | `String` | 是 | 城市名称，如"西安" |

**成功响应 200**
```json
{
  "cityName": "西安",
  "latitude": 34.26,
  "longitude": 108.94,
  "updateTime": 1714192000000
}
```

**错误响应 404**
```json
{
  "error": "未找到城市：西安"
}
```

---

### 2.4 `GET /api/location/local` — 仅查数据库（不调 API）

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | `String` | 是 | 城市名称 |

**成功响应 200**（返回完整 `Location` 实体）
```json
{
  "id": 1,
  "cityName": "西安",
  "latitude": 34.26,
  "longitude": 108.94,
  "updateTime": 1714192000000
}
```

**错误响应 404**
```json
{
  "error": "数据库中未找到城市：西安"
}
```

---

### 2.5 `GET /api/location/fetch` — 强制调 API 获取位置（不保存，仅测试）

**请求参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `city` | `String` | 是 | 城市名称 |

**成功响应 200**（`id` 为 `null`，因未持久化）
```json
{
  "id": null,
  "cityName": "西安",
  "latitude": 34.26,
  "longitude": 108.94,
  "updateTime": 1714192000000
}
```

**错误响应 500**
```json
{
  "error": "调用和风 API 失败：和风天气 API 错误，code：xxx"
}
```

---

## 三、端点汇总

| 方法 | 路径 | 用途 |
|---|---|---|
| `GET` | `/api/weather/info?city=北京` | 天气 + 空气质量综合 |
| `GET` | `/api/weather/air?city=北京` | 仅空气质量 |
| `GET` | `/api/location/search?city=北京` | 查经纬度（DB 优先 → API 回退） |
| `GET` | `/api/location/local?city=北京` | 仅查数据库 |
| `GET` | `/api/location/fetch?city=北京` | 强制调 API（不保存，测试用） |

> 注意：`/api/weather/*` 两个端点标注了 `@CrossOrigin(origins = "*")`，允许跨域；`/api/location/*` 未标注跨域。
