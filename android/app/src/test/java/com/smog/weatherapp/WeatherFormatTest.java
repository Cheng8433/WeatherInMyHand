package com.smog.weatherapp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link WeatherFormat} 的纯函数单测：这些方法不碰 Context/Resources，因此是普通 JVM 测试
 * （跑法 {@code ./gradlew :app:testDebugUnitTest}，不需要模拟器）。
 *
 * <p>钉住两类真实风险：
 * <ol>
 *   <li><b>关键字判定顺序</b>——"雷阵雨"必须是雷不是雨、"雨夹雪"必须是雪不是雨。
 *       顺序错了不报编译错，只让界面显示错的图标，肉眼很难发现；</li>
 *   <li><b>kindOf 与 emojiFor 是两张手写的、几乎一模一样的关键字阶梯</b>（一张返回枚举、
 *       一张返回图标，复制粘贴改出来的）。任一边改了另一边忘了改，就会出现
 *       "图标是雨、首页动效天空出太阳"的矛盾——见 {@link #kindAndEmojiNeverDriftApart()}。</li>
 * </ol>
 *
 * <p>注意：本文件含 emoji 字面量，依赖 AGP 默认的 UTF-8 源码编码（{@code compileOptions.encoding}
 * 默认即 UTF-8，此处未显式覆盖）。
 */
public class WeatherFormatTest {

    private static final String DEFAULT_EMOJI = "🌤";

    // ==================== emojiFor：判定顺序 ====================

    @Test
    public void emojiForPrefersThunderOverRain() {
        assertEquals("⛈", WeatherFormat.emojiFor("雷阵雨"));
        assertEquals("⛈", WeatherFormat.emojiFor("雷"));
    }

    @Test
    public void emojiForPrefersSnowOverRain() {
        assertEquals("❄️", WeatherFormat.emojiFor("雨夹雪"));
        assertEquals("❄️", WeatherFormat.emojiFor("小雪"));
    }

    @Test
    public void emojiForCoversEachCondition() {
        assertEquals("🌧", WeatherFormat.emojiFor("中雨"));
        assertEquals("🌫", WeatherFormat.emojiFor("雾"));
        assertEquals("🌫", WeatherFormat.emojiFor("霾"));
        assertEquals("🌬", WeatherFormat.emojiFor("大风"));
        assertEquals("☁️", WeatherFormat.emojiFor("阴"));
        assertEquals("⛅", WeatherFormat.emojiFor("多云"));
        assertEquals("☀️", WeatherFormat.emojiFor("晴"));
    }

    @Test
    public void emojiForFallsBackToDefault() {
        assertEquals(DEFAULT_EMOJI, WeatherFormat.emojiFor(null));
        assertEquals(DEFAULT_EMOJI, WeatherFormat.emojiFor(""));
        assertEquals(DEFAULT_EMOJI, WeatherFormat.emojiFor("未知现象"));
    }

    // ==================== kindOf：同一套顺序 ====================

    @Test
    public void kindOfUsesTheSameKeywordLadder() {
        assertEquals(WeatherFormat.Kind.THUNDER, WeatherFormat.kindOf("雷阵雨"));
        assertEquals(WeatherFormat.Kind.SNOW, WeatherFormat.kindOf("雨夹雪"));
        assertEquals(WeatherFormat.Kind.RAIN, WeatherFormat.kindOf("中雨"));
        assertEquals(WeatherFormat.Kind.FOG, WeatherFormat.kindOf("霾"));
        assertEquals(WeatherFormat.Kind.WIND, WeatherFormat.kindOf("大风"));
        assertEquals(WeatherFormat.Kind.OVERCAST, WeatherFormat.kindOf("阴"));
        // "晴转多云"同时含「云」与「晴」，顺序规定云在前
        assertEquals(WeatherFormat.Kind.PARTLY_CLOUDY, WeatherFormat.kindOf("晴转多云"));
        assertEquals(WeatherFormat.Kind.SUNNY, WeatherFormat.kindOf("晴"));
        assertEquals(WeatherFormat.Kind.DEFAULT, WeatherFormat.kindOf(null));
        assertEquals(WeatherFormat.Kind.DEFAULT, WeatherFormat.kindOf(""));
        assertEquals(WeatherFormat.Kind.DEFAULT, WeatherFormat.kindOf("冰雹"));
    }

    /** kindOf 与 emojiFor 只许同时识别或同时不识别，防止两张阶梯漂移。 */
    @Test
    public void kindAndEmojiNeverDriftApart() {
        String[] samples = {
                "雷阵雨", "雷", "小雪", "雨夹雪", "中雨", "大雨", "雾", "霾", "大风",
                "阴", "多云", "晴转多云", "晴", "冰雹", "浮尘", "未知现象", ""
        };
        for (String weather : samples) {
            boolean kindIsDefault = WeatherFormat.kindOf(weather) == WeatherFormat.Kind.DEFAULT;
            boolean emojiIsDefault = DEFAULT_EMOJI.equals(WeatherFormat.emojiFor(weather));
            assertEquals("天气【" + weather + "】的分类与图标判定应一致（检查 kindOf/emojiFor 的判断顺序是否同步改了）",
                    emojiIsDefault, kindIsDefault);
        }
    }

    // ==================== AQI 分级 ====================

    private static final int GREEN = 0xFF2E9E5B;
    private static final int YELLOW = 0xFFE0A300;
    private static final int ORANGE = 0xFFF08500;
    private static final int RED = 0xFFE0393A;
    private static final int PURPLE = 0xFF9C48B6;
    private static final int MAROON = 0xFF7B1E4E;

    @Test
    public void aqiColorSwitchesGradeExactlyAtBoundaries() {
        assertEquals(GREEN, WeatherFormat.aqiColor(0));
        assertEquals(GREEN, WeatherFormat.aqiColor(50));    // 50 仍为一级
        assertEquals(YELLOW, WeatherFormat.aqiColor(51));   // 51 跳级
        assertEquals(YELLOW, WeatherFormat.aqiColor(100));
        assertEquals(ORANGE, WeatherFormat.aqiColor(101));
        assertEquals(ORANGE, WeatherFormat.aqiColor(150));
        assertEquals(RED, WeatherFormat.aqiColor(151));
        assertEquals(RED, WeatherFormat.aqiColor(200));
        assertEquals(PURPLE, WeatherFormat.aqiColor(201));
        assertEquals(PURPLE, WeatherFormat.aqiColor(300));
        assertEquals(MAROON, WeatherFormat.aqiColor(301));
        assertEquals(MAROON, WeatherFormat.aqiColor(500));
    }

    /** 底色必须与分级色同色、只改不透明度，否则会出现「数字绿、底色红」的错配。 */
    @Test
    public void aqiTintKeepsGradeRgbAndForcesAlpha() {
        int[] samples = {0, 50, 51, 100, 101, 150, 151, 200, 201, 300, 301, 500};
        for (int aqi : samples) {
            int tint = WeatherFormat.aqiTint(aqi);
            assertEquals("AQI=" + aqi + " 的底色 RGB 应与分级色一致",
                    WeatherFormat.aqiColor(aqi) & 0xFFFFFF, tint & 0xFFFFFF);
            assertEquals("AQI=" + aqi + " 的底色不透明度应固定为 0x24",
                    0x24, (tint >>> 24) & 0xFF);
        }
    }
}
