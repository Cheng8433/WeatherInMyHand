package com.smog.weatherapp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_LOCATION_PERMISSION = 1;
    private static final String BASE_URL = "http://10.0.2.2:8080/api/";

    private TextView tvCityName, tvAqi, tvAirQuality, tvPm25, tvPm10, tvWeather, tvTemperature, tvHumidity;
    private Button btnRefreshLocation, btnViewDetails;

    private OkHttpClient httpClient = new OkHttpClient();
    private LocationManager locationManager;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private String currentCity = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkLocationPermission();
        loadCurrentLocationFromServer();
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
        btnRefreshLocation = findViewById(R.id.btnRefreshLocation);
        btnViewDetails = findViewById(R.id.btnViewDetails);

        btnRefreshLocation.setOnClickListener(v -> getLocation());
        btnViewDetails.setOnClickListener(v -> {
            if (!currentCity.isEmpty()) {
                Intent intent = new Intent(MainActivity.this, WeatherDetailActivity.class);
                intent.putExtra("city", currentCity);
                startActivity(intent);
            } else {
                Toast.makeText(this, "请先获取定位", Toast.LENGTH_SHORT).show();
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
            getLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getLocation();
            } else {
                Toast.makeText(this, "需要定位权限才能显示位置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void getLocation() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {

            Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }

            if (location != null) {
                reverseGeocode(location.getLatitude(), location.getLongitude());
            } else {
                Toast.makeText(this, "无法获取位置，请检查GPS", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void reverseGeocode(double lat, double lon) {
        String url = "https://api.map.baidu.com/reverse_geocoding/v3/?ak=YOUR_BAIDU_AK&location=" + lon + "," + lat + "&output=json&pois=0";

        Request request = new Request.Builder().url(url).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "网络错误", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONObject result = json.getJSONObject("result");
                    JSONObject addressComponent = result.getJSONObject("address_component");
                    String city = addressComponent.getString("city");

                    mainHandler.post(() -> {
                        currentCity = city.replace("市", "");
                        tvCityName.setText(currentCity);
                        saveLocationToServer(currentCity, lat, lon);
                        loadWeatherData(currentCity);
                    });
                }
            }
        });
    }

    private void saveLocationToServer(String cityName, double lat, double lon) {
        JSONObject json = new JSONObject();
        try {
            json.put("cityName", cityName);
            json.put("latitude", lat);
            json.put("longitude", lon);
        } catch (Exception e) {
            e.printStackTrace();
        }

        Request request = new Request.Builder()
                .url(BASE_URL + "location/save")
                .post(okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json"), json.toString()))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "保存定位失败", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
            }
        });
    }

    private void loadCurrentLocationFromServer() {
        Request request = new Request.Builder().url(BASE_URL + "location/current").build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    boolean success = json.optBoolean("success", false);
                    if (success) {
                        JSONObject data = json.optJSONObject("data");
                        if (data != null) {
                            String city = data.optString("cityName", "");
                            mainHandler.post(() -> {
                                currentCity = city;
                                tvCityName.setText(city);
                                loadWeatherData(city);
                            });
                        }
                    }
                }
            }
        });
    }

    private void loadWeatherData(String city) {
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?city=" + city)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "加载天气数据失败", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    boolean success = json.optBoolean("success", false);
                    if (success) {
                        JSONObject data = json.optJSONObject("data");
                        mainHandler.post(() -> updateWeatherUI(data));
                    }
                }
            }
        });
    }

    private void updateWeatherUI(JSONObject data) {
        if (data == null) return;

        tvAqi.setText(String.valueOf(data.optInt("aqi", 0)));
        tvAirQuality.setText(data.optString("airQuality", "未知"));
        tvPm25.setText(data.optString("pm25", "--"));
        tvPm10.setText(data.optString("pm10", "--"));
        tvWeather.setText(data.optString("weather", "--"));
        tvTemperature.setText(data.optDouble("temperature", 0) + "°C");
        tvHumidity.setText(data.optDouble("humidity", 0) + "%");

        setAirQualityColor(data.optInt("aqi", 0));
    }

    private void setAirQualityColor(int aqi) {
        int color;
        if (aqi <= 50) {
            color = 0xFF4CAF50; // 绿色
        } else if (aqi <= 100) {
            color = 0xFFFFEB3B; // 黄色
        } else if (aqi <= 150) {
            color = 0xFFFF9800; // 橙色
        } else if (aqi <= 200) {
            color = 0xFFF44336; // 红色
        } else {
            color = 0xFF9C27B0; // 紫色
        }
        tvAqi.setTextColor(color);
    }
}