package mera.orders.repository;

import mera.orders.entity.OrderEditHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderEditHistoryRepository extends JpaRepository<OrderEditHistory, Long> {
}
