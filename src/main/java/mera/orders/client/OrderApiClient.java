package mera.orders.client;

import lombok.extern.slf4j.Slf4j;
import mera.orders.DTO.OrderApiDto;
import mera.orders.DTO.OrderListResponseDto;
import mera.orders.exception.ApiClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class OrderApiClient {

  private final RestTemplate restTemplate;

  @Value("${api.order.base-url}")
  private String baseUrl;

  @Value("${api.order.api-key}")
  private String apiKey;

  public OrderApiClient(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  /**
   * Fetch orders using configured URL (legacy method).
   */
  public List<OrderApiDto> fetchOrders() {
    return fetchOrdersDynamic(0, 0, 1, 200, "inserted_at", null);
  }

  /**
   * Fetch orders with full dynamic parameters.
   * api_key is always included from config.
   *
   * @param startTimestamp Unix timestamp in seconds
   * @param endTimestamp  Unix timestamp in seconds
   * @param pageNumber     1-based page number
   * @param pageSize       page size
   * @param updateStatus   "inserted_at" or "updated_at"
   * @param status         order status filter, null/blank = no filter
   */
  public List<OrderApiDto> fetchOrdersDynamic(
      long startTimestamp,
      long endTimestamp,
      int pageNumber,
      int pageSize,
      String updateStatus,
      String status
  ) {
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl)
        .queryParam("api_key", apiKey)
        .queryParam("startDateTime", startTimestamp)
        .queryParam("endDateTime", endTimestamp)
        .queryParam("page_number", pageNumber)
        .queryParam("page_size", pageSize)
        .queryParam("updateStatus", updateStatus);

    if (status != null && !status.isBlank()) {
      builder.queryParam("status", status);
    }

    String url = builder.build().toUriString();
    log.info("Calling API: {}", maskUrl(url));

    try {
      ResponseEntity<OrderListResponseDto> response = restTemplate.getForEntity(
          url,
          OrderListResponseDto.class
      );

      if (response.getBody() == null) {
        log.warn("API response body is null");
        return Collections.emptyList();
      }

      List<OrderApiDto> orders = response.getBody().getData();
      if (orders == null) {
        log.warn("API response data field is null");
        return Collections.emptyList();
      }

      log.info("Fetched {} orders from API", orders.size());
      return orders;

    } catch (HttpClientErrorException e) {
      String body = e.getResponseBodyAsString();
      log.error("API client error {}: {}", e.getStatusCode(), body);
      throw new ApiClientException(
          "API returned client error: " + e.getStatusCode(),
          e,
          e.getStatusCode(),
          body
      );

    } catch (HttpServerErrorException e) {
      String body = e.getResponseBodyAsString();
      log.error("API server error {}: {}", e.getStatusCode(), body);
      throw new ApiClientException(
          "API returned server error: " + e.getStatusCode(),
          e,
          e.getStatusCode(),
          body
      );

    } catch (ResourceAccessException e) {
      log.error("Connection error: {}", e.getMessage());
      throw new ApiClientException("Failed to connect to API: " + e.getMessage(), e);

    } catch (ApiClientException e) {
      throw e;

    } catch (Exception e) {
      log.error("Unexpected error calling API: {}", e.getMessage(), e);
      throw new ApiClientException("Unexpected error: " + e.getMessage(), e);
    }
  }

  private String maskUrl(String url) {
    if (url == null) return "null";
    return url.replaceAll("api_key=[^&]*", "api_key=***");
  }
}
