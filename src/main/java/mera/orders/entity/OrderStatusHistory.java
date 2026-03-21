package mera.orders.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "order_status_histories")
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "old_status")
    private Integer oldStatus;

    @Column(name = "new_status", nullable = false)
    private Integer newStatus;

    @Column(name = "editor_id", length = 64)
    private String editorId;

    @Column(name = "editor_name", length = 255)
    private String editorName;

    @Column(name = "editor_fb", length = 64)
    private String editorFb;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", referencedColumnName = "id", insertable = false, updatable = false)
    private Order order;
}
