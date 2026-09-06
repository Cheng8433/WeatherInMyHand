package com.smog.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.smog.entity.Location;
import com.smog.repository.LocationRepository;
import com.smog.midwdget.JwtUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Service
public class LocationService {

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private JwtUtil jwtUtil;

    // 显式设置超时，避免上游挂起时请求线程长时间阻塞
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final String API_HOST = "https://nx4nmurq3h.re.qweatherapi.com";

    /**
     * 根据城市名称从和风天气 API 获取地理位置（经纬度、标准城市名等）
     * @param cityName 城市名称（中文，如“北京”）
     * @return Location 实体（未保存到数据库）
     * @throws IOException 当 API 调用失败或未找到城市时
     */
    public Location fetchLocationFromApi(String cityName) throws IOException {
        String token = jwtUtil.generateToken();
        // 对中文城市名进行 URL 编码，避免特殊字符问题
        String encodedCity;
        try {
            encodedCity = URLEncoder.encode(cityName, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            throw new IOException("城市名称编码失败", e);
        }
        String url = API_HOST + "/geo/v2/city/lookup?location=" + encodedCity;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("城市查询 API 请求失败，HTTP 状态码：" + response.code());
            }
            String body = response.body().string();
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            // 检查响应码
            if (!json.has("code") || !"200".equals(json.get("code").getAsString())) {
                String errCode = json.has("code") ? json.get("code").getAsString() : "未知";
                throw new IOException("和风天气 API 错误，code：" + errCode);
            }

            if (!json.has("location") || json.getAsJsonArray("location").size() == 0) {
                throw new IOException("未找到城市：" + cityName);
            }

            JsonArray locations = json.getAsJsonArray("location");
            JsonObject first = locations.get(0).getAsJsonObject();

            Location location = new Location();
            // 使用 API 返回的标准城市名（可能与你传入的名称略有不同，如“北京”保持不变）
            location.setCityName(first.get("name").getAsString());
            double lat = Double.parseDouble(first.get("lat").getAsString());
            double lon = Double.parseDouble(first.get("lon").getAsString());
            location.setLatitude(lat);
            location.setLongitude(lon);
            location.setUpdateTime(System.currentTimeMillis());

            return location;
        }
    }

    /**
     * 从 API 获取城市位置并保存到数据库
     * @param cityName 城市名称
     * @return 保存后的 Location 实体
     * @throws IOException 如果获取或保存失败
     */
    public Location saveLocationFromApi(String cityName) throws IOException {
        Location location = fetchLocationFromApi(cityName);
        return locationRepository.save(location);
    }

    /**
     * 获取数据库中最近一次查询的位置（不区分城市）
     * @return 最近一次的位置，可能为空
     */
    public Location getCurrentLocation() {
        return locationRepository.findTopByOrderByUpdateTimeDesc().orElse(null);
    }

    /**
     * 根据城市名称获取位置：优先从数据库查询，若不存在则从 API 获取并保存
     * @param cityName 城市名称
     * @return Location 实体（保证存在）
     * @throws RuntimeException 包装了 IOException（当 API 调用失败时）
     */
    public Location getOrFetchLocation(String cityName) {
        return locationRepository.findTopByCityNameOrderByUpdateTimeDesc(cityName)
                .orElseGet(() -> {
                    try {
                        return saveLocationFromApi(cityName);
                    } catch (IOException e) {
                        throw new RuntimeException("获取城市位置失败：" + cityName, e);
                    }
                });
    }

    /**
     * 根据城市名称从数据库查询位置（不会触发 API 调用）
     * @param cityName 城市名称
     * @return 可能为空
     */
    public Location getLocationFromDB(String cityName) {
        return locationRepository.findTopByCityNameOrderByUpdateTimeDesc(cityName).orElse(null);
    }

    // 在 LocationService 中添加以下方法

    /**
     * 逆地理编码：根据经纬度查询最近城市（不入库）。和风 geo/v2/city/lookup 支持传入 "经度,纬度" 反向查找。
     * @param lat 纬度
     * @param lon 经度
     * @return 未保存的 Location（含 API 返回的标准城市名与原始经纬度）
     * @throws IOException 当 API 调用失败或未找到城市时
     */
    public Location reverseGeocode(double lat, double lon) throws IOException {
        String token = jwtUtil.generateToken();
        String locationParam = lon + "," + lat; // 经度在前，纬度在后
        String url = API_HOST + "/geo/v2/city/lookup?location=" + locationParam;

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("逆地理编码 API 请求失败，HTTP 状态码：" + response.code());
            }
            String body = response.body().string();
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (!json.has("code") || !"200".equals(json.get("code").getAsString())) {
                String errCode = json.has("code") ? json.get("code").getAsString() : "未知";
                throw new IOException("和风天气 API 错误，code：" + errCode);
            }

            if (!json.has("location") || json.getAsJsonArray("location").size() == 0) {
                throw new IOException("未找到经纬度对应的城市");
            }

            JsonArray locations = json.getAsJsonArray("location");
            JsonObject first = locations.get(0).getAsJsonObject();

            Location location = new Location();
            location.setCityName(first.get("name").getAsString());
            location.setLatitude(lat);
            location.setLongitude(lon);
            location.setUpdateTime(System.currentTimeMillis());
            return location;
        }
    }

    /**
     * 根据经纬度保存位置（逆地理编码出城市名后入库）。
     * 同一城市仅保留最新一条（存在则更新），避免每次上报都新增行导致表无限增长。
     */
    public Location saveLocationByLatLon(double lat, double lon) throws IOException {
        Location fetched = reverseGeocode(lat, lon);
        return locationRepository.findTopByCityNameOrderByUpdateTimeDesc(fetched.getCityName())
                .map(existing -> {
                    existing.setLatitude(fetched.getLatitude());
                    existing.setLongitude(fetched.getLongitude());
                    existing.setUpdateTime(System.currentTimeMillis());
                    return locationRepository.save(existing);
                })
                .orElseGet(() -> locationRepository.save(fetched));
    }
}