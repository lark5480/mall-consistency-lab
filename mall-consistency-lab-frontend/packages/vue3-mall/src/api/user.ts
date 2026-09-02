import type {
  Address,
  AddressSaveRequest,
  ChangePasswordRequest,
  UpdateProfileRequest,
  UserProfile
} from '@mall/shared';
import request from './request';

export async function getProfile(): Promise<UserProfile> {
  return (await request.get<never, { data: UserProfile }>('/v1/users/me')).data;
}

export async function updateProfile(payload: UpdateProfileRequest): Promise<UserProfile> {
  return (await request.put<never, { data: UserProfile }>('/v1/users/me', payload)).data;
}

export async function changePassword(payload: ChangePasswordRequest): Promise<void> {
  await request.put('/v1/users/me/password', payload);
}

export async function listAddresses(): Promise<Address[]> {
  return (await request.get<never, { data: Address[] }>('/v1/users/me/addresses')).data;
}

export async function createAddress(payload: AddressSaveRequest): Promise<Address> {
  return (await request.post<never, { data: Address }>('/v1/users/me/addresses', payload)).data;
}

export async function updateAddress(id: number, payload: AddressSaveRequest): Promise<Address> {
  return (await request.put<never, { data: Address }>(`/v1/users/me/addresses/${id}`, payload)).data;
}

export async function deleteAddress(id: number): Promise<void> {
  await request.delete(`/v1/users/me/addresses/${id}`);
}
