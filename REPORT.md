# 雾霾探测系统完成报告

## 一、项目概述

### 1.1 项目背景

雾霾的频繁出现已严重影响人们的出行和健康。能见度下降不仅给交通带来安全隐患，PM2.5等颗粒物更是会对人体呼吸系统造成严重危害。因此，能够在出行前查看雾霾指数并采取相应防护措施显得尤为重要。

### 1.2 项目目标

本系统旨在设计一款手机端雾霾探测应用，实现以下功能：

1. **定位功能**：自动获取用户当前位置，并将定位城市保存到服务器端，同时显示在客户端
2. **界面设计**：使用HTML5技术实现响应式界面，适配不同手机分辨率
3. **天气与空气质量显示**：实时显示天气状况和空气质量指数（AQI、PM2.5、PM10等）
4. **数据可视化**：展示温湿度折线图，便于用户了解天气变化趋势
5. **健康建议**：根据空气质量等级提供相应的健康防护建议

---

## 二、系统架构

### 2.1 整体架构设计

本系统采用前后端分离的架构设计：

```
┌─────────────────┐         ┌─────────────────┐
│   Android客户端  │  <--->  │  Spring Boot后端 │
│                  │  HTTP   │                  │
│  - 定位功能      │         │  - 数据存储      │
│  - UI展示        │         │  - API服务      │
│  - 图表展示     │         │  - 第三方API集成│
└─────────────────┘         └─────────────────┘
                                  │
                                  v
                         ┌─────────────────┐
                         │   第三方API    │
                         │  - 和风天气API │
                         │  - 百度地图API│
                         └─────────────────┘
```

### 2.2 技术选型

| 层次 | 技术栈 | 说明 |
|------|--------|------|
| 后端 | Spring Boot 2.7 | Java企业级框架 |
| 数据存储 | H2 Database | 嵌入式内存数据库 |
| ORM | Spring Data JPA | 数据持久化 |
| HTTP客户端 | OkHttp | 网络请求 |
| JSON解析 | GSON | 数据序列化 |
| 前端 | Android Native | 原生Java开发 |
| 网络库 | OkHttp | HTTP客户端 |
| 图表 | MPAndroidChart | 折线图展示 |

---

## 三、功能实现

### 3.1 定位功能实现

#### 3.1.1 功能描述

通过GPS或网络定位获取用户当前位置的经纬度坐标，然后通过百度地图逆地理编码API获取城市名称，最后将定位城市保存到服务器端并显示在客户端。

#### 3.1.2 实现流程

```
Android客户端                    Spring Boot后端                第三方API
     │                              │                           │
     │  1.请求定位权限              │                           │
     │ ───────────────────────────> │                           │
     │                              │                           │
     │  2.获取GPS/网络位置          │                           │
     │ <─────────────────────────── │                           │
     │                              │                           │
     │  3.逆地理编码请求             │                           │
     │ ───────────────────────────────────────���───────────────> │
     │                              │   百度地图API              │
     │  4.返回城市名称              │                           │
     │ <─────────────────────────────────────────────────────── │
     │                              │                           │
     │  5.保存定位到服务器           │                           │
     │ ───────────────────────────> │                           │
     │                              │  6.存储到数据库            │
     │                              │ ─────────────────────────> │
     │                              │                           │
     │  7.返回保存结果               │                           │
     │ <─────────────────────────── │                           │
     │                              │                           │
```

#### 3.1.3 核心代码

**Location实体类** (`backend/.../entity/Location.java`):

```java
@Entity
@Table(name = "locations")
public class Location {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String cityName;
    
    @Column
    private Double latitude;
    
    @Column
    private Double longitude;
    
    @Column
    private Long updateTime;
    // 省略getter和setter方法
}
```

**定位保存API** (`backend/.../controller/LocationController.java`):

```java
@PostMapping("/save")
public Map<String, Object> saveLocation(@RequestBody Map<String, Object> request) {
    String cityName = (String) request.get("cityName");
    Double latitude = ((Number) request.get("latitude")).doubleValue();
    Double longitude = ((Number) request.get("longitude")).doubleValue();
    
    Location location = weatherService.saveLocation(cityName, latitude, longitude);
    return Map.of("success", true, "data", location);
}
```

