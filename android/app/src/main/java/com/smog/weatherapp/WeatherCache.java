package com.smog.weatherapp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 最近几次成功天气响应的本地缓存（SharedPreferences，零依赖）。
 * 用途：断网/后端失败时离线兜底，以及启动时先用缓存“秒开”。
 *
 * 有界性：SharedPreferences 是整份 XML 一次性读进内存的，所以这里必须自己设上限——
 * 最多保留 {@link #MAX_CITIES} 个城市（超出按写入时间淘汰最旧的），
 * 且每条缓存超过 {@link #MAX_AGE_MS} 即视为失效并清除，避免把严重过期的天气当实时展示。
 */
public final class WeatherCache {

    private static final String PREFS = "weather_cache";
    private static final String KEY_LAST_CITY = "last_city";
    private static final String KEY_PREFIX = "data_";        // 后接城市名 → 该城快照 JSON
    private static final String KEY_TIME_PREFIX = "time_";   // 后接城市名 → 该条数据的观测时刻

    /** 最多保留的城市份数，超出按写入时间淘汰最旧的。 */
    private static final int MAX_CITIES = 10;
    /** 缓存有效期：超过 24 小时不再当作可用数据。 */
    private static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    private WeatherCache() {
    }

    /**
     * 成功渲染后落一份缓存：key=城市名，同时把“最近城市”指针移到该城。
     *
     * 时效基准取数据自身的 updateTime（= 服务端这次观测的时刻），而不是“写入时刻”：
     * 否则把本地缓存重新渲染一遍也会刷新时间戳，反复冷启动就能让旧缓存永不过期。
     * 因此写入时刻只前进不后退——内容不比已存的更新就直接跳过。
     */
    public static void save(Context context, String city, JSONObject data) {
        if (data == null) {
            return;
        }
        String key = norm(city);
        if (key.isEmpty()) {
            return;
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // 最近城市指针与数据时效无关：只要渲染过就该记住，故不受下面“时间戳不后退”的约束
        sp.edit().putString(KEY_LAST_CITY, key).apply();

        long observedAt = data.optLong("updateTime", 0L);
        long stamp = observedAt > 0L ? observedAt : System.currentTimeMillis();
        long stored = sp.getLong(KEY_TIME_PREFIX + key, 0L);
        if (stored > 0L && stamp <= stored) {
            return;   // 内容不比已存的更新：不动数据与时间戳，避免旧缓存被反复“续命”
        }
        sp.edit()
                .putString(KEY_PREFIX + key, data.toString())
                .putLong(KEY_TIME_PREFIX + key, stamp)
                .apply();
        evictOldest(sp);
    }

    /** 取指定城市的缓存；无、损坏或已超期则返回 null（超期的顺手清掉）。 */
    public static JSONObject get(Context context, String city) {
        String key = norm(city);
        if (key.isEmpty()) {
            return null;
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject data = parse(sp.getString(KEY_PREFIX + key, null));
        if (data == null) {
            return null;
        }
        if (isExpired(sp, key, System.currentTimeMillis())) {
            sp.edit().remove(KEY_PREFIX + key).remove(KEY_TIME_PREFIX + key).apply();
            return null;
        }
        return data;
    }

    /** 最近一次查看的城市名（可能为空）。 */
    public static String getLastCity(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CITY, "");
    }

    /** 取最近一次缓存数据；指针缺失/已失效时退回“仍然有效的最新一份”，都没有则 null。 */
    public static JSONObject getLastData(Context context) {
        JSONObject data = get(context, getLastCity(context));
        if (data != null) {
            return data;
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        String newest = null;
        long newestAt = 0L;
        for (String k : sp.getAll().keySet()) {
            if (!k.startsWith(KEY_PREFIX)) {
                continue;
            }
            String city = k.substring(KEY_PREFIX.length());
            long at = sp.getLong(KEY_TIME_PREFIX + city, 0L);
            if (at <= 0L || now - at > MAX_AGE_MS) {
                continue;
            }
            if (at > newestAt) {
                newestAt = at;
                newest = city;
            }
        }
        return newest == null ? null : get(context, newest);
    }

    /** 写入时刻缺失或已超期。 */
    private static boolean isExpired(SharedPreferences sp, String key, long now) {
        long at = sp.getLong(KEY_TIME_PREFIX + key, 0L);
        return at <= 0L || now - at > MAX_AGE_MS;
    }

    /** 城市份数超过上限时，按写入时间从旧到新淘汰，直到只剩 MAX_CITIES 份。 */
    private static void evictOldest(SharedPreferences sp) {
        Map<String, ?> all = sp.getAll();
        List<String> cities = new ArrayList<>();
        for (String k : all.keySet()) {
            if (k.startsWith(KEY_PREFIX)) {
                cities.add(k.substring(KEY_PREFIX.length()));
            }
        }
        int overflow = cities.size() - MAX_CITIES;
        if (overflow <= 0) {
            return;
        }
        cities.sort(Comparator.comparingLong(c -> sp.getLong(KEY_TIME_PREFIX + c, 0L)));
        SharedPreferences.Editor editor = sp.edit();
        for (int i = 0; i < overflow; i++) {
            String c = cities.get(i);
            editor.remove(KEY_PREFIX + c).remove(KEY_TIME_PREFIX + c);
        }
        editor.apply();
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
