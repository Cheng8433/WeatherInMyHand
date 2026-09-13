# 掌中天气 (WeatherInMyHand)

## 项目结构

```
WeatherInMyHand/
├── backend/                 # Spring Boot 后端（和风天气 v1，Ed25519 JWT）
│   ├── src/main/java/com/smog/
│   │   ├── SmogApplication.java
│   │   ├── controller/    # WeatherController（唯一端点 /api/weather/info）
│   │   ├── dto/           # Weather（/info 的响应模型，不落库，无 JPA 注解）
│   │   ├── entity/        # Location（服务端唯一的持久化实体）
│   │   ├── repository/    # LocationRepository（城市名→坐标缓存）
│   │   ├── service/       # 业务逻辑（天气/定位）
│   │   ├── exception/     # 全局异常处理
│   │   └── midwidget/     # JwtUtil（Ed25519 JWT 签发）
│   └── src/main/resources/application.properties
├── android/                 # Android 前端（单 Activity + 底部 3 Tab）
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml        # 关闭明文；挂 networkSecurityConfig
│   │   ├── java/com/smog/weatherapp/
│   │   │   ├── MainActivity.java      # 唯一入口：视图装配/切页、请求序号裁定、隐私同意门、缓存秒开
│   │   │   ├── PrivacyActivity.java   # 关于与隐私政策页（版本号/数据来源/重置同意）
│   │   │   ├── PrivacyStore.java      # 隐私同意态持久化（SharedPreferences）
│   │   │   ├── ThemeHelper.java       # 4 套主题切换与持久化（SharedPreferences）
│   │   │   ├── WeatherCache.java      # 每城快照离线缓存 + 断网/失败兜底
│   │   │   ├── WeatherSceneView.java  # 首页整屏动效天空（按天气/主题出粒子与日月）
│   │   │   ├── WeatherFormat.java     # emoji/风向/污染物 格式化与 AQI 分级
│   │   │   ├── PageRenderer.java      # 三页渲染：哪个字段填哪个控件（+ 页脚时效）
│   │   │   ├── UiFormat.java          # 单值怎么显示：数值+单位/污染物读数/百分比串
│   │   │   ├── LoadOverlay.java       # 全局细加载条 + 错误重试浮层
│   │   │   ├── LocationHelper.java    # 一次性实时定位（权限/监听/10 秒超时）
│   │   │   ├── FavoritesStore.java    # 城市收藏夹持久化（SharedPreferences 有序列表，可单测的纯函数）
│   │   │   ├── FavoritesDialog.java   # 收藏城市弹窗：点行切城 / 收藏或取消当前城 / ✕ 删除
│   │   │   └── net/
│   │   │       ├── WeatherApi.java    # 后端 HTTP 客户端 + 统一 JSON 契约解析
│   │   │       └── NetworkStatus.java # 系统联网状态查询（请求前/定位前共用同一口径）
│   │   └── res/
│   │       ├── layout/    # activity_main、header_bar、page_today/air/trend、activity_privacy、dialog_favorites/item_favorite
│   │       ├── menu/      # menu_bottom（底部导航 3 项）
│   │       ├── mipmap-*/  # 桌面图标（自适应 + 各密度 PNG）
│   │       ├── xml/       # network_security_config（system + 内置 Sectigo R46 根）
│   │       ├── raw/       # sectigo_r46.pem（公开根证书，随源码分发）
│   │       └── values/    # theme(5 套主题)/colors/attrs/strings/arrays
│   ├── app/src/test/java/com/smog/weatherapp/   # 本地 JVM 单测（WeatherFormatTest / UiFormatTest）
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
- 隐私合规：首启不可关闭的同意门（`PrivacyStore`）、关于与隐私政策页（`PrivacyActivity`）。当前版本 **1.0.6（versionCode 7）**。
- 质量加固（1.0.3，2026-09-10）：安卓修掉定位监听/超时回调泄漏（`onDestroy` 注销 + 取消在途请求）、响应体读取异常导致加载条卡死、并发请求旧城市覆盖新城市（请求序号）；后端 `/air` 失败降级与数字字段解析容错，写路径补事务（该端点已于 1.0.5 删除，见下）。
- 数据可信度（1.0.4，2026-09-11）：客户端开始读取后端 `stale` 降级标记，今天页页脚显示「数据更新：MM-dd HH:mm」，降级或读本地缓存时明示「离线缓存 · 更新于 …」；`WeatherCache` 加容量上限（最多 10 城，按写入时间淘汰）与 24 小时有效期；网络判断改用 `getActiveNetwork` + `NetworkCapabilities`（原废弃 `getActiveNetworkInfo` 会把有网误判为无网络）；后端 `/api/location/local` 改用 HashMap 组装，避免可空经纬度触发 `Map.of` 的 NPE（该接口已于 2026-09-12 整体删除，见下）。
- 发版瘦身与稳健性（1.0.4，2026-09-11）：Android release 打开 **R8**（`minifyEnabled` + `shrinkResources`，规则见 `app/proguard-rules.pro`），release 包从 **8.1 MB 降到 2.8 MB**（未压缩 debug 包 8.1 MB 作参照；其中单是把 AnyChart 的整库 `-keep` 收窄到「只保 JS 桥」就省下 1.1 MB）。`gradle.properties` 补 `org.gradle.jvmargs=-Xmx2048m`，否则 R8 会因默认 512m 堆 GC 抖动中断构建。后端 `WeatherService` 的天气/空气缓存放进 `ResultCache`（上限 200 条 + 按时间淘汰 + 同城单飞，避免并发下同一城市重复打和风），日志改用 `logback-spring.xml` 按天/按 10MB 滚动（保留 14 天、总量 200MB），兜底异常不再把 `e.getMessage()` 透给客户端（Hibernate/SQL 消息会带出表名与 SQL 片段，只进日志）；`WeatherFormat` 里残留的中文（AQI 等级/评估/健康建议、风向、污染物名）全部外置到 `arrays.xml` / `strings.xml`。
- 死代码与一致性清理（1.0.4，2026-09-12）：删除零调用的 `WeatherService.saveLocation`、`WeatherFormat.pollutantUnit`，以及 `MainActivity` 中从未被置为 `true` 的 `hasPerformedInitialLocation` 字段与其不可达守卫；后端包名拼写 `com.smog.midwdget` 修正为 `com.smog.midwidget`（同步两处 `import` 与本文结构树）；`MainActivity.attrColor` 补上 `resolveAttribute` 的返回值判断，主题属性缺失时回退默认色，不再返回全透明 `0`；`getWeatherAndAirQuality` 包装定位失败异常改用 `new IOException(e.getMessage(), e)`——原先 `new IOException(e)` 会让 `getMessage()` 变成 `java.lang.RuntimeException: …`，把内部类名带进客户端错误响应。
- 限流真实 IP 收口（1.0.4，2026-09-12）：原先 `RateLimitInterceptor.clientIp()` 无条件取 `X-Forwarded-For` 首段，而部署文档给的 nginx 配置是 `$proxy_add_x_forwarded_for`（**追加**，客户端自带段留在最左）——两者叠加等于把限流 key 交给调用方：每请求换一个假 IP 就能绕开「按 IP 30 次/分」，只剩全局额度兜底，还能伪造任意 IP 精准封掉别人。现改为：`server.address=127.0.0.1` 让后端只监听回环（公网唯一入口是 nginx 443，直连 8080 的明文与伪造头两条路一起消失），真实 IP 交给 Tomcat `RemoteIpValve` 解析（`server.tomcat.remoteip.*`，只采信来自可信代理的连接、从右往左跳过代理段取真实地址，可信代理收紧到只有本机），`clientIp()` 退回 `request.getRemoteAddr()`。文档侧同步把 nginx 改成 `$remote_addr` 覆盖写做纵深防御，并纠正了 HTTPS-DEPLOY.md §3.3「首段由反代注入即正确」的错误论断。
- 冷启动不再串城（1.0.4，2026-09-12）：客户端原先冷启动会调 `GET /api/location/local` 恢复「上次城市」，但该接口返回的是服务器 DB 里**全局最新**的一条 Location（按 `updateTime`，不区分设备）——新装 App、无本地缓存城市、又拿不到 GPS 的用户会看到「服务器上最近有人查过的那个城市」，多人共用时等于串城且泄露他人位置。现移除该调用：冷启动只信**本机** `WeatherCache` 快照（每台设备各自的上一座城市），拿不到就提示「未获取到位置」，与既有「只信实时定位」口径一致。随之下线已无用的 `guardByGps` 低优先级分支与 `isGpsResultApplied` 字段（请求先后一律由 `uiApplySeq`「最后发起者胜」裁定）。
- 架构拆分（2026-09-12）：`MainActivity` 由 **1049 行降到 506 行**，把四块横切关注点整块抽出成平铺的普通类（不引入任何架构框架），全部为零行为变化的机械搬运——`net/WeatherApi`（OkHttp + 统一 JSON 契约解析，回调刻意区分"请求没送达"与"送到了但不可用"，因为调用方对这两种情况的话术本就不同）、`net/NetworkStatus`（联网判断，请求前与定位前共用同一口径）、`LoadOverlay`（细加载条 + 错误重试浮层；"重试要重发哪个请求"仍由 Activity 每次注入）、`LocationHelper`（权限/监听/10 秒超时，含"重复调用先清理旧注册""拿到 fix 顺手取消超时任务"两处易错时序，`onDestroy` 的注销链收成 `stop()`）、`PageRenderer` + `UiFormat`（前者管"哪个字段填哪个控件"、后者管"同一个值怎么显示"，拆开是因为二者变化的原因不同）。留在 Activity 的只剩它才懂的事：请求序号 `uiApplySeq`"最后发起者胜"的裁定、搜索/刷新/GPS 三条路径各自的兜底语义、隐私同意门、本地缓存秒开。测试补 `UiFormatTest`（安卓单测增至 13 例）。
- 位置数据最小化（1.0.4，2026-09-12）：把「位置不上服务器」这件事做彻底，分两侧。(1) 服务端删掉 `GET /api/location/local` 全套访问路径（`LocationController.getLatestLocation`、`LocationService.getCurrentLocation`、`LocationRepository.findTopByOrderByUpdateTimeDesc`）以及零调用的 `WeatherService.getCurrentLocation`、随之无用的 `locationRepository` 字段与 `LocationService.getLocationFromDB`——它没有主人标识，"最新一条"只可能是全局语义，留不下任何合理的将来用法；(2) 坐标不再落库：`saveLocationByLatLon`/`reverseGeocode` 原先把**调用方上传的原始经纬度**存进 `locations`，现改为一律存**逆地理编码出的城市中心坐标**，且该约束放在服务端强制——位置属敏感个人信息，天气只需城市级粒度，不该让某个客户端版本决定要不要写精确坐标；客户端同时移除 `saveLocationToServer` 调用与方法（其唯一消费者 `/local` 已删，城市缓存由后端 `getOrFetchLocation` 按需自填），GPS fix 现在只用于当次天气查询、不上传也不留存；(3) 顺带修掉写路径不一致：`saveLocationFromApi` 原先是裸 `save()`，每搜一次城市就 INSERT 一行（表随使用增长、同城堆重复行，也是 AGENTS.md 里"唯一索引暂缓"的成因），现与经纬度路径共用同一个 `upsertByCityName`，按 API 返回的标准城市名去重，同一城市库里始终只有一行。
- 精简与超时对齐（1.0.5，2026-09-12）：(1) **客户端读超时 10 秒提到 30 秒**——OkHttp 默认 10 秒短于后端一次综合请求的最坏预算（缓存未命中时串行打 4 次和风：地理编码→实时→空气→逐小时，每次上游 connect 5s + read 15s），后端还在取数客户端就先断开，界面误报「网络错误」而服务端其实可能已经成功，这次刷新等于白费；(2) **删掉两处死链路**：`GET /api/weather/air` 客户端从不调用、且综合路径不经过它的缓存（`airCache` 在生产环境恒空），`POST /api/location/save` 对 App 自身完全冗余（城市缓存由后端 `getOrFetchLocation` 按需自填，这次 POST 只是每次搜索白花一次请求与一个限流额度）——随之删除 `LocationController` 整个类、`LocationService.saveLocationByLatLon`（唯一调用者就是它）与「按经纬度写库」分支，服务端至此**没有任何写位置的入口**，限流路径也收拢为只覆盖 `/api/weather/**`；(3) **删 `getCityNameFromLastWeather` 及其空白城市兜底分支**——它取「全库最新一条」天气记录的城市名，正是已删的 `/api/location/local` 的全局语义，且实际不可达（`/info` 与 `/air` 都必传城市名）；`getAirQualityByLatLon` 改为城市名为空时显式报错，并清理随之无用的 `WeatherRepository.findTopByOrderByUpdateTimeDesc`；(4) **修 `staleOrRethrow` 的消息外泄**：非 IOException 原先被包成 `new IOException(cause.getMessage(), cause)`，而 `GlobalExceptionHandler` 会把 IOException 的 message 原样返回给客户端，等于让 JPA/SQL 的表名与 SQL 片段绕过「细节只进日志」的闸门，现改为固定文案对外、原始异常只挂因果链供日志追溯；(5) **重写 `API-DOC.md`**：它此前仍在描述已删的 `/api/location/local` 与「无记录返回 404」这个已不存在的例外、称客户端每次定位都会上报坐标（与隐私修复正好相反）、把 `/air` 的天气字段写成恒为 null（实际会复用该城已有行），并补上 `stale` 降级标记与限流两处契约说明；同时订正文档里「4 套主题」的笔误（实为 5 套）。
- 天气数据不再落库（2026-09-12，后端；并入尚未出包的 1.0.5）：删掉 `weather_data` 表所在的整层持久化——`WeatherRepository`、`Weather` 上的 JPA 注解与自增主键一并移除，该类从 `entity/` 移到 `dto/`（它从来就是 `/info` 的响应模型，由 Controller 直接序列化成 `data`）。它一直是**只写不读**的：全仓库对它的 5 处引用里，两处读只是 upsert 的底稿，响应里的 `data` 来自内存对象、缓存窗口内来自 `infoCache`，连 `stale` 降级读的也是内存 `ResultCache.lastKnown()` 而非数据库——所以它唯一的实际作用是每次缓存未命中多出 3 次全行写，并把 `locations` 拖进「想加 `city_name` 唯一索引又怕存量重复行」的僵局。**服务端至此只剩 `locations` 一张表**，那个唯一索引缺口随删除消失（而非被修补）。接口侧只有一处可见变化：`data` 不再有 `id`（客户端不读它）；`updateTime` 保留，它由服务端取数时显式赋值，客户端的「数据更新」页脚与本地缓存 24h 有效期判定都靠它。另有一处更诚实的行为变化：上游本次没返回的字段现在为 `null`，不再从旧行沿用上一次的值（旧值冒充本次数据且不带任何标记，正是 1.0.4「数据可信度」要治的病）。**而这层「沿用」还一直遮着一个真 bug**：`mergeAirQuality` 逐个搬运空气质量字段时漏了 `aqiCN`（搬了 `aqi`/`aqiUs`/`aqiQa` 与全部污染物，唯独没有它），所以 `aqiCN` 此前只能在「该城上一行恰好存过」时才有值——同一城市反复查询看着正常，**换一个从未查过的城市就是 null**。去掉 DB 沿用后它必然恒为 null，本轮顺手补上这一行；已用全新城市验证（`黄浦` 首查即返回 `aqiCN`），与 `aqi` 取值一致。
- 城市收藏夹（1.0.6，2026-09-13，安卓；后端零改动）：顶栏新增 ☆ 按钮，弹出收藏城市弹窗——点某城即切换过去，不用再手输城市名。切城复用 `searchWeatherByCity`（`MainActivity.java:364`）的「用户明确指定某城」语义：失败只回落到**该城自己**的本地缓存、绝不拿别的城市顶替，并发裁定沿用现成的 `uiApplySeq`「最后发起者胜」，收藏夹本身不引入第二套序号。弹窗列表里当前城市带「（当前）」标记、每行右侧 ✕ 取消收藏，底部一行对当前城市一键收藏/取消（无城市可收藏时置灰）。**收藏是纯设备本地态**：`FavoritesStore` 用 SharedPreferences 存一个有序列表，上限 12（满了**拒绝新增**并提示，而非静默淘汰旧收藏——手动挑的城市被悄悄丢掉比直接说“满了”更意外），不入云端、不上服务器；后端是「按城市名取数」的无状态接口，本就无需为收藏加任何东西。可测性做了分层：`parse`/`join`/`plus`/`minus` 是不碰 Context 的纯函数（单测新增 13 例，安卓单测合计 26 例），带 Context 的公开方法只是它们的薄壳；也正因此**没有**用 JSON 存储——`org.json` 在本地 JVM 单测里会抛（Android SDK 桩），用它会把这份可测性锁死。已知取舍：收藏比对的是**字符串**城市名，GPS 逆地理可能解析成区级名（如「黄浦」）而收藏的是「上海」，两者会各占一条，本轮不做行政区划归一。

## 运行方法

### 后端
```bash
cd backend
mvn spring-boot:run      # 默认 :8080；H2 数据落在 backend/data/（不入库）
```
云端部署方式见 `backend/`（systemd `smog.service` + ufw），不在本仓库提交。

### Android
用 Android Studio 打开 `android/` 目录运行。后端基地址由 `android/app/build.gradle` 的 `BuildConfig.BACK_HOST_API` 决定，当前指向云端 HTTPS 实例 `https://118.178.147.156/api/`；如需连本机后端，改成 `http://10.0.2.2:8080/api/`（模拟器）或本机局域网 IP，并临时放开该地址的明文（仅本地调试用）。
