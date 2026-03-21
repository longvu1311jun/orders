package mera.orders.repository;

import mera.orders.entity.ProductVariation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductVariationRepository extends JpaRepository<ProductVariation, String> {
}
