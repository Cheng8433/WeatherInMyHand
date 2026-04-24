package com.smog.controller;

import com.smog.entity.Location;
import com.smog.service.LocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    @Autowired
    private LocationService locationService;

    /**
     * 根据城市名称获取经纬度
     * GET /api/location/search?city=西安
     */
    @GetMapping("/search")
    public ResponseEntity<?> getLocation(@RequestParam("city") String cityName) {
        try {
            Location location = locationService.getOrFetchLocation(cityName);
            Map<String, Object> result = new HashMap<>();
            result.put("cityName", location.getCityName());
            result.put("latitude", location.getLatitude());
            result.put("longitude", location.getLongitude());
            result.put("updateTime", location.getUpdateTime());
            return ResponseEntity.ok(result);
        } catch (RuntimeException e) {
            String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return ResponseEntity.status(404).body(Map.of("error", errorMsg));
        }
    }

    /**
     * 仅从数据库查询（不触发和风 API）
     * GET /api/location/local?city=西安
     */
    @GetMapping("/local")
    public ResponseEntity<?> getLocalLocation(@RequestParam("city") String cityName) {
        Location location = locationService.getLocationFromDB(cityName);
        if (location == null) {
            return ResponseEntity.status(404).body(Map.of("error", "数据库中未找到城市：" + cityName));
        }
        return ResponseEntity.ok(location);
    }

    /**
     * 强制从和风 API 获取位置（不保存到数据库，仅用于测试）
     * GET /api/location/fetch?city=西安
     */
    @GetMapping("/fetch")
    public ResponseEntity<?> fetchFromApi(@RequestParam("city") String cityName) {
        try {
            Location location = locationService.fetchLocationFromApi(cityName);
            return ResponseEntity.ok(location);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "调用和风 API 失败：" + e.getMessage()));
        }
    }
}