package com.smog.weatherapp;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.anychart.AnyChart;
import com.anychart.AnyChartView;
import com.anychart.chart.common.dataentry.DataEntry;
import com.anychart.chart.common.dataentry.ValueDataEntry;
import com.anychart.charts.Cartesian;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 三页（今天 / 空气 / 趋势）的数据落地渲染：后端那份 data 的哪个字段，填到哪个控件、显示成什么样。
 *
 * <p>从 MainActivity 整块搬出来，是这次重构里「拆得最值」的一块：它占了 Activity 近三成篇幅，
 * 却与请求时序、定位、缓存兜底毫无关系——纯「读 JSON → 设控件」，改样式时不必再穿过一千行找它。
 *
 * <p>本类只做渲染，<b>不</b>决定「渲染什么数据」：缓存兜底选哪份、并发返回谁覆盖谁，都在 Activity。
 * 视图查找在此完成，须在 {@code setContentView} 之后构造；{@code weatherScene} 由外部传入
 * （Activity 的切页逻辑也要用它，故不由本类独占）。
 */
final class PageRenderer {

    private final Activity activity;

    // today
    private final TextView tvWeatherEmoji, tvWeatherDesc, tvTempNum, tvTempUnit, tvHeroSub, tvDataTime;
    private final TextView tvMFeels, tvMHumidity, tvMCloud, tvMVis, tvMPressure, tvMPrecip, tvMDew, tvMWindSp;
    private final TextView tvWindMain, tvWindSub;

    // air
    private final TextView tvAqiNum, tvAqiLevel, tvPrimaryPoll, tvAqiAssessment;
    private final TextView tvPollPm25, tvPollPm10, tvPollNo2, tvPollO3, tvPollCo, tvPollSo2;
    private final TextView tvHealthAdvice;

    // trend
    private final TextView tvTrendSummary;
    private final AnyChartView chartHourly;

    private final WeatherSceneView weatherScene;

    PageRenderer(Activity activity, WeatherSceneView weatherScene) {
        this.activity = activity;
        this.weatherScene = weatherScene;

        tvWeatherEmoji = activity.findViewById(R.id.tvWeatherEmoji);
        tvWeatherDesc = activity.findViewById(R.id.tvWeatherDesc);
        tvTempNum = activity.findViewById(R.id.tvTempNum);
        tvTempUnit = activity.findViewById(R.id.tvTempUnit);
        tvHeroSub = activity.findViewById(R.id.tvHeroSub);
        tvDataTime = activity.findViewById(R.id.tvDataTime);
        tvMFeels = activity.findViewById(R.id.tvMFeels);
        tvMHumidity = activity.findViewById(R.id.tvMHumidity);
        tvMCloud = activity.findViewById(R.id.tvMCloud);
        tvMVis = activity.findViewById(R.id.tvMVis);
        tvMPressure = activity.findViewById(R.id.tvMPressure);
        tvMPrecip = activity.findViewById(R.id.tvMPrecip);
        tvMDew = activity.findViewById(R.id.tvMDew);
        tvMWindSp = activity.findViewById(R.id.tvMWindSp);
        tvWindMain = activity.findViewById(R.id.tvWindMain);
        tvWindSub = activity.findViewById(R.id.tvWindSub);

        tvAqiNum = activity.findViewById(R.id.tvAqiNum);
        tvAqiLevel = activity.findViewById(R.id.tvAqiLevel);
        tvPrimaryPoll = activity.findViewById(R.id.tvPrimaryPoll);
        tvAqiAssessment = activity.findViewById(R.id.tvAqiAssessment);
        tvPollPm25 = activity.findViewById(R.id.tvPollPm25);
        tvPollPm10 = activity.findViewById(R.id.tvPollPm10);
        tvPollNo2 = activity.findViewById(R.id.tvPollNo2);
        tvPollO3 = activity.findViewById(R.id.tvPollO3);
        tvPollCo = activity.findViewById(R.id.tvPollCo);
        tvPollSo2 = activity.findViewById(R.id.tvPollSo2);
        tvHealthAdvice = activity.findViewById(R.id.tvHealthAdvice);

        tvTrendSummary = activity.findViewById(R.id.tvTrendSummary);
        chartHourly = activity.findViewById(R.id.chartHourly);
    }

