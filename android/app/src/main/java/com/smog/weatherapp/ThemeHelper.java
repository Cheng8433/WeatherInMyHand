package com.smog.weatherapp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 主题选择：名称 → 样式映射、偏好保存/读取、选择弹窗。
 * 新增主题只需往数组里加一项（名称 + 对应 Theme.Weather.XXX 样式）。
 * 名称文案外置在 strings.xml/arrays.xml（theme_names），顺序须与 STYLES/DARK 一致。
 */
public final class ThemeHelper {

    private static final int[] STYLES = {
            R.style.Theme_Weather_Sky,
            R.style.Theme_Weather_Night,
            R.style.Theme_Weather_Forest,
            R.style.Theme_Weather_Sunset,
            R.style.Theme_Weather_Immersive
    };

    // 各主题是否暗色调（与 NAMES/STYLES 一一对应；驱动动效星月等昼夜判定）
    private static final boolean[] DARK = {false, true, false, false, true};

    private static final String PREFS = "weather_theme";
    private static final String KEY_INDEX = "index";

    private ThemeHelper() {
    }

    public static int currentIndex(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int idx = sp.getInt(KEY_INDEX, 0);
        return Math.max(0, Math.min(STYLES.length - 1, idx));
    }

    public static void save(Context context, int index) {
        SharedPreferences.Editor ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putInt(KEY_INDEX, index);
        ed.apply();
    }

    /**
     * 必须在 Activity.setContentView 之前调用，否则主题不会生效。
     */
    /** 主题是否暗色调（深底出星月、动效对比强）。 */
    public static boolean isDark(int index) {
        return index >= 0 && index < DARK.length && DARK[index];
    }

    public static void applyTheme(Activity activity) {
        activity.setTheme(STYLES[currentIndex(activity)]);
    }

    /** 主题名（自 arrays.xml 读取，顺序与 STYLES/DARK 对应）。 */
    private static String[] names(Context context) {
        return context.getResources().getStringArray(R.array.theme_names);
    }

    public static String currentName(Context context) {
        String[] names = names(context);
        int idx = Math.max(0, Math.min(names.length - 1, currentIndex(context)));
        return names[idx];
    }

    /**
     * 弹出单选主题列表；选中后保存并回调（由调用方负责 recreate）。
     */
    public static void showPicker(Activity activity, final Runnable onSelected) {
        String[] names = names(activity);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.theme_pick_title)
                .setSingleChoiceItems(names, currentIndex(activity), (dialog, which) -> {
                    save(activity, which);
                    dialog.dismiss();
                    if (onSelected != null) {
                        onSelected.run();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
