package mera.orders.repository;

import mera.orders.entity.CustomerTag;
import mera.orders.entity.CustomerTagId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerTagRepository extends JpaRepository<CustomerTag, CustomerTagId> {
}
