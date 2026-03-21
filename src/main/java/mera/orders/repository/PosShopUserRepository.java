package mera.orders.repository;

import mera.orders.entity.PosShopUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosShopUserRepository extends JpaRepository<PosShopUser, String> {
}
