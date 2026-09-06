package com.smog.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 上游 API / 业务失败（和风天气错误、城市未找到、无效参数等）。
     * 统一返回 HTTP 200 + {success:false, message}，保持与 Android 端的既有契约：
     * 前端先判 response.isSuccessful() 再读 success 布尔，200 才能让真实错误文案透传到 toast。
     */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> handleIOException(IOException e) {
        log.warn("请求处理失败: {}", e.getMessage());
        return error(e.getMessage());
    }

    /** 兜底异常（含 LocationService 包装的 RuntimeException 等）。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> handleException(Exception e) {
        log.error("未预期的服务端异常", e);
        return error(e.getMessage());
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", message != null ? message : "服务器内部错误");
        return result;
    }
}
