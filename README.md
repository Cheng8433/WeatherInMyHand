# 雾霾探测系统

## 项目结构

```
WeatherInMyHand/
├── backend/                 # Spring Boot后端
│   ├── src/main/java/com/smog/
│   │   ├── SmogApplication.java
│   │   ├── controller/   # API控制器
│   │   ├── entity/      # 数据实体
│   │   ├── repository/ # 数据仓库
│   │   └── service/     # 业务逻辑
│   └── pom.xml
├── android/              # Android前端
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

## 技术栈

- 后端：Spring Boot 2.7 + JPA + H2数据库
- 前端：Android + OkHttp + MPAndroidChart
- API：和风天气API、百度地图API

## 配置说明

1. 修改 `backend/src/main/resources/application.properties` 中的API Key
2. 修改 `MainActivity.java` 中的百度地图AK

## 运行方法

### 后端
```bash
cd backend
mvn spring-boot:run
```

### Android
使用Android Studio打开android目录，运行即可