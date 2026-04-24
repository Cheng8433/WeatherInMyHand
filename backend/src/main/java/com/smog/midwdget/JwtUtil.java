package com.smog.midwdget;

import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtUtil {

    // 从和风天气控制台获取的 Key ID (凭据ID)
    private static final String kid = "K95D3VE8WH";
    // 项目ID (Project ID)
    private static final String projectId = "2N8569G9JK";

    public String generateToken() {
        try {
            String privateKeyContent = new String(Files.readAllBytes(Paths.get("E:\\GitHub\\WeatherInMyHand\\backend\\ed25519-private.pem")))
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] privateKeyBytes = java.util.Base64.getDecoder().decode(privateKeyContent);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("Ed25519");
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            // 构建并返回 JWT (添加 .subject 字段)
            return Jwts.builder()
                    .header().keyId(kid).and()
                    .issuer(kid)           // 签发者 = 凭据ID
                    .subject(projectId)    // 主题 = 项目ID (必须)
                    .issuedAt(new Date())
                    .expiration(Date.from(Instant.now().plusSeconds(3600)))
                    .signWith(privateKey, Jwts.SIG.EdDSA)
                    .compact();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate JWT", e);
        }
    }
}