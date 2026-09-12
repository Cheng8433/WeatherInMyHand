package com.smog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层配置：给会触发和风上游请求的接口（/api/weather/**）挂上限流拦截器。
 * 该路径下的 /info 会打到和风（地理编码/实时/空气/逐小时），纳入同一把窗口与额度即可。
 * 窗口固定为 1 分钟；额度经 application.properties 的 rate.limit.* 可调。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final long WINDOW_MILLIS = 60 * 1000L; // 固定 1 分钟窗口

    private final ObjectMapper objectMapper;

    @Value("${rate.limit.per-ip-per-minute:30}")
    private int perIpPerMinute;

    @Value("${rate.limit.global-per-minute:120}")
    private int globalPerMinute;

    public WebConfig(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(perIpPerMinute, globalPerMinute, WINDOW_MILLIS, objectMapper))
                .addPathPatterns("/api/weather/**");
    }
}
