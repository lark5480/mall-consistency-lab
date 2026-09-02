import axios from 'axios';
import type { AxiosError } from 'axios';
import router from '../router';

export interface ApiResult<T> {
  code: number;
  message: string;
  data: T;
}

const request = axios.create({ baseURL: '/api' });

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('mall_token');
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

request.interceptors.response.use(
  (response) => response.data as unknown as typeof response,
  (error: AxiosError<ApiResult<unknown>>) => {
    const result = error.response?.data;
    if (error.response?.status === 401) {
      localStorage.removeItem('mall_token');
      void router.push('/login');
    }
    return Promise.reject(Object.assign(error, { code: result?.code, message: result?.message ?? '请求失败' }));
  }
);

export default request;
