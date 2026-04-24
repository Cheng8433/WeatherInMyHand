package com.smog.entity;

import javax.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "weather_data")
public class Weather {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ================== 基础信息 ==================
    @Column(nullable = false)
    private String cityName;          // 城市名称

    @Column
    private Long updateTime;          // 数据更新时间戳（毫秒）

    // ================== 实时天气 (now) ==================
    @Column
    private String weather;           // 天气状况（如“多云”）

    @Column
    private Double temperature;       // 温度（℃）

    @Column
    private Double feelsLike;         // 体感温度（℃）

    @Column
    private Double humidity;          // 相对湿度（%）

    @Column
    private String windDir;           // 风向（如“东南风”）

    @Column
    private String windScale;         // 风力等级（如“1”）

    @Column
    private Double windSpeed;         // 风速（km/h）

    @Column
    private Double precip;            // 降水量（mm）

    @Column
    private Double pressure;          // 大气压力（hPa）

    @Column
    private Double vis;               // 能见度（km）

    @Column
    private String cloud;             // 云量（%）

    @Column
    private Double dew;               // 露点温度（℃）

    // ================== 空气质量 - 指数 ==================
    @Column
    private Integer aqi;              // 兼容旧字段，将映射为 us-epa 的 aqi

    @Column
    private Integer aqiUs;            // 美国标准 AQI (us-epa)

    @Column
    private BigDecimal aqiQa;         // QAQI 指数（和风自研，小数）

    @Column
    private String airQuality;        // 空气质量类别（如“Good”）

    @Column
    private String primaryPollutant;  // 首要污染物代码（如“pm2p5”）

    // ================== 污染物浓度 ==================
    @Column
    private String pm25;              // PM2.5 浓度（μg/m³），保留字符串兼容

    @Column
    private String pm10;              // PM10 浓度（μg/m³）

    @Column
    private Double pm25Value;         // PM2.5 数值（便于计算）

    @Column
    private Double pm10Value;         // PM10 数值

    @Column
    private Double no2;               // 二氧化氮浓度（ppb）

    @Column
    private Double o3;                // 臭氧浓度（ppb）

    @Column
    private Double co;                // 一氧化碳浓度（ppm）

    @Column
    private Double so2;               // 二氧化硫浓度（ppb，若接口返回）

    // ================== 构造器、Getter 和 Setter ==================
    public Weather() {}

    // ----- 基础信息 -----
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCityName() { return cityName; }
    public void setCityName(String cityName) { this.cityName = cityName; }
    public Long getUpdateTime() { return updateTime; }
    public void setUpdateTime(Long updateTime) { this.updateTime = updateTime; }

    // ----- 实时天气 -----
    public String getWeather() { return weather; }
    public void setWeather(String weather) { this.weather = weather; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getFeelsLike() { return feelsLike; }
    public void setFeelsLike(Double feelsLike) { this.feelsLike = feelsLike; }
    public Double getHumidity() { return humidity; }
    public void setHumidity(Double humidity) { this.humidity = humidity; }
    public String getWindDir() { return windDir; }
    public void setWindDir(String windDir) { this.windDir = windDir; }
    public String getWindScale() { return windScale; }
    public void setWindScale(String windScale) { this.windScale = windScale; }
    public Double getWindSpeed() { return windSpeed; }
    public void setWindSpeed(Double windSpeed) { this.windSpeed = windSpeed; }
    public Double getPrecip() { return precip; }
    public void setPrecip(Double precip) { this.precip = precip; }
    public Double getPressure() { return pressure; }
    public void setPressure(Double pressure) { this.pressure = pressure; }
    public Double getVis() { return vis; }
    public void setVis(Double vis) { this.vis = vis; }
    public String getCloud() { return cloud; }
    public void setCloud(String cloud) { this.cloud = cloud; }
    public Double getDew() { return dew; }
    public void setDew(Double dew) { this.dew = dew; }

    // ----- 空气质量指数 -----
    public Integer getAqi() { return aqi; }
    public void setAqi(Integer aqi) { this.aqi = aqi; }
    public Integer getAqiUs() { return aqiUs; }
    public void setAqiUs(Integer aqiUs) { this.aqiUs = aqiUs; }
    public BigDecimal getAqiQa() { return aqiQa; }
    public void setAqiQa(BigDecimal aqiQa) { this.aqiQa = aqiQa; }
    public String getAirQuality() { return airQuality; }
    public void setAirQuality(String airQuality) { this.airQuality = airQuality; }
    public String getPrimaryPollutant() { return primaryPollutant; }
    public void setPrimaryPollutant(String primaryPollutant) { this.primaryPollutant = primaryPollutant; }

    // ----- 污染物浓度 -----
    public String getPm25() { return pm25; }
    public void setPm25(String pm25) { this.pm25 = pm25; }
    public String getPm10() { return pm10; }
    public void setPm10(String pm10) { this.pm10 = pm10; }
    public Double getPm25Value() { return pm25Value; }
    public void setPm25Value(Double pm25Value) { this.pm25Value = pm25Value; }
    public Double getPm10Value() { return pm10Value; }
    public void setPm10Value(Double pm10Value) { this.pm10Value = pm10Value; }
    public Double getNo2() { return no2; }
    public void setNo2(Double no2) { this.no2 = no2; }
    public Double getO3() { return o3; }
    public void setO3(Double o3) { this.o3 = o3; }
    public Double getCo() { return co; }
    public void setCo(Double co) { this.co = co; }
    public Double getSo2() { return so2; }
    public void setSo2(Double so2) { this.so2 = so2; }
}