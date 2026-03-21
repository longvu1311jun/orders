package mera.orders.repository;

import mera.orders.entity.PosUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosUserRepository extends JpaRepository<PosUser, String> {
}
