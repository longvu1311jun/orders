package mera.orders.repository;

import mera.orders.entity.ProductSubstitution;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSubstitutionRepository extends JpaRepository<ProductSubstitution, Long> {
}
