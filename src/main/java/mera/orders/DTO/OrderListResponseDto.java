package mera.orders.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderListResponseDto {
  private List<OrderApiDto> data;
  private Integer pageNumber;
  private Integer pageSize;
  private Boolean success;
  private Integer totalEntries;
  private Integer totalPages;
}
