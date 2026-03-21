package mera.orders.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PageDTO {
  private String id;
  private String name;
  private String username;
}
