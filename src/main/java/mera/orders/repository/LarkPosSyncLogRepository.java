package mera.orders.repository;

import mera.orders.entity.LarkPosSyncLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LarkPosSyncLogRepository extends JpaRepository<LarkPosSyncLog, Long> {
}
