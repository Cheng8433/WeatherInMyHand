package com.smog.weatherapp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Looper;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_LOCATION_PERMISSION = 1;
    // 后端 API 基础地址（真机调试需改为电脑局域网 IP）
    private static final String BASE_URL = "http://10.198.105.198:8080/api/";

    // UI 控件
    private TextView tvCityName, tvAqi, tvAirQuality, tvPm25, tvPm10, tvWeather, tvTemperature, tvHumidity;

    // 在 initViews() 方法末尾添加控件初始化
    private EditText etSearchCity;
    private ImageView ivSearch, ivRefreshLocation;
    private Button  btnViewDetails;

    private OkHttpClient httpClient = new OkHttpClient();
    private LocationManager locationManager;
    private String currentCity = "";      // 仍用于显示和跳转，数据来自后端

    private boolean hasPerformedInitialLocation = false; // 标记是否已执行过初始定位
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        // 先尝试加载服务器保存的城市
        loadCurrentLocationFromServer();
        // 不再直接调用 checkLocationPermission()，改为在服务器加载失败时触发
    }

    private void initViews() {
        tvCityName = findViewById(R.id.tvCityName);
        tvAqi = findViewById(R.id.tvAqi);
        tvAirQuality = findViewById(R.id.tvAirQuality);
        tvPm25 = findViewById(R.id.tvPm25);
        tvPm10 = findViewById(R.id.tvPm10);
        tvWeather = findViewById(R.id.tvWeather);
        tvTemperature = findViewById(R.id.tvTemperature);
        tvHumidity = findViewById(R.id.tvHumidity);
        btnViewDetails = findViewById(R.id.btnViewDetails);

        etSearchCity = findViewById(R.id.etSearchCity);
        ivSearch = findViewById(R.id.ivSearch);

        btnViewDetails.setOnClickListener(v -> {
            if (!currentCity.isEmpty()) {
                Intent intent = new Intent(MainActivity.this, WeatherDetailActivity.class);
                intent.putExtra("city", currentCity);
                startActivity(intent);
            } else {
                Toast.makeText(MainActivity.this, "请先获取定位", Toast.LENGTH_SHORT).show();
            }
        });

        // 搜索功能
        ivSearch.setOnClickListener(v -> {
            String city = etSearchCity.getText().toString().trim();
            if (!city.isEmpty()) {
                searchWeatherByCity(city);
            } else {
                Toast.makeText(this, "请输入城市名称", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 根据用户输入的城市名主动查询天气
     */
    private void searchWeatherByCity(String cityName) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        // 直接调用后端接口（假设后端支持 /weather/info?city=xxx）
        String url = BASE_URL + "weather/info?city=" + cityName;
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "网络错误", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        boolean success = json.optBoolean("success", false);
                        if (success) {
                            JSONObject data = json.optJSONObject("data");
                            runOnUiThread(() -> {
                                // 更新当前城市（后端可能返回标准城市名）
                                if (data.has("cityName")) {
                                    currentCity = data.optString("cityName");
                                    tvCityName.setText(currentCity);
                                } else {
                                    currentCity = cityName;
                                    tvCityName.setText(cityName);
                                }
                                updateWeatherUI(data);
                                // 可选：将搜索的城市保存到服务器（让下次冷启动使用）
                                saveSearchedCityToServer(currentCity);
                            });
                        } else {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "未找到该城市天气信息", Toast.LENGTH_SHORT).show());
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "数据解析失败", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "服务器响应错误", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    /**
     * 手动搜索后，可以将城市保存到后端（作为下次启动的默认城市）
     */
    private void saveSearchedCityToServer(String cityName) {
        JSONObject json = new JSONObject();
        try {
            json.put("cityName", cityName);
            json.put("latitude", 0); // 没有经纬度，只传城市名
            json.put("longitude", 0);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json.toString());
        Request request = new Request.Builder()
                .url(BASE_URL + "location/save")
                .post(body)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) { }
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }
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

    /**
     * 获取当前设备位置（只获取经纬度，不做逆地理编码）
     */
    private void getCurrentLocation() {
        if (hasPerformedInitialLocation && !currentCity.isEmpty()) {
            // 已经初始化过且已有城市，不再自动定位（防止搜索后被覆盖）
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
                // 获取到经纬度后直接传给后端
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                saveLocationToServer(lat, lon);      // 保存经纬度（后端会解析城市）
                loadWeatherDataByLocation(lat, lon); // 用经纬度请求天气
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Toast.makeText(MainActivity.this, provider + " 定位已关闭", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) { }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) { }
        };

        if (isGpsEnabled) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
        } else if (isNetworkEnabled) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
        }

        // 超时处理：10秒后仍未获取新位置，尝试最后已知位置
        new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
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
                double lat = lastKnown.getLatitude();
                double lon = lastKnown.getLongitude();
                saveLocationToServer(lat, lon);
                loadWeatherDataByLocation(lat, lon);
            } else {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "无法获取位置，请检查 GPS 或网络", Toast.LENGTH_SHORT).show());
            }
        }, 10000);
    }

    /**
     * 将经纬度保存到后端（不再需要前端传递城市名）
     */
    private void saveLocationToServer(double lat, double lon) {
        JSONObject json = new JSONObject();
        try {
            json.put("latitude", lat);
            json.put("longitude", lon);
        } catch (JSONException e) {
            e.printStackTrace();
        }

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json.toString());
        Request request = new Request.Builder()
                .url(BASE_URL + "location/save")
                .post(body)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "保存定位失败", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close(); // 静默处理
            }
        });
    }

    /**
     * 用经纬度从后端获取天气数据（后端应支持 lat, lon 参数）
     */
    private void loadWeatherDataByLocation(double lat, double lon) {
        String url = BASE_URL + "weather/info?lat=" + lat + "&lon=" + lon;
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "加载天气数据失败", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        boolean success = json.optBoolean("success", false);
                        if (success) {
                            JSONObject data = json.optJSONObject("data");
                            runOnUiThread(() -> {
                                // 后端返回的数据中应包含城市名，用于界面显示
                                if (data != null && data.has("cityName")) {
                                    currentCity = data.optString("cityName");
                                    tvCityName.setText(currentCity);
                                }
                                updateWeatherUI(data);
                            });
                        } else {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "获取天气数据失败", Toast.LENGTH_SHORT).show());
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "天气数据解析错误", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "服务器响应错误", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    /**
     * 从服务器加载上次保存的位置（用于冷启动恢复）
     * 如果成功加载到城市，就不再请求定位；否则进行一次初始定位。
     */
    private void loadCurrentLocationFromServer() {
        Request request = new Request.Builder().url(BASE_URL + "location/local").build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // 网络失败，尝试定位作为降级
                runOnUiThread(() -> performInitialLocationIfNeeded());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        boolean success = json.optBoolean("success", false);
                        if (success) {
                            JSONObject data = json.optJSONObject("data");
                            if (data != null) {
                                String city = data.optString("cityName", "");
                                if (!city.isEmpty()) {
                                    runOnUiThread(() -> {
                                        currentCity = city;
                                        tvCityName.setText(city);
                                        loadWeatherData(city);   // 用城市加载天气
                                    });
                                    return; // 已有城市，不再定位
                                }
                            }
                        }
                        // 服务器没有保存城市，进行初始定位
                        runOnUiThread(() -> performInitialLocationIfNeeded());
                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> performInitialLocationIfNeeded());
                    }
                } else {
                    runOnUiThread(() -> performInitialLocationIfNeeded());
                }
            }
        });
    }

    /**
     * 执行一次初始定位（仅当尚未执行过且当前城市为空时）
     */
    private void performInitialLocationIfNeeded() {
        if (!hasPerformedInitialLocation && currentCity.isEmpty()) {
            hasPerformedInitialLocation = true;
            checkLocationPermission(); // 内部会调用 getCurrentLocation()
        } else {
            // 已有城市，不再定位
            if (!currentCity.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "当前城市：" + currentCity, Toast.LENGTH_SHORT).show());
            }
        }
    }


    /**
     * 兼容旧接口：用城市名加载天气（保留用于服务器恢复）
     */
    private void loadWeatherData(String city) {
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?city=" + city)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "加载天气数据失败", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        boolean success = json.optBoolean("success", false);
                        if (success) {
                            JSONObject data = json.optJSONObject("data");
                            runOnUiThread(() -> updateWeatherUI(data));
                        } else {
                            runOnUiThread(() -> Toast.makeText(MainActivity.this, "获取天气数据失败", Toast.LENGTH_SHORT).show());
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "天气数据解析错误", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "服务器响应错误", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void updateWeatherUI(JSONObject data) {
        if (data == null) return;

        int aqi = data.optInt("aqi", 0);
        tvAqi.setText(String.valueOf(aqi));
        tvAirQuality.setText(data.optString("airQuality", "未知"));
        tvPm25.setText(data.optString("pm25", "--"));
        tvPm10.setText(data.optString("pm10", "--"));
        tvWeather.setText(data.optString("weather", "--"));
        double temp = data.optDouble("temperature", 0);
        tvTemperature.setText(String.format("%.1f°C", temp));
        double humidity = data.optDouble("humidity", 0);
        tvHumidity.setText(String.format("%.1f%%", humidity));

        setAirQualityColor(aqi);
    }

    private void setAirQualityColor(int aqi) {
        int color;
        if (aqi <= 50) color = 0xFF4CAF50;
        else if (aqi <= 100) color = 0xFFFFEB3B;
        else if (aqi <= 150) color = 0xFFFF9800;
        else if (aqi <= 200) color = 0xFFF44336;
        else color = 0xFF9C27B0;
        tvAqi.setTextColor(color);
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