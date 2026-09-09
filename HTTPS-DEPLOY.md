# HTTPS 上线部署（HTTP → HTTPS 切换）

> 仓库配套：Android「掌中天气」 / Spring Boot backend。
> 落地状态（2026-09-09）：本仓库已按**纯公网 IP + ZeroSSL IP 证书 + nginx 反代 443→8080** 完成 HTTPS 上线——BACK_HOST_API=https://118.178.147.156/api/、Manifest 关闭 cleartext、App 内置 Sectigo R46 公共根兜底系统信任库较旧的设备。证书 2026-12-07 到期（90 天短证），到期前需续签并重配 nginx。下方 A/B（域名 + 备案）路线保留，供将来申请域名后平滑切换域名端点。

---

## 1. 为什么绕不开一个域名

- 公开 CA（Let's Encrypt / 阿里云免费证书）**不为纯 IP 签发证书**。没有域名就没有受浏览器/Android 信任的 HTTPS，哪怕 Caddy 的「自动 HTTPS」也一样要域名。
- 当前 `118.178.147.156` 是阿里云**大陆**实例：域名解析到它上面走 80/443，必须先完成 **ICP 备案**。
- 自签名证书不可行：未受信任的证书在正式分发的 App 里会直接握手失败（用户没法一个个装根证书）。

### 两条路线（选一即可）

| 路线 | 适用场景 | 需要做的 | 预计耗时 |
|---|---|---|---|
| **A 国内合规** | 面向国内用户、要最低延迟与合规背书 | 买域名 → 阿里云 ICP 备案 → 证书 → 反代 | 备案约 7–20 天（大部分是等待） |
| **B 境外服务器免备案** | 想马上有一个正式 HTTPS 上线点 | 买域名 + 一台境外轻量服务器（如香港）+ Caddy 自动签发 | 当天可通 |

两条路线**服务器侧配置完全一致**（域名 + 反代到本地 8080），本文件通用。切换 App 端点只差一个域名，改一处即可。

---

## 2. 通用前置：买域名 + 解析

1. 注册一个域名（示例：`weather.example.com`，下文所有 `你的域名` 都替换成它）。
2. 添加 A 记录，解析到服务器公网 IP：
   - 路线 A：`118.178.147.156`（当前阿里云大陆 ECS）；
   - 路线 B：新开的境外服务器公网 IP。
3. 路线 A 需先在阿里云提交 ICP 备案，**备案通过后**域名才能在国内实例上正常服务 80/443。

> 建议：备案周期长，**现在就买域名 + 启动备案**，其余步骤做好后随时代码一键切换。

---

## 3. 服务器：只暴露 80/443，反代到本地 8080

### 3.0 端口收敛（重要）

先在云控制台**安全组/防火墙只放行 80、443**，把 8080 关掉对公网的暴露。
好处：① 绕开明文直连；② 限流看到的是真实用户 IP（见 §3.3），不会被直连伪造 XFF 绕过。

### 3.1 方案 A：Caddy（推荐，证书自动签发 + 自动续期）

`/etc/caddy/Caddyfile`：

```caddyfile
你的域名 {
    reverse_proxy 127.0.0.1:8080

    encode gzip

    header {
        X-Content-Type-Options nosniff
        X-Frame-Options SAMEORIGIN
        Referrer-Policy no-referrer
    }
}
```

安装并启动（以 Debian/Ubuntu 为例；CentOS 系把 `apt` 换成 `dnf`，包名同样叫 `caddy`）：

```bash
apt update && apt install -y caddy
systemctl enable --now caddy
caddy validate --config /etc/caddy/Caddyfile   # 先验证再重启
systemctl restart caddy
```

Caddy 会在域名解析生效后自动向 Let's Encrypt 申请证书，并在到期前自动续期。

### 3.2 方案 B：Nginx（证书手动管理，适合已习惯 Nginx 时）

