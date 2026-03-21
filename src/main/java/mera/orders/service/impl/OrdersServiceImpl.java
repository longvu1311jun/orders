package mera.orders.service.impl;

import lombok.RequiredArgsConstructor;
import mera.orders.entity.Order;
import mera.orders.repository.OrderRepository;
import mera.orders.service.OrdersService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrdersServiceImpl implements OrdersService {
  private final OrderRepository orderRepository;

  @Override
  public Order getById(Long id) {
    return orderRepository.findById(id).orElse(null);
  }
}
