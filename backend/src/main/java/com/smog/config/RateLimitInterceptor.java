package com.smog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单内存限流：对会触发和风上游请求的接口做【固定窗口】限流，两层：
 *   1) 按客户端 IP 限制（防某个来源刷接口）
 *   2) 全局限制（防多个来源合计把和风配额烧光）
 * 正常单手机低频使用远低于默认值；纯内存、无外部依赖，进程重启即清零。
 * 超限时仍回 HTTP 200 + {success:false, message}，保持与 GlobalExceptionHandler 的统一契约，
 * 让 Android 端能正常把它当作一次业务错误 toast 展示，而不是“服务器错误 5xx”。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);
    private static final String GLOBAL_KEY = "$global$";

    private final FixedWindow perIpWindow;
    private final FixedWindow globalWindow;
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(int perIpPerWindow, int globalPerWindow, long windowMillis, ObjectMapper objectMapper) {
        this.perIpWindow = new FixedWindow(perIpPerWindow, windowMillis);
        this.globalWindow = new FixedWindow(globalPerWindow, windowMillis);
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        long now = System.currentTimeMillis();
        String ip = clientIp(request);

        // 先判单 IP，再判全局：被单 IP 拦住的不再消耗全局额度
        boolean allowed = perIpWindow.tryAcquire(ip, now) && globalWindow.tryAcquire(GLOBAL_KEY, now);
        if (allowed) {
            return true;
        }

        log.warn("触发限流：IP={}, URI={}", ip, request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "请求过于频繁，请稍后再试");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }

    /** 固定窗口计数：同窗口内每个 key 至多放行 limit 次；越界请求只计数不再放行，次窗口自动重置 */
    private static final class FixedWindow {
        private final int limit;
        private final long windowMillis;
        // key -> [windowStart, count]；同一窗口内复用数组，窗口变化时整段重置
        private final Map<String, long[]> buckets = new ConcurrentHashMap<>();

        FixedWindow(int limit, long windowMillis) {
            this.limit = limit;
            this.windowMillis = windowMillis;
        }

        boolean tryAcquire(String key, long now) {
            final long windowStart = now - (now % windowMillis);
            long[] bucket = buckets.compute(key, (k, cur) -> {
                if (cur == null || cur[0] != windowStart) {
                    return new long[]{windowStart, 1L};
                }
                cur[1] = cur[1] + 1L;
                return cur;
            });
            shrinkIfNeeded(now, windowStart);
            return bucket[1] <= limit;
        }

        /** 防止恶意随机 IP（或伪造 XFF）让 map 无限增长：过大时只保留当前窗口 */
        private void shrinkIfNeeded(long now, long windowStart) {
            if (buckets.size() > 1000) {
                buckets.entrySet().removeIf(e -> e.getValue()[0] != windowStart);
            }
        }
    }
}
