package com.smog.weatherapp;

import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 单值 → 展示文本的格式化策略：数值带单位、污染物读数、百分比串。
 *
 * <p>与 {@link PageRenderer} 分开，是因为两者的「变」的原因不同：这里改的是<b>同一个值怎么显示</b>
 * （几位小数、有没有单位、缺值显示成什么），那边改的是<b>哪个字段填哪个控件</b>。混在一起时，
 * 想统一「缺值一律显示 --」得在几百行渲染代码里翻找。
 *
 * <p>无状态（不读实例字段），故全是 static，且 {@link #percentStr} 可直接被 JVM 单测覆盖。
 */
final class UiFormat {

    private UiFormat() {
    }

    /** 数值 + 单位；NaN 显示 "--"。 */
    static void setDouble(TextView tv, double v, String unit, int digits) {
        if (Double.isNaN(v)) {
            tv.setText("--");
            return;
        }
        // 数字与单位分开拼：unit 可能含 "%"，不能拼进 format 串，否则触发 UnknownFormatConversionException
        String fmt = "%." + digits + "f";
        tv.setText(String.format(Locale.ROOT, fmt, v) + unit);
    }

    /** 污染物读数：CO 保留两位小数，其余取整；缺值显示 "--"。 */
    static void setPoll(TextView tv, JSONObject d, String key, String code) {
        double v = d.optDouble(key, Double.NaN);
        if (Double.isNaN(v)) {
            tv.setText("--");
            return;
        }
        tv.setText("co".equals(code) ? String.format(Locale.ROOT, "%.2f", v) : String.valueOf(Math.round(v)));
    }

    /** 云量百分比：上游可能给 "80"、"80%" 或非数值文本，能补 "%" 就补，否则原样显示。 */
    static String percentStr(String cloud) {
        if (cloud == null || cloud.isEmpty()) return "--";
        String c = cloud.trim();
        if (c.endsWith("%")) return c;
        try {
            Double.parseDouble(c);
            return c + "%";
        } catch (NumberFormatException e) {
            return c;
        }
    }
}
