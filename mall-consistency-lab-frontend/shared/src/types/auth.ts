export type UserRole = 'USER' | 'ADMIN';

export interface AuthResponse {
  token: string;
  userId: number;
  username: string;
  role: UserRole;
}

/** 登录态摘要：存入 localStorage 的 mall_user */
export interface CurrentUser {
  userId: number;
  username: string;
  role: UserRole;
}

/** 「我的」页面完整资料 */
export interface UserProfile {
  userId: number;
  username: string;
  phone?: string | null;
  email?: string | null;
  avatar?: string | null;
  role: UserRole;
  createdAt?: string;
}

export interface UpdateProfileRequest {
  phone?: string | null;
  email?: string | null;
  avatar?: string | null;
}

export interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

export interface Address {
  id: number;
  receiver: string;
  phone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
  isDefault: boolean;
  createdAt?: string;
}

export interface AddressSaveRequest {
  receiver: string;
  phone: string;
  province: string;
  city: string;
  district: string;
  detail: string;
  isDefault?: boolean;
}
