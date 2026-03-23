package mera.orders.repository;

import mera.orders.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

  List<OrderItem> findAllByIdIn(List<Long> ids);
}
