package com.smog.weatherapp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 主题选择：名称 → 样式映射、偏好保存/读取、选择弹窗。
 * 新增主题只需往数组里加一项（名称 + 对应 Theme.Weather.XXX 样式）。
 */
public final class ThemeHelper {

    public static final String[] NAMES = {
            "天蓝 · 晴（浅）",
            "深蓝 · 夜空（暗）",
            "森林 · 青绿（浅）",
            "暖橙 · 日落（浅）"
    };

    private static final int[] STYLES = {
            R.style.Theme_Weather_Sky,
            R.style.Theme_Weather_Night,
            R.style.Theme_Weather_Forest,
            R.style.Theme_Weather_Sunset
    };

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
    public static void applyTheme(Activity activity) {
        activity.setTheme(STYLES[currentIndex(activity)]);
    }

    public static String currentName(Context context) {
        return NAMES[currentIndex(context)];
    }

    /**
     * 弹出单选主题列表；选中后保存并回调（由调用方负责 recreate）。
     */
    public static void showPicker(Activity activity, final Runnable onSelected) {
        new MaterialAlertDialogBuilder(activity)
                .setTitle("选择主题")
                .setSingleChoiceItems(NAMES, currentIndex(activity), (dialog, which) -> {
                    save(activity, which);
                    dialog.dismiss();
                    if (onSelected != null) {
                        onSelected.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