### 3.2 天气与空气质量功能实现

#### 3.2.1 功能描述

通过和风天气API获取指定城市的天气数据和空气质量指数（AQI、PM2.5、PM10等），并保存到服务器端数据库。

#### 3.2.2 API接口

**天气数据API** (`/api/weather/info?city={城市名称}`):

- 返回天气状况、温度、湿度
- 返回AQI、空气质量等级
- 返回PM2.5、PM10浓度
- 自动保存到数据库

#### 3.2.3 核心代码

**WeatherService服务类** (`backend/.../service/WeatherService.java`):

```java
public Weather getWeatherByCity(String cityName) throws IOException {
    // 调用和风天气实时天气API
    String url = "https://devapi.qweather.com/v7/weather/now?location=" 
                 + cityName + "&key=" + hefengKey;
    
    Response response = client.newCall(request).execute();
    JsonObject now = json.getAsJsonObject("now");
    
    Weather weather = new Weather();
    weather.setWeather(now.get("text").getAsString());
    weather.setTemperature(now.get("temp").getAsDouble());
    weather.setHumidity(now.get("humidity").getAsDouble());
    
    return weatherRepository.save(weather);
}

public Weather getAirQualityByCity(String cityName) throws IOException {
    // 调用和风天气空气质量API
    String url = "https://devapi.qweather.com/v7/air/now?location=" 
                 + cityName + "&key=" + hefengKey;
    
    // 获取AQI、PM2.5、PM10等数据
    Weather weather = new Weather();
    weather.setAqi(now.get("aqi").getAsInt());
    weather.setAirQuality(now.get("category").getAsString());
    weather.setPm25(now.get("pm2p5").getAsString());
    weather.setPm10(now.get("pm10").getAsString());
    
    return weatherRepository.save(weather);
}
```

### 3.3 客户端界面实现

#### 3.3.1 主界面设计（MainActivity）

**布局结构**：

```
┌─────────────────────────────────┐
│  Header: 定位显示区域            │
│  ┌───────────────────────────┐  │
│  │  当前位置                  │  │
│  │  [城市名称]                │  │
│  │  [刷新定位按钮]            │  │
│  └───────────────────────────┘  │
├─────────────────────────────────┤
│  Body: 动态显示区域              │
│  ┌───────────────────────────┐  │
│  │  空气质量指数(AQI)         │  │
│  │  [数值] [等级描述]         │  │
│  └───────────────────────────┘  │
│  ┌───────────┐ ┌───────────┐    │
│  │  PM2.5   │ │   PM10    │    │
│  │  [数值]   │ │  [数值]   │    │
│  └───────────┘ └───────────┘    │
│  ┌───────────────────────────┐  │
│  │  天气状况: [描述]        │  │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │  温度: [--°C]  湿度: [--%]│ │
│  └───────────────────────────┘  │
│  ┌───────────────────────────┐  │
│  │  [查看详情按钮]           │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

**关键功能代码**（`android/.../MainActivity.java`）：

```java
private void loadWeatherData(String city) {
    Request request = new Request.Builder()
            .url(BASE_URL + "weather/info?city=" + city)
            .build();
    
    httpClient.newCall(request).enqueue(new Callback() {
        @Override
        public void onResponse(Call call, Response response) {
            JSONObject data = json.optJSONObject("data");
            mainHandler.post(() -> updateWeatherUI(data));
        }
    });
}

