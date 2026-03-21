package org.report.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mera.orders.dto.OrderPreviewRequest;
import mera.orders.service.OrderPreviewResult;
import mera.orders.service.OrderPreviewService;
import mera.orders.service.OrderSyncResult;
import mera.orders.service.OrderSyncService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderSyncController {

  private final OrderSyncService orderSyncService;
  private final OrderPreviewService orderPreviewService;

  /**
   * POST /api/orders/sync
   * Sync orders từ API vào DB. Mỗi request gọi một batch sync.
   */
  @PostMapping("/sync")
  public ResponseEntity<OrderSyncResult> syncOrders() {
    log.info("Manual sync triggered via HTTP");
    try {
      OrderSyncResult result = orderSyncService.syncOrders();
      log.info("Sync completed: total={}, customers={}, orders={}, items={}",
          result.getTotalOrdersFromApi(),
          result.getCustomerChanges(),
          result.getOrderChanges(),
          result.getOrderItemChanges());
      return ResponseEntity.ok(result);
    } catch (Exception e) {
      log.error("Sync failed: {}", e.getMessage(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
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