    /** 页脚数据时效：正常显示观测时间；降级/读缓存时显示「离线缓存 · 更新于 …」。 */
    void renderDataTime(JSONObject data, boolean offline) {
        long ts = data == null ? 0L : data.optLong("updateTime", 0L);
        if (ts <= 0L) {
            tvDataTime.setText(activity.getString(R.string.data_time_unknown));
            return;
        }
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
        tvDataTime.setText(offline
                ? activity.getString(R.string.data_time_offline, time)
                : activity.getString(R.string.data_time_fresh, time));
    }

    void renderToday(JSONObject d) {
        String weather = d.optString("weather", "");
        tvWeatherEmoji.setText(WeatherFormat.emojiFor(weather));
        tvWeatherDesc.setText(weather.isEmpty() ? "--" : weather);
        weatherScene.setWeather(weather);   // 动效天空跟随当前天气现象

        double temp = d.optDouble("temperature", Double.NaN);
        if (Double.isNaN(temp)) {
            tvTempNum.setText("--");
            tvTempUnit.setVisibility(View.INVISIBLE);
        } else {
            tvTempUnit.setVisibility(View.VISIBLE);
            tvTempNum.setText(String.valueOf(Math.round(temp)));
        }

        double feels = d.optDouble("feelsLike", Double.NaN);
        double hum = d.optDouble("humidity", Double.NaN);
        String feelsTxt = Double.isNaN(feels) ? "--" : (Math.round(feels) + "°");
        String humTxt = Double.isNaN(hum) ? "--" : (Math.round(hum) + "%");
        tvHeroSub.setText(activity.getString(R.string.hero_met_grid_format, feelsTxt, humTxt));

        UiFormat.setDouble(tvMFeels, feels, "°", 0);
        UiFormat.setDouble(tvMHumidity, hum, "%", 0);
        tvMCloud.setText(UiFormat.percentStr(d.optString("cloud", "")));
        UiFormat.setDouble(tvMVis, d.optDouble("vis", Double.NaN), " km", 1);
        UiFormat.setDouble(tvMPressure, d.optDouble("pressure", Double.NaN), " hPa", 0);
        UiFormat.setDouble(tvMPrecip, d.optDouble("precip", Double.NaN), " mm", 1);
        UiFormat.setDouble(tvMDew, d.optDouble("dew", Double.NaN), "°", 0);
        UiFormat.setDouble(tvMWindSp, d.optDouble("windSpeed", Double.NaN), " km/h", 0);

        String dir = WeatherFormat.windDirCn(activity.getResources(), d.optString("windDir", ""));
        String scale = d.optString("windScale", "").trim();
        tvWindMain.setText(dir + (scale.isEmpty() ? "" : "  " + scale));
        double spd = d.optDouble("windSpeed", Double.NaN);
        tvWindSub.setText(Double.isNaN(spd) ? "--" : activity.getString(R.string.wind_speed_format, Math.round(spd)));
    }

