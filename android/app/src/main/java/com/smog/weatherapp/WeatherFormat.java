package com.smog.weatherapp;

import java.util.Locale;

/**
 * 展示层格式化：天气 → emoji、风向英文缩写 → 中文、污染物 → 中文名、AQI 分级色/文案。
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

    private static final String[][] COMPASS = {
            {"N", "北"}, {"NNE", "北北东"}, {"NE", "东北"}, {"ENE", "东北东"},
            {"E", "东"}, {"ESE", "东南东"}, {"SE", "东南"}, {"SSE", "南南东"},
            {"S", "南"}, {"SSW", "南南西"}, {"SW", "西南"}, {"WSW", "西南西"},
            {"W", "西"}, {"WNW", "西北西"}, {"NW", "西北"}, {"NNW", "北北西"}
    };

    /** 和风 compass 缩写 → 中文风，如 "SE" → "东南风"；无法识别原样返回。 */
    public static String windDirCn(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "--";
        }
        String c = code.trim();
        // 后端可能已直接给中文（如“东南风”或“东南”）
        if (c.contains("风")) {
            return c;
        }
        for (String[] pair : COMPASS) {
            if (pair[0].equalsIgnoreCase(c)) {
                return pair[1] + "风";
            }
        }
        return code;
    }

    /** 污染物 code → 中文名。 */
    public static String pollutantName(String code) {
        if (code == null || code.isEmpty()) {
            return "--";
        }
        switch (code.toLowerCase(Locale.ROOT)) {
            case "pm2p5": return "颗粒物 PM2.5";
            case "pm10": return "可吸入颗粒物 PM10";
            case "no2": return "二氧化氮 NO₂";
            case "so2": return "二氧化硫 SO₂";
            case "co": return "一氧化碳 CO";
            case "o3": return "臭氧 O₃";
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

    /** AQI → 中文等级（后端给了 airQuality 时优先用后端文本）。 */
    public static String aqiLevel(int aqi) {
        switch (grade(aqi)) {
            case 0: return "优";
            case 1: return "良";
            case 2: return "轻度污染";
            case 3: return "中度污染";
            case 4: return "重度污染";
            default: return "严重污染";
        }
    }

    /** AQI → 综合评估一句话。 */
    public static String assessment(int aqi) {
        switch (grade(aqi)) {
            case 0: return "空气质量非常好，适合所有户外活动。";
            case 1: return "空气质量可接受，敏感人群请注意防护。";
            case 2: return "轻度污染，敏感人群应减少户外活动。";
            case 3: return "中度污染，建议减少户外活动，外出佩戴口罩。";
            case 4: return "重度污染，避免户外活动，尽量待在室内。";
            default: return "严重污染，请尽可能留在室内并开启空气净化。";
        }
    }

    /** AQI → 健康建议（换行条目）。 */
    public static String healthAdvice(int aqi) {
        switch (grade(aqi)) {
            case 0:
                return "• 可正常进行户外活动\n• 适合开窗通风";
            case 1:
                return "• 敏感人群可佩戴口罩\n• 减少长时间剧烈运动";
            case 2:
                return "• 敏感人群避免长时间户外活动\n• 建议佩戴口罩\n• 减少剧烈运动";
            case 3:
                return "• 外出佩戴口罩\n• 减少户外活动\n• 避免室外锻炼";
            case 4:
                return "• 尽量待在室内\n• 关闭门窗\n• 使用空气净化器";
            default:
                return "• 避免一切户外活动\n• 关闭门窗并使用空气净化器\n• 必须外出时佩戴防霾口罩";
        }
    }
}
