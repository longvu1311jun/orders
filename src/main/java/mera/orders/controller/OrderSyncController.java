package mera.orders.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mera.orders.dto.OrderPreviewRequest;
import mera.orders.service.OrderPreviewResult;
import mera.orders.service.OrderSyncResult;
import mera.orders.service.OrderSyncService;
import mera.orders.service.OrderPreviewService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderSyncController {

  private final OrderSyncService orderSyncService;
  private final OrderPreviewService orderPreviewService;

  /**
   * POST /api/orders/sync
   * Sync orders từ API vào DB. Nhận cùng body như /preview để dùng chung date logic.
   */
  @PostMapping("/sync")
  public ResponseEntity<?> syncOrders(@RequestBody OrderPreviewRequest request) {
    log.info("Sync request received: startDate={}, endDate={}, status={}, page={}, size={}, updateStatus={}",
        request.getStartDate(), request.getEndDate(),
        request.getStatus(), request.getPageNumber(), request.getPageSize(), request.getUpdateStatus());
    try {
      // Parse dates to timestamps (same logic as preview: local 00:00 - 7h to start, local 23:59:59 - 7h to end)
      LocalDate startLocal = LocalDate.parse(request.getStartDate());
      LocalDate endLocal = LocalDate.parse(request.getEndDate());

      long startTs = LocalDateTime.of(startLocal, LocalTime.of(0, 0, 0))
          .minusHours(7).toEpochSecond(ZoneOffset.UTC);
      long endTs = LocalDateTime.of(endLocal, LocalTime.of(23, 59, 59))
          .minusHours(7).toEpochSecond(ZoneOffset.UTC);

      int pageNum = request.getPageNumber() != null ? request.getPageNumber() : 1;
      int pageSz = request.getPageSize() != null ? request.getPageSize() : 200;
      String updStatus = request.getUpdateStatus() != null ? request.getUpdateStatus() : "inserted_at";
      String status = request.getStatus();

      OrderSyncResult result = orderSyncService.syncOrders(
          startTs, endTs, pageNum, pageSz, updStatus, status);
      log.info("Sync completed: total={}, customers={}, orders={}, items={}",
          result.getTotalOrdersFromApi(),
          result.getCustomerChanges(),
          result.getOrderChanges(),
          result.getOrderItemChanges());
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException e) {
      log.warn("Sync validation failed: {}", e.getMessage());
      return ResponseEntity.badRequest().body(
          java.util.Map.of("error", e.getMessage()));
    } catch (Exception e) {
      log.error("Sync failed: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
          java.util.Map.of("error", e.getMessage()));
    }
  }

  /**
   * POST /api/orders/preview
   * Gọi API Pancake để lấy orders với filter,
   * trả JSON về frontend. api_key không bị lộ.
   */
  @PostMapping("/preview")
  public ResponseEntity<?> previewOrders(@RequestBody OrderPreviewRequest request) {
    log.info("Preview request received: startDate={}, endDate={}, status={}, page={}, size={}",
        request.getStartDate(), request.getEndDate(),
        request.getStatus(), request.getPageNumber(), request.getPageSize());
    try {
      OrderPreviewResult result = orderPreviewService.preview(request);
      return ResponseEntity.ok(result);
    } catch (IllegalArgumentException e) {
      log.warn("Preview validation failed: {}", e.getMessage());
      return ResponseEntity.badRequest().body(
          java.util.Map.of("error", e.getMessage())
      );
    } catch (Exception e) {
      log.error("Preview failed: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
          java.util.Map.of("error", e.getMessage())
      );
    }
  }

  /**
   * GET /api/orders/sync/health
   * Check xem controller và service bean có hoạt động không.
   */
  @GetMapping("/sync/health")
  public ResponseEntity<String> health() {
    return ResponseEntity.ok("OrderSyncController is up");
  }
}
