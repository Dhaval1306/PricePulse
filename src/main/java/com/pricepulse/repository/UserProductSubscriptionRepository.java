package com.pricepulse.repository;

import com.pricepulse.entity.Product;
import com.pricepulse.entity.User;
import com.pricepulse.entity.UserProductSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserProductSubscriptionRepository extends JpaRepository<UserProductSubscription, Long> {
    List<UserProductSubscription> findByUserAndIsActiveTrue(User user);
    List<UserProductSubscription> findByProductAndIsActiveTrue(Product product);
    Optional<UserProductSubscription> findByUserAndProduct(User user, Product product);
}
