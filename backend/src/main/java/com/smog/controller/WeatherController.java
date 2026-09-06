package com.smog.controller;

import com.smog.entity.Weather;
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
     * 获取天气和空气质量信息
     * 支持两种参数：
     *   /info?city=北京
     *   /info?lat=39.9042&lon=116.4074
     * 错误统一由 GlobalExceptionHandler 返回 {success:false, message}（HTTP 200）
     */
    @GetMapping("/info")
    public Map<String, Object> getWeatherInfo(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon) throws IOException {

        Weather weather;
        if (lat != null && lon != null) {
            weather = weatherService.getWeatherAndAirQualityByLatLon(lat, lon);
        } else if (city != null && !city.isEmpty()) {
            weather = weatherService.getWeatherAndAirQuality(city);
        } else {
            throw new IllegalArgumentException("缺少城市名或经纬度参数");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", weather);
        return result;
    }

    @GetMapping("/air")
    public Map<String, Object> getAirQuality(@RequestParam String city) throws IOException {
        Weather weather = weatherService.getAirQualityByCity(city);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", weather);
        return result;
    }
}
