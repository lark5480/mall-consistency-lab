export type ProductStatus = 'ON_SALE' | 'OFF_SALE';

/** 商品分类 */
export interface Category {
  id: number;
  name: string;
}

export interface Product {
  id: number;
  name: string;
  description?: string;
  price: number;
  stock: number;
  categoryId: number;
  imageUrl?: string;
  status: ProductStatus;
  createdAt?: string;
}

export interface ProductCreateRequest {
  name: string;
  description?: string;
  price: number;
  stock: number;
  categoryId: number;
  imageUrl?: string;
  status?: ProductStatus;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}
