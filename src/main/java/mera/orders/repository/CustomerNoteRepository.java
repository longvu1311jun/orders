package mera.orders.repository;

import mera.orders.entity.CustomerNote;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerNoteRepository extends JpaRepository<CustomerNote, String> {
}
