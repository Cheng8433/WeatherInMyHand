package com.smog.controller;

import com.smog.service.WeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/weather")
@CrossOrigin(origins = "*")
public class WeatherController {

    @Autowired
    private WeatherService weatherService;

    /**
     * 获取天气和空气质量信息（按城市 TTL 缓存，命中免打和风；上游失败降级返回最近成功快照）。
     * 支持两种参数：
     *   /info?city=北京
     *   /info?lat=39.9042&lon=116.4074
     * 响应：{success:true, data}；若本次为降级数据则额外带 stale:true（data 仍完整，契约不变）。
     * 真正的错误统一由 GlobalExceptionHandler 返回 {success:false, message}（HTTP 200）。
     */
    @GetMapping("/info")
    public Map<String, Object> getWeatherInfo(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon) throws IOException {

        WeatherService.WeatherResult result;
        if (lat != null && lon != null) {
            result = weatherService.getWeatherAndAirQualityByLatLon(lat, lon);
        } else if (city != null && !city.isEmpty()) {
            result = weatherService.getWeatherAndAirQualityCached(city);
        } else {
            throw new IllegalArgumentException("缺少城市名或经纬度参数");
        }

        return wrap(result);
    }

    /** /api/weather/air?city=北京（带缓存与降级，契约同上） */
    @GetMapping("/air")
    public Map<String, Object> getAirQuality(@RequestParam String city) throws IOException {
        return wrap(weatherService.getAirQualityCached(city));
    }

    /** 组装统一成功契约；仅在降级（stale）时附加标记字段，不影响 Android 对 data 的读取 */
    private Map<String, Object> wrap(WeatherService.WeatherResult result) {
        Map<String, Object> map = new HashMap<>();
        map.put("success", true);
        map.put("data", result.getWeather());
        if (result.isStale()) {
            map.put("stale", true);
        }
        return map;
    }
}