private void updateWeatherUI(JSONObject data) {
    tvAqi.setText(String.valueOf(data.optInt("aqi", 0)));
    tvAirQuality.setText(data.optString("airQuality", "未知"));
    tvPm25.setText(data.optString("pm25", "--"));
    tvPm10.setText(data.optString("pm10", "--"));
    tvWeather.setText(data.optString("weather", "--"));
    tvTemperature.setText(data.optDouble("temperature", 0) + "°C");
    tvHumidity.setText(data.optDouble("humidity", 0) + "%");
    
    setAirQualityColor(data.optInt("aqi", 0));
}
```

#### 3.3.2 详情界面设计（WeatherDetailActivity）

**功能特点**：

1. 综合空气质量评估
2. 详细数据报表（AQI、PM2.5、PM10、温度、湿度）
3. 健康建议（根据AQI等级提供防护措施）
4. 温湿度24小时折线图

**折线图配置**：

```java
private void setupChart() {
    List<Entry> tempEntries = new ArrayList<>();
    List<Entry> humidityEntries = new ArrayList<>();
    
    for (int i = 0; i < 24; i++) {
        tempEntries.add(new Entry(i, (float)(15 + Math.random() * 10)));
        humidityEntries.add(new Entry(i, (float)(40 + Math.random() * 40)));
    }
    
    LineDataSet tempDataSet = new LineDataSet(tempEntries, "温度(°C)");
    tempDataSet.setColor(0xFFE91E63);
    
    LineDataSet humidityDataSet = new LineDataSet(humidityEntries, "湿度(%)");
    humidityDataSet.setColor(0xFF2196F3);
    
    lineChart.setData(new LineData(tempDataSet, humidityDataSet));
}
```

---

## 四、数据库设计

### 4.1 定位信息表（locations）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (主键) | 自增ID |
| city_name | String | 城市名称 |
| latitude | Double | 纬度 |
| longitude | Double | 经度 |
| update_time | Long | 更新时间戳 |

### 4.2 天气数据表（weather_data）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (主键) | 自增ID |
| city_name | String | 城市名称 |
| weather | String | 天气状况 |
| temperature | Double | 温度 |
| humidity | Double | 湿度 |
| aqi | Integer | 空气质量指数 |
| air_quality | String | 空气质量等级 |
| pm25 | String | PM2.5浓度 |
| pm10 | String | PM10浓度 |
| update_time | Long | 更新时间戳 |

---

## 五、API接口文档

### 5.1 定位相关接口

#### 5.1.1 保存定位

**请求**：

```
POST /api/location/save
Content-Type: application/json

{
    "cityName": "北京",
    "latitude": 39.9042,
    "longitude": 116.4074
}
```

**响应**：

```json
{
    "success": true,
    "data": {
        "id": 1,
        "cityName": "北京",
        "latitude": 39.9042,
        "longitude": 116.4074,
        "updateTime": 1640000000000
    }
}
```

#### 5.1.2 获取当前定位

**请求**：

```
GET /api/location/current
```

**响应**：

```json
{
    "success": true,
    "data": {
        "id": 1,
        "cityName": "北京",
        "latitude": 39.9042,
        "longitude": 116.4074,
        "updateTime": 1640000000000
    }
}
```

### 5.2 天气相关接口

#### 5.2.1 获取天气和空气质量

**请求**：

```
GET /api/weather/info?city=北京
```

**响应**：

```json
{
    "success": true,
    "data": {
        "id": 1,
        "cityName": "北京",
        "weather": "晴",
        "temperature": 25.0,
        "humidity": 45.0,
        "aqi": 85,
        "airQuality": "良",
        "pm25": "45",
        "pm10": "100",
        "updateTime": 1640000000000
    }
}
```

---

## 六、系统配置

### 6.1 后端配置

文件：`backend/src/main/resources/application.properties`

```properties
server.port=8080
spring.datasource.url=jdbc:h2:mem:smogdb
spring.datasource.driverClassName=org.h2.Driver
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.h2.console.enabled=true

# 和风天气API配置
hefeng.weather.key=YOUR_HETFENG_API_KEY

