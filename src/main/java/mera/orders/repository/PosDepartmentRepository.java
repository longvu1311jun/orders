package mera.orders.repository;

import mera.orders.entity.PosDepartment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosDepartmentRepository extends JpaRepository<PosDepartment, Long> {
}