    void renderAir(JSONObject d) {
        int aqi = d.optInt("aqi", -1);
        if (aqi < 0) {
            tvAqiNum.setText("--");
            tvAqiLevel.setText("--");
            tvAqiAssessment.setText(activity.getString(R.string.air_none));
            return;
        }
        int color = WeatherFormat.aqiColor(aqi);

        tvAqiNum.setText(String.valueOf(aqi));
        tvAqiNum.setTextColor(color);
        // 数值底色：同色半透明圆角条
        GradientDrawable pill = new GradientDrawable();
        pill.setShape(GradientDrawable.RECTANGLE);
        pill.setCornerRadius(dp(20));
        pill.setColor(WeatherFormat.aqiTint(aqi));
        tvAqiNum.setPadding(dp(18), dp(4), dp(18), dp(4));
        tvAqiNum.setBackground(pill);

        String level = d.optString("airQuality", "");
        tvAqiLevel.setText(level.isEmpty() ? WeatherFormat.aqiLevel(activity.getResources(), aqi) : level);
        tvAqiLevel.setTextColor(color);
        tvAqiAssessment.setText(WeatherFormat.assessment(activity.getResources(), aqi));

        String primary = d.optString("primaryPollutant", "");
        tvPrimaryPoll.setText(primary.isEmpty() ? "--"
                : WeatherFormat.pollutantName(activity.getResources(), primary));

        UiFormat.setPoll(tvPollPm25, d, "pm25Value", "pm2p5");
        UiFormat.setPoll(tvPollPm10, d, "pm10Value", "pm10");
        UiFormat.setPoll(tvPollNo2, d, "no2", "no2");
        UiFormat.setPoll(tvPollO3, d, "o3", "o3");
        UiFormat.setPoll(tvPollCo, d, "co", "co");
        UiFormat.setPoll(tvPollSo2, d, "so2", "so2");

        tvHealthAdvice.setText(WeatherFormat.healthAdvice(activity.getResources(), aqi));
    }

    void renderTrend(JSONObject d) {
        JSONArray hourly = d.optJSONArray("hourlyForecast");
        if (hourly == null || hourly.length() == 0) {
            tvTrendSummary.setText(activity.getString(R.string.trend_none));
            chartHourly.setChart(null);
            return;
        }
        List<DataEntry> tempData = new ArrayList<>();
        List<DataEntry> humData = new ArrayList<>();
        double maxT = Double.NEGATIVE_INFINITY, minT = Double.POSITIVE_INFINITY;
        for (int i = 0; i < hourly.length(); i++) {
            JSONObject item = hourly.optJSONObject(i);
            if (item == null) continue;
            String fxTime = item.optString("fxTime", "");
            String label = fxTime.length() >= 16 ? fxTime.substring(11, 16) : fxTime;
            try {
                double temp = Double.parseDouble(item.optString("temp", "0"));
                double hum = Double.parseDouble(item.optString("humidity", "0"));
                tempData.add(new ValueDataEntry(label, temp));
                humData.add(new ValueDataEntry(label, hum));
                if (temp > maxT) maxT = temp;
                if (temp < minT) minT = temp;
            } catch (NumberFormatException ignored) {
            }
        }
        if (!tempData.isEmpty()) {
            String maxTxt = maxT == Double.NEGATIVE_INFINITY ? "--" : (Math.round(maxT) + "°");
            String minTxt = minT == Double.POSITIVE_INFINITY ? "--" : (Math.round(minT) + "°");
            tvTrendSummary.setText(activity.getString(R.string.trend_24h_format, maxTxt, minTxt));
            Cartesian chart = AnyChart.line();
            chart.line(tempData).name(activity.getString(R.string.chart_series_temp));
            chart.line(humData).name(activity.getString(R.string.chart_series_hum));
            String txt = "#" + Integer.toHexString(0xFFFFFF & attrColor(R.attr.textPrimary));
            String txtSec = "#" + Integer.toHexString(0xFFFFFF & attrColor(R.attr.textSecondary));
            chart.background("#00000000");
            chart.xAxis(0).labels().fontColor(txtSec);
            chart.yAxis(0).labels().fontColor(txtSec);
            chart.legend().fontColor(txt);
            chartHourly.setChart(chart);
        } else {
            tvTrendSummary.setText(activity.getString(R.string.trend_none));
            chartHourly.setChart(null);
        }
    }

    /** 主题色属性 → ARGB；解析不到时退回默认蓝（异常路径，正常主题都定义得到）。 */
    private int attrColor(int attrRes) {
        TypedValue tv = new TypedValue();
        if (activity.getTheme().resolveAttribute(attrRes, tv, true)) {
            return tv.data;
        }
        return 0xFF2196F3;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
