package com.smog.controller;

import com.smog.entity.Location;
import com.smog.service.WeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/location")
@CrossOrigin(origins = "*")
public class LocationController {

    @Autowired
    private WeatherService weatherService;

    @PostMapping("/save")
    public Map<String, Object> saveLocation(@RequestBody Map<String, Object> request) {
        Map<String, Object> result = new HashMap<>();
        try {
            String cityName = (String) request.get("cityName");
            Double latitude = request.get("latitude") != null ? 
                ((Number) request.get("latitude")).doubleValue() : null;
            Double longitude = request.get("longitude") != null ? 
                ((Number) request.get("longitude")).doubleValue() : null;

            Location location = weatherService.saveLocation(cityName, latitude, longitude);
            result.put("success", true);
            result.put("data", location);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }

    @GetMapping("/current")
    public Map<String, Object> getCurrentLocation() {
        Map<String, Object> result = new HashMap<>();
        try {
            Location location = weatherService.getCurrentLocation();
            result.put("success", true);
            result.put("data", location);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", e.getMessage());
        }
        return result;
    }
}