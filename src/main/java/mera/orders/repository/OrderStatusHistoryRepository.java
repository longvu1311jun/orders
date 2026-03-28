package mera.orders.repository;

import java.util.List;
import mera.orders.entity.OrderStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {
  List<OrderStatusHistory> findAllByOrder_IdIn(List<Long> orderIds);
}
