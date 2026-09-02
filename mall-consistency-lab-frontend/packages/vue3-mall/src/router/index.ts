import { createRouter, createWebHistory } from 'vue-router';
import MallHome from '../views/MallHome.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: MallHome },
    { path: '/product/:id', name: 'product', component: () => import('../views/ProductDetail.vue') },
    { path: '/order/confirm/:productId', name: 'order-confirm', component: () => import('../views/OrderConfirm.vue') },
    { path: '/orders', name: 'orders', component: () => import('../views/MyOrders.vue') },
    { path: '/orders/:orderNo', name: 'order-detail', component: () => import('../views/OrderDetail.vue') },
    { path: '/profile', name: 'profile', component: () => import('../views/Profile.vue') },
    { path: '/addresses', name: 'addresses', component: () => import('../views/AddressList.vue') },
    { path: '/login', name: 'login', component: () => import('../views/Login.vue') }
  ]
});

export default router;
