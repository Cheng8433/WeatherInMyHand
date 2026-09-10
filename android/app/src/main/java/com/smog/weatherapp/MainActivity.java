package com.smog.weatherapp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.anychart.AnyChart;
import com.anychart.AnyChartView;
import com.anychart.chart.common.dataentry.DataEntry;
import com.anychart.chart.common.dataentry.ValueDataEntry;
import com.anychart.charts.Cartesian;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private static final String BASE_URL = BuildConfig.BACK_HOST_API;

    private static final int TAB_TODAY = 0;
    private static final int TAB_AIR = 1;
    private static final int TAB_TREND = 2;

    // header
    private TextView tvCityName;
    private EditText etSearchCity;
    private ImageButton btnSearch, btnRefresh, btnTheme, btnAbout;

    // 三页根容器
    private ScrollView scrollToday, scrollAir, scrollTrend;
    private BottomNavigationView bottomNav;
    private WeatherSceneView weatherScene;

    // today
    private TextView tvWeatherEmoji, tvWeatherDesc, tvTempNum, tvTempUnit, tvHeroSub;
    private TextView tvMFeels, tvMHumidity, tvMCloud, tvMVis, tvMPressure, tvMPrecip, tvMDew, tvMWindSp;
    private TextView tvWindMain, tvWindSub;

    // air
    private TextView tvAqiNum, tvAqiLevel, tvPrimaryPoll, tvAqiAssessment;
    private TextView tvPollPm25, tvPollPm10, tvPollNo2, tvPollO3, tvPollCo, tvPollSo2;
    private TextView tvHealthAdvice;

    // trend
    private TextView tvTrendSummary;
    private AnyChartView chartHourly;

    private OkHttpClient httpClient = new OkHttpClient();
    private LocationManager locationManager;
    /** 当前注册中的定位监听：保存引用以便 onDestroy 注销，避免 Activity 销毁后仍回调 */
    private LocationListener locationListener;
    private String currentCity = "";

    private boolean hasPerformedInitialLocation = false;
    private boolean isGpsResultApplied = false;

    /** 主线程 Handler 与定位超时任务：保存引用以便 onDestroy 移除未执行的延时回调 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable locationTimeoutRunnable;

    /** 请求序号：只允许「最后发起」的那次结果落到 UI，避免并发返回时旧城市覆盖新城市 */
    private int uiApplySeq = 0;

    private JSONObject lastData = null;
    private int currentTab = TAB_TODAY;

    // 首启隐私同意：未同意前不发起任何定位/联网取数
    private boolean weatherStarted = false;
    private boolean privacyDialogUp = false;

    // 全局加载/错误重试 UI
    private View loadingBar, errorPanel, btnRetry, btnCancelRetry;
    private TextView tvErrorMsg;
    private int pendingLoads = 0;
    private Runnable retryAction = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 setContentView 之前（且在 super.onCreate 前），否则状态栏/窗口配色不随主题变
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        if (PrivacyStore.accepted(this)) {
            startWeatherFlow();
        }
        // 未同意时由 onResume 弹出首启同意；同意后才真正开跑天气/定位
        registerBackHandler();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从「查看隐私政策全文」返回、或旋转等场景下若仍未同意，继续弹窗要求先同意
        promptPrivacyConsent();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销定位监听 + 取消定位超时回调：Activity 销毁后不应再有位置回调或延时任务
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (locationTimeoutRunnable != null) {
            mainHandler.removeCallbacks(locationTimeoutRunnable);
        }
        // 取消所有在途 HTTP 请求，避免回调持有已销毁的 Activity
        httpClient.dispatcher().cancelAll();
    }

    /** 通过隐私同意后才执行的天气主流程（缓存秒开 + 恢复上次城市 + GPS 定位）。 */
    private void startWeatherFlow() {
        weatherStarted = true;
        showTab(TAB_TODAY);
        bottomNav.setSelectedItemId(R.id.nav_today);
        paintLastCachedWeather();              // 启动先用本地缓存秒开（断网也有内容），联网后刷新覆盖
        loadCurrentLocationFromServer();       // 先显示上次查看的城市，零等待
        checkLocationPermission();             // 后台同时进行 GPS 定位
    }

    /**
     * 首启隐私政策同意弹窗（不可取消）。未同意前不发起定位/联网取数。
     *  - 同意并继续 → 持久化同意态并启动天气主流程；
     *  - 不同意 → 提示后退出；
     *  - 查看全文 → 打开「关于与隐私」页，返回后若仍未同意会再次弹窗。
     */
    private void promptPrivacyConsent() {
        if (weatherStarted || PrivacyStore.accepted(this) || privacyDialogUp || isFinishing()) {
            return;
        }
        privacyDialogUp = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_consent_title)
                .setMessage(R.string.privacy_consent_summary)
                .setCancelable(false)
                .setPositiveButton(R.string.action_agree, (dialog, which) -> {
                    PrivacyStore.setAccepted(this, true);
                    startWeatherFlow();
                })
                .setNegativeButton(R.string.action_disagree, (dialog, which) -> {
                    Toast.makeText(this, getString(R.string.privacy_consent_disagree_toast), Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNeutralButton(R.string.action_view_policy, (dialog, which) -> openPrivacyPage())
                .setOnDismissListener(dialog -> privacyDialogUp = false)
                .show();
    }

    /** 打开「关于与隐私」页。 */
    private void openPrivacyPage() {
        startActivity(new Intent(this, PrivacyActivity.class));
    }

    /** 返回键：输入法展开先收键盘；不在「今天」页先回首页；已在首页再弹退出确认。 */
    private void registerBackHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (etSearchCity.hasFocus() && hideSoftKeyboard()) {
                    return;
                }
                if (currentTab != TAB_TODAY) {
                    showTab(TAB_TODAY);
                    bottomNav.setSelectedItemId(R.id.nav_today);
                } else {
                    confirmExit();
                }
            }
        });
    }

    private boolean hideSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null || !imm.isActive()) {
            return false;
        }
        View focused = getCurrentFocus();
        if (focused != null) {
            focused.clearFocus();
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
        return true;
    }

    private void confirmExit() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.exit_title)
                .setMessage(R.string.exit_message)
                .setPositiveButton(R.string.action_exit, (dialog, which) -> finish())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void initViews() {
        tvCityName = findViewById(R.id.tvCityName);
        etSearchCity = findViewById(R.id.etSearchCity);
        btnSearch = findViewById(R.id.btnSearch);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnTheme = findViewById(R.id.btnTheme);
        btnAbout = findViewById(R.id.btnAbout);

        scrollToday = findViewById(R.id.scrollToday);
        scrollAir = findViewById(R.id.scrollAir);
        scrollTrend = findViewById(R.id.scrollTrend);
        bottomNav = findViewById(R.id.bottomNav);
        weatherScene = findViewById(R.id.weatherScene);

        tvWeatherEmoji = findViewById(R.id.tvWeatherEmoji);
        tvWeatherDesc = findViewById(R.id.tvWeatherDesc);
        tvTempNum = findViewById(R.id.tvTempNum);
        tvTempUnit = findViewById(R.id.tvTempUnit);
        tvHeroSub = findViewById(R.id.tvHeroSub);

        tvMFeels = findViewById(R.id.tvMFeels);
        tvMHumidity = findViewById(R.id.tvMHumidity);
        tvMCloud = findViewById(R.id.tvMCloud);
        tvMVis = findViewById(R.id.tvMVis);
        tvMPressure = findViewById(R.id.tvMPressure);
        tvMPrecip = findViewById(R.id.tvMPrecip);
        tvMDew = findViewById(R.id.tvMDew);
        tvMWindSp = findViewById(R.id.tvMWindSp);
        tvWindMain = findViewById(R.id.tvWindMain);
        tvWindSub = findViewById(R.id.tvWindSub);

        tvAqiNum = findViewById(R.id.tvAqiNum);
        tvAqiLevel = findViewById(R.id.tvAqiLevel);
        tvPrimaryPoll = findViewById(R.id.tvPrimaryPoll);
        tvAqiAssessment = findViewById(R.id.tvAqiAssessment);
        tvPollPm25 = findViewById(R.id.tvPollPm25);
        tvPollPm10 = findViewById(R.id.tvPollPm10);
        tvPollNo2 = findViewById(R.id.tvPollNo2);
        tvPollO3 = findViewById(R.id.tvPollO3);
        tvPollCo = findViewById(R.id.tvPollCo);
        tvPollSo2 = findViewById(R.id.tvPollSo2);
        tvHealthAdvice = findViewById(R.id.tvHealthAdvice);

        tvTrendSummary = findViewById(R.id.tvTrendSummary);
        chartHourly = findViewById(R.id.chartHourly);

        loadingBar = findViewById(R.id.loadingBar);
        errorPanel = findViewById(R.id.errorPanel);
        tvErrorMsg = findViewById(R.id.errorMsg);
        btnRetry = findViewById(R.id.btnRetry);
        btnCancelRetry = findViewById(R.id.btnCancelRetry);

        btnSearch.setOnClickListener(v -> {
            String city = etSearchCity.getText().toString().trim();
            if (city.isEmpty()) {
                Toast.makeText(this, getString(R.string.search_toast_empty), Toast.LENGTH_SHORT).show();
            } else {
                searchWeatherByCity(city);
            }
        });
        btnRefresh.setOnClickListener(v -> {
            if (currentCity.isEmpty()) {
                Toast.makeText(this, getString(R.string.refresh_toast_no_city), Toast.LENGTH_SHORT).show();
            } else {
                loadWeatherData(currentCity);
            }
        });
        btnTheme.setOnClickListener(v -> ThemeHelper.showPicker(this, () -> recreate()));
        btnAbout.setOnClickListener(v -> openPrivacyPage());
        btnRetry.setOnClickListener(v -> {
            // 不提前收起卡片：若重试仍失败，showErrorPanel 会因“卡片已可见”而补一条 toast 反馈
            if (retryAction != null) {
                retryAction.run();
            }
        });
        btnCancelRetry.setOnClickListener(v -> errorPanel.setVisibility(View.GONE));

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_today) {
                showTab(TAB_TODAY);
            } else if (id == R.id.nav_air) {
                showTab(TAB_AIR);
            } else {
                showTab(TAB_TREND);
            }
            return true;
        });
    }

    private void showTab(int tab) {
        currentTab = tab;
        scrollToday.setVisibility(tab == TAB_TODAY ? View.VISIBLE : View.GONE);
        scrollAir.setVisibility(tab == TAB_AIR ? View.VISIBLE : View.GONE);
        scrollTrend.setVisibility(tab == TAB_TREND ? View.VISIBLE : View.GONE);
        // 动效天空只在「今天」页显示；GONE 后 WeatherSceneView 自动停止绘制省电
        weatherScene.setVisibility(tab == TAB_TODAY ? View.VISIBLE : View.GONE);
        // AnyChartView 在 GONE 状态下不渲染，切到“趋势”且已有数据时再画
        if (tab == TAB_TREND && lastData != null) {
            renderTrend(lastData);
        }
    }

    // ==================== 数据落地：统一渲染三页 ====================

    private void applyAllPages(JSONObject data) {
        if (data == null) return;
        lastData = data;
        hideErrorPanel();   // 有内容可看就把错误浮层收起
        // 每次成功渲染都落地本地缓存（含后台降级的旧数据），供断网/失败兜底
        String city = data.optString("cityName", currentCity);
        if (!city.isEmpty()) {
            WeatherCache.save(this, city, data);
        }
        renderToday(data);
        renderAir(data);
        if (currentTab == TAB_TREND) {
            renderTrend(data);
        }
    }

    // ==================== 全局加载态 / 错误重试 ====================

    /** 一次天气请求开始（计数式，支持多个请求重叠，全部结束才收加载条）。线程安全。 */
    private void beginLoad() {
        runOnUiThread(() -> {
            pendingLoads++;
            if (pendingLoads == 1 && loadingBar != null) {
                loadingBar.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 一次天气请求结束。线程安全（可能由 OkHttp 回调线程调用）。 */
    private void endLoad() {
        runOnUiThread(() -> {
            if (pendingLoads > 0) {
                pendingLoads--;
            }
            if (pendingLoads <= 0 && loadingBar != null) {
                pendingLoads = 0;
                loadingBar.setVisibility(View.GONE);
            }
        });
    }

    private void hideErrorPanel() {
        if (errorPanel != null) {
            errorPanel.setVisibility(View.GONE);
        }
    }

    /** 显示错误浮层；若浮层本就可见（说明这次是重试又失败）则补一条 toast 反馈。线程安全。 */
    private void showErrorPanel(final String msg) {
        runOnUiThread(() -> {
            boolean retried = errorPanel != null && errorPanel.getVisibility() == View.VISIBLE;
            tvErrorMsg.setText(msg == null || msg.isEmpty()
                    ? getString(R.string.error_load_failed_default) : msg);
            if (errorPanel != null) {
                errorPanel.setVisibility(View.VISIBLE);
            }
            if (retried) {
                Toast.makeText(this,
                        msg == null || msg.isEmpty() ? getString(R.string.error_load_failed_short) : msg,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** 启动秒开：把最近一次本地缓存先渲染出来（断网也有内容），联网后的结果会覆盖。 */
    private void paintLastCachedWeather() {
        JSONObject cached = WeatherCache.getLastData(this);
        if (cached != null) {
            String city = cached.optString("cityName", "");
            if (!city.isEmpty()) {
                currentCity = city;
                tvCityName.setText(city);
            }
            applyAllPages(cached);
        }
    }

    /**
     * 当前城市（刷新 / GPS / 冷启动恢复）加载失败统一收口：先结束加载态再做兜底。
     * 只会命中「同一城市」的缓存——跨城市不顶替，避免把别的城市数据误当本次结果。
     *   - 有该城缓存 → 渲染缓存 + toast；
     *   - 无缓存但屏幕已有旧数据 → 不盖错误层，仅 toast 原始错误；
     *   - 完全无数据 → 显示居中错误浮层 + 重试按钮。
     * 任意线程调用，内部自行切回 UI 线程。
     */
    private void fallbackToCache(final String requestedCity, final String errorMsg) {
        endLoad();
        JSONObject hit = WeatherCache.get(this, requestedCity);
        if (hit == null) {
            // 仅当请求的城市正好是“最近一次缓存”的城市时才允许顶上
            JSONObject last = WeatherCache.getLastData(this);
            if (last != null && (requestedCity.isEmpty()
                    || requestedCity.equals(last.optString("cityName", "")))) {
                hit = last;
            }
        }
        final JSONObject cached = hit;
        runOnUiThread(() -> {
            if (cached != null) {
                applyAllPages(cached);
                Toast.makeText(this, getString(R.string.toast_use_cache), Toast.LENGTH_SHORT).show();
            } else if (lastData != null) {
                Toast.makeText(this,
                        errorMsg == null || errorMsg.isEmpty() ? getString(R.string.error_load_failed) : errorMsg,
                        Toast.LENGTH_SHORT).show();
            } else {
                showErrorPanel(errorMsg == null || errorMsg.isEmpty()
                        ? getString(R.string.error_load_failed_default) : errorMsg);
            }
        });
    }

    /**
     * 手动搜索某城市的失败处理：目标城市是用户明确指定的，语义与“刷新当前城”不同。
     *   - 该城市有缓存 → 切换过去并显示缓存 + toast；
     *   - 该城市无缓存 → 弹居中错误浮层 + 重试（绝不拿别的城市数据冒充）。
     * 任意线程调用，内部自行切回 UI 线程。
     */
    private void handleSearchFailure(final String city, final String errorMsg) {
        endLoad();
        JSONObject exact = WeatherCache.get(this, city);
        runOnUiThread(() -> {
            if (exact != null) {
                String cn = exact.optString("cityName", city);
                currentCity = cn;
                tvCityName.setText(cn);
                applyAllPages(exact);
                Toast.makeText(this, getString(R.string.toast_use_cache), Toast.LENGTH_SHORT).show();
            } else {
                showErrorPanel(errorMsg == null || errorMsg.isEmpty()
                        ? getString(R.string.error_city_not_found) : errorMsg);
            }
        });
    }

    private void renderToday(JSONObject d) {
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
        tvHeroSub.setText(getString(R.string.hero_met_grid_format, feelsTxt, humTxt));

        setDouble(tvMFeels, feels, "°", 0);
        setDouble(tvMHumidity, hum, "%", 0);
        tvMCloud.setText(percentStr(d.optString("cloud", "")));
        setDouble(tvMVis, d.optDouble("vis", Double.NaN), " km", 1);
        setDouble(tvMPressure, d.optDouble("pressure", Double.NaN), " hPa", 0);
        setDouble(tvMPrecip, d.optDouble("precip", Double.NaN), " mm", 1);
        setDouble(tvMDew, d.optDouble("dew", Double.NaN), "°", 0);
        setDouble(tvMWindSp, d.optDouble("windSpeed", Double.NaN), " km/h", 0);

        String dir = WeatherFormat.windDirCn(d.optString("windDir", ""));
        String scale = d.optString("windScale", "").trim();
        tvWindMain.setText(dir + (scale.isEmpty() ? "" : "  " + scale));
        double spd = d.optDouble("windSpeed", Double.NaN);
        tvWindSub.setText(Double.isNaN(spd) ? "--" : getString(R.string.wind_speed_format, Math.round(spd)));
    }

    private void renderAir(JSONObject d) {
        int aqi = d.optInt("aqi", -1);
        if (aqi < 0) {
            tvAqiNum.setText("--");
            tvAqiLevel.setText("--");
            tvAqiAssessment.setText(getString(R.string.air_none));
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
        tvAqiLevel.setText(level.isEmpty() ? WeatherFormat.aqiLevel(aqi) : level);
        tvAqiLevel.setTextColor(color);
        tvAqiAssessment.setText(WeatherFormat.assessment(aqi));

        String primary = d.optString("primaryPollutant", "");
        tvPrimaryPoll.setText(primary.isEmpty() ? "--" : WeatherFormat.pollutantName(primary));

        setPoll(tvPollPm25, d, "pm25Value", "pm2p5");
        setPoll(tvPollPm10, d, "pm10Value", "pm10");
        setPoll(tvPollNo2, d, "no2", "no2");
        setPoll(tvPollO3, d, "o3", "o3");
        setPoll(tvPollCo, d, "co", "co");
        setPoll(tvPollSo2, d, "so2", "so2");

        tvHealthAdvice.setText(WeatherFormat.healthAdvice(aqi));
    }

    private void renderTrend(JSONObject d) {
        JSONArray hourly = d.optJSONArray("hourlyForecast");
        if (hourly == null || hourly.length() == 0) {
            tvTrendSummary.setText(getString(R.string.trend_none));
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
            tvTrendSummary.setText(getString(R.string.trend_24h_format, maxTxt, minTxt));
            Cartesian chart = AnyChart.line();
            chart.line(tempData).name(getString(R.string.chart_series_temp));
            chart.line(humData).name(getString(R.string.chart_series_hum));
            String txt = "#" + Integer.toHexString(0xFFFFFF & attrColor(R.attr.textPrimary));
            String txtSec = "#" + Integer.toHexString(0xFFFFFF & attrColor(R.attr.textSecondary));
            chart.background("#00000000");
            chart.xAxis(0).labels().fontColor(txtSec);
            chart.yAxis(0).labels().fontColor(txtSec);
            chart.legend().fontColor(txt);
            chartHourly.setChart(chart);
        } else {
            tvTrendSummary.setText(getString(R.string.trend_none));
            chartHourly.setChart(null);
        }
    }

    // ==================== 请求 ====================

    private void searchWeatherByCity(String cityName) {
        retryAction = () -> searchWeatherByCity(cityName);
        if (!isNetworkAvailable()) {
            handleSearchFailure(cityName, getString(R.string.error_no_network));
            return;
        }
        final int reqSeq = ++uiApplySeq;   // 本次搜索成为最新请求，更早的在途结果回来后会被丢弃
        String url = BASE_URL + "weather/info?city=" + urlEncode(cityName);
        beginLoad();
        httpClient.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (reqSeq != uiApplySeq) {
                    endLoad();
                    return;
                }
                handleSearchFailure(cityName, getString(R.string.error_network_error));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handleJson(response, new JsonHandler() {
                    @Override
                    public void onData(JSONObject data) {
                        runOnUiThread(() -> {
                            endLoad();
                            if (reqSeq != uiApplySeq) return;   // 已被更晚的请求取代
                            if (data != null && data.has("cityName")) {
                                currentCity = data.optString("cityName");
                            } else {
                                currentCity = cityName;
                            }
                            tvCityName.setText(currentCity);
                            applyAllPages(data);
                            saveSearchedCityToServer(currentCity);
                        });
                    }

                    @Override
                    public void onFail(String msg) {
                        if (reqSeq != uiApplySeq) {
                            endLoad();
                            return;
                        }
                        handleSearchFailure(cityName,
                                msg == null || msg.isEmpty() ? getString(R.string.error_search_not_found) : msg);
                    }
                });
            }
        });
    }

    private void loadWeatherData(String city) {
        loadWeatherData(city, false);
    }

    /**
     * @param guardByGps 为 true 时仅当 GPS 定位结果尚未落地才应用（供“显示上次城市”的冷启动路径使用，
     *                   避免旧的服务器城市快照覆盖更新的 GPS 结果）；手动搜索/刷新传 false 始终应用。
     */
    private void loadWeatherData(String city, boolean guardByGps) {
        if (city == null || city.trim().isEmpty()) return;
        // guardByGps=true 的冷启动恢复是低优先级路径：不占用请求序号，仍由 isGpsResultApplied 兜底
        final int reqSeq = guardByGps ? uiApplySeq : ++uiApplySeq;
        retryAction = () -> loadWeatherData(city, guardByGps);
        beginLoad();
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?city=" + urlEncode(city))
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // 冷启动恢复路径若已有更新的 GPS 结果落地，就别用旧缓存覆盖它
                if (guardByGps && isGpsResultApplied) {
                    endLoad();
                    return;
                }
                if (!guardByGps && reqSeq != uiApplySeq) {
                    endLoad();
                    return;
                }
                fallbackToCache(city, getString(R.string.error_load_weather_failed));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handleJson(response, new JsonHandler() {
                    @Override
                    public void onData(JSONObject data) {
                        runOnUiThread(() -> {
                            endLoad();
                            if (!guardByGps && reqSeq != uiApplySeq) return;   // 已被更晚的请求取代
                            if (!guardByGps || !isGpsResultApplied) {
                                applyAllPages(data);
                            }
                        });
                    }

                    @Override
                    public void onFail(String msg) {
                        if (guardByGps && isGpsResultApplied) {
                            endLoad();
                            return;
                        }
                        if (!guardByGps && reqSeq != uiApplySeq) {
                            endLoad();
                            return;
                        }
                        fallbackToCache(city,
                                msg == null || msg.isEmpty() ? getString(R.string.error_get_weather_failed) : msg);
                    }
                });
            }
        });
    }

    private void loadWeatherDataByLocation(double lat, double lon) {
        String lastCity = currentCity;   // GPS 结果尚未返回前，用当前城市做缓存兜底
        final int reqSeq = ++uiApplySeq; // 新的定位结果成为最新请求，覆盖更早的搜索结果
        retryAction = () -> loadWeatherDataByLocation(lat, lon);
        beginLoad();
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?lat=" + lat + "&lon=" + lon)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (reqSeq != uiApplySeq) {
                    endLoad();
                    return;
                }
                fallbackToCache(lastCity, getString(R.string.error_load_weather_failed));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handleJson(response, new JsonHandler() {
                    @Override
                    public void onData(JSONObject data) {
                        runOnUiThread(() -> {
                            endLoad();
                            if (reqSeq != uiApplySeq) return;   // 已被更晚的请求取代
                            isGpsResultApplied = true;
                            if (data != null && data.has("cityName")) {
                                currentCity = data.optString("cityName");
                                tvCityName.setText(currentCity);
                            }
                            applyAllPages(data);
                        });
                    }

                    @Override
                    public void onFail(String msg) {
                        if (reqSeq != uiApplySeq) {
                            endLoad();
                            return;
                        }
                        fallbackToCache(lastCity,
                                msg == null || msg.isEmpty() ? getString(R.string.error_get_weather_failed) : msg);
                    }
                });
            }
        });
    }

    private void loadCurrentLocationFromServer() {
        Request request = new Request.Builder().url(BASE_URL + "location/local").build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handleJson(response, new JsonHandler() {
                    @Override
                    public void onData(JSONObject data) {
                        runOnUiThread(() -> {
                            if (!isGpsResultApplied && data != null) {
                                String city = data.optString("cityName", "");
                                if (!city.isEmpty()) {
                                    currentCity = city;
                                    tvCityName.setText(city);
                                    loadWeatherData(city, true);
                                }
                            }
                        });
                    }

                    @Override
                    public void onFail(String msg) {
                    }
                });
            }
        });
    }

    /** 解析统一 JSON 契约：{success:true,data} / {success:false,message}，在调用线程内执行回调。 */
    private void handleJson(Response response, JsonHandler handler) {
        boolean successful = response.isSuccessful();
        int code = response.code();
        String body;
        try {
            body = response.body() == null ? "" : response.body().string();
        } catch (IOException e) {
            // 读响应体途中连接中断（超时/断网）：必须走失败回调，否则加载条永远收不掉
            handler.onFail(getString(R.string.error_network_error));
            return;
        }
        if (successful) {
            try {
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("success", false)) {
                    handler.onData(json.optJSONObject("data"));
                } else {
                    handler.onFail(json.optString("message", ""));
                }
            } catch (JSONException e) {
                handler.onFail(getString(R.string.error_parse_failed));
            }
        } else {
            handler.onFail(getString(R.string.error_server, code));
        }
    }

    private interface JsonHandler {
        void onData(JSONObject data);

        void onFail(String msg);
    }

    // ==================== 定位（沿用原逻辑） ====================

    private void checkLocationPermission() {
        boolean fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fineGranted || coarseGranted) {
            getCurrentLocation();
            return;
        }
        // 同时申请精确 + 大致位置：任一被授权即可定位（Android 12+ 选「大致位置」/「仅使用时」也走得通）
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                REQUEST_LOCATION_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (anyLocationGranted(grantResults)) {
                getCurrentLocation();
            } else {
                Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show();
                showCityFallbackIfPending();
            }
        }
    }

    /** grantResults 与请求时的权限顺序一一对应，两个里任一命中即代表可定位。 */
    private static boolean anyLocationGranted(int[] grantResults) {
        if (grantResults == null) return false;
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    /**
     * 启动定位彻底失败、且尚无任何城市可显示时，把顶栏从「获取定位中…」回退成中性提示，
     * 避免冷启动无缓存 + 拿不到定位时一直卡在加载文案。
     */
    private void showCityFallbackIfPending() {
        runOnUiThread(() -> {
            if (currentCity.isEmpty() && tvCityName != null) {
                tvCityName.setText(getString(R.string.header_city_unavailable));
            }
        });
    }

    private void getCurrentLocation() {
        if (hasPerformedInitialLocation && !currentCity.isEmpty()) {
            return;
        }
        if (!isNetworkAvailable()) {
            Toast.makeText(this, getString(R.string.location_no_network), Toast.LENGTH_SHORT).show();
        }
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGpsEnabled && !isNetworkEnabled) {
            Toast.makeText(this, getString(R.string.location_service_off), Toast.LENGTH_SHORT).show();
            showCityFallbackIfPending();
            return;
        }

        // 重复调用时先清掉上一次的监听与超时任务，避免重复注册和回调泄漏
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
        if (locationTimeoutRunnable != null) {
            mainHandler.removeCallbacks(locationTimeoutRunnable);
            locationTimeoutRunnable = null;
        }

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                locationManager.removeUpdates(this);
                // 已拿到定位，取消 10 秒超时任务，否则它会晚一步误报「定位不可用」
                if (locationTimeoutRunnable != null) {
                    mainHandler.removeCallbacks(locationTimeoutRunnable);
                    locationTimeoutRunnable = null;
                }
                saveLocationToServer(location.getLatitude(), location.getLongitude());
                loadWeatherDataByLocation(location.getLatitude(), location.getLongitude());
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Toast.makeText(MainActivity.this, getString(R.string.location_provider_off, provider), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }
        };

        if (isGpsEnabled) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
        } else if (isNetworkEnabled) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
        }

        locationTimeoutRunnable = () -> {
            locationTimeoutRunnable = null;
            if (locationManager != null) {
                locationManager.removeUpdates(locationListener);
            }
            // 不做「最后已知位置」兜底：陈旧坐标可能来自模拟器默认/异地（如 Mountain View），
            // 宁可不显示、也不误导。冷启动已由 location/local 恢复上次城市，此处仍无城市再提示。
            if (currentCity.isEmpty()) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, getString(R.string.location_unavailable), Toast.LENGTH_SHORT).show();
                    showCityFallbackIfPending();
                });
            }
        };
        mainHandler.postDelayed(locationTimeoutRunnable, 10000);
    }

    private void saveSearchedCityToServer(String cityName) {
        JSONObject json = new JSONObject();
        try {
            json.put("cityName", cityName);
            json.put("latitude", 0);
            json.put("longitude", 0);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        postLocation(json);
    }

    private void saveLocationToServer(double lat, double lon) {
        JSONObject json = new JSONObject();
        try {
            json.put("latitude", lat);
            json.put("longitude", lon);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        postLocation(json);
    }

    private void postLocation(JSONObject json) {
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json.toString());
        Request request = new Request.Builder()
                .url(BASE_URL + "location/save")
                .post(body)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }

    // ==================== 辅助 ====================

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private void setDouble(TextView tv, double v, String unit, int digits) {
        if (Double.isNaN(v)) {
            tv.setText("--");
            return;
        }
        // 数字与单位分开拼：unit 可能含 "%"，不能拼进 format 串，否则触发 UnknownFormatConversionException
        String fmt = "%." + digits + "f";
        tv.setText(String.format(Locale.ROOT, fmt, v) + unit);
    }

    private void setPoll(TextView tv, JSONObject d, String key, String code) {
        double v = d.optDouble(key, Double.NaN);
        if (Double.isNaN(v)) {
            tv.setText("--");
            return;
        }
        tv.setText("co".equals(code) ? String.format(Locale.ROOT, "%.2f", v) : String.valueOf(Math.round(v)));
    }

    private static String percentStr(String cloud) {
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

    private int attrColor(int attrRes) {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(attrRes, tv, true);
        return tv.data;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isNetworkAvailable() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }
}
