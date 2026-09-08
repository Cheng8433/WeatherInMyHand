package com.smog.weatherapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * 最近一次成功天气响应的本地缓存（SharedPreferences，零依赖）。
 * 用途：断网/后端失败时离线兜底，以及启动时先用缓存“秒开”。
 * 仅存 JSON 字符串，无过期策略——显示时由调用方决定是否兜底，避免给用户造成“实时”错觉。
 */
public final class WeatherCache {

    private static final String PREFS = "weather_cache";
    private static final String KEY_LAST_CITY = "last_city";
    private static final String KEY_PREFIX = "data_";   // 后接城市名，实现按城市各存一份

    private WeatherCache() {
    }

    /** 成功渲染后落一份缓存：key=城市名，同时把“最近城市”指针移到该城。 */
    public static void save(Context context, String city, JSONObject data) {
        if (data == null) {
            return;
        }
        String key = norm(city);
        if (key.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_PREFIX + key, data.toString());
        editor.putString(KEY_LAST_CITY, key);
        editor.apply();
    }

    /** 取指定城市的缓存；无则 null。 */
    public static JSONObject get(Context context, String city) {
        String key = norm(city);
        if (key.isEmpty()) {
            return null;
        }
        return parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_PREFIX + key, null));
    }

    /** 最近一次查看的城市名（可能为空）。 */
    public static String getLastCity(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CITY, "");
    }

    /** 取最近一次缓存数据；指针缺失/数据异常时退回任意一份（含城市名的），都没有则 null。 */
    public static JSONObject getLastData(Context context) {
        JSONObject data = get(context, getLastCity(context));
        if (data != null) {
            return data;
        }
        Map<String, ?> all = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX)) {
                JSONObject fallback = parse(entry.getValue() instanceof String
                        ? (String) entry.getValue() : null);
                if (fallback != null && !fallback.optString("cityName", "").isEmpty()) {
                    return fallback;
                }
            }
        }
        return null;
    }

    private static JSONObject parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String norm(String city) {
        return city == null ? "" : city.trim();
    }
}
