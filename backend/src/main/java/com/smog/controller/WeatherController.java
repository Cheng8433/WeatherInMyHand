package com.smog.controller;

import com.smog.entity.Weather;
import com.smog.service.WeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/weather")
@CrossOrigin(origins = "*")
public class WeatherController {

    @Autowired
    private WeatherService weatherService;

    /**
     * 获取天气和空气质量信息
     * 支持两种参数：
     *   /info?city=北京
     *   /info?lat=39.9042&lon=116.4074
     */
    @GetMapping("/info")
    public Map<String, Object> getWeatherInfo(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon) {

        Map<String, Object> result = new HashMap<>();
        try {
            Weather weather;
            if (lat != null && lon != null) {
                // 使用经纬度查询
                // 需要先根据经纬度获取城市名（或直接调用支持经纬度的service方法）
                weather = weatherService.getWeatherAndAirQualityByLatLon(lat, lon);
            } else if (city != null && !city.isEmpty()) {
                weather = weatherService.getWeatherAndAirQuality(city);
            } else {
                result.put("success", false);
                result.put("message", "缺少城市名或经纬度参数");
                return result;
            }
            result.put("success", true);
            result.put("data", weather);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/air")
    public Map<String, Object> getAirQuality(@RequestParam String city) {
        Map<String, Object> result = new HashMap<>();
        try {
            Weather weather = weatherService.getAirQualityByCity(city);
            result.put("success", true);
            result.put("data", weather);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}