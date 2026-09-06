package com.smog.midwdget;

import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtUtil {

    // 从和风天气控制台获取的 Key ID (凭据ID)
    private static final String kid = "T7WKCV7R8P";
    // 项目ID (Project ID)
    private static final String projectId = "2N8569G9JK";

    // 默认私钥文件名（相对 backend 运行目录）
    private static final String DEFAULT_KEY_FILE = "ed25519-private.pem";

    @Value("${private.pem.path:" + DEFAULT_KEY_FILE + "}")
    private String privatePemPath;   // 可配置：环境变量 PRIVATE_PEM_PATH 或 application.properties

    private volatile String cachedToken;
    private volatile long cachedTokenExpiryMs;

    /**
     * 生成（并缓存）和风天气 JWT。
     * 一次 /api/weather/info 内部会多次调用本方法；token 有效期 1 小时，读盘 + 解析私钥较贵，
     * 因此在距过期 60 秒前直接复用缓存，避免每次请求都重复读 pem / 初始化 KeyFactory。
     * 若运行中轮换了私钥/凭据，需重启或等待 token 过期后才生效。
     */
    public String generateToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < cachedTokenExpiryMs - 60_000L) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken != null && System.currentTimeMillis() < cachedTokenExpiryMs - 60_000L) {
                return cachedToken;
            }
            cachedToken = buildToken();
            cachedTokenExpiryMs = System.currentTimeMillis() + 3600_000L; // 与 token 1h 有效期对齐
            return cachedToken;
        }
    }

    private String buildToken() {
        try {
            Path keyPath = resolvePrivateKeyPath();
            String privateKeyContent = new String(Files.readAllBytes(keyPath))
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] privateKeyBytes = java.util.Base64.getDecoder().decode(privateKeyContent);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            // 构建并返回 JWT
            // 注意：不要设置 issuer(iss) —— 官方文档将 iss 列为“保留字段”，
            // 一旦 payload 带上 iss 会被网关判定为无效 token，返回 401。
            // 只保留: header kid(凭据ID) + payload sub(项目ID)/iat/exp。
            return Jwts.builder()
                    .header().keyId(kid).and()
                    .subject(projectId)    // sub = 项目ID (必须)
                    .issuedAt(new Date())
                    .expiration(Date.from(Instant.now().plusSeconds(3600)))
                    .signWith(privateKey, Jwts.SIG.EdDSA)
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT", e);
        }
    }

    /**
     * 解析私钥文件路径。优先级：配置值(environment PRIVATE_PEM_PATH / application.properties
     * 的 private.pem.path) → 默认文件名。相对路径会同时基于“当前工作目录”与“当前目录/backend”
     * 两个基准尝试，兼容在仓库根目录或 backend 目录下启动两种场景。
     */
    private Path resolvePrivateKeyPath() {
        String given = (privatePemPath == null || privatePemPath.isBlank())
                ? DEFAULT_KEY_FILE : privatePemPath;

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            candidates.add(Paths.get(userDir, given).toString());
            candidates.add(Paths.get(userDir, "backend", given).toString());
        }
        candidates.add(given);

        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("找不到 Ed25519 私钥文件，已尝试：" + candidates
                + "。请将私钥放在上述任一位置，或用环境变量 PRIVATE_PEM_PATH 指定绝对/相对路径。");
    }
}