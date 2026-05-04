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
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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

    // 请求定位权限的请求码
    private static final int REQUEST_LOCATION_PERMISSION = 1;

    // 后端 API 基础地址（模拟器访问宿主机用 10.0.2.2，真机调试需改为局域网 IP）
    private static final String BASE_URL = "http://10.0.2.2:8080/api/";

    // 百度地图逆地理编码 AK（请替换为自己申请的 AK）
    private static final String BAIDU_MAP_AK = "YOUR_BAIDU_AK";

    // UI 控件
    private TextView tvCityName, tvAqi, tvAirQuality, tvPm25, tvPm10, tvWeather, tvTemperature, tvHumidity;
    private Button btnRefreshLocation, btnViewDetails;

    // 网络客户端
    private OkHttpClient httpClient = new OkHttpClient();

    // 定位服务
    private LocationManager locationManager;

    // 当前城市名（用于详情页跳转）
    private String currentCity = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();                     // 初始化控件和点击事件
        checkLocationPermission();       // 检查并请求定位权限
        loadCurrentLocationFromServer(); // 尝试从服务器加载上次保存的位置
    }

    /**
     * 初始化所有 UI 控件，并设置按钮监听器
     */
    private void initViews() {
        tvCityName = findViewById(R.id.tvCityName);
        tvAqi = findViewById(R.id.tvAqi);
        tvAirQuality = findViewById(R.id.tvAirQuality);
        tvPm25 = findViewById(R.id.tvPm25);
        tvPm10 = findViewById(R.id.tvPm10);
        tvWeather = findViewById(R.id.tvWeather);
        tvTemperature = findViewById(R.id.tvTemperature);
        tvHumidity = findViewById(R.id.tvHumidity);
        btnRefreshLocation = findViewById(R.id.btnRefreshLocation);
        btnViewDetails = findViewById(R.id.btnViewDetails);

        // 刷新位置按钮：重新获取当前位置
        btnRefreshLocation.setOnClickListener(v -> getCurrentLocation());

        // 查看详情按钮：跳转到 WeatherDetailActivity
        btnViewDetails.setOnClickListener(v -> {
            if (!currentCity.isEmpty()) {
                Intent intent = new Intent(MainActivity.this, WeatherDetailActivity.class);
                intent.putExtra("city", currentCity);
                startActivity(intent);
            } else {
                Toast.makeText(MainActivity.this, "请先获取定位", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 检查定位权限状态，未授权则申请，否则直接获取位置
     */
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
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getCurrentLocation();
            } else {
                Toast.makeText(this, "需要定位权限才能显示位置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 获取当前设备位置（使用 LocationListener 请求最新位置）
     */
    private void getCurrentLocation() {
        // 检查网络是否可用（可选，提高用户体验）
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "网络不可用，无法获取定位", Toast.LENGTH_SHORT).show();
            return;
        }

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // 优先使用 GPS，如果 GPS 未开启则使用网络定位
        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGpsEnabled && !isNetworkEnabled) {
            Toast.makeText(this, "请开启位置服务（GPS 或网络定位）", Toast.LENGTH_SHORT).show();
            return;
        }

        // 注册位置监听器，超时 10 秒后自动移除
        LocationListener locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                // 获取到有效位置后，立即停止定位更新
                locationManager.removeUpdates(this);
                reverseGeocode(location.getLatitude(), location.getLongitude());
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

        // 请求位置更新（若只想要单次定位，可使用 requestSingleUpdate，但兼容性稍差）
        // 这里使用最小时间间隔 0，最小距离 0，立刻返回最新位置
        if (isGpsEnabled) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
        } else if (isNetworkEnabled) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
        }

        // 设置超时：10 秒后如果还没收到位置，使用最后已知位置或提示失败
        new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (locationManager != null) {
                locationManager.removeUpdates(locationListener);
            }
            // 超时后尝试获取最后已知位置
            Location lastKnown = null;
            if (isGpsEnabled) {
                lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (lastKnown == null && isNetworkEnabled) {
                lastKnown = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (lastKnown != null) {
                reverseGeocode(lastKnown.getLatitude(), lastKnown.getLongitude());
            } else {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "无法获取位置，请检查 GPS 或网络", Toast.LENGTH_SHORT).show());
            }
        }, 10000);
    }

    /**
     * 使用百度地图逆地理编码 API 将经纬度转换为城市名
     * @param lat 纬度
     * @param lon 经度
     */
    private void reverseGeocode(double lat, double lon) {
        // 修正参数顺序：百度要求 location=纬度,经度
        String url = "https://api.map.baidu.com/reverse_geocoding/v3/?ak=" + BAIDU_MAP_AK
                + "&location=" + lat + "," + lon + "&output=json&pois=0";

        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "逆地理编码网络错误", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        JSONObject result = json.getJSONObject("result");
                        JSONObject addressComponent = result.getJSONObject("address_component");
                        String city = addressComponent.getString("city");
                        // 去除可能的后缀“市”、“自治州”等简化显示
                        String cityName = city.replace("市", "").replace("自治州", "");
                        runOnUiThread(() -> {
                            currentCity = cityName;
                            tvCityName.setText(currentCity);
                            // 保存当前城市及坐标到服务器
                            saveLocationToServer(currentCity, lat, lon);
                            // 加载天气数据
                            loadWeatherData(currentCity);
                        });
                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "解析位置信息失败", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "逆地理编码请求失败", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    /**
     * 将当前城市和经纬度保存到后端
     */
    private void saveLocationToServer(String cityName, double lat, double lon) {
        JSONObject json = new JSONObject();
        try {
            json.put("cityName", cityName);
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
                // 保存成功无需额外提示，静默处理
                response.close();
            }
        });
    }

    /**
     * 从服务器加载上次保存的城市和天气信息（用于冷启动恢复）
     */
    private void loadCurrentLocationFromServer() {
        Request request = new Request.Builder().url(BASE_URL + "location/current").build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // 网络异常或服务器未启动，不做处理，等待用户手动刷新
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
                                        loadWeatherData(city);
                                    });
                                }
                            }
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    /**
     * 从后端获取指定城市的空气质量及天气数据
     * @param city 城市名
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

    /**
     * 更新界面上的天气和空气质量信息
     * @param data 包含 aqi, airQuality, pm25, pm10, weather, temperature, humidity 的 JSONObject
     */
    private void updateWeatherUI(JSONObject data) {
        if (data == null) {
            return;
        }

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

    /**
     * 根据 AQI 值修改数字显示颜色
     * @param aqi 空气质量指数
     */
    private void setAirQualityColor(int aqi) {
        int color;
        if (aqi <= 50) {
            color = 0xFF4CAF50; // 绿色 - 优
        } else if (aqi <= 100) {
            color = 0xFFFFEB3B; // 黄色 - 良
        } else if (aqi <= 150) {
            color = 0xFFFF9800; // 橙色 - 轻度污染
        } else if (aqi <= 200) {
            color = 0xFFF44336; // 红色 - 中度污染
        } else {
            color = 0xFF9C27B0; // 紫色 - 重度污染
        }
        tvAqi.setTextColor(color);
    }

    /**
     * 检查当前是否有可用的网络连接
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }
}