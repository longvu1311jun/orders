package mera.orders.repository;

import mera.orders.entity.VariationWarehouseStock;
import mera.orders.entity.VariationWarehouseStockId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VariationWarehouseStockRepository extends JpaRepository<VariationWarehouseStock, VariationWarehouseStockId> {
}
