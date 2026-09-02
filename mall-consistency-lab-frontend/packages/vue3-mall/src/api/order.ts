import type { Order, OrderDetail, PageResult, Product } from '@mall/shared';
import request from './request';

export async function getProduct(productId: number): Promise<Product> {
  return (await request.get<never, { data: Product }>(`/v1/products/${productId}`)).data;
}

export async function createOrder(productId: number, count: number, addressId: number) {
  return (await request.post<never, { data: Order }>('/v1/orders', { productId, count, addressId })).data;
}


export async function listOrders(page: number, size: number) {
  return (await request.get<never, { data: PageResult<Order> }>('/v1/orders', { params: { page, size } })).data;
}

export async function getOrder(orderNo: string): Promise<OrderDetail> {
  return (await request.get<never, { data: OrderDetail }>(`/v1/orders/${orderNo}`)).data;
}

export async function payOrder(orderNo: string) {
  return (await request.post<never, { data: Order }>(`/v1/orders/${orderNo}/pay`)).data;
}

export async function cancelOrder(orderNo: string) {
  return (await request.post<never, { data: Order }>(`/v1/orders/${orderNo}/cancel`)).data;
}

/** 确认收货：仅已发货(SHIPPED)订单可操作，完成后进入终态 COMPLETED */
export async function completeOrder(orderNo: string) {
  return (await request.post<never, { data: Order }>(`/v1/orders/${orderNo}/complete`)).data;
}