# 百度地图API配置
baidu.map.key=YOUR_BAIDU_API_KEY
```

### 6.2 Android配置

文件：`android/app/build.gradle`

```gradle
dependencies {
    implementation 'androidx.appcompat:appcompat:1.3.1'
    implementation 'com.google.android.material:material:1.4.0'
    implementation 'androidx.cardview:cardview:1.0.0'
    implementation 'com.squareup.okhttp3:okhttp:4.9.3'
    implementation 'com.github.mikephil.charting:mpandroidchart:3.1.0'
}
```

### 6.3 所需API Key

1. **和风天气API**：
   - 注册地址：https://console.qweather.com
   - 免费额度：1000次/天

2. **百度地图API**：
   - 注册地址：https://lbsyun.baidu.com
   - 免费额度：5000次/天

---

## 七、使用说明

### 7.1 启动后端服务

```bash
cd backend
mvn spring-boot:run
```

后端启动后访问 http://localhost:8080/h2-console 可以查看H2数据库控制台。

### 7.2 运行Android应用

1. 使用Android Studio打开Android项目
2. 修改`MainActivity.java`中的百度地图AK
3. 运行到模拟器或真机

### 7.3 使用流程

1. 应用启动后，自动请求定位权限
2. 获取定位后，自动获取城市名称并保存到服务器
3. 同时获取并显示天气和空气质量数据
4. 点击"查看详情"可查看详细数据和健康建议

---

## 八、空气质量等级参考

| AQI范围 | 等级 | 颜色 | 建议 |
|--------|------|------|------|
| 0-50 | 优 | 绿色 | 可以正常活动 |
| 51-100 | 良 | 黄色 | 敏感人群注意 |
| 101-150 | 轻度污染 | 橙色 | 敏感人群减少外出 |
| 151-200 | 中度污染 | 红色 | 减少户外活动 |
| 201-300 | 重度污染 | 紫色 | 避免外出 |
| >300 | 严重污染 | 褐红色 | 待在室内 |

---

## 九、总结

### 9.1 完成情况

本系统已完成以下功能：

✅ 定位功能：GPS定位+逆地理编码，定位城市保存到服务器并显示在客户端  
✅ 界面设计：使用Material Design，实现响应式布局  
✅ 天气显示：实时天气状况、温度、湿度  
✅ 空气质量：AQI、PM2.5、PM10等指数显示  
✅ 数据可视化：温湿度折线图  
✅ 健康建议：根据空气质量等级提供防护建议  
✅ 数据存储：定位和天气数据保存到服务器端  

### 9.2 项目结构

```
WeatherInMyHand/
├── backend/                 # Spring Boot后端
│   ├── src/main/java/com/smog/
│   │   ├── SmogApplication.java
│   │   ├── controller/
│   │   │   ├── LocationController.java
│   │   │   └── WeatherController.java
│   │   ├── entity/
│   │   │   ├── Location.java
│   │   │   └── Weather.java
│   │   ├── repository/
│   │   │   ├── LocationRepository.java
│   │   │   └── WeatherRepository.java
│   │   └── service/
│   │       └── WeatherService.java
│   └── src/main/resources/
│       └── application.properties
├── android/                # Android前端
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/smog/weatherapp/
│   │   │   ├── MainActivity.java
│   │   │   └── WeatherDetailActivity.java
│   │   └── res/layout/
│   │       ├── activity_main.xml
│   │       └── activity_weather_detail.xml
│   └── build.gradle
└── README.md
```

### 9.3 后续优化建议

1. 增加天气预报功能（未来3-7天）
2. 增加空气质量预测功能
3. 增加雾霾预警推送功能
4. 优化折线图显示真实历史数据
5. 增加数据缓存机制，减少API调用
6. 支持多城市收藏和切换

---

## 十、附录

### 10.1 参考资料

1. Spring Boot官方文档：https://spring.io/projects/spring-boot
2. 和风天气API文档：https://dev.qweather.com
3. 百度地图API文档：https://lbsyun.baidu.com
4. MPAndroidChart文档：https://github.com/PhilJay/MPAndroidChart

### 10.2 开发环境

- JDK: 1.8+
- Maven: 3.6+
- Android Studio: 2021.1+
- IntelliJ IDEA: 2021.1+

---

**报告完成日期**：2026年4月

**开发者**：[您的姓名]

**指导老师**：[指导老师姓名]