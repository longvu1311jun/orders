package mera.orders.repository;

import mera.orders.entity.OrderSource;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSourceRepository extends JpaRepository<OrderSource, String> {
}
