package com.smog.weatherapp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link UiFormat#percentStr} 的单测（普通 JVM 测试，跑法 {@code ./gradlew :app:testDebugUnitTest}）。
 *
 * <p>它是从 MainActivity 抽出来的纯函数，钉住的是「上游给的云量到底是什么形态」这件小事：
 * 和风有时给 {@code "80"}、有时给 {@code "80%"}、有时给非数值文本或空串。以前它埋在
 * Activity 的一千行里没人测，抽出来正好补上——补 "%"/不补 "/原样透传" 三种分支错哪个，
 * 界面上都只是少一个百分号，肉眼几乎不会发现。
 */
public class UiFormatTest {

    @Test
    public void addsPercentSignToBareNumber() {
        assertEquals("80%", UiFormat.percentStr("80"));
        assertEquals("0%", UiFormat.percentStr("0"));
        assertEquals("12.5%", UiFormat.percentStr("12.5"));
    }

    @Test
    public void keepsExistingPercentSignWithoutDoubling() {
        assertEquals("80%", UiFormat.percentStr("80%"));
        assertEquals("80%", UiFormat.percentStr(" 80% "));
    }

    @Test
    public void trimsBeforeDeciding() {
        // 上游带空格时不该出现 "  80  %" 之类的结果
        assertEquals("80%", UiFormat.percentStr(" 80 "));
    }

    @Test
    public void passesThroughNonNumericText() {
        assertEquals("少云", UiFormat.percentStr("少云"));
        assertEquals("N/A", UiFormat.percentStr("N/A"));
    }

    @Test
    public void missingValueShowsDash() {
        assertEquals("--", UiFormat.percentStr(null));
        assertEquals("--", UiFormat.percentStr(""));
    }
}
