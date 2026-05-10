package com.smog.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    public Weather getWeatherByCity(double latitude, double longitude, String cityName) throws IOException {
        log.info("获取实时天气");
        String token = jwtUtil.generateToken();
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
            JsonElement rootElement = JsonParser.parseString(body);
            if (!rootElement.isJsonObject()) {
                throw new IOException("响应不是有效的 JSON 对象");
            }
            JsonObject json = rootElement.getAsJsonObject();

            String code = getString(json, "code");
            if (!"200".equals(code)) {
                log.error("和风天气API返回错误码：{}", code);
                throw new IOException("和风天气 API 错误，code：" + code);
            }

            Weather weather = new Weather();
            weather.setCityName(cityName);
            weather.setUpdateTime(System.currentTimeMillis());

            JsonObject now = getJsonObject(json, "now");
            if (now != null) {
                if (now.has("text") && now.get("text").isJsonPrimitive())
                    weather.setWeather(now.get("text").getAsString());
                Double temp = getDouble(now, "temp");
                if (temp != null) weather.setTemperature(temp);
                Double feelsLike = getDouble(now, "feelsLike");
                if (feelsLike != null) weather.setFeelsLike(feelsLike);
                Double humidity = getDouble(now, "humidity");
                if (humidity != null) weather.setHumidity(humidity);
                String windDir = getString(now, "windDir");
                if (windDir != null) weather.setWindDir(windDir);
                String windScale = getString(now, "windScale");
                if (windScale != null) weather.setWindScale(windScale);
                Double windSpeed = getDouble(now, "windSpeed");
                if (windSpeed != null) weather.setWindSpeed(windSpeed);
                Double precip = getDouble(now, "precip");
                if (precip != null) weather.setPrecip(precip);
                Double pressure = getDouble(now, "pressure");
                if (pressure != null) weather.setPressure(pressure);
                Double vis = getDouble(now, "vis");
                if (vis != null) weather.setVis(vis);
                String cloud = getString(now, "cloud");
                if (cloud != null) weather.setCloud(cloud);
                Double dew = getDouble(now, "dew");
                if (dew != null) weather.setDew(dew);
            } else {
                log.warn("实时天气响应中没有 'now' 字段");
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
            JsonElement rootElement = JsonParser.parseString(body);
            if (!rootElement.isJsonObject()) {
                throw new IOException("空气质量响应不是有效的 JSON 对象");
            }
            JsonObject json = rootElement.getAsJsonObject();

            if (json.has("code") && json.get("code").isJsonPrimitive()) {
                String code = json.get("code").getAsString();
                if (!"200".equals(code)) {
                    log.error("空气质量API返回错误码：{}", code);
                    throw new IOException("和风天气 API 错误，code：" + code);
                }
            }

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

            // 安全解析 indexes 数组
            JsonArray indexes = getJsonArray(json, "indexes");
            if (indexes != null) {
                for (int i = 0; i < indexes.size(); i++) {
                    JsonElement indexElem = indexes.get(i);
                    if (!indexElem.isJsonObject()) continue;
                    JsonObject index = indexElem.getAsJsonObject();
                    String idxCode = getString(index, "code");
                    if ("us-epa".equals(idxCode)) {
                        Integer aqiVal = getInt(index, "aqi");
                        if (aqiVal != null) {
                            weather.setAqi(aqiVal);
                            weather.setAqiUs(aqiVal);
                        }
                        String category = getString(index, "category");
                        if (category != null) weather.setAirQuality(category);
                        JsonObject primary = getJsonObject(index, "primaryPollutant");
                        if (primary != null) {
                            String primaryCode = getString(primary, "code");
                            if (primaryCode != null) weather.setPrimaryPollutant(primaryCode);
                        }
                    } else if ("cn-mee".equals(idxCode)) {
                        Integer aqiVal = getInt(index, "aqi");
                        if (aqiVal != null) {
                            weather.setAqi(aqiVal);
                            weather.setAqiCN(aqiVal);
                        }
                        String category = getString(index, "category");
                        if (category != null) weather.setAirQuality(category);
                        JsonObject primary = getJsonObject(index, "primaryPollutant");
                        if (primary != null) {
                            String primaryCode = getString(primary, "code");
                            if (primaryCode != null) weather.setPrimaryPollutant(primaryCode);
                        }
                    }
                }
                log.debug("空气质量指数解析完成，AQI(US): {}", weather.getAqiUs());
            } else {
                log.warn("空气质量响应中没有 'indexes' 字段");
            }

            // 安全解析 pollutants 数组
            JsonArray pollutants = getJsonArray(json, "pollutants");
            if (pollutants != null) {
                for (int i = 0; i < pollutants.size(); i++) {
                    JsonElement pollElem = pollutants.get(i);
                    if (!pollElem.isJsonObject()) continue;
                    JsonObject pollutant = pollElem.getAsJsonObject();
                    String pollCode = getString(pollutant, "code");
                    JsonObject conc = getJsonObject(pollutant, "concentration");
                    if (conc != null && pollCode != null) {
                        Double value = getDouble(conc, "value");
                        if (value != null) {
                            switch (pollCode) {
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
                }
                log.debug("污染物浓度解析完成，PM2.5: {}", weather.getPm25Value());
            } else {
                log.warn("空气质量响应中没有 'pollutants' 字段");
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

        // 获取 24 小时逐小时预报（温湿度趋势）
        try {
            List<Map<String, Object>> hourly = getHourlyForecast(loc.getLatitude(), loc.getLongitude());
            weather.setHourlyForecast(hourly);
            log.info("24小时逐小时预报获取成功，共 {} 条数据", hourly.size());
        } catch (Exception e) {
            log.warn("获取逐小时预报失败: {}", e.getMessage());
        }

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

    private List<Map<String, Object>> getHourlyForecast(double latitude, double longitude) throws IOException {
        String token = jwtUtil.generateToken();
        String locationParam = String.format("%.2f,%.2f", longitude, latitude);
        String url = API_HOST + "/v7/weather/24h?location=" + locationParam;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("逐小时预报API响应失败，状态码：" + response.code());
            }
            String body = response.body().string();
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                throw new IOException("逐小时预报响应不是有效 JSON");
            }
            JsonObject json = root.getAsJsonObject();
            String code = getString(json, "code");
            if (!"200".equals(code)) {
                throw new IOException("和风天气逐小时预报错误，code：" + code);
            }
            JsonArray hourlyArray = getJsonArray(json, "hourly");
            if (hourlyArray == null) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < hourlyArray.size(); i++) {
                JsonObject item = hourlyArray.get(i).getAsJsonObject();
                Map<String, Object> map = new java.util.HashMap<>();
                map.put("fxTime", getString(item, "fxTime"));
                map.put("temp", getString(item, "temp"));
                map.put("humidity", getString(item, "humidity"));
                result.add(map);
            }
            return result;
        }
    }

    /**
     * 根据经纬度获取天气和空气质量（内部会先逆地理编码获取城市名）
     */
    public Weather getWeatherAndAirQualityByLatLon(double lat, double lon) throws IOException {
        // 1. 调用和风逆地理编码获取城市名
        String cityName = getCityNameByLatLon(lat, lon);
        // 2. 再用城市名获取天气（或者直接用经纬度获取，但现有方法已支持）
        return getWeatherAndAirQuality(cityName);
    }

    /**
     * 仅根据经纬度获取城市名（逆地理编码）
     */
    private String getCityNameByLatLon(double lat, double lon) throws IOException {
        String token = jwtUtil.generateToken();
        String locationParam = lon + "," + lat;
        String url = API_HOST + "/geo/v2/city/lookup?location=" + locationParam;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("逆地理编码失败，状态码：" + response.code());
            }
            String body = response.body().string();
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                throw new IOException("逆地理编码响应不是有效 JSON");
            }
            JsonObject json = root.getAsJsonObject();
            String code = getString(json, "code");
            if (!"200".equals(code)) {
                throw new IOException("逆地理编码错误，code：" + code);
            }
            JsonArray locations = getJsonArray(json, "location");
            if (locations == null || locations.size() == 0) {
                throw new IOException("未找到对应城市");
            }
            JsonObject firstLoc = locations.get(0).getAsJsonObject();
            String cityName = getString(firstLoc, "name");
            if (cityName == null) {
                throw new IOException("未找到城市名称字段");
            }
            return cityName;
        } catch (Exception e) {
            log.error("逆地理编码失败", e);
            throw new IOException("逆地理编码失败: " + e.getMessage(), e);
        }
    }

    // ==================== 安全的 JSON 解析辅助方法 ====================

    private JsonObject getJsonObject(JsonObject parent, String key) {
        if (parent.has(key) && parent.get(key).isJsonObject()) {
            return parent.getAsJsonObject(key);
        }
        return null;
    }

    private JsonArray getJsonArray(JsonObject parent, String key) {
        if (parent.has(key) && parent.get(key).isJsonArray()) {
            return parent.getAsJsonArray(key);
        }
        return null;
    }

    private String getString(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private Double getDouble(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsDouble();
        }
        return null;
    }

    private Integer getInt(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (NumberFormatException e) {
                log.warn("无法将字段 {} 解析为 Integer: {}", key, obj.get(key));
                return null;
            }
        }
        return null;
    }

}