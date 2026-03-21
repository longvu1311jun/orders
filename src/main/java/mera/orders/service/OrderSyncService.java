package mera.orders.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mera.orders.client.OrderApiClient;
import mera.orders.DTO.CustomerDTO;
import mera.orders.DTO.OrderApiDto;
import mera.orders.DTO.OrderItemApiDto;
import mera.orders.DTO.ShippingAddressDTO;
import mera.orders.DTO.VariationInfoApiDto;
import mera.orders.entity.Customer;
import mera.orders.entity.Order;
import mera.orders.entity.OrderItem;
import mera.orders.repository.CustomerRepository;
import mera.orders.repository.OrderItemRepository;
import mera.orders.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSyncService {

  private final OrderApiClient orderApiClient;
  private final CustomerRepository customerRepository;
  private final OrderRepository orderRepository;
  private final OrderItemRepository orderItemRepository;

  // ============================================================
  // Public entry points
  // ============================================================

  /**
   * Sync orders using default parameters (legacy, no date range).
   * Calls API with timestamp=0,0 — returns all orders from the beginning.
   */
  public OrderSyncResult syncOrders() {
    return syncOrders(0, 0, 1, 200, "inserted_at", null);
  }

  /**
   * Sync orders using dynamic parameters (uses same timestamp logic as preview).
   *
   * @param startTimestamp Unix timestamp (seconds) - start of range
   * @param endTimestamp   Unix timestamp (seconds) - end of range
   * @param pageNumber     1-based page number
   * @param pageSize       page size
   * @param updateStatus   "inserted_at" or "updated_at"
   * @param status         order status filter, null/blank = no filter
   */
  public OrderSyncResult syncOrders(
      long startTimestamp,
      long endTimestamp,
      int pageNumber,
      int pageSize,
      String updateStatus,
      String status
  ) {
    log.info("Starting order sync: startTs={}, endTs={}, page={}, size={}, updateStatus={}, status={}",
        startTimestamp, endTimestamp, pageNumber, pageSize, updateStatus, status);

    List<OrderApiDto> orders = orderApiClient.fetchOrdersDynamic(
        startTimestamp, endTimestamp, pageNumber, pageSize, updateStatus, status
    );

    OrderSyncResult result = OrderSyncResult.builder()
        .totalOrdersFromApi(orders.size())
        .insertedCustomers(0)
        .updatedCustomers(0)
        .insertedOrders(0)
        .updatedOrders(0)
        .insertedOrderItems(0)
        .updatedOrderItems(0)
        .skippedOrders(0)
        .build();

    for (OrderApiDto dto : orders) {
      try {
        OrderSyncResult partial = syncSingleOrder(dto);
        mergeResult(result, partial);
      } catch (Exception e) {
        log.error("Failed to sync order id={}: {}", dto.getId(), e.getMessage());
        result.getErrorMessages().add("Order " + dto.getId() + ": " + e.getMessage());
      }
    }

    log.info("Sync completed. total={}, customers={}, orders={}, items={}, skipped={}",
        result.getTotalOrdersFromApi(),
        result.getCustomerChanges(),
        result.getOrderChanges(),
        result.getOrderItemChanges(),
        result.getSkippedOrders());

    return result;
  }

  // ============================================================
  // Sync single order (one transaction per order)
  // ============================================================

  @Transactional
  public OrderSyncResult syncSingleOrder(OrderApiDto dto) {
    OrderSyncResult.OrderSyncResultBuilder rb = OrderSyncResult.builder()
        .totalOrdersFromApi(1);

    // 1. Upsert customer
    CustomerSyncResult custResult = upsertCustomer(dto.getCustomer(), dto.getShopId());
    rb.insertedCustomers(custResult.inserted)
       .updatedCustomers(custResult.updated);

    // 2. Upsert order
    OrderSyncResult orderResult = upsertOrder(dto);
    rb.insertedOrders(orderResult.getInsertedOrders())
       .updatedOrders(orderResult.getUpdatedOrders());

    // 3. Upsert order items
    int insertedItems = 0;
    int updatedItems = 0;
    if (dto.getItems() != null && !dto.getItems().isEmpty()) {
      for (OrderItemApiDto itemDto : dto.getItems()) {
        ItemSyncResult itemResult = upsertOrderItem(itemDto, dto.getId());
        insertedItems += itemResult.inserted;
        updatedItems += itemResult.updated;
      }
    }
    rb.insertedOrderItems(insertedItems)
       .updatedOrderItems(updatedItems);

    return rb.build();
  }

  // ============================================================
  // Upsert Customer
  // ============================================================

  private CustomerSyncResult upsertCustomer(CustomerDTO dto, Long shopId) {
    if (dto == null || dto.getId() == null) {
      return new CustomerSyncResult(0, 0);
    }

    String customerId = dto.getId();
    Optional<Customer> existing = customerRepository.findById(customerId);

    Customer customer;
    boolean isNew;
    if (existing.isPresent()) {
      customer = existing.get();
      isNew = false;
    } else {
      customer = new Customer();
      customer.setId(customerId);
      isNew = true;
    }

    // Required fields
    customer.setShopId(shopId);
    customer.setName(dto.getName() != null ? dto.getName() : "Unknown");

    // Optional fields
    customer.setGender(dto.getGender());
    customer.setFbId(dto.getFbId());
    customer.setReferralCode(dto.getReferralCode());
    customer.setInsertedAt(parseDateTime(dto.getInsertedAt(), "customer.insertedAt"));
    customer.setUpdatedAt(parseDateTime(dto.getUpdatedAt(), "customer.updatedAt"));

    customerRepository.save(customer);

    return new CustomerSyncResult(isNew ? 1 : 0, isNew ? 0 : 1);
  }

  // ============================================================
  // Upsert Order
  // ============================================================

  private OrderSyncResult upsertOrder(OrderApiDto dto) {
    Long orderId = parseOrderId(dto.getId());
    if (orderId == null) {
      throw new IllegalArgumentException("Cannot parse order id: " + dto.getId());
    }

    Optional<Order> existing = orderRepository.findById(orderId);
    Order order;
    boolean isNew;
    if (existing.isPresent()) {
      order = existing.get();
      isNew = false;
    } else {
      order = new Order();
      order.setId(orderId);
      isNew = true;
    }

    // Basic fields
    order.setShopId(dto.getShopId());
    order.setStatus(dto.getStatus() != null ? dto.getStatus() : 0);
    order.setStatusName(dto.getStatusName());

    // Customer ID from nested object
    if (dto.getCustomer() != null && dto.getCustomer().getId() != null) {
      order.setCustomerId(dto.getCustomer().getId());
    }

    // Money fields (Double from DTO -> entity uses Double or BigDecimal)
    if (dto.getTotalPrice() != null) {
      order.setTotalPrice(dto.getTotalPrice());
    }
    if (dto.getCod() != null) {
      order.setCod(BigDecimal.valueOf(dto.getCod()));
    }
    if (dto.getPrepaid() != null) {
      order.setPrepaid(BigDecimal.valueOf(dto.getPrepaid()));
    }
    if (dto.getShippingFee() != null) {
      order.setShippingFee(BigDecimal.valueOf(dto.getShippingFee()));
    }

    // Bill info
    order.setBillFullName(dto.getBillFullName());
    order.setBillPhoneNumber(dto.getBillPhoneNumber());
    order.setBillEmail(dto.getBillEmail());

    // Note
    order.setNote(dto.getNote());

    // Shipping address from nested object
    ShippingAddressDTO addr = dto.getShippingAddress();
    if (addr != null) {
      order.setShippingFullName(addr.getFullName());
      order.setShippingPhoneNumber(addr.getPhoneNumber());
      order.setShippingAddress(addr.getAddress());
      order.setShippingFullAddress(addr.getFullAddress());
      order.setShippingProvinceName(addr.getProvinceName());
      order.setShippingDistrictName(addr.getDistrictName());
      order.setShippingCommuneName(addr.getCommuneName());
    }

    // Datetime fields
    order.setInsertedAt(parseDateTime(dto.getInsertedAt(), "order.insertedAt"));
    order.setUpdatedAt(parseDateTime(dto.getUpdatedAt(), "order.updatedAt"));

    orderRepository.save(order);

    return OrderSyncResult.builder()
        .insertedOrders(isNew ? 1 : 0)
        .updatedOrders(isNew ? 0 : 1)
        .build();
  }

  // ============================================================
  // Upsert OrderItem
  // ============================================================

  private ItemSyncResult upsertOrderItem(OrderItemApiDto dto, String orderIdStr) {
    if (dto == null) {
      return new ItemSyncResult(0, 0);
    }

    Long orderId = parseOrderId(orderIdStr);
    if (orderId == null) {
      log.warn("Cannot parse orderId from: {}", orderIdStr);
      return new ItemSyncResult(0, 0);
    }

    // Parse item id - API may return String
    Long itemId = parseItemId(dto.getId());
    if (itemId == null) {
      log.warn("Cannot parse item id: {}", dto.getId());
      return new ItemSyncResult(0, 0);
    }

    Optional<OrderItem> existing = orderItemRepository.findById(itemId);
    OrderItem item;
    boolean isNew;
    if (existing.isPresent()) {
      item = existing.get();
      isNew = false;
    } else {
      item = new OrderItem();
      item.setId(itemId);
      isNew = true;
    }

    // FK to order
    item.setOrderId(orderId);

    // Basic fields
    item.setProductId(dto.getProductId());
    item.setVariationId(dto.getVariationId());
    item.setQuantity(dto.getQuantity() != null ? dto.getQuantity() : 1);

    // Money
    if (dto.getDiscountEachProduct() != null) {
      item.setDiscountEachProduct(dto.getDiscountEachProduct());
    }
    if (dto.getTotalDiscount() != null) {
      item.setTotalDiscount(dto.getTotalDiscount().doubleValue());
    }

    // Variation info
    VariationInfoApiDto varInfo = dto.getVariationInfo();
    if (varInfo != null) {
      item.setVariationName(varInfo.getName());
      item.setRetailPrice(varInfo.getRetailPrice() != null ? varInfo.getRetailPrice().doubleValue() : null);
      item.setWeight(varInfo.getWeight());
      // variationId from variation_info if not set from outer
      if (item.getVariationId() == null && varInfo.getName() != null) {
        item.setVariationName(varInfo.getName());
      }
    }

    // Product name (flatten from variation_info or direct)
    if (varInfo != null && varInfo.getName() != null) {
      item.setProductName(varInfo.getName());
    }

    orderItemRepository.save(item);

    return new ItemSyncResult(isNew ? 1 : 0, isNew ? 0 : 1);
  }

  // ============================================================
  // ID parsing helpers
  // ============================================================

  /**
   * Parse order ID from API response.
   * API may return String like "A100315290.37" or plain numeric string.
   * Strip non-numeric characters and parse as Long.
   */
  Long parseOrderId(String id) {
    if (id == null || id.isBlank()) {
      return null;
    }
    try {
      return Long.parseLong(id.trim());
    } catch (NumberFormatException e) {
      // Try strip non-numeric
      String numeric = id.replaceAll("[^0-9]", "");
      if (!numeric.isEmpty()) {
        return Long.parseLong(numeric);
      }
      return null;
    }
  }

  /**
   * Parse item ID. Same logic as order ID.
   */
  Long parseItemId(String id) {
    return parseOrderId(id);
  }

  // ============================================================
  // Datetime parsing helpers
  // ============================================================

  private static final List<DateTimeFormatter> DATETIME_FORMATTERS = List.of(
      DateTimeFormatter.ISO_LOCAL_DATE_TIME,
      DateTimeFormatter.ISO_DATE_TIME,
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
  );

  private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
      DateTimeFormatter.ISO_LOCAL_DATE,
      DateTimeFormatter.ofPattern("yyyy-MM-dd")
  );

  LocalDateTime parseDateTime(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return LocalDateTime.now();
    }
    for (DateTimeFormatter fmt : DATETIME_FORMATTERS) {
      try {
        return LocalDateTime.parse(value, fmt);
      } catch (DateTimeParseException ignored) {
      }
    }
    // Try epoch millis
    try {
      long millis = Long.parseLong(value.trim());
      return LocalDateTime.ofInstant(
          java.time.Instant.ofEpochMilli(millis),
          java.time.ZoneId.systemDefault()
      );
    } catch (NumberFormatException ignored) {
    }
    log.warn("Cannot parse datetime '{}' for field {}, using current time", value, fieldName);
    return LocalDateTime.now();
  }

  LocalDate parseDate(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    for (DateTimeFormatter fmt : DATE_FORMATTERS) {
      try {
        return LocalDate.parse(value, fmt);
      } catch (DateTimeParseException ignored) {
      }
    }
    log.warn("Cannot parse date '{}' for field {}", value, fieldName);
    return null;
  }

  // ============================================================
  // Result helpers
  // ============================================================

  private void mergeResult(OrderSyncResult target, OrderSyncResult source) {
    target.getErrorMessages().addAll(source.getErrorMessages());
  }

  // ============================================================
  // Inner result classes
  // ============================================================

  private record CustomerSyncResult(int inserted, int updated) {}

  private record ItemSyncResult(int inserted, int updated) {}
}
