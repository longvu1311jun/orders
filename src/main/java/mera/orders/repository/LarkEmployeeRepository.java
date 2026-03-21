package mera.orders.repository;

import mera.orders.entity.LarkEmployee;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LarkEmployeeRepository extends JpaRepository<LarkEmployee, String> {
}
