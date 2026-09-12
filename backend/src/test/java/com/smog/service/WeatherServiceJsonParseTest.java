package com.smog.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上游 JSON 解析容错（2026-09-10 加固项的回归测试）。
 *
 * <p>和风偶发把数值字段返回成 {@code ""} / {@code "N/A"}，早前会让 NumberFormatException
 * 冒泡出去、拖垮**整次**解析（一个坏字段毁掉整个城市的数据）。现在口径是：单个字段
 * 降级为 null，其它字段照常解析。
 *
 * <p>这些辅助方法是 static + 包级可见的纯函数，因此无需起 Spring 上下文即可直接覆盖。
 */
class WeatherServiceJsonParseTest {

    private static JsonObject json(String raw) {
        return JsonParser.parseString(raw).getAsJsonObject();
    }

    @Test
    @DisplayName("getDouble：数字与「字符串形式的数字」都能解析")
    void getDoubleParsesNumericValues() {
        assertThat(WeatherService.getDouble(json("{\"v\":12.5}"), "v")).isEqualTo(12.5);
        assertThat(WeatherService.getDouble(json("{\"v\":\"13\"}"), "v")).isEqualTo(13.0);
        assertThat(WeatherService.getDouble(json("{\"v\":-7}"), "v")).isEqualTo(-7.0);
    }

    @Test
    @DisplayName("getDouble：N/A 与空串降级为 null，不再抛异常")
    void getDoubleToleratesNonNumeric() {
        assertThat(WeatherService.getDouble(json("{\"v\":\"N/A\"}"), "v")).isNull();
        assertThat(WeatherService.getDouble(json("{\"v\":\"\"}"), "v")).isNull();
        assertThat(WeatherService.getDouble(json("{\"v\":\"--\"}"), "v")).isNull();
        assertThat(WeatherService.getDouble(json("{\"v\":\"abc\"}"), "v")).isNull();
    }

    @Test
    @DisplayName("getDouble：字段缺失、为 null 或非标量时返回 null")
    void getDoubleRejectsMissingAndNonPrimitive() {
        assertThat(WeatherService.getDouble(json("{}"), "v")).isNull();
        assertThat(WeatherService.getDouble(json("{\"v\":null}"), "v")).isNull();
        assertThat(WeatherService.getDouble(json("{\"v\":{\"value\":1}}"), "v")).isNull();
        assertThat(WeatherService.getDouble(json("{\"v\":[1,2]}"), "v")).isNull();
    }

    @Test
    @DisplayName("getInt：可解析则返回，非数字降级为 null")
    void getIntBehavesLikeGetDouble() {
        assertThat(WeatherService.getInt(json("{\"v\":1008}"), "v")).isEqualTo(1008);
        assertThat(WeatherService.getInt(json("{\"v\":\"42\"}"), "v")).isEqualTo(42);
        assertThat(WeatherService.getInt(json("{\"v\":\"N/A\"}"), "v")).isNull();
        assertThat(WeatherService.getInt(json("{\"v\":\"\"}"), "v")).isNull();
        assertThat(WeatherService.getInt(json("{}"), "v")).isNull();
        assertThat(WeatherService.getInt(json("{\"v\":[1]}"), "v")).isNull();
    }

    @Test
    @DisplayName("getString：标量取值，非标量/缺失为 null")
    void getStringOnlyAcceptsPrimitive() {
        assertThat(WeatherService.getString(json("{\"v\":\"晴\"}"), "v")).isEqualTo("晴");
        assertThat(WeatherService.getString(json("{\"v\":123}"), "v")).isEqualTo("123");
        assertThat(WeatherService.getString(json("{\"v\":{\"a\":1}}"), "v")).isNull();
        assertThat(WeatherService.getString(json("{}"), "v")).isNull();
    }

    @Test
    @DisplayName("getJsonObject / getJsonArray：类型不符时返回 null 而不是抛异常")
    void structuredAccessorsAreTypeSafe() {
        JsonObject withObject = json("{\"v\":{\"a\":1}}");
        assertThat(WeatherService.getJsonObject(withObject, "v")).isNotNull();
        assertThat(WeatherService.getJsonObject(withObject, "v").get("a").getAsInt()).isEqualTo(1);

        JsonObject withScalar = json("{\"v\":1}");
        assertThat(WeatherService.getJsonObject(withScalar, "v")).isNull();
        assertThat(WeatherService.getJsonArray(withScalar, "v")).isNull();

        JsonArray array = WeatherService.getJsonArray(json("{\"v\":[1,2,3]}"), "v");
        assertThat(array).isNotNull();
        assertThat(array.size()).isEqualTo(3);

        assertThat(WeatherService.getJsonObject(json("{}"), "v")).isNull();
        assertThat(WeatherService.getJsonArray(json("{}"), "v")).isNull();
    }

    @Test
    @DisplayName("一个坏字段不影响同级字段——这正是加固前的崩溃点")
    void oneBadFieldDoesNotBreakSiblings() {
        JsonObject obj = json("{\"temp\":\"N/A\",\"humidity\":\"55\",\"pressure\":1008,\"text\":\"晴\"}");

        assertThat(WeatherService.getDouble(obj, "temp")).isNull();          // 坏字段降级
        assertThat(WeatherService.getDouble(obj, "humidity")).isEqualTo(55.0); // 同级字段照常
        assertThat(WeatherService.getInt(obj, "pressure")).isEqualTo(1008);
        assertThat(WeatherService.getString(obj, "text")).isEqualTo("晴");
    }
}
