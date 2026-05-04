package com.smog.weatherapp;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import org.json.JSONException;
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
        setupChart(); // 仅配置图表样式，数据稍后填充（真实数据需后端提供）
    }

    /**
     * 配置折线图样式（X轴标签等）
     * 注意：当前使用随机模拟数据演示，实际应用应改为真实小时数据接口
     */
    private void setupChart() {
        lineChart.getDescription().setEnabled(false);
        lineChart.setTouchEnabled(true);
        lineChart.setDragEnabled(true);
        lineChart.setScaleEnabled(true);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        // 可选：设置 X 轴标签为 0时 ~ 23时
        String[] hours = new String[24];
        for (int i = 0; i < 24; i++) hours[i] = i + "时";
        xAxis.setValueFormatter(new com.github.mikephil.charting.formatter.IndexAxisValueFormatter(hours));

        // 模拟数据（实际应调用后端接口获取真实小时数据）
        List<Entry> tempEntries = new ArrayList<>();
        List<Entry> humidityEntries = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            // 模拟一天温度变化（正弦波），比纯随机更合理
            double temp = 15 + 8 * Math.sin((i - 14) * Math.PI / 12);
            double humidity = 50 + 20 * Math.sin((i - 8) * Math.PI / 12);
            tempEntries.add(new Entry(i, (float) temp));
            humidityEntries.add(new Entry(i, (float) humidity));
        }

        LineDataSet tempDataSet = new LineDataSet(tempEntries, "温度(°C)");
        tempDataSet.setColor(0xFFE91E63);
        tempDataSet.setDrawCircles(false);
        tempDataSet.setLineWidth(2f);

        LineDataSet humidityDataSet = new LineDataSet(humidityEntries, "湿度(%)");
        humidityDataSet.setColor(0xFF2196F3);
        humidityDataSet.setDrawCircles(false);
        humidityDataSet.setLineWidth(2f);

        lineChart.setData(new LineData(tempDataSet, humidityDataSet));
        lineChart.invalidate(); // 刷新图表
    }

    /**
     * 加载当前天气详情（AQI、温度、湿度等）
     */
    private void loadWeatherDetail() {
        Request request = new Request.Builder()
                .url(BASE_URL + "weather/info?city=" + city)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(WeatherDetailActivity.this, "网络请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);   // 可能抛出 JSONException
                        boolean success = json.optBoolean("success", false);
                        if (success) {
                            JSONObject data = json.optJSONObject("data");
                            runOnUiThread(() -> updateUI(data));
                        } else {
                            runOnUiThread(() -> Toast.makeText(WeatherDetailActivity.this, "获取天气数据失败", Toast.LENGTH_SHORT).show());
                        }
                    } catch (JSONException e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(WeatherDetailActivity.this, "天气数据解析错误", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> Toast.makeText(WeatherDetailActivity.this, "服务器响应错误: " + response.code(), Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    /**
     * 更新界面上的天气信息
     */
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