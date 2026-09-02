import { createRouter, createWebHistory } from 'vue-router';
import AdminLayout from '../views/AdminLayout.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
    {
      path: '/',
      component: AdminLayout,
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue') },
        { path: 'products', name: 'products', component: () => import('../views/product/ProductList.vue') },
        { path: 'categories', name: 'categories', component: () => import('../views/category/CategoryList.vue') },
        { path: 'orders', name: 'orders', component: () => import('../views/order/OrderList.vue') }
      ]
    }
  ]
});

interface StoredUser {
  role?: string;
}

function readStoredRole(): string | undefined {
  try {
    const raw = localStorage.getItem('mall_user');
    return raw ? (JSON.parse(raw) as StoredUser).role : undefined;
  } catch {
    return undefined;
  }
}

router.beforeEach((to) => {
  if (to.name === 'login') return true;
  if (!localStorage.getItem('mall_token') || !localStorage.getItem('mall_user')) {
    return { name: 'login', query: { redirect: to.fullPath } };
  }
  // 后台仅允许管理员：普通 USER 登录后不能访问任何后台页面
  if (readStoredRole() !== 'ADMIN') {
    return { name: 'login', query: { redirect: to.fullPath, forbidden: '1' } };
  }
  return true;
});

export default router;
