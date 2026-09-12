package com.smog.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流拦截器行为锁定。
 *
 * <p>其中 {@link #spoofedXForwardedForIsIgnored()} 是 2026-09-12 安全加固的回归测试：
 * 历史缺陷是 {@code clientIp()} 自行读 X-Forwarded-For 首段，而线上 nginx 用的是
 * {@code $proxy_add_x_forwarded_for}（**追加**，客户端自带的段会留在最左边），
 * 两者叠加等于把限流 key 交给调用方——每请求换一个假 IP 即可绕开「按 IP 30 次/分」，
 * 只剩全局额度兜底，还能伪造任意 IP 精准封掉别人。
 * 现在的口径是只认 {@code getRemoteAddr()}（由 Tomcat RemoteIpValve 在可信代理前提下解析好）。
 */
class RateLimitInterceptorTest {

    private static final long WINDOW_MS = 60_000L;

    private static RateLimitInterceptor interceptor(int perIpLimit, int globalLimit) {
        return new RateLimitInterceptor(perIpLimit, globalLimit, WINDOW_MS, new ObjectMapper());
    }

    private static MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/weather/info");
        request.setRemoteAddr(ip);
        return request;
    }

    /** 模拟一次请求，返回是否被放行。 */
    private static boolean pass(RateLimitInterceptor interceptor, MockHttpServletRequest request) throws Exception {
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("同一 IP 超过窗口额度后被拦")
    void blocksAfterPerIpLimit() throws Exception {
        RateLimitInterceptor interceptor = interceptor(2, 100);

        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isTrue();
        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isTrue();
        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isFalse();
    }

    @Test
    @DisplayName("按 IP 的额度互相独立：A 被限满不影响 B")
    void perIpQuotaIsIndependent() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 100);

        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isTrue();
        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isFalse();
        assertThat(pass(interceptor, requestFrom("2.2.2.2"))).isTrue();
    }

    @Test
    @DisplayName("全局额度跨 IP 生效，兜住「换 IP 刷接口」")
    void globalQuotaAppliesAcrossIps() throws Exception {
        RateLimitInterceptor interceptor = interceptor(100, 2);

        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isTrue();
        assertThat(pass(interceptor, requestFrom("2.2.2.2"))).isTrue();
        assertThat(pass(interceptor, requestFrom("3.3.3.3"))).isFalse();
    }

    @Test
    @DisplayName("被单 IP 拦住的不消耗全局额度（否则刷单个 IP 就能拖垮所有人）")
    void rejectedRequestDoesNotConsumeGlobalBudget() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 3);

        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isTrue();   // 全局 1
        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isFalse();  // 单 IP 拦下，全局不计数
        assertThat(pass(interceptor, requestFrom("2.2.2.2"))).isTrue();   // 全局 2
        assertThat(pass(interceptor, requestFrom("3.3.3.3"))).isTrue();   // 全局 3
        assertThat(pass(interceptor, requestFrom("4.4.4.4"))).isFalse();  // 全局耗尽
    }

    @Test
    @DisplayName("伪造的 X-Forwarded-For 不参与计数：限流 key 只认 getRemoteAddr")
    void spoofedXForwardedForIsIgnored() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 100);

        MockHttpServletRequest first = requestFrom("9.9.9.9");
        first.addHeader("X-Forwarded-For", "1.1.1.1");
        assertThat(pass(interceptor, first)).isTrue();

        MockHttpServletRequest second = requestFrom("9.9.9.9");
        second.addHeader("X-Forwarded-For", "2.2.2.2");   // 同一来源换个假 IP
        assertThat(pass(interceptor, second))
                .as("同一 remoteAddr 换 XFF 仍应被限住，否则该头可用来绕过按 IP 限流")
                .isFalse();
    }

    @Test
    @DisplayName("超限仍回 HTTP 200 + {success:false,message}，与统一错误契约一致")
    void overLimitKeepsUnifiedContract() throws Exception {
        RateLimitInterceptor interceptor = interceptor(1, 100);
        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isTrue();

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(requestFrom("1.1.1.1"), response, new Object())).isFalse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).contains("application/json");
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.get("success").asBoolean()).isFalse();
        // 顺带锁住中文文案的编码：源文件/序列化任一环节退化成非 UTF-8，这里会立刻红
        assertThat(body.get("message").asText()).contains("频繁");
    }

    @Test
    @DisplayName("跨窗口后额度重置")
    void quotaResetsInNextWindow() throws Exception {
        // 窗口取 30ms，睡 50ms 必然跨窗（比固定 60s 窗口真实可测）
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, 100, 30L, new ObjectMapper());

        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isTrue();
        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isFalse();

        Thread.sleep(50L);

        assertThat(pass(interceptor, requestFrom("1.1.1.1"))).isTrue();
    }
}
