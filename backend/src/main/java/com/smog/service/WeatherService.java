package com.smog.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smog.entity.Location;
import com.smog.entity.Weather;
import com.smog.repository.LocationRepository;
import com.smog.repository.WeatherRepository;
import com.smog.midwdget.JwtUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class WeatherService {

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private WeatherRepository weatherRepository;

    @Autowired
    private JwtUtil jwtUtil;

    private final OkHttpClient client = new OkHttpClient();

    /**
     * 获取 JWT 令牌（对外暴露，可用于测试）
     */
    public String getToken() {
        return jwtUtil.generateToken();
    }

    /**
     * 保存城市位置信息
     */
    public Location saveLocation(String cityName, Double latitude, Double longitude) {
        Location location = new Location();
        location.setCityName(cityName);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setUpdateTime(System.currentTimeMillis());
        return locationRepository.save(location);
    }

    /**
     * 获取最近一次查询的位置
     */
    public Location getCurrentLocation() {
        return locationRepository.findTopByOrderByUpdateTimeDesc().orElse(null);
    }

    /**
     * 根据城市名称获取实时天气（使用 JWT 认证）
     */
    public Weather getWeatherByCity(String cityName) throws IOException {
        // 生成 JWT 令牌
        String token = jwtUtil.generateToken();

        // 构建请求 URL（不再携带 key 参数）
        String url = "https://nx4nmurq3h.re.qweatherapi.com/v7/weather/now?location=" + cityName;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API 请求失败，HTTP 状态码：" + response.code());
            }
            String body = response.body().string();
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            // 检查和风天气返回的 code 字段（非 200 表示错误）
            if (json.has("code") && !"200".equals(json.get("code").getAsString())) {
                throw new IOException("和风天气 API 错误，code：" + json.get("code").getAsString() +
                        "，message：" + (json.has("message") ? json.get("message").getAsString() : "无"));
            }

            Weather weather = new Weather();
            weather.setCityName(cityName);
            weather.setUpdateTime(System.currentTimeMillis());

            if (json.has("now")) {
                JsonObject now = json.getAsJsonObject("now");
                weather.setWeather(now.get("text").getAsString());
                weather.setTemperature(now.get("temp").getAsDouble());
                weather.setHumidity(now.get("humidity").getAsDouble());
            }

            return weatherRepository.save(weather);
        }
    }

    /**
     * 根据城市名称获取空气质量（使用 JWT 认证）
     */
    public Weather getAirQualityByCity(String cityName) throws IOException {
        // 生成 JWT 令牌
        String token = jwtUtil.generateToken();

        String url = "https://nx4nmurq3h.re.qweatherapi.com/v7/air/now?location=" + cityName;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("API 请求失败，HTTP 状态码：" + response.code());
            }
            String body = response.body().string();
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (json.has("code") && !"200".equals(json.get("code").getAsString())) {
                throw new IOException("和风天气 API 错误，code：" + json.get("code").getAsString());
            }

            // 尝试获取已有的天气记录，如果没有则新建一个
            Weather weather = weatherRepository.findTopByCityNameOrderByUpdateTimeDesc(cityName)
                    .orElse(new Weather());
            weather.setCityName(cityName); // 确保城市名称被设置

            if (json.has("now")) {
                JsonObject now = json.getAsJsonObject("now");
                weather.setAqi(now.get("aqi").getAsInt());
                weather.setAirQuality(now.get("category").getAsString());
                weather.setPm25(now.get("pm2p5").getAsString());
                weather.setPm10(now.get("pm10").getAsString());
                weather.setUpdateTime(System.currentTimeMillis());
            }

            return weatherRepository.save(weather);
        }
    }

    /**
     * 获取天气和空气质量（组合调用）
     */
    public Weather getWeatherAndAirQuality(String cityName) throws IOException {

        getWeatherByCity(cityName);
        return getAirQualityByCity(cityName);
    }
}