先给域名申请证书（阿里云免费 DV 证书，或 acme.sh 签 Let's Encrypt），拿到 `fullchain.pem / privkey.pem` 后：

```nginx
server {
    listen 80;
    server_name 你的域名;
    # HTTP 全部跳 HTTPS
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    http2 on;
    server_name 你的域名;

    ssl_certificate     /etc/nginx/ssl/你的域名/fullchain.pem;
    ssl_certificate_key /etc/nginx/ssl/你的域名/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;

    # 把真实客户端 IP 交给后端（RateLimitInterceptor 读取 X-Forwarded-For 首段）
    proxy_set_header X-Forwarded-For  $proxy_add_x_forwarded_for;
    proxy_set_header Host             $host;
    proxy_set_header X-Forwarded-Proto https;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
    }
}
```

### 3.3 反代后确认的两个后端事实（已核对源码，无需改动）

- **限流取的是真实 IP**：`RateLimitInterceptor.clientIp()` 先读 `X-Forwarded-For` 首段，由反代注入即正确。
- **路径原样透传**：App 请求的是 `https://你的域名/api/...`，反代把整条 URI 透传给 Spring Boot，后端 Controller 的 `/api` 前缀不变，**无需任何 rewrite**。

---

## 4. 验证服务器 HTTPS 就绪

```bash
curl -I https://你的域名/api/weather/info?city=北京
# 期望：HTTP/1.1 200、证书链完整（无 SSL 警告）
```

浏览器打开 `https://你的域名/api/…` 应显示绿色锁。**确认能通后再改 Android，顺序不能反。**

---

## 5. Android 上线改动（共两处 + 重新签名发布）

> ⚠️ 必须在 §4 服务器 HTTPS 可访问之后再做，否则发布版会连不上后端（明文通道已被关闭）。

1. **改后端地址** —— `android/app/build.gradle` 的 `buildConfigField`（当前约第 26 行）：

   ```gradle
   buildConfigField "String", "BACK_HOST_API", "\"https://你的域名/api/\""
   ```

   （保留尾部 `/api/`；debug / release 共用同一字段，域名就绪后开发态也走 HTTPS，不再需要明文。）

2. **关掉明文开关** —— `android/app/src/main/AndroidManifest.xml`：删除
   `android:usesCleartextTraffic="true"` 这个属性，并同步更新顶部那段「合规说明」注释。
   （Android 9+ 默认即禁止明文流量，删掉等于强制 HTTPS，从根上消除合规风险。）

   - 可选（仅当你需要在 debug 里连本地/局域网明文后端时才做）：新增
     `app/src/debug/res/xml/network_security_config.xml` 只放行 `127.0.0.1` 等调试地址，
     `main` 里则引一份全局禁明文的安全配置。**主流程用不到，先别加。**

3. **重新签名发布**：

   ```bash
   cd android
   export JAVA_HOME="C:/Program Files/Java/jdk-21"
   export ANDROID_HOME="E:/IDE Sources/Android SDK"
   ./gradlew :app:assembleRelease
   ```

   发布版 APK 上传前先在真机装一遍验收（见 §6）。

---

## 6. 真机验收清单

- [ ] 冷启动：顶栏城市 + 今天数据正常显示（流量为 HTTPS）
- [ ] 刷新、切换 3 个 Tab、换主题、手动搜索、GPS 定位各跑一遍
- [ ] 无 `Cleartext HTTP traffic not permitted` 类崩溃
- [ ] 隐私页（撤回同意后重登）、退出确认正常
- [ ] 连几次服务器 `curl -I`，确认无证书告警、无跳 http 的残留请求

---

## 7. 还没做的两件事（避免踩坑）

- ❌ **别在服务器 HTTPS 就绪前**改 Android 地址/删明文开关——会立刻断掉现有直连 IP 的版本。
- ❌ **别用自签名证书**凑 HTTPS——正式分发的 App 无法让用户信任它。
