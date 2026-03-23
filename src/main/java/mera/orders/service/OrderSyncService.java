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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  public OrderSyncResult syncOrders() {
    return syncOrders(0, 0, 1, 200, "inserted_at", null);
  }

  public OrderSyncResult syncOrders(
      long startTimestamp,
      long endTimestamp,
      int pageNumber,
      int pageSize,
      String updateStatus,
      String status
  ) {
    log.info("Starting order sync with pagination: startTs={}, endTs={}, page={}, size={}, updateStatus={}, status={}",
        startTimestamp, endTimestamp, pageNumber, pageSize, updateStatus, status);

    int totalEntries = 0;
    int insertedCustomers = 0;
    int updatedCustomers = 0;
    int insertedOrders = 0;
    int updatedOrders = 0;
    int insertedOrderItems = 0;
    int updatedOrderItems = 0;
    int skippedOrders = 0;
    var errorMessages = new ArrayList<String>();
    var skippedOrderIds = new ArrayList<String>();

    int currentPage = pageNumber;
    int totalPages = 1;
    int totalSynced = 0;

    do {
      log.info("Fetching page {} of {}...", currentPage, totalPages);

      var resp = orderApiClient.fetchOrdersPage(
          startTimestamp, endTimestamp, currentPage, pageSize, updateStatus, status
      );

      List<OrderApiDto> orders = resp.getData() != null ? resp.getData() : List.of();

      if (resp.getTotalPages() != null) totalPages = resp.getTotalPages();
      if (resp.getTotalEntries() != null) totalEntries = resp.getTotalEntries();

      log.info("Page {}: fetched {} orders (totalEntries={}, totalPages={})",
          currentPage, orders.size(), resp.getTotalEntries(), resp.getTotalPages());

      BatchSyncResult batchResult = syncPageBatch(orders);

      insertedCustomers += batchResult.insertedCustomers;
      updatedCustomers += batchResult.updatedCustomers;
      insertedOrders += batchResult.insertedOrders;
      updatedOrders += batchResult.updatedOrders;
      insertedOrderItems += batchResult.insertedOrderItems;
      updatedOrderItems += batchResult.updatedOrderItems;
      skippedOrders += batchResult.skippedOrders;
      errorMessages.addAll(batchResult.errorMessages);
      skippedOrderIds.addAll(batchResult.skippedOrderIds);
      totalSynced += orders.size();

      currentPage++;

    } while (currentPage <= totalPages);

    OrderSyncResult result = OrderSyncResult.builder()
        .totalOrdersFromApi(totalEntries)
        .insertedCustomers(insertedCustomers)
        .updatedCustomers(updatedCustomers)
        .insertedOrders(insertedOrders)
        .updatedOrders(updatedOrders)
        .insertedOrderItems(insertedOrderItems)
        .updatedOrderItems(updatedOrderItems)
        .skippedOrders(skippedOrders)
        .errorMessages(errorMessages)
        .build();

    log.info("Sync completed. totalEntries={}, synced={}, customers={}, orders={}, items={}, skipped={}",
        result.getTotalOrdersFromApi(), totalSynced,
        result.getCustomerChanges(), result.getOrderChanges(),
        result.getOrderItemChanges(), result.getSkippedOrders());

    saveSkippedOrders(skippedOrderIds, errorMessages);

    return result;
  }

  // ============================================================
  // Batch sync — one transaction per page
  // ============================================================

  @Transactional
  public BatchSyncResult syncPageBatch(List<OrderApiDto> orders) {
    if (orders == null || orders.isEmpty()) {
      return new BatchSyncResult(0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    // --- Step 1: Collect IDs ---
    List<Long> orderIds = new ArrayList<>();
    List<Long> itemIds = new ArrayList<>();
    List<String> customerIds = new ArrayList<>();

    for (OrderApiDto dto : orders) {
      Long orderId = parseOrderId(dto.getId());
      if (orderId != null) orderIds.add(orderId);

      if (dto.getCustomer() != null && dto.getCustomer().getId() != null) {
        customerIds.add(dto.getCustomer().getId());
      }

      if (dto.getItems() != null) {
        for (OrderItemApiDto item : dto.getItems()) {
          Long itemId = parseItemId(item.getId());
          if (itemId != null) itemIds.add(itemId);
        }
      }
    }

    // --- Step 2: Bulk fetch existing (3 queries total) ---
    Map<Long, Order> existingOrders = new HashMap<>();
    for (Order o : orderRepository.findAllByIdIn(orderIds)) {
      existingOrders.put(o.getId(), o);
    }

    Map<String, Customer> existingCustomers = new HashMap<>();
    for (Customer c : customerRepository.findAllByIdIn(customerIds)) {
      existingCustomers.put(c.getId(), c);
    }

    Map<Long, OrderItem> existingItems = new HashMap<>();
    for (OrderItem i : orderItemRepository.findAllByIdIn(itemIds)) {
      existingItems.put(i.getId(), i);
    }

    // --- Step 3: Build entity lists (map from DTO, no DB queries) ---
    List<Order> ordersToSave = new ArrayList<>();
    List<OrderItem> itemsToSave = new ArrayList<>();
    List<Customer> customersToSave = new ArrayList<>();
    Set<String> seenCustomerIds = new HashSet<>();

    int insertedCustomers = 0, updatedCustomers = 0;
    int insertedOrders = 0, updatedOrders = 0;
    int insertedOrderItems = 0, updatedOrderItems = 0;
    int skippedOrders = 0;
    var errorMessages = new ArrayList<String>();
    var skippedOrderIds = new ArrayList<String>();

    for (OrderApiDto dto : orders) {
      try {
        // --- Customer ---
        if (dto.getCustomer() != null && dto.getCustomer().getId() != null
            && !seenCustomerIds.contains(dto.getCustomer().getId())) {
          CustomerSyncResult custRes = mapCustomer(dto.getCustomer(), dto.getShopId(), existingCustomers);
          if (custRes.entity != null) {
            customersToSave.add(custRes.entity);
            if (custRes.isNew) insertedCustomers++;
            else updatedCustomers++;
          }
          seenCustomerIds.add(dto.getCustomer().getId());
        }

        // --- Order ---
        Long orderId = parseOrderId(dto.getId());
        if (orderId == null) {
          throw new IllegalArgumentException("Cannot parse order id: " + dto.getId());
        }
        Order order = existingOrders.get(orderId);
        boolean isNewOrder = (order == null);
        if (isNewOrder) {
          order = new Order();
          order.setId(orderId);
        }
        mapOrder(order, dto, isNewOrder);
        ordersToSave.add(order);
        if (isNewOrder) insertedOrders++;
        else updatedOrders++;

        // --- Order Items ---
        if (dto.getItems() != null) {
          for (OrderItemApiDto itemDto : dto.getItems()) {
            Long itemId = parseItemId(itemDto.getId());
            if (itemId == null) {
              log.warn("Cannot parse item id: {}", itemDto.getId());
              continue;
            }
            OrderItem item = existingItems.get(itemId);
            boolean isNewItem = (item == null);
            if (isNewItem) {
              item = new OrderItem();
              item.setId(itemId);
            }
            mapOrderItem(item, itemDto, orderId);
            itemsToSave.add(item);
            if (isNewItem) insertedOrderItems++;
            else updatedOrderItems++;
          }
        }

      } catch (Exception e) {
        log.error("Failed to sync order id={}: {}", dto.getId(), e.getMessage());
        errorMessages.add("Order " + dto.getId() + ": " + e.getMessage());
        skippedOrderIds.add(dto.getId());
        skippedOrders++;
      }
    }

    // --- Step 4: Batch save (3 queries total) ---
    if (!customersToSave.isEmpty()) {
      customerRepository.saveAll(customersToSave);
      log.debug("Saved {} customers", customersToSave.size());
    }
    if (!ordersToSave.isEmpty()) {
      orderRepository.saveAll(ordersToSave);
      log.debug("Saved {} orders", ordersToSave.size());
    }
    if (!itemsToSave.isEmpty()) {
      orderItemRepository.saveAll(itemsToSave);
      log.debug("Saved {} order items", itemsToSave.size());
    }

    return new BatchSyncResult(
        insertedCustomers, updatedCustomers,
        insertedOrders, updatedOrders,
        insertedOrderItems, updatedOrderItems,
        skippedOrders, errorMessages, skippedOrderIds
    );
  }

  // ============================================================
  // Mapping helpers (no DB access)
  // ============================================================

  private record CustomerSyncResult(Customer entity, boolean isNew) {}

  private CustomerSyncResult mapCustomer(CustomerDTO dto, Long shopId, Map<String, Customer> existing) {
    if (dto == null || dto.getId() == null) {
      return new CustomerSyncResult(null, false);
    }
    Customer customer = existing.get(dto.getId());
    boolean isNew = (customer == null);
    if (isNew) {
      customer = new Customer();
      customer.setId(dto.getId());
    }
    customer.setShopId(shopId);
    customer.setName(dto.getName() != null ? dto.getName() : "Unknown");
    customer.setGender(dto.getGender());
    customer.setFbId(dto.getFbId());
    customer.setReferralCode(dto.getReferralCode());
    customer.setInsertedAt(parseDateTime(dto.getInsertedAt(), "customer.insertedAt"));
    customer.setUpdatedAt(parseDateTime(dto.getUpdatedAt(), "customer.updatedAt"));
    return new CustomerSyncResult(customer, isNew);
  }

  private void mapOrder(Order order, OrderApiDto dto, boolean isNew) {
    order.setShopId(dto.getShopId());
    order.setStatus(dto.getStatus() != null ? dto.getStatus() : 0);
    order.setStatusName(dto.getStatusName());

    if (dto.getCustomer() != null && dto.getCustomer().getId() != null) {
      order.setCustomerId(dto.getCustomer().getId());
    }
    if (dto.getCreator() != null && dto.getCreator().getId() != null) {
      order.setCreatorId(dto.getCreator().getId());
    }

    if (dto.getTotalPrice() != null) order.setTotalPrice(dto.getTotalPrice());
    if (dto.getCod() != null) order.setCod(BigDecimal.valueOf(dto.getCod()));
    if (dto.getPrepaid() != null) order.setPrepaid(BigDecimal.valueOf(dto.getPrepaid()));
    if (dto.getShippingFee() != null) order.setShippingFee(BigDecimal.valueOf(dto.getShippingFee()));

    order.setBillFullName(dto.getBillFullName());
    order.setBillPhoneNumber(dto.getBillPhoneNumber());
    order.setBillEmail(dto.getBillEmail());
    order.setNote(dto.getNote());
    order.setOrderSources(dto.getOrderSources());
    order.setOrderSourcesName(dto.getOrderSourcesName());
    if (dto.getSystemId() != null) order.setOrderCode(String.valueOf(dto.getSystemId()));

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

    if (isNew) order.setInsertedAt(parseDateTime(dto.getInsertedAt(), "order.insertedAt"));
    order.setUpdatedAt(parseDateTime(dto.getUpdatedAt(), "order.updatedAt"));
  }

  private void mapOrderItem(OrderItem item, OrderItemApiDto dto, Long orderId) {
    item.setOrderId(orderId);
    item.setProductId(dto.getProductId());
    item.setVariationId(dto.getVariationId());
    item.setQuantity(dto.getQuantity() != null ? dto.getQuantity() : 1);
    if (dto.getDiscountEachProduct() != null) item.setDiscountEachProduct(dto.getDiscountEachProduct());
    if (dto.getTotalDiscount() != null) item.setTotalDiscount(dto.getTotalDiscount().doubleValue());

    VariationInfoApiDto varInfo = dto.getVariationInfo();
    if (varInfo != null) {
      item.setVariationName(varInfo.getName());
      item.setRetailPrice(varInfo.getRetailPrice() != null ? varInfo.getRetailPrice().doubleValue() : null);
      item.setWeight(varInfo.getWeight());
      if (varInfo.getName() != null) item.setProductName(varInfo.getName());
    }
  }

  // ============================================================
  // ID parsing helpers
  // ============================================================

  Long parseOrderId(String id) {
    if (id == null || id.isBlank()) return null;
    try {
      return Long.parseLong(id.trim());
    } catch (NumberFormatException e) {
      String numeric = id.replaceAll("[^0-9]", "");
      if (!numeric.isEmpty()) return Long.parseLong(numeric);
      return null;
    }
  }

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
    if (value == null || value.isBlank()) return LocalDateTime.now();
    for (DateTimeFormatter fmt : DATETIME_FORMATTERS) {
      try {
        return LocalDateTime.parse(value, fmt);
      } catch (DateTimeParseException ignored) {}
    }
    try {
      long millis = Long.parseLong(value.trim());
      return LocalDateTime.ofInstant(
          java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault());
    } catch (NumberFormatException ignored) {}
    log.warn("Cannot parse datetime '{}' for field {}, using current time", value, fieldName);
    return LocalDateTime.now();
  }

  LocalDate parseDate(String value, String fieldName) {
    if (value == null || value.isBlank()) return null;
    for (DateTimeFormatter fmt : DATE_FORMATTERS) {
      try {
        return LocalDate.parse(value, fmt);
      } catch (DateTimeParseException ignored) {}
    }
    log.warn("Cannot parse date '{}' for field {}", value, fieldName);
    return null;
  }

  // ============================================================
  // Skipped orders file writer
  // ============================================================

  private static final DateTimeFormatter FILE_DATE_FMT =
      DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

  private void saveSkippedOrders(List<String> skippedIds, List<String> errorMessages) {
    if (skippedIds == null || skippedIds.isEmpty()) {
      log.info("No skipped orders — skipping file write.");
      return;
    }
    try {
      Path logDir = Paths.get("logs");
      if (!Files.exists(logDir)) Files.createDirectories(logDir);

      String timestamp = LocalDateTime.now().format(FILE_DATE_FMT);
      Path file = logDir.resolve("skipped_orders_" + timestamp + ".json");

      var lines = new StringBuilder();
      lines.append("[\n");
      for (int i = 0; i < skippedIds.size(); i++) {
        String id = skippedIds.get(i);
        String reason = (i < errorMessages.size()) ? errorMessages.get(i) : "unknown";
        lines.append("  {\"orderId\": \"").append(id).append("\", \"reason\": \"").append(reason).append("\"}");
        if (i < skippedIds.size() - 1) lines.append(",");
        lines.append("\n");
      }
      lines.append("]");
      Files.writeString(file, lines.toString());
      log.info("Skipped orders saved to: {}", file.toAbsolutePath());
    } catch (IOException e) {
      log.error("Failed to write skipped orders file: {}", e.getMessage());
    }
  }

  // ============================================================
  // Result classes
  // ============================================================

  private record BatchSyncResult(
      int insertedCustomers, int updatedCustomers,
      int insertedOrders, int updatedOrders,
      int insertedOrderItems, int updatedOrderItems,
      int skippedOrders,
      List<String> errorMessages, List<String> skippedOrderIds
  ) {}
}

