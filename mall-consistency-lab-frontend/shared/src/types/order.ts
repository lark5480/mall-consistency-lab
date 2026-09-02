import type { Address } from './auth';

export type OrderStatus = 'PENDING' | 'PAID' | 'SHIPPED' | 'COMPLETED' | 'CANCELLED';

export interface OrderItem {
  productId: number;
  productName: string;
  price: number;
  count: number;
  /** 下单时固化的商品图片快照；v1.3 之前的存量订单可能为空 */
  imageUrl?: string | null;
}

export interface Order {
  orderNo: string;
  status: OrderStatus;
  totalAmount: number;
  addressId?: number | null;
  /** 下单时固化的收货人快照 */
  receiverName?: string | null;
  receiverPhone?: string | null;
  receiverAddress?: string | null;
  /** 仅详情接口返回；列表为 null */
  items?: OrderItem[] | null;
  /** B 端全量查询返回用户 id；C 端为本人 id */
  userId?: number;
  createdAt: string;
}

/** 订单详情（含明细），detail 接口返回 */
export type OrderDetail = Order & { items: OrderItem[] };

export interface CreateOrderRequest {
  productId: number;
  count: number;
  addressId: number;
}

/** 地址簿条目 = 共享 Address 的别名，语义化导出便于 C 端使用 */
export type OrderAddress = Address;

export function formatAddress(address: Pick<Address, 'province' | 'city' | 'district' | 'detail'>): string {
  return `${address.province} ${address.city} ${address.district} ${address.detail}`;
}
