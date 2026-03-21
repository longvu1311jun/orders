package mera.orders.controller;

import lombok.RequiredArgsConstructor;
import mera.orders.client.OrderApiClient;
import mera.orders.entity.Order;
import mera.orders.service.OrderSyncResult;
import mera.orders.service.OrderSyncService;
import mera.orders.service.OrdersService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrdersController {
  private final OrdersService ordersService;
  private final OrderSyncService orderSyncService;
  private final OrderApiClient orderApiClient;

  @GetMapping("/getById/{id}")
  public ResponseEntity<Order> getById(@PathVariable Long id) {
    return ResponseEntity.ok(ordersService.getById(id));
  }

  @GetMapping("/fetch")
  public ResponseEntity<Void> fetchOrders() {
    var orders = orderApiClient.fetchOrders();
    return ResponseEntity.ok().build();
  }

  @GetMapping("/sync")
  public ResponseEntity<OrderSyncResult> syncOrders() {
    OrderSyncResult result = orderSyncService.syncOrders();
    return ResponseEntity.ok(result);
  }
}
