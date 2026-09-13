package com.smog.weatherapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link FavoritesStore} 纯函数部分的单测（普通 JVM 测试，跑法 {@code ./gradlew :app:testDebugUnitTest}）。
 *
 * <p>收藏夹把整个列表压在单条 SharedPreferences 字符串上，于是「存串怎么解析/怎么拼回去」成了
 * 唯一会静默出错的地方：解析少 trim 一下、多留一个空行，界面上只是多出一行空白或重复城市，
 * 肉眼很难发现。带 Context 的薄壳方法测不了（本地单测不碰 Android framework），
 * 所以这里钉住的是它下面那四个纯函数。
 */
public class FavoritesStoreTest {

    // ==================== parse / join ====================

    @Test
    public void parseHandlesNullAndEmpty() {
        assertTrue(FavoritesStore.parse(null).isEmpty());
        assertTrue(FavoritesStore.parse("").isEmpty());
        assertTrue(FavoritesStore.parse("\n\n").isEmpty());
        assertTrue(FavoritesStore.parse("   \n \t ").isEmpty());
    }

    @Test
    public void parseTrimsDropsBlankLinesAndDeduplicates() {
        assertEquals(Arrays.asList("北京", "上海"),
                FavoritesStore.parse(" 北京 \n\n 上海 \n北京\n"));
    }

    @Test
    public void parsePreservesInsertionOrder() {
        assertEquals(Arrays.asList("广州", "北京", "上海"),
                FavoritesStore.parse("广州\n北京\n上海"));
    }

    @Test
    public void joinSkipsBlankEntriesAndUsesNewline() {
        assertEquals("北京\n上海", FavoritesStore.join(Arrays.asList("北京", "上海")));
        assertEquals("北京", FavoritesStore.join(Arrays.asList("北京", "", null, "  ")));
        assertEquals("", FavoritesStore.join(null));
        assertEquals("", FavoritesStore.join(new ArrayList<>()));
    }

    @Test
    public void parseAndJoinRoundTrip() {
        List<String> cities = Arrays.asList("北京", "上海", "广州");
        assertEquals(cities, FavoritesStore.parse(FavoritesStore.join(cities)));
    }

    // ==================== plus ====================

    @Test
    public void plusAppendsTrimmedCityAtEnd() {
        assertEquals(Arrays.asList("北京", "上海"),
                FavoritesStore.plus(Collections.singletonList("北京"), " 上海 "));
    }

    @Test
    public void plusIgnoresBlankCity() {
        List<String> base = Collections.singletonList("北京");
        assertEquals(base, FavoritesStore.plus(base, null));
        assertEquals(base, FavoritesStore.plus(base, "   "));
    }

    @Test
    public void plusIsNoOpWhenAlreadyPresent() {
        List<String> base = Arrays.asList("北京", "上海");
        assertEquals(base, FavoritesStore.plus(base, " 北京 "));
    }

    @Test
    public void plusStopsAtMaxCities() {
        List<String> full = new ArrayList<>();
        for (int i = 0; i < FavoritesStore.MAX_CITIES; i++) {
            full.add("城市" + i);
        }
        assertEquals(FavoritesStore.MAX_CITIES, FavoritesStore.plus(full, "再多一个").size());
    }

    @Test
    public void plusStillDeduplicatesWhenFull() {
        // 满了以后再收藏一个已存在的城市，不该因为「满了」就误判成新增
        List<String> full = new ArrayList<>();
        for (int i = 0; i < FavoritesStore.MAX_CITIES; i++) {
            full.add("城市" + i);
        }
        assertEquals(full, FavoritesStore.plus(full, "城市0"));
    }

    // ==================== minus ====================

    @Test
    public void minusRemovesTrimmedCity() {
        assertEquals(Collections.singletonList("上海"),
                FavoritesStore.minus(Arrays.asList("北京", "上海"), " 北京 "));
    }

    @Test
    public void minusIsNoOpWhenAbsent() {
        List<String> base = Arrays.asList("北京", "上海");
        assertEquals(base, FavoritesStore.minus(base, "广州"));
        assertEquals(base, FavoritesStore.minus(base, null));
        assertEquals(base, FavoritesStore.minus(base, "  "));
    }

    // ==================== 不改入参 ====================

    @Test
    public void plusAndMinusDoNotMutateInput() {
        List<String> base = new ArrayList<>(Collections.singletonList("北京"));
        FavoritesStore.plus(base, "上海");
        FavoritesStore.minus(base, "北京");
        assertEquals(Collections.singletonList("北京"), base);
    }
}
