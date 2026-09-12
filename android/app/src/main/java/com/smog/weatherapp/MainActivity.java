package com.smog.weatherapp;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.smog.weatherapp.net.NetworkStatus;
import com.smog.weatherapp.net.WeatherApi;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

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
    /** 动效天空：切页时要显隐，故留在 Activity；三页渲染所需的其他控件都在 PageRenderer 内 */
    private WeatherSceneView weatherScene;

    /** 三页渲染。在 initViews 里 new（构造要 findViewById） */
    private PageRenderer pages;

    /** 后端 HTTP 客户端。在 onCreate 里 new（构造要取 Resources），不能在字段初始化器里建 */
    private WeatherApi weatherApi;

    /** 一次性定位。在 onCreate 里 new（构造要 Activity），onDestroy 必须 stop() */
    private LocationHelper location;
    private String currentCity = "";

    /** 请求序号：只允许「最后发起」的那次结果落到 UI，避免并发返回时旧城市覆盖新城市 */
    private int uiApplySeq = 0;

    private JSONObject lastData = null;
    private int currentTab = TAB_TODAY;

    // 首启隐私同意：未同意前不发起任何定位/联网取数
    private boolean weatherStarted = false;
    private boolean privacyDialogUp = false;

    // 全局加载/错误重试 UI。在 initViews 里 new（构造要 findViewById），见 LoadOverlay
    private LoadOverlay overlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 setContentView 之前（且在 super.onCreate 前），否则状态栏/窗口配色不随主题变
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 必须在这里 new 而不是字段初始化器：WeatherApi 构造要取 Resources，
        // 而字段初始化器跑在 Activity 构造期，那时 attach() 还没发生、getResources() 会抛。
        weatherApi = new WeatherApi(this);
        location = new LocationHelper(this, new LocationHelper.Callback() {
            @Override
            public void onLocation(double latitude, double longitude) {
                loadWeatherDataByLocation(latitude, longitude);
            }

            @Override
            public void onLocationAborted() {
                showCityFallbackIfPending();
            }

            @Override
            public void onLocationTimeout() {
                // 不做「最后已知位置」兜底（见 LocationHelper）；已有城市可显示时静默，别吓人
                if (currentCity.isEmpty()) {
                    Toast.makeText(MainActivity.this, getString(R.string.location_unavailable),
                            Toast.LENGTH_SHORT).show();
                    showCityFallbackIfPending();
                }
            }
        });

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
        if (location != null) {
            location.stop();
        }
        // 取消所有在途 HTTP 请求，避免回调持有已销毁的 Activity
        if (weatherApi != null) {
            weatherApi.cancelAll();
        }
    }

    /** 通过隐私同意后才执行的天气主流程（本地缓存秒开 + GPS 定位）。 */
    private void startWeatherFlow() {
        weatherStarted = true;
        showTab(TAB_TODAY);
        bottomNav.setSelectedItemId(R.id.nav_today);
        paintLastCachedWeather();              // 启动先用本地缓存秒开（断网也有内容），联网后刷新覆盖
        location.checkLocationPermission();    // 后台同时进行 GPS 定位
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

        // 三页渲染：三页所需控件的查找都在 PageRenderer 内部完成（weatherScene 切页也要用，故传入）
        pages = new PageRenderer(this, weatherScene);
        // 加载条 / 错误重试浮层：视图查找与两个按钮的接线都在 LoadOverlay 内部完成
        overlay = new LoadOverlay(this);

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
            pages.renderTrend(lastData);
        }
    }

    // ==================== 数据落地：统一渲染三页 ====================

    /**
     * @param offline 数据并非本次实时取得：后端上游失败降级（stale）或直接读的本地缓存。
     *                页脚据此明示「离线缓存」，避免用户把旧数据当实时。
     */
    private void applyAllPages(JSONObject data, boolean offline) {
        if (data == null) return;
        lastData = data;
        overlay.hideErrorPanel();   // 有内容可看就把错误浮层收起
        // 每次成功渲染都落地本地缓存（含后台降级的旧数据），供断网/失败兜底
        String city = data.optString("cityName", currentCity);
        if (!city.isEmpty()) {
            WeatherCache.save(this, city, data);
        }
        pages.renderDataTime(data, offline);
        pages.renderToday(data);
        pages.renderAir(data);
        if (currentTab == TAB_TREND) {
            pages.renderTrend(data);
        }
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
            applyAllPages(cached, true);   // 本地缓存：明示为离线数据
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
        overlay.endLoad();
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
                applyAllPages(cached, true);   // 兜底读的是本地缓存：明示为离线数据
                Toast.makeText(this, getString(R.string.toast_use_cache), Toast.LENGTH_SHORT).show();
            } else if (lastData != null) {
                Toast.makeText(this,
                        errorMsg == null || errorMsg.isEmpty() ? getString(R.string.error_load_failed) : errorMsg,
                        Toast.LENGTH_SHORT).show();
            } else {
                overlay.showErrorPanel(errorMsg == null || errorMsg.isEmpty()
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
        overlay.endLoad();
        JSONObject exact = WeatherCache.get(this, city);
        runOnUiThread(() -> {
            if (exact != null) {
                String cn = exact.optString("cityName", city);
                currentCity = cn;
                tvCityName.setText(cn);
                applyAllPages(exact, true);   // 搜索失败后回落到该城缓存：明示为离线数据
                Toast.makeText(this, getString(R.string.toast_use_cache), Toast.LENGTH_SHORT).show();
            } else {
                overlay.showErrorPanel(errorMsg == null || errorMsg.isEmpty()
                        ? getString(R.string.error_city_not_found) : errorMsg);
            }
        });
    }

    // ==================== 请求 ====================

    private void searchWeatherByCity(String cityName) {
        overlay.setRetryAction(() -> searchWeatherByCity(cityName));
        if (!NetworkStatus.isOnline(this)) {
            handleSearchFailure(cityName, getString(R.string.error_no_network));
            return;
        }
        final int reqSeq = ++uiApplySeq;   // 本次搜索成为最新请求，更早的在途结果回来后会被丢弃
        overlay.beginLoad();
        weatherApi.fetchByCity(cityName, new WeatherApi.ResultCallback() {
            @Override
            public void onData(JSONObject data, boolean stale) {
                runOnUiThread(() -> {
                    overlay.endLoad();
                    if (reqSeq != uiApplySeq) return;   // 已被更晚的请求取代
                    if (data != null && data.has("cityName")) {
                        currentCity = data.optString("cityName");
                    } else {
                        currentCity = cityName;
                    }
                    tvCityName.setText(currentCity);
                    applyAllPages(data, stale);
                    weatherApi.saveCity(currentCity);
                });
            }

            @Override
            public void onTransportError() {
                if (reqSeq != uiApplySeq) {
                    overlay.endLoad();
                    return;
                }
                handleSearchFailure(cityName, getString(R.string.error_network_error));
            }

            @Override
            public void onFail(String msg) {
                if (reqSeq != uiApplySeq) {
                    overlay.endLoad();
                    return;
                }
                handleSearchFailure(cityName,
                        msg == null || msg.isEmpty() ? getString(R.string.error_search_not_found) : msg);
            }
        });
    }

    private void loadWeatherData(String city) {
        if (city == null || city.trim().isEmpty()) return;
        final int reqSeq = ++uiApplySeq;
        overlay.setRetryAction(() -> loadWeatherData(city));
        overlay.beginLoad();
        weatherApi.fetchByCity(city, new WeatherApi.ResultCallback() {
            @Override
            public void onData(JSONObject data, boolean stale) {
                runOnUiThread(() -> {
                    overlay.endLoad();
                    if (reqSeq != uiApplySeq) return;   // 已被更晚的请求取代
                    applyAllPages(data, stale);
                });
            }

            @Override
            public void onTransportError() {
                if (reqSeq != uiApplySeq) {
                    overlay.endLoad();
                    return;
                }
                fallbackToCache(city, getString(R.string.error_load_weather_failed));
            }

            @Override
            public void onFail(String msg) {
                if (reqSeq != uiApplySeq) {
                    overlay.endLoad();
                    return;
                }
                fallbackToCache(city,
                        msg == null || msg.isEmpty() ? getString(R.string.error_get_weather_failed) : msg);
            }
        });
    }

    private void loadWeatherDataByLocation(double lat, double lon) {
        String lastCity = currentCity;   // GPS 结果尚未返回前，用当前城市做缓存兜底
        final int reqSeq = ++uiApplySeq; // 新的定位结果成为最新请求，覆盖更早的搜索结果
        overlay.setRetryAction(() -> loadWeatherDataByLocation(lat, lon));
        overlay.beginLoad();
        weatherApi.fetchByLatLon(lat, lon, new WeatherApi.ResultCallback() {
            @Override
            public void onData(JSONObject data, boolean stale) {
                runOnUiThread(() -> {
                    overlay.endLoad();
                    if (reqSeq != uiApplySeq) return;   // 已被更晚的请求取代
                    if (data != null && data.has("cityName")) {
                        currentCity = data.optString("cityName");
                        tvCityName.setText(currentCity);
                    }
                    applyAllPages(data, stale);
                });
            }

            @Override
            public void onTransportError() {
                if (reqSeq != uiApplySeq) {
                    overlay.endLoad();
                    return;
                }
                fallbackToCache(lastCity, getString(R.string.error_load_weather_failed));
            }

            @Override
            public void onFail(String msg) {
                if (reqSeq != uiApplySeq) {
                    overlay.endLoad();
                    return;
                }
                fallbackToCache(lastCity,
                        msg == null || msg.isEmpty() ? getString(R.string.error_get_weather_failed) : msg);
            }
        });
    }

    // ==================== 定位 ====================

    /** 权限回调是 Activity 的框架方法、不能搬走，这里转交给 LocationHelper。 */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        location.onPermissionResult(requestCode, grantResults);
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
}
