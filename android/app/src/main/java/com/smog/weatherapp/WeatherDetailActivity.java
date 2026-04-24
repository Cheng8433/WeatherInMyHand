package com.smog.weatherapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WeatherDetailActivity extends AppCompatActivity {

    private static final String BASE_URL = "http://10.0.2.2:8080/api/";

    private TextView tvDetailCity, tvAssessment, tvDetailAqi, tvDetailAirQuality;
    private TextView tvDetailPm25, tvDetailPm10, tvDetailWeather;
    private TextView tvDetailTemperature, tvDetailHumidity, tvHealthAdvice;
    private LineChart lineChart;

    private OkHttpClient httpClient = new OkHttpClient();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
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
        lineChart = findViewById(R.id.lineChart);

        tvDetailCity.setText(city + "天气详情");
        setupChart();
    }

    private void setupChart() {
        lineChart.getDescription().setEnabled(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);

        List<Entry> tempEntries = new ArrayList<>();
        List<Entry> humidityEntries = new ArrayList<>();

        for (int i = 0; i < 24; i++) {
            tempEntries.add(new Entry(i, (float) (15 + Math.random() * 10)));
            humidityEntries.add(new Entry(i, (float) (40 + Math.random() * 40)));
        }

        LineDataSet tempDataSet = new LineDataSet(tempEntries, "温度(°C)");
        tempDataSet.setColor(0xFFE91E63);
        tempDataSet.setDrawCircles(false);

        LineDataSet humidityDataSet = new LineDataSet(humidityEntries, "湿度(%)");
        humidityDataSet.setColor(0xFF2196F3);
        humidityDataSet.setDrawCircles(false);

        lineChart.setData(new LineData(tempDataSet, humidityDataSet));
        lineChart.invalidate();
    }

    private void loadWeatherDetail() {
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?city=" + city)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> Toast.makeText(WeatherDetailActivity.this, "加载失败", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    boolean success = json.optBoolean("success", false);
                    if (success) {
                        JSONObject data = json.optJSONObject("data");
                        mainHandler.post(() -> updateUI(data));
                    }
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
        tvDetailTemperature.setText(data.optDouble("temperature", 0) + "°C");
        tvDetailHumidity.setText(data.optDouble("humidity", 0) + "%");

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