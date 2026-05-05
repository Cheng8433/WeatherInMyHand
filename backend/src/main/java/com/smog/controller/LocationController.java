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
     * 客户端保存位置（支持两种方式）：
     * 1. 上传经纬度：{"latitude": 39.9042, "longitude": 116.4074}
     * 2. 上传城市名：{"cityName": "北京", "latitude": 0, "longitude": 0}
     */
    @PostMapping("/save")
    public ResponseEntity<?> saveLocation(@RequestBody Map<String, Object> payload) {
        try {
            Location location;
            // 判断是否有经纬度且非零（定位场景）
            double lat = payload.get("latitude") != null ? ((Number) payload.get("latitude")).doubleValue() : 0;
            double lon = payload.get("longitude") != null ? ((Number) payload.get("longitude")).doubleValue() : 0;
            String cityName = (String) payload.get("cityName");

            if (lat != 0 && lon != 0) {
                // 定位场景：根据经纬度逆地理编码获取城市名
                location = locationService.saveLocationByLatLon(lat, lon);
            } else if (cityName != null && !cityName.isEmpty()) {
                // 手动搜索场景：根据城市名保存
                location = locationService.saveLocationFromApi(cityName);
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "无效的请求参数"));
            }
            return ResponseEntity.ok(location);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 获取最近一次保存的位置（用于冷启动恢复）
     */
    @GetMapping("/local")
    public ResponseEntity<?> getLatestLocation() {
        Location location = locationService.getCurrentLocation();
        if (location == null) {
            return ResponseEntity.status(404).body(Map.of("success", false, "message", "没有历史位置"));
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", Map.of(
                "cityName", location.getCityName(),
                "latitude", location.getLatitude(),
                "longitude", location.getLongitude()
        ));
        return ResponseEntity.ok(result);
    }

    // 其他接口（search, fetch）保持不变...
}