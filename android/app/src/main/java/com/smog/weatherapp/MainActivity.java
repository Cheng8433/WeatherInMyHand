package com.smog.weatherapp;

import android.Manifest;
import android.content.Context;
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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private ImageButton btnSearch, btnRefresh, btnTheme;

    // 三页根容器
    private ScrollView scrollToday, scrollAir, scrollTrend;
    private BottomNavigationView bottomNav;

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
    private String currentCity = "";

    private boolean hasPerformedInitialLocation = false;
    private boolean isGpsResultApplied = false;

    private JSONObject lastData = null;
    private int currentTab = TAB_TODAY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 必须在 setContentView 之前（且在 super.onCreate 前），否则状态栏/窗口配色不随主题变
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        showTab(TAB_TODAY);
        bottomNav.setSelectedItemId(R.id.nav_today);
        loadCurrentLocationFromServer();       // 先显示上次查看的城市，零等待
        checkLocationPermission();             // 后台同时进行 GPS 定位
    }

    private void initViews() {
        tvCityName = findViewById(R.id.tvCityName);
        etSearchCity = findViewById(R.id.etSearchCity);
        btnSearch = findViewById(R.id.btnSearch);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnTheme = findViewById(R.id.btnTheme);

        scrollToday = findViewById(R.id.scrollToday);
        scrollAir = findViewById(R.id.scrollAir);
        scrollTrend = findViewById(R.id.scrollTrend);
        bottomNav = findViewById(R.id.bottomNav);

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

        btnSearch.setOnClickListener(v -> {
            String city = etSearchCity.getText().toString().trim();
            if (city.isEmpty()) {
                Toast.makeText(this, "请输入城市名称", Toast.LENGTH_SHORT).show();
            } else {
                searchWeatherByCity(city);
            }
        });
        btnRefresh.setOnClickListener(v -> {
            if (currentCity.isEmpty()) {
                Toast.makeText(this, "还没有可刷新的城市，先搜索或定位", Toast.LENGTH_SHORT).show();
            } else {
                loadWeatherData(currentCity);
            }
        });
        btnTheme.setOnClickListener(v -> ThemeHelper.showPicker(this, () -> recreate()));

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
        // AnyChartView 在 GONE 状态下不渲染，切到“趋势”且已有数据时再画
        if (tab == TAB_TREND && lastData != null) {
            renderTrend(lastData);
        }
    }

    // ==================== 数据落地：统一渲染三页 ====================

    private void applyAllPages(JSONObject data) {
        if (data == null) return;
        lastData = data;
        renderToday(data);
        renderAir(data);
        if (currentTab == TAB_TREND) {
            renderTrend(data);
        }
    }

    private void renderToday(JSONObject d) {
        String weather = d.optString("weather", "");
        tvWeatherEmoji.setText(WeatherFormat.emojiFor(weather));
        tvWeatherDesc.setText(weather.isEmpty() ? "--" : weather);

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
        tvHeroSub.setText("体感 " + feelsTxt + " · 湿度 " + humTxt);

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
        tvWindSub.setText(Double.isNaN(spd) ? "--" : ("风速 " + Math.round(spd) + " km/h"));
    }

    private void renderAir(JSONObject d) {
        int aqi = d.optInt("aqi", -1);
        if (aqi < 0) {
            tvAqiNum.setText("--");
            tvAqiLevel.setText("--");
            tvAqiAssessment.setText("暂无空气质量数据");
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
            tvTrendSummary.setText("暂无逐小时预报数据");
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
            tvTrendSummary.setText("未来 24 小时 · 最高 " + maxTxt + " · 最低 " + minTxt);
            Cartesian chart = AnyChart.line();
            chart.line(tempData).name("温度 (°C)");
            chart.line(humData).name("湿度 (%)");
            String txt = "#" + Integer.toHexString(0xFFFFFF & attrColor(R.attr.textPrimary));
            String txtSec = "#" + Integer.toHexString(0xFFFFFF & attrColor(R.attr.textSecondary));
            chart.background("#00000000");
            chart.xAxis(0).labels().fontColor(txtSec);
            chart.yAxis(0).labels().fontColor(txtSec);
            chart.legend().fontColor(txt);
            chartHourly.setChart(chart);
        } else {
            tvTrendSummary.setText("暂无逐小时预报数据");
            chartHourly.setChart(null);
        }
    }

    // ==================== 请求 ====================

    private void searchWeatherByCity(String cityName) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = BASE_URL + "weather/info?city=" + urlEncode(cityName);
        httpClient.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "网络错误", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handleJson(response, new JsonHandler() {
                    @Override
                    public void onData(JSONObject data) {
                        runOnUiThread(() -> {
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
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                msg == null || msg.isEmpty() ? "未找到该城市天气信息" : msg,
                                Toast.LENGTH_SHORT).show());
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
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?city=" + urlEncode(city))
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "加载天气数据失败", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handleJson(response, new JsonHandler() {
                    @Override
                    public void onData(JSONObject data) {
                        runOnUiThread(() -> {
                            if (!guardByGps || !isGpsResultApplied) {
                                applyAllPages(data);
                            }
                        });
                    }

                    @Override
                    public void onFail(String msg) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                msg == null || msg.isEmpty() ? "获取天气数据失败" : msg,
                                Toast.LENGTH_SHORT).show());
                    }
                });
            }
        });
    }

    private void loadWeatherDataByLocation(double lat, double lon) {
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?lat=" + lat + "&lon=" + lon)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "加载天气数据失败", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                handleJson(response, new JsonHandler() {
                    @Override
                    public void onData(JSONObject data) {
                        runOnUiThread(() -> {
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
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                msg == null || msg.isEmpty() ? "获取天气数据失败" : msg,
                                Toast.LENGTH_SHORT).show());
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
    private void handleJson(Response response, JsonHandler handler) throws IOException {
        boolean successful = response.isSuccessful();
        String body = response.body() == null ? "" : response.body().string();
        if (successful) {
            try {
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("success", false)) {
                    handler.onData(json.optJSONObject("data"));
                } else {
                    handler.onFail(json.optString("message", ""));
                }
            } catch (JSONException e) {
                handler.onFail("解析失败");
            }
        } else {
            handler.onFail("服务器错误 " + response.code());
        }
    }

    private interface JsonHandler {
        void onData(JSONObject data);

        void onFail(String msg);
    }

    // ==================== 定位（沿用原逻辑） ====================

    private void checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_LOCATION_PERMISSION);
        } else {
            getCurrentLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation();
            } else {
                Toast.makeText(this, "需要定位权限才能显示位置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void getCurrentLocation() {
        if (hasPerformedInitialLocation && !currentCity.isEmpty()) {
            return;
        }
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "网络不可用，无法获取定位", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "请开启位置服务（GPS 或网络定位）", Toast.LENGTH_SHORT).show();
            return;
        }

        LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                locationManager.removeUpdates(this);
                saveLocationToServer(location.getLatitude(), location.getLongitude());
                loadWeatherDataByLocation(location.getLatitude(), location.getLongitude());
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Toast.makeText(MainActivity.this, provider + " 定位已关闭", Toast.LENGTH_SHORT).show();
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

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (locationManager != null) {
                locationManager.removeUpdates(locationListener);
            }
            Location lastKnown = null;
            if (isGpsEnabled) {
                lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (lastKnown == null && isNetworkEnabled) {
                lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (lastKnown != null) {
                saveLocationToServer(lastKnown.getLatitude(), lastKnown.getLongitude());
                loadWeatherDataByLocation(lastKnown.getLatitude(), lastKnown.getLongitude());
            } else {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "无法获取位置，请检查 GPS 或网络", Toast.LENGTH_SHORT).show());
            }
        }, 10000);
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
