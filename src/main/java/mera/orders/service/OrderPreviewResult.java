package mera.orders.service;

import lombok.Builder;
import lombok.Getter;
import mera.orders.DTO.OrderApiDto;

import java.util.List;

@Getter
@Builder
public class OrderPreviewResult {
  private final String startDate;
  private final String endDate;
  private final long startTimestamp;
  private final long endTimestamp;
  private final Integer pageNumber;
  private final Integer pageSize;
  private final String updateStatus;
  private final String status;  // null means no filter
  private final int totalFromApi;
  private final List<OrderApiDto> orders;
}
