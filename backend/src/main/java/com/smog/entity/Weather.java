package com.smog.entity;

import javax.persistence.*;

@Entity
@Table(name = "weather_data")
public class Weather {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String cityName;

    @Column
    private String weather;

    @Column
    private Double temperature;

    @Column
    private Double humidity;

    @Column
    private Integer aqi;

    @Column
    private String airQuality;

    @Column
    private String pm25;

    @Column
    private String pm10;

    @Column
    private Long updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCityName() { return cityName; }
    public void setCityName(String cityName) { this.cityName = cityName; }
    public String getWeather() { return weather; }
    public void setWeather(String weather) { this.weather = weather; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getHumidity() { return humidity; }
    public void setHumidity(Double humidity) { this.humidity = humidity; }
    public Integer getAqi() { return aqi; }
    public void setAqi(Integer aqi) { this.aqi = aqi; }
    public String getAirQuality() { return airQuality; }
    public void setAirQuality(String airQuality) { this.airQuality = airQuality; }
    public String getPm25() { return pm25; }
    public void setPm25(String pm25) { this.pm25 = pm25; }
    public String getPm10() { return pm10; }
    public void setPm10(String pm10) { this.pm10 = pm10; }
    public Long getUpdateTime() { return updateTime; }
    public void setUpdateTime(Long updateTime) { this.updateTime = updateTime; }
}