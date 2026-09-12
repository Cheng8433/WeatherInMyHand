package com.smog.controller;

import com.smog.entity.Location;
import com.smog.service.LocationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/location")
public class LocationController {

    @Autowired
    private LocationService locationService;

    /**
     * 客户端保存位置（支持两种方式）：
     * 1. 上传经纬度：{"latitude": 39.9042, "longitude": 116.4074}（逆地理编码得城市名）
     * 2. 上传城市名：{"cityName": "北京", "latitude": 0, "longitude": 0}
     * 错误统一由 GlobalExceptionHandler 返回 {success:false, message}（HTTP 200）
     *
     * <p>隐私口径：这里上传的经纬度**只用于当次逆地理编码**，落库的是解析出的【城市】坐标，
     * 不是调用方的原始定位。天气只需要城市级粒度，长期留存米级个人位置既无必要也不合规
     * （位置属敏感个人信息），因此这条约束放在服务端强制，不依赖某个客户端版本自觉。
     */
    @PostMapping("/save")
    public Location saveLocation(@RequestBody Map<String, Object> payload) throws IOException {
        double lat = payload.get("latitude") != null ? ((Number) payload.get("latitude")).doubleValue() : 0;
        double lon = payload.get("longitude") != null ? ((Number) payload.get("longitude")).doubleValue() : 0;
        String cityName = (String) payload.get("cityName");

        if (lat != 0 && lon != 0) {
            // 定位场景：根据经纬度逆地理编码获取城市名
            return locationService.saveLocationByLatLon(lat, lon);
        } else if (cityName != null && !cityName.isEmpty()) {
            // 手动搜索场景：根据城市名保存
            return locationService.saveLocationFromApi(cityName);
        } else {
            throw new IllegalArgumentException("无效的请求参数");
        }
    }
}
