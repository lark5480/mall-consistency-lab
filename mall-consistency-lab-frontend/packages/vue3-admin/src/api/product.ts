import request from './request';
import type { PageResult, Product, ProductCreateRequest } from '@mall/shared';

interface ListParams { page: number; size: number; categoryId?: number; keyword?: string }

export async function listProducts(params: ListParams) {
  return (await request.get<never, { data: PageResult<Product> }>('/v1/products', { params })).data;
}

export async function saveProduct(product: ProductCreateRequest & { id?: number }) {
  return product.id
    ? (await request.put<never, { data: Product }>(`/v1/products/${product.id}`, product)).data
    : (await request.post<never, { data: Product }>('/v1/products', product)).data;
}

export async function deleteProduct(id: number) {
  await request.delete(`/v1/products/${id}`);
}
