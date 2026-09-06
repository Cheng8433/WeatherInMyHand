# 掌中天气 (WeatherInMyHand)

## 项目结构

```
WeatherInMyHand/
├── backend/                 # Spring Boot 后端（和风天气 v1，Ed25519 JWT）
│   ├── src/main/java/com/smog/
│   │   ├── SmogApplication.java
│   │   ├── controller/    # LocationController / WeatherController
│   │   ├── entity/        # Location / Weather
│   │   ├── repository/    # JPA 数据仓库
│   │   ├── service/       # 业务逻辑（天气/定位）
│   │   ├── exception/     # 全局异常处理
│   │   └── midwdget/      # JwtUtil（Ed25519 JWT 签发）
│   └── src/main/resources/application.properties
├── android/                 # Android 前端（单 Activity + 底部 3 Tab）
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/smog/weatherapp/
│   │   │   ├── MainActivity.java    # 唯一入口：今天/空气质量/趋势 三页 + 搜索/刷新/主题
│   │   │   ├── ThemeHelper.java     # 4 套主题切换与持久化（SharedPreferences）
│   │   │   └── WeatherFormat.java   # emoji/风向/污染物 格式化与 AQI 分级
│   │   └── res/
│   │       ├── layout/    # activity_main、header_bar、page_today/air/trend
│   │       ├── menu/      # menu_bottom（底部导航 3 项）
│   │       ├── mipmap-*/  # 桌面图标（自适应 + 各密度 PNG）
│   │       └── values/    # theme(4 套主题)/colors/attrs
│   └── build.gradle
└── README.md
```

## 技术栈

- 后端：Spring Boot 2.7 + JPA + H2数据库
- 前端：Android + OkHttp + AnyChart
- API：和风天气API（Ed25519 JWT 认证）

## 配置说明

后端与和风天气的认证走 **Ed25519 JWT**（无需 API Key）。把私钥 `ed25519-private.pem` 放到 `backend/` 下即可，或用环境变量 `PRIVATE_PEM_PATH`（也支持 `backend/.env`）指定路径；仓库中不含任何私钥。

## 运行方法

### 后端
```bash
cd backend
mvn spring-boot:run      # 默认 :8080；H2 数据落在 backend/data/（不入库）
```
云端部署方式见 `backend/`（systemd `smog.service` + ufw），不在本仓库提交。

### Android
用 Android Studio 打开 `android/` 目录运行。后端基地址由 `android/app/build.gradle` 的 `BuildConfig.BACK_HOST_API` 决定，当前指向云端试用实例 `http://118.178.147.156:8080/api/`；如需连本机后端，改回 `http://10.0.2.2:8080/api/`（模拟器）或本机局域网 IP。
