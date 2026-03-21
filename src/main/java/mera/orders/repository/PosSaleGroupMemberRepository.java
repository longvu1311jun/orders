package mera.orders.repository;

import mera.orders.entity.PosSaleGroupMember;
import mera.orders.entity.PosSaleGroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosSaleGroupMemberRepository extends JpaRepository<PosSaleGroupMember, PosSaleGroupMemberId> {
}
