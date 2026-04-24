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

    @GetMapping("/info")
    public Map<String, Object> getWeatherInfo(@RequestParam String city) {
        Map<String, Object> result = new HashMap<>();
        try {
            Weather weather = weatherService.getWeatherAndAirQuality(city);
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