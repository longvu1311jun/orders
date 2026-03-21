package mera.orders.service;

import mera.orders.entity.Order;

public interface OrdersService {
  Order getById(Long id);
}
