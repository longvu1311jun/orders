package mera.orders.repository;

import mera.orders.entity.CustomerPhoneNumber;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPhoneNumberRepository extends JpaRepository<CustomerPhoneNumber, Long> {
}
