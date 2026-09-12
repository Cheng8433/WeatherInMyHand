package com.smog.repository;

import com.smog.entity.Weather;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WeatherRepository extends JpaRepository<Weather, Long> {
    Optional<Weather> findTopByCityNameOrderByUpdateTimeDesc(String cityName);
}