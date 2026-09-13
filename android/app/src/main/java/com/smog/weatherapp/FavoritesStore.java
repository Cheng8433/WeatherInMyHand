package com.smog.weatherapp;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 城市收藏夹的本地持久化（SharedPreferences，零依赖），与 {@link WeatherCache} / {@link ThemeHelper} 同一路数。
 *
 * <p>存的是一个<b>有序列表</b>（顺序 = 用户添加顺序，不做重排），整个列表压在单条 key 上、
 * 用换行分隔。之所以不用 JSON：城市名来自单行 {@code EditText} 或后端 {@code cityName}，不可能含换行；
 * 而 {@code org.json} 在本地 JVM 单测里会抛（Android SDK 桩），用它就把这套逻辑的可测性锁死了。
 *
 * <p>可测性靠分层：{@link #parse}/{@link #join}/{@link #plus}/{@link #minus} 是不碰 Context 的纯函数
 * （package-private，供 {@code FavoritesStoreTest} 直接测），带 Context 的公开方法只是它们的薄壳。
 *
 * <p>有界性：{@link #MAX_CITIES} 个上限，满了就<b>拒绝新增</b>而不是淘汰旧的——收藏是用户手动挑的，
 * 悄悄丢掉他早先收藏的城市比直接说“满了”更让人意外。
 */
public final class FavoritesStore {

    private static final String PREFS = "weather_favorites";
    private static final String KEY = "cities";   // 单 key，城市名按 \n 分隔
    private static final String SEP = "\n";

    /** 收藏上限；超出后 {@link #add} 不再写入（由调用方提示用户）。 */
    public static final int MAX_CITIES = 12;

    private FavoritesStore() {
    }

    /** 全部收藏城市，按添加顺序；无收藏时是空表（不返回 null）。 */
    public static List<String> all(Context context) {
        return parse(prefs(context).getString(KEY, ""));
    }

    /** 某城是否已收藏（trim 后按城市名字符串精确比对）。 */
    public static boolean isFavorite(Context context, String city) {
        String c = norm(city);
        return !c.isEmpty() && all(context).contains(c);
    }

    /**
     * 追加收藏。
     *
     * @return true 表示确实写入了。注意：城市已存在（调用方应先 {@link #isFavorite} 判断）或已达
     *         {@link #MAX_CITIES} 上限时都返回 false，且不改动已存内容。
     */
    public static boolean add(Context context, String city) {
        List<String> before = all(context);
        List<String> after = plus(before, city);
        if (after.size() == before.size()) {
            return false;
        }
        prefs(context).edit().putString(KEY, join(after)).apply();
        return true;
    }

    /** 取消收藏；该城本就不在收藏里则什么都不做。 */
    public static void remove(Context context, String city) {
        List<String> before = all(context);
        List<String> after = minus(before, city);
        if (after.size() == before.size()) {
            return;
        }
        prefs(context).edit().putString(KEY, join(after)).apply();
    }

    // ==================== 纯函数（不碰 Context，可直接单测） ====================

    /** 解析存串：逐行 trim、丢弃空行、去重（保留首次出现的位置）。null/空串得空表。 */
    static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split(SEP, -1)) {
            String c = line.trim();
            if (c.isEmpty() || out.contains(c)) {
                continue;
            }
            out.add(c);
        }
        return out;
    }

    /** 序列化为存串：逐项 trim，trim 后为空的跳过。与 {@link #parse} 同一口径，故 {@code parse(join(x))} 往返稳定。 */
    static String join(List<String> cities) {
        StringBuilder sb = new StringBuilder();
        if (cities == null) {
            return "";
        }
        for (String c : cities) {
            String v = c == null ? "" : c.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** 追加城市（trim 后）。空串/已存在/已达上限 → 原样返回；返回新表，不改入参。 */
    static List<String> plus(List<String> cities, String city) {
        List<String> out = new ArrayList<>(cities == null ? new ArrayList<>() : cities);
        String c = norm(city);
        if (c.isEmpty() || out.contains(c) || out.size() >= MAX_CITIES) {
            return out;
        }
        out.add(c);
        return out;
    }

    /** 移除城市（trim 后）。不在表里则原样返回；返回新表，不改入参。 */
    static List<String> minus(List<String> cities, String city) {
        List<String> out = new ArrayList<>(cities == null ? new ArrayList<>() : cities);
        String c = norm(city);
        if (!c.isEmpty()) {
            out.remove(c);
        }
        return out;
    }

    // ==================== 内部 ====================

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String norm(String city) {
        return city == null ? "" : city.trim();
    }
}
