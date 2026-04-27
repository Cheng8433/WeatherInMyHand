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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private WeatherRepository weatherRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private LocationService locationService;

    private final OkHttpClient client = new OkHttpClient();

    private static final String API_HOST = "https://nx4nmurq3h.re.qweatherapi.com";

    public String getToken() {
        return jwtUtil.generateToken();
    }

    @Deprecated
    public Location saveLocation(String cityName, Double latitude, Double longitude) {
        Location location = new Location();
        location.setCityName(cityName);
        location.setLatitude(latitude);
        location.setLongitude(longitude);
        location.setUpdateTime(System.currentTimeMillis());
        return locationRepository.save(location);
    }

    public Location getCurrentLocation() {
        return locationRepository.findTopByOrderByUpdateTimeDesc().orElse(null);
    }

    // ==================== 实时天气 API ====================

    public Weather getWeatherByCity(double latitude, double longitude,String cityName) throws IOException {
        log.info("获取实时天气");
        String token = jwtUtil.generateToken();
        // 保留小数点后两位
        String latStr = String.format("%.2f", latitude);
        String lonStr = String.format("%.2f", longitude);
        String url = API_HOST + "/v7/weather/now?location=" + lonStr + "," + latStr;
        log.debug("请求URL: {}", url);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("和风天气API响应失败，状态码：{}", response.code());
                throw new IOException("API 请求失败，HTTP 状态码：" + response.code());
            }
            String body = response.body().string();
            log.debug("实时天气响应体：{}", body);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (json.has("code") && !"200".equals(json.get("code").getAsString())) {
                log.error("和风天气API返回错误码：{}", json.get("code").getAsString());
                throw new IOException("和风天气 API 错误，code：" + json.get("code").getAsString());
            }

            Weather weather = new Weather();
            weather.setCityName(cityName);
            weather.setUpdateTime(System.currentTimeMillis());

            if (json.has("now")) {
                JsonObject now = json.getAsJsonObject("now");
                if (now.has("text")) weather.setWeather(now.get("text").getAsString());
                if (now.has("temp")) weather.setTemperature(now.get("temp").getAsDouble());
                if (now.has("feelsLike")) weather.setFeelsLike(now.get("feelsLike").getAsDouble());
                if (now.has("humidity")) weather.setHumidity(now.get("humidity").getAsDouble());
                if (now.has("windDir")) weather.setWindDir(now.get("windDir").getAsString());
                if (now.has("windScale")) weather.setWindScale(now.get("windScale").getAsString());
                if (now.has("windSpeed")) weather.setWindSpeed(now.get("windSpeed").getAsDouble());
                if (now.has("precip")) weather.setPrecip(now.get("precip").getAsDouble());
                if (now.has("pressure")) weather.setPressure(now.get("pressure").getAsDouble());
                if (now.has("vis")) weather.setVis(now.get("vis").getAsDouble());
                if (now.has("cloud")) weather.setCloud(now.get("cloud").getAsString());
                if (now.has("dew")) weather.setDew(now.get("dew").getAsDouble());
            }

            return weatherRepository.save(weather);
        } catch (Exception e) {
            log.error("获取实时天气异常", e);
            throw e;
        }
    }

    // ==================== 空气质量 API ====================

    public Weather getAirQualityByLatLon(double latitude, double longitude, String cityName) throws IOException {
        log.info("获取空气质量，经度：{}，纬度：{}，城市名：{}", longitude, latitude, cityName);
        String token = jwtUtil.generateToken();
        String url = API_HOST + "/airquality/v1/current/" + latitude + "/" + longitude;
        log.debug("空气质量请求URL: {}", url);

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.error("空气质量API响应失败，状态码：{}", response.code());
                throw new IOException("API 请求失败，HTTP 状态码：" + response.code());
            }
            String body = response.body().string();
            log.debug("空气质量响应体：{}", body);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (json.has("code") && !"200".equals(json.get("code").getAsString())) {
                log.error("空气质量API返回错误码：{}", json.get("code").getAsString());
                throw new IOException("和风天气 API 错误，code：" + json.get("code").getAsString());
            }

            // 确定城市名
            String finalCityName = cityName;
            if (finalCityName == null || finalCityName.trim().isEmpty()) {
                finalCityName = getCityNameFromLastWeather();
                log.debug("使用上次天气记录中的城市名：{}", finalCityName);
            }
            if (finalCityName == null) {
                finalCityName = "未知地点";
                log.warn("无法获取城市名，使用默认值：未知地点");
            }

            Weather weather = weatherRepository.findTopByCityNameOrderByUpdateTimeDesc(finalCityName)
                    .orElse(new Weather());
            weather.setCityName(finalCityName);
            weather.setUpdateTime(System.currentTimeMillis());

            // 解析 indexes
            if (json.has("indexes") && json.get("indexes").isJsonArray()) {
                JsonArray indexes = json.getAsJsonArray("indexes");
                for (int i = 0; i < indexes.size(); i++) {
                    JsonObject index = indexes.get(i).getAsJsonObject();
                    String code = index.get("code").getAsString();
                    if ("us-epa".equals(code)) {
                        if (index.has("aqi")) weather.setAqi(index.get("aqi").getAsInt());
                        if (index.has("aqi")) weather.setAqiUs(index.get("aqi").getAsInt());
                        if (index.has("category")) weather.setAirQuality(index.get("category").getAsString());
                        if (index.has("primaryPollutant")) {
                            JsonObject primary = index.getAsJsonObject("primaryPollutant");
                            if (primary.has("code")) weather.setPrimaryPollutant(primary.get("code").getAsString());
                        }
                    } else if ("cn-mee".equals(code)) {
                        if (index.has("aqi")) weather.setAqi(index.get("aqi").getAsInt());
                        if (index.has("aqi")) weather.setAqiCN(index.get("aqi").getAsInt());
                        if (index.has("category")) weather.setAirQuality(index.get("category").getAsString());
                        if (index.has("primaryPollutant")) {
                           JsonObject primary = index.getAsJsonObject("primaryPollutant");
                           if (primary.has("code")) weather.setPrimaryPollutant(primary.get("code").getAsString());
                    }
                }
            }
                log.debug("空气质量指数解析完成，AQI(US): {}", weather.getAqiUs());
            }

            // 解析 pollutants
            if (json.has("pollutants") && json.get("pollutants").isJsonArray()) {
                JsonArray pollutants = json.getAsJsonArray("pollutants");
                for (int i = 0; i < pollutants.size(); i++) {
                    JsonObject pollutant = pollutants.get(i).getAsJsonObject();
                    String code = pollutant.get("code").getAsString();
                    if (pollutant.has("concentration")) {
                        JsonObject conc = pollutant.getAsJsonObject("concentration");
                        double value = conc.get("value").getAsDouble();
                        switch (code) {
                            case "pm2p5":
                                weather.setPm25(String.valueOf(value));
                                weather.setPm25Value(value);
                                break;
                            case "pm10":
                                weather.setPm10(String.valueOf(value));
                                weather.setPm10Value(value);
                                break;
                            case "no2":
                                weather.setNo2(value);
                                break;
                            case "o3":
                                weather.setO3(value);
                                break;
                            case "co":
                                weather.setCo(value);
                                break;
                            case "so2":
                                weather.setSo2(value);
                                break;
                        }
                    }
                }
                log.debug("污染物浓度解析完成，PM2.5: {}", weather.getPm25Value());
            }

            return weatherRepository.save(weather);
        } catch (Exception e) {
            log.error("获取空气质量异常", e);
            throw e;
        }
    }

    public Weather getAirQualityByCity(String cityName) throws IOException {
        log.info("根据城市名称获取空气质量：{}", cityName);
        Location location = locationService.getOrFetchLocation(cityName);
        log.debug("获取到位置：经度={}，纬度={}", location.getLongitude(), location.getLatitude());
        return getAirQualityByLatLon(location.getLatitude(), location.getLongitude(), cityName);
    }

    private String getCityNameFromLastWeather() {
        return weatherRepository.findTopByOrderByUpdateTimeDesc()
                .map(Weather::getCityName)
                .orElse(null);
    }

    // ==================== 组合调用 ====================

    public Weather getWeatherAndAirQuality(String cityName) throws IOException {
        Location loc;
        try {
            loc = locationService.getOrFetchLocation(cityName);
            log.debug("位置信息有效：经度={}, 纬度={}", loc.getLongitude(), loc.getLatitude());
        } catch (Exception e) {
            log.warn("获取位置信息失败，仅返回天气数据: {}", e.getMessage());
            throw new IOException(e);
        }
        log.info("========== 开始获取综合天气与空气质量，城市：{} ==========", cityName);
        Weather weather = getWeatherByCity(loc.getLatitude(), loc.getLongitude(), cityName);
        log.info("实时天气获取成功，温度={}，天气={}", weather.getTemperature(), weather.getWeather());

        Weather airWeather = getAirQualityByLatLon(loc.getLatitude(), loc.getLongitude(), cityName);
        mergeAirQuality(weather, airWeather);
        log.info("空气质量数据合并完成，AQI={}", weather.getAqiUs());
        return weatherRepository.save(weather);
    }

    private void mergeAirQuality(Weather target, Weather source) {
        if (source.getAqi() != null) target.setAqi(source.getAqi());
        if (source.getAqiUs() != null) target.setAqiUs(source.getAqiUs());
        if (source.getAqiQa() != null) target.setAqiQa(source.getAqiQa());
        if (source.getAirQuality() != null) target.setAirQuality(source.getAirQuality());
        if (source.getPrimaryPollutant() != null) target.setPrimaryPollutant(source.getPrimaryPollutant());
        if (source.getPm25() != null) target.setPm25(source.getPm25());
        if (source.getPm10() != null) target.setPm10(source.getPm10());
        if (source.getPm25Value() != null) target.setPm25Value(source.getPm25Value());
        if (source.getPm10Value() != null) target.setPm10Value(source.getPm10Value());
        if (source.getNo2() != null) target.setNo2(source.getNo2());
        if (source.getO3() != null) target.setO3(source.getO3());
        if (source.getCo() != null) target.setCo(source.getCo());
        if (source.getSo2() != null) target.setSo2(source.getSo2());
        target.setUpdateTime(System.currentTimeMillis());
        log.debug("合并空气质量字段完成");
    }
}