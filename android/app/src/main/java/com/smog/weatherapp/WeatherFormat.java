package com.smog.weatherapp;

import android.content.res.Resources;

import java.util.Locale;

/**
 * 展示层格式化：天气 → emoji、风向英文缩写 → 中文、污染物 → 中文名、AQI 分级色/文案。
 *
 * 除 emoji 与 SI 单位外，所有面向用户的文字都取自 strings.xml / arrays.xml（见 aqi_levels、
 * aqi_assessments、aqi_health_advice、wind_dirs、pollutant_*），方法因此需要传入 Resources。
 */
public final class WeatherFormat {

    private WeatherFormat() {
    }

    /**
     * 天气现象分类，供动效背景等需要区分“晴/多云/雨/雪…”的场景使用。
     * 判断顺序与 {@link #emojiFor} 保持一致。
     */
    public enum Kind {
        THUNDER, SNOW, RAIN, FOG, WIND, OVERCAST, PARTLY_CLOUDY, SUNNY, DEFAULT
    }

    /** 天气文本 → 现象分类（雷→雪→雨→雾霾→风→阴→云→晴）。 */
    public static Kind kindOf(String weather) {
        if (weather == null || weather.isEmpty()) {
            return Kind.DEFAULT;
        }
        if (weather.contains("雷")) return Kind.THUNDER;
        if (weather.contains("雪")) return Kind.SNOW;
        if (weather.contains("雨")) return Kind.RAIN;
        if (weather.contains("雾") || weather.contains("霾")) return Kind.FOG;
        if (weather.contains("风")) return Kind.WIND;
        if (weather.contains("阴")) return Kind.OVERCAST;
        if (weather.contains("云")) return Kind.PARTLY_CLOUDY;
        if (weather.contains("晴")) return Kind.SUNNY;
        return Kind.DEFAULT;
    }

    /** 天气文本 → 大图标 emoji（按关键字先后判断）。 */
    public static String emojiFor(String weather) {
        if (weather == null || weather.isEmpty()) {
            return "🌤";
        }
        if (weather.contains("雷")) return "⛈";
        if (weather.contains("雪")) return "❄️";
        if (weather.contains("雨")) return "🌧";
        if (weather.contains("雾") || weather.contains("霾")) return "🌫";
        if (weather.contains("风")) return "🌬";
        if (weather.contains("阴")) return "☁️";
        if (weather.contains("云")) return "⛅";
        if (weather.contains("晴")) return "☀️";
        return "🌤";
    }

    /** 和风 compass 缩写的枚举顺序；下标与 arrays.xml 的 wind_dirs 一一对应。 */
    private static final String[] COMPASS = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    /** 和风 compass 缩写 → 中文风，如 "SE" → "东南风"；无法识别原样返回。 */
    public static String windDirCn(Resources res, String code) {
        if (code == null || code.trim().isEmpty()) {
            return "--";
        }
        String c = code.trim();
        // 后端可能已直接给中文（如“东南风”或“东南”）
        if (c.contains("风")) {
            return c;
        }
        String[] names = res.getStringArray(R.array.wind_dirs);
        for (int i = 0; i < COMPASS.length && i < names.length; i++) {
            if (COMPASS[i].equalsIgnoreCase(c)) {
                return names[i];
            }
        }
        return code;
    }

    /** 污染物 code → 中文名。 */
    public static String pollutantName(Resources res, String code) {
        if (code == null || code.isEmpty()) {
            return "--";
        }
        switch (code.toLowerCase(Locale.ROOT)) {
            case "pm2p5": return res.getString(R.string.pollutant_pm2p5);
            case "pm10": return res.getString(R.string.pollutant_pm10);
            case "no2": return res.getString(R.string.pollutant_no2);
            case "so2": return res.getString(R.string.pollutant_so2);
            case "co": return res.getString(R.string.pollutant_co);
            case "o3": return res.getString(R.string.pollutant_o3);
            default: return code.toUpperCase(Locale.ROOT);
        }
    }

    /** 污染物是否需要显示单位（μg/m³），CO 用 mg/m³。 */
    public static String pollutantUnit(String code) {
        return "co".equalsIgnoreCase(code == null ? "" : code) ? "mg/m³" : "μg/m³";
    }

    // ---- AQI 分级 ----
    private static int grade(int aqi) {
        if (aqi <= 50) return 0;
        if (aqi <= 100) return 1;
        if (aqi <= 150) return 2;
        if (aqi <= 200) return 3;
        if (aqi <= 300) return 4;
        return 5;
    }

    /** AQI 分级色（跨主题一致，深浅主题下都尽量可读）。 */
    public static int aqiColor(int aqi) {
        switch (grade(aqi)) {
            case 0: return 0xFF2E9E5B;
            case 1: return 0xFFE0A300;
            case 2: return 0xFFF08500;
            case 3: return 0xFFE0393A;
            case 4: return 0xFF9C48B6;
            default: return 0xFF7B1E4E;
        }
    }

    /** AQI 数值的底色：同色半透明，用于浅色圆底。 */
    public static int aqiTint(int aqi) {
        int color = aqiColor(aqi);
        return (color & 0x00FFFFFF) | 0x24000000;
    }

    /** 取分级数组的第 grade(aqi) 项，长度不足时原样返回兜底值。 */
    private static String gradeItem(Resources res, int arrayId, int aqi, String fallback) {
        String[] items = res.getStringArray(arrayId);
        int g = grade(aqi);
        return g < items.length ? items[g] : fallback;
    }

    /** AQI → 中文等级（后端给了 airQuality 时优先用后端文本）。 */
    public static String aqiLevel(Resources res, int aqi) {
        return gradeItem(res, R.array.aqi_levels, aqi, "--");
    }

    /** AQI → 综合评估一句话。 */
    public static String assessment(Resources res, int aqi) {
        return gradeItem(res, R.array.aqi_assessments, aqi, "");
    }

    /** AQI → 健康建议（换行条目）。 */
    public static String healthAdvice(Resources res, int aqi) {
        return gradeItem(res, R.array.aqi_health_advice, aqi, "");
    }
}
