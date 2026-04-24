package com.smog.repository;

import com.smog.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface LocationRepository extends JpaRepository<Location, Long> {
    Optional<Location> findTopByOrderByUpdateTimeDesc();
    Optional<Location> findTopByCityNameOrderByUpdateTimeDesc(String cityName);
}