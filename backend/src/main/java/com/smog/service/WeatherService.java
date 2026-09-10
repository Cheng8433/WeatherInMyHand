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
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    // 显式设置超时，避免上游挂起时请求线程长时间阻塞
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final String API_HOST = "https://nx4nmurq3h.re.qweatherapi.com";

    // ==================== 结果缓存（内存 TTL，命中免打和风；上游失败降级 stale） ====================

    /** 缓存有效期：实时天气/空气质量/逐小时 10 分钟内视为足够新，之后才允许重新请求和风 */
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    /** key = 城市名（trim 后）。info（综合）与 air（仅空气）分开两套快照，避免缓存条目互相覆盖。 */
    private final Map<String, CacheEntry> infoCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> airCache = new ConcurrentHashMap<>();

    /** 一次成功结果的不可变快照：数据 + 抓取时刻 */
    private static final class CacheEntry {
        final Weather weather;
        final long fetchedAt;
        CacheEntry(Weather weather, long fetchedAt) {
            this.weather = weather;
            this.fetchedAt = fetchedAt;
        }
    }

    /** 服务层结果：stale=true 表示本次上游失败，weather 为最近一次成功快照（供 controller 加标记） */
    public static final class WeatherResult {
        private final Weather weather;
        private final boolean stale;
        WeatherResult(Weather weather, boolean stale) {
            this.weather = weather;
            this.stale = stale;
        }
        public Weather getWeather() { return weather; }
        public boolean isStale() { return stale; }
    }

    private String cacheKey(String cityName) {
        return cityName == null ? "" : cityName.trim();
    }

    /** 命中且未过期则返回条目，否则返回 null（过期条目先不删，留给降级用） */
    private CacheEntry fresh(Map<String, CacheEntry> cache, String key) {
        CacheEntry e = cache.get(key);
        if (e != null && System.currentTimeMillis() - e.fetchedAt < CACHE_TTL_MS) {
            return e;
        }
        return null;
    }

    /**
     * 上游失败时：有缓存快照就降级返回（带 stale），否则原样抛出让统一异常处理兜底。
     * 兼容 RuntimeException（如定位解析失败）：直接冒泡会绕过“HTTP 200 + success:false”契约，
     * 统一包装成 IOException，保证错误响应格式与其它端点一致。
     */
    private WeatherResult staleOrRethrow(Map<String, CacheEntry> cache, String key, Exception cause)
            throws IOException {
        CacheEntry stale = cache.get(key);
        if (stale != null) {
            log.warn("上游请求失败（{}），降级返回城市【{}】的最近一次成功缓存", cause.getMessage(), key);
            return new WeatherResult(stale.weather, true);
        }
        if (cause instanceof IOException) {
            throw (IOException) cause;
        }
        throw new IOException(cause.getMessage(), cause);
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

    // 事务覆盖“查最近一条 → 更新或新建 → save”的读改写，避免半途异常留下不一致行
    @Transactional
    public Weather getWeatherByCity(double latitude, double longitude, String cityName) throws IOException {
        log.info("获取实时天气");
        String token = jwtUtil.generateToken();
        String latStr = String.format("%.2f", latitude);
        String lonStr = String.format("%.2f", longitude);
        // 和风 v1 实时天气（v7 已弃用）：/weather/v1/current/{latitude}/{longitude}
        String url = API_HOST + "/weather/v1/current/" + latStr + "/" + lonStr;
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
            // v1 实时天气响应无顶层 code/now 字段，数据即响应本体，成功与否只看 HTTP 状态
            JsonObject json = rootElement.getAsJsonObject();

            // 复用该城市最近一条记录做“更新”（upsert），避免每次请求都 INSERT 新行导致表无限增长。
            // 没有任何接口读历史行，始终只需保留每个城市的最新快照。
            Weather weather = weatherRepository.findTopByCityNameOrderByUpdateTimeDesc(cityName)
                    .orElseGet(Weather::new);
            weather.setCityName(cityName);
            weather.setUpdateTime(System.currentTimeMillis());

            JsonObject condition = getJsonObject(json, "condition");
            if (condition != null) {
                String text = getString(condition, "text");
                if (text != null) weather.setWeather(text);
            }

            JsonObject temperatureObj = getJsonObject(json, "temperature");
            if (temperatureObj != null) {
                Double temp = getDouble(temperatureObj, "value");
                if (temp != null) weather.setTemperature(temp);
            }

            JsonObject feelsLikeObj = getJsonObject(json, "feelsLike");
            if (feelsLikeObj != null) {
                Double feelsLike = getDouble(feelsLikeObj, "value");
                if (feelsLike != null) weather.setFeelsLike(feelsLike);
            }

            // v1 湿度为 0~1 小数，转成 0~100 百分比以保持原字段语义
            Double humidityRatio = getDouble(json, "humidity");
            if (humidityRatio != null) weather.setHumidity(Math.round(humidityRatio * 1000) / 10.0);

            JsonObject wind = getJsonObject(json, "wind");
            if (wind != null) {
                JsonObject direction = getJsonObject(wind, "direction");
                if (direction != null) {
                    String compass = getString(direction, "compass");
                    if (compass != null) weather.setWindDir(compass);
                }
                if (wind.has("scale") && wind.get("scale").isJsonPrimitive())
                    weather.setWindScale(wind.get("scale").getAsString());
                JsonObject speed = getJsonObject(wind, "speed");
                if (speed != null) {
                    Double windMs = getDouble(speed, "value");
                    if (windMs != null) weather.setWindSpeed(Math.round(windMs * 3.6 * 10) / 10.0); // m/s -> km/h
                }
            }

            JsonObject precipitation = getJsonObject(json, "precipitation");
            if (precipitation != null) {
                JsonObject amount = getJsonObject(precipitation, "amount");
                if (amount != null) {
                    Double precip = getDouble(amount, "value");
                    if (precip != null) weather.setPrecip(precip);
                }
            }

            JsonObject pressureObj = getJsonObject(json, "pressure");
            if (pressureObj != null) {
                Double pressure = getDouble(pressureObj, "value");
                if (pressure != null) weather.setPressure(pressure);
            }

            // v1 能见度单位为米，转成公里以保持原字段语义
            JsonObject visObj = getJsonObject(json, "visibility");
            if (visObj != null) {
                Double visMeters = getDouble(visObj, "value");
                if (visMeters != null) weather.setVis(Math.round(visMeters / 10.0) / 100.0);
            }

            // v1 云量为 0~1 小数，转成百分比字符串
            Double cloudRatio = getDouble(json, "cloudCover");
            if (cloudRatio != null) weather.setCloud(String.valueOf(Math.round(cloudRatio * 100)));

            JsonObject dewObj = getJsonObject(json, "dewPoint");
            if (dewObj != null) {
                Double dew = getDouble(dewObj, "value");
                if (dew != null) weather.setDew(dew);
            }

            return weatherRepository.save(weather);
        } catch (Exception e) {
            log.error("获取实时天气异常", e);
            throw e;
        }
    }

    // ==================== 空气质量 API ====================

    @Transactional
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
        String latStr = String.format("%.2f", latitude);
        String lonStr = String.format("%.2f", longitude);
        // 和风 v1 逐小时（v7 /24h 已弃用）：默认返回 24 小时；localTime=true 使 forecastTime 为当地时间
        String url = API_HOST + "/weather/v1/hourly/" + latStr + "/" + lonStr + "?localTime=true";

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
            JsonArray hours = getJsonArray(json, "hours");
            if (hours == null) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (int i = 0; i < hours.size(); i++) {
                if (!hours.get(i).isJsonObject()) continue;
                JsonObject item = hours.get(i).getAsJsonObject();
                Map<String, Object> map = new java.util.HashMap<>();
                // 保持 Android 折线图读取的键名不变：fxTime/temp/humidity
                map.put("fxTime", getString(item, "forecastTime"));
                JsonObject tempObj = getJsonObject(item, "temperature");
                if (tempObj != null) {
                    Double temp = getDouble(tempObj, "value");
                    map.put("temp", temp != null ? String.valueOf(temp) : null);
                }
                Double humidityRatio = getDouble(item, "humidity");
                map.put("humidity", humidityRatio != null
                        ? String.valueOf(Math.round(humidityRatio * 1000) / 10.0) : null);
                result.add(map);
            }
            return result;
        }
    }

    /**
     * 根据经纬度获取天气和空气质量（内部先逆地理编码得城市名），带缓存与失败降级。
     */
    public WeatherResult getWeatherAndAirQualityByLatLon(double lat, double lon) throws IOException {
        // 复用 LocationService 的逆地理编码（只查不存，避免每次 GPS 上报新增 location 行）
        String cityName = locationService.reverseGeocode(lat, lon).getCityName();
        // 再按城市名走统一缓存链路获取天气 + 空气质量 + 逐小时
        return getWeatherAndAirQualityCached(cityName);
    }

    /** /api/weather/info（城市版）带缓存入口：10 分钟内命中免打和风，失败时降级返回最近成功快照 */
    public WeatherResult getWeatherAndAirQualityCached(String cityName) throws IOException {
        String key = cacheKey(cityName);
        CacheEntry hit = fresh(infoCache, key);
        if (hit != null) {
            log.debug("命中综合天气缓存：{}", key);
            return new WeatherResult(hit.weather, false);
        }
        try {
            Weather fresh = getWeatherAndAirQuality(cityName);
            infoCache.put(key, new CacheEntry(fresh, System.currentTimeMillis()));
            log.debug("已刷新综合天气缓存：{}", key);
            return new WeatherResult(fresh, false);
        } catch (IOException | RuntimeException e) {
            return staleOrRethrow(infoCache, key, e);
        }
    }

    /** /api/weather/air 带缓存入口：命中免打和风空气质量接口，失败时降级返回最近成功快照 */
    public WeatherResult getAirQualityCached(String cityName) throws IOException {
        String key = cacheKey(cityName);
        CacheEntry hit = fresh(airCache, key);
        if (hit != null) {
            log.debug("命中空气质量缓存：{}", key);
            return new WeatherResult(hit.weather, false);
        }
        try {
            Weather fresh = getAirQualityByCity(cityName);
            airCache.put(key, new CacheEntry(fresh, System.currentTimeMillis()));
            log.debug("已刷新空气质量缓存：{}", key);
            return new WeatherResult(fresh, false);
        } catch (IOException | RuntimeException e) {
            return staleOrRethrow(airCache, key, e);
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
            try {
                return obj.get(key).getAsDouble();
            } catch (NumberFormatException | UnsupportedOperationException e) {
                // 上游偶尔把数值字段返回成非数字（如 "" 或 "N/A"），不能让一个字段拖垮整次解析
                log.warn("无法将字段 {} 解析为 Double: {}", key, obj.get(key));
                return null;
            }
        }
        return null;
    }

    private Integer getInt(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsInt();
            } catch (NumberFormatException | UnsupportedOperationException e) {
                log.warn("无法将字段 {} 解析为 Integer: {}", key, obj.get(key));
                return null;
            }
        }
        return null;
    }

}