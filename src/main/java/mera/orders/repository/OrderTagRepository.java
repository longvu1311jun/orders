package mera.orders.repository;

import mera.orders.entity.OrderTag;
import mera.orders.entity.OrderTagId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderTagRepository extends JpaRepository<OrderTag, OrderTagId> {
}
