package com.pricepulse.repository;

import com.pricepulse.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface PriceHistoryRepository extends JpaRepository<PriceHistory, Long> {
    List<PriceHistory> findByProductIdOrderByRecordedAtDesc(Long productId);
    Optional<PriceHistory> findFirstByProductIdOrderByRecordedAtDesc(Long productId);
}
