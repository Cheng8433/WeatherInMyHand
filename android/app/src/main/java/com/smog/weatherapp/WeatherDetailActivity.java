package com.smog.weatherapp;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WeatherDetailActivity extends AppCompatActivity {

    private static final String BASE_URL = "http://10.198.101.242:8080/api/";

    private TextView tvDetailCity, tvAssessment, tvDetailAqi, tvDetailAirQuality;
    private TextView tvDetailPm25, tvDetailPm10, tvDetailWeather;
    private TextView tvDetailTemperature, tvDetailHumidity, tvHealthAdvice;

    private OkHttpClient httpClient = new OkHttpClient();
    private String city;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_weather_detail);

        city = getIntent().getStringExtra("city");
        initViews();
        loadWeatherDetail();
    }

    private void initViews() {
        tvDetailCity = findViewById(R.id.tvDetailCity);
        tvAssessment = findViewById(R.id.tvAssessment);
        tvDetailAqi = findViewById(R.id.tvDetailAqi);
        tvDetailAirQuality = findViewById(R.id.tvDetailAirQuality);
        tvDetailPm25 = findViewById(R.id.tvDetailPm25);
        tvDetailPm10 = findViewById(R.id.tvDetailPm10);
        tvDetailWeather = findViewById(R.id.tvDetailWeather);
        tvDetailTemperature = findViewById(R.id.tvDetailTemperature);
        tvDetailHumidity = findViewById(R.id.tvDetailHumidity);
        tvHealthAdvice = findViewById(R.id.tvHealthAdvice);

        tvDetailCity.setText(city + "天气详情");
    }

    private void loadWeatherDetail() {
        String encodedCity;
        try {
            encodedCity = URLEncoder.encode(city, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            encodedCity = city;
        }
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?city=" + encodedCity)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(WeatherDetailActivity.this,
                        "网络请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
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
                            runOnUiThread(() -> updateUI(data));
                        } else {
                            runOnUiThread(() -> Toast.makeText(WeatherDetailActivity.this,
                                    "获取天气数据失败", Toast.LENGTH_SHORT).show());
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(WeatherDetailActivity.this,
                                "天气数据解析错误", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(WeatherDetailActivity.this,
                            "服务器响应错误: " + response.code(), Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    private void updateUI(JSONObject data) {
        if (data == null) return;

        int aqi = data.optInt("aqi", 0);
        String airQuality = data.optString("airQuality", "未知");

        tvDetailAqi.setText(String.valueOf(aqi));
        tvDetailAirQuality.setText(airQuality);
        tvDetailPm25.setText(data.optString("pm25", "--"));
        tvDetailPm10.setText(data.optString("pm10", "--"));
        tvDetailWeather.setText(data.optString("weather", "--"));
        double temp = data.optDouble("temperature", 0);
        tvDetailTemperature.setText(String.format("%.1f°C", temp));
        double humidity = data.optDouble("humidity", 0);
        tvDetailHumidity.setText(String.format("%.1f%%", humidity));

        tvAssessment.setText(getAssessment(aqi));
        tvHealthAdvice.setText(getHealthAdvice(aqi));
    }

    private String getAssessment(int aqi) {
        if (aqi <= 50) return "优 - 空气质量非常好，适合所有户外活动";
        else if (aqi <= 100) return "良 - 空气质量可接受，敏感人群需注意";
        else if (aqi <= 150) return "轻度污染 - 敏感人群减少户外活动";
        else if (aqi <= 200) return "中度污染 - 所有人应减少户外活动";
        else if (aqi <= 300) return "重度污染 - 避免户外活动";
        else return "严重污染 - 尽可能待在室内";
    }

    private String getHealthAdvice(int aqi) {
        if (aqi <= 50) return "• 空气质量优良，可正常进行户外活动\n• 适合开窗通风";
        else if (aqi <= 100) return "• 敏感人群佩戴口罩\n• 减少剧烈运动";
        else if (aqi <= 150) return "• 敏感人群避免户外活动\n• 建议佩戴口罩\n• 减少户外运动";
        else if (aqi <= 200) return "• 所有人佩戴口罩\n• 减少户外活动\n• 避免室外锻炼";
        else if (aqi <= 300) return "• 尽量待在室内\n• 关闭门窗\n• 使用空气净化器";
        else return "• 避免一切户外活动\n• 关闭门窗\n• 使用空气净化器\n• 如需外出必须佩戴防雾霾口罩";
    }
}
