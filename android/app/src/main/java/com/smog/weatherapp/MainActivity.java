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
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

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

    private TextView tvCityName, tvAqi, tvAirQuality, tvPm25, tvPm10, tvWeather, tvTemperature, tvHumidity;

    private EditText etSearchCity;
    private Button btnSearch;
    private Button btnViewDetails;

    private OkHttpClient httpClient = new OkHttpClient();
    private LocationManager locationManager;
    private String currentCity = "";

    private boolean hasPerformedInitialLocation = false;
    private boolean isGpsResultApplied = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadCurrentLocationFromServer();  // 先显示上次查看的城市，零等待
        checkLocationPermission();        // 后台同时进行 GPS 定位
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
        btnSearch = findViewById(R.id.ivSearch);

        btnViewDetails.setOnClickListener(v -> {
            if (!currentCity.isEmpty()) {
                Intent intent = new Intent(MainActivity.this, WeatherDetailActivity.class);
                intent.putExtra("city", currentCity);
                startActivity(intent);
            } else {
                Toast.makeText(MainActivity.this, "请先获取定位", Toast.LENGTH_SHORT).show();
            }
        });

        btnSearch.setOnClickListener(v -> {
            String city = etSearchCity.getText().toString().trim();
            if (!city.isEmpty()) {
                searchWeatherByCity(city);
            } else {
                Toast.makeText(this, "请输入城市名称", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void searchWeatherByCity(String cityName) {
        if (!isNetworkAvailable()) {
            Toast.makeText(this, "网络不可用", Toast.LENGTH_SHORT).show();
            return;
        }

        String encodedCity;
        try {
            encodedCity = URLEncoder.encode(cityName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encodedCity = cityName; // 如果编码失败，使用原始城市名
        }
        String url = BASE_URL + "weather/info?city=" + encodedCity;
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
                                if (data != null && data.has("cityName")) {
                                    currentCity = data.optString("cityName");
                                    tvCityName.setText(currentCity);
                                } else {
                                    currentCity = cityName;
                                    tvCityName.setText(cityName);
                                }
                                updateWeatherUI(data);
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

    private void saveSearchedCityToServer(String cityName) {
        JSONObject json = new JSONObject();
        try {
            json.put("cityName", cityName);
            json.put("latitude", 0);
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
                double lat = location.getLatitude();
                double lon = location.getLongitude();
                saveLocationToServer(lat, lon);
                loadWeatherDataByLocation(lat, lon);
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
                response.close();
            }
        });
    }

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
                                if (data != null && data.has("cityName")) {
                                    isGpsResultApplied = true;
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
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "服务器响应错误: " + response.code(), Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void loadCurrentLocationFromServer() {
        Request request = new Request.Builder().url(BASE_URL + "location/local").build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) { }

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
                                        if (!isGpsResultApplied) {
                                            currentCity = city;
                                            tvCityName.setText(city);
                                            loadWeatherData(city);
                                        }
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

    private void performInitialLocationIfNeeded() {
        if (!hasPerformedInitialLocation && currentCity.isEmpty()) {
            hasPerformedInitialLocation = true;
            checkLocationPermission();
        } else {
            if (!currentCity.isEmpty()) {
                runOnUiThread(() -> Toast.makeText(this, "当前城市：" + currentCity, Toast.LENGTH_SHORT).show());
            }
        }
    }

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
                            runOnUiThread(() -> {
                                if (!isGpsResultApplied) {
                                    updateWeatherUI(data);
                                }
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
