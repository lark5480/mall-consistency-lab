<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">Mall 管理后台</div>
      <el-menu :default-active="$route.path" router background-color="#001529"
        text-color="#c7cbd4" active-text-color="#ffffff">
        <el-menu-item index="/dashboard"><el-icon><Odometer /></el-icon>数据看板</el-menu-item>
        <el-menu-item index="/products"><el-icon><Goods /></el-icon>商品管理</el-menu-item>
        <el-menu-item index="/categories"><el-icon><Menu /></el-icon>分类管理</el-menu-item>
        <el-menu-item index="/orders"><el-icon><Tickets /></el-icon>订单管理</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ pageTitle }}</span>
        <el-dropdown @command="handleCommand">
          <span class="admin-name">
            <el-icon><UserFilled /></el-icon>
            {{ currentUsername }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { ElMessageBox } from 'element-plus';
import { ArrowDown, Goods, Menu, Odometer, Tickets, UserFilled } from '@element-plus/icons-vue';
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();

const TITLES: Record<string, string> = {
  '/dashboard': '数据看板',
  '/products': '商品管理',
  '/categories': '分类管理',
  '/orders': '订单管理'
};

const pageTitle = computed(() => TITLES[route.path] ?? '管理后台');
const currentUsername = computed(() => {
  try {
    const raw = localStorage.getItem('mall_user');
    return raw ? (JSON.parse(raw) as { username?: string }).username ?? '管理员' : '管理员';
  } catch {
    return '管理员';
  }
});

async function handleCommand(command: string) {
  if (command !== 'logout') return;
  await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' });
  localStorage.removeItem('mall_token');
  localStorage.removeItem('mall_user');
  await router.replace('/login');
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #001529; }
.brand {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 600;
  letter-spacing: 1px;
}
.aside :deep(.el-menu) { border-right: none; }
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-light);
  background: #fff;
}
.title { font-size: 16px; font-weight: 600; }
.admin-name { display: flex; align-items: center; gap: 6px; cursor: pointer; color: var(--el-text-color-primary); }
.main { background: #f5f7fa; }
</style>
