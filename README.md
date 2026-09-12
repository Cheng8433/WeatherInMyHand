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
│   │   ├── AndroidManifest.xml        # 关闭明文；挂 networkSecurityConfig
│   │   ├── java/com/smog/weatherapp/
│   │   │   ├── MainActivity.java      # 唯一入口：今天/空气质量/趋势 三页 + 搜索/刷新/主题/定位
│   │   │   ├── PrivacyActivity.java   # 关于与隐私政策页（版本号/数据来源/重置同意）
│   │   │   ├── PrivacyStore.java      # 隐私同意态持久化（SharedPreferences）
│   │   │   ├── ThemeHelper.java       # 4 套主题切换与持久化（SharedPreferences）
│   │   │   ├── WeatherCache.java      # 每城快照离线缓存 + 断网/失败兜底
│   │   │   ├── WeatherSceneView.java  # 首页整屏动效天空（按天气/主题出粒子与日月）
│   │   │   └── WeatherFormat.java     # emoji/风向/污染物 格式化与 AQI 分级
│   │   └── res/
│   │       ├── layout/    # activity_main、header_bar、page_today/air/trend、activity_privacy
│   │       ├── menu/      # menu_bottom（底部导航 3 项）
│   │       ├── mipmap-*/  # 桌面图标（自适应 + 各密度 PNG）
│   │       ├── xml/       # network_security_config（system + 内置 Sectigo R46 根）
│   │       ├── raw/       # sectigo_r46.pem（公开根证书，随源码分发）
│   │       └── values/    # theme(4 套主题)/colors/attrs/strings/arrays
│   └── build.gradle
└── README.md
```

## 技术栈

- 后端：Spring Boot 2.7 + JPA + H2数据库
- 前端：Android + OkHttp + AnyChart
- API：和风天气API（Ed25519 JWT 认证）

## 配置说明

后端与和风天气的认证走 **Ed25519 JWT**（无需 API Key）。把私钥 `ed25519-private.pem` 放到 `backend/` 下即可，或用环境变量 `PRIVATE_PEM_PATH`（也支持 `backend/.env`）指定路径；仓库中不含任何私钥。

## 上线状态（2026-09-09）

- 已切 **HTTPS**：云端 nginx 装 ZeroSSL **IP 证书**（纯公网 IP、无域名），443 TLS 反代到后端 `127.0.0.1:8080`，80 全跳 301；后端收拢为只绑回环、**公网明文 8080 已关闭**。详见 `HTTPS-DEPLOY.md`。
- Android 端 `BACK_HOST_API=https://118.178.147.156/api/`，Manifest **关闭明文**（`usesCleartextTraffic=false`），并内置 **Sectigo R46 公共根**（`res/raw/sectigo_r46.pem`），以兼容系统信任库较旧、缺 R46 新根的设备。
- 隐私合规：首启不可关闭的同意门（`PrivacyStore`）、关于与隐私政策页（`PrivacyActivity`）。当前版本 **1.0.4（versionCode 5）**。
- 质量加固（1.0.3，2026-09-10）：安卓修掉定位监听/超时回调泄漏（`onDestroy` 注销 + 取消在途请求）、响应体读取异常导致加载条卡死、并发请求旧城市覆盖新城市（请求序号）；后端 `/air` 失败降级与数字字段解析容错，写路径补事务。
- 数据可信度（1.0.4，2026-09-11）：客户端开始读取后端 `stale` 降级标记，今天页页脚显示「数据更新：MM-dd HH:mm」，降级或读本地缓存时明示「离线缓存 · 更新于 …」；`WeatherCache` 加容量上限（最多 10 城，按写入时间淘汰）与 24 小时有效期；网络判断改用 `getActiveNetwork` + `NetworkCapabilities`（原废弃 `getActiveNetworkInfo` 会把有网误判为无网络）；后端 `/api/location/local` 改用 HashMap 组装，避免可空经纬度触发 `Map.of` 的 NPE。
- 发版瘦身与稳健性（1.0.4，2026-09-11）：Android release 打开 **R8**（`minifyEnabled` + `shrinkResources`，规则见 `app/proguard-rules.pro`），release 包从 **8.1 MB 降到 2.8 MB**（未压缩 debug 包 8.1 MB 作参照；其中单是把 AnyChart 的整库 `-keep` 收窄到「只保 JS 桥」就省下 1.1 MB）。`gradle.properties` 补 `org.gradle.jvmargs=-Xmx2048m`，否则 R8 会因默认 512m 堆 GC 抖动中断构建。后端 `WeatherService` 的天气/空气缓存放进 `ResultCache`（上限 200 条 + 按时间淘汰 + 同城单飞，避免并发下同一城市重复打和风），日志改用 `logback-spring.xml` 按天/按 10MB 滚动（保留 14 天、总量 200MB），兜底异常不再把 `e.getMessage()` 透给客户端（Hibernate/SQL 消息会带出表名与 SQL 片段，只进日志）；`WeatherFormat` 里残留的中文（AQI 等级/评估/健康建议、风向、污染物名）全部外置到 `arrays.xml` / `strings.xml`。

## 运行方法

### 后端
```bash
cd backend
mvn spring-boot:run      # 默认 :8080；H2 数据落在 backend/data/（不入库）
```
云端部署方式见 `backend/`（systemd `smog.service` + ufw），不在本仓库提交。

### Android
用 Android Studio 打开 `android/` 目录运行。后端基地址由 `android/app/build.gradle` 的 `BuildConfig.BACK_HOST_API` 决定，当前指向云端 HTTPS 实例 `https://118.178.147.156/api/`；如需连本机后端，改成 `http://10.0.2.2:8080/api/`（模拟器）或本机局域网 IP，并临时放开该地址的明文（仅本地调试用）。
