import type {
  Category,
  Order,
  OrderDetail,
  OrderStatus,
  PageResult
} from '@mall/shared';
import request from './request';

export interface OrderPageQuery {
  page: number;
  size: number;
  status?: OrderStatus | '';
  keyword?: string;
}

export interface OrderStats {
  todayCount: number;
  todayGmv: number;
  totalCount: number;
  totalGmv: number;
  pendingShipCount: number;
  trend: { date: string; count: number; gmv: number }[];
}

export interface ProductStats {
  totalCount: number;
  onSaleCount: number;
  offSaleCount: number;
  lowStockCount: number;
}

export async function listAllOrders(query: OrderPageQuery) {
  return (await request.get<never, { data: PageResult<Order> }>('/v1/admin/orders', {
    params: {
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      keyword: query.keyword || undefined
    }
  })).data;
}

export async function getAdminOrder(orderNo: string): Promise<OrderDetail> {
  return (await request.get<never, { data: OrderDetail }>(`/v1/admin/orders/${orderNo}`)).data;
}

export async function shipOrder(orderNo: string): Promise<Order> {
  return (await request.post<never, { data: Order }>(`/v1/admin/orders/${orderNo}/ship`)).data;
}

export async function getOrderStats(): Promise<OrderStats> {
  return (await request.get<never, { data: OrderStats }>('/v1/admin/stats/orders')).data;
}

export async function getProductStats(): Promise<ProductStats> {
  return (await request.get<never, { data: ProductStats }>('/v1/admin/stats/products')).data;
}

// 分类管理（公开列表接口供商品表单复用）
export async function listCategories(): Promise<Category[]> {
  return (await request.get<never, { data: Category[] }>('/v1/categories')).data;
}

export async function createCategory(name: string): Promise<Category> {
  return (await request.post<never, { data: Category }>('/v1/admin/categories', { name })).data;
}

export async function updateCategory(id: number, name: string): Promise<Category> {
  return (await request.put<never, { data: Category }>(`/v1/admin/categories/${id}`, { name })).data;
}

export async function deleteCategory(id: number): Promise<void> {
  await request.delete(`/v1/admin/categories/${id}`);
}
