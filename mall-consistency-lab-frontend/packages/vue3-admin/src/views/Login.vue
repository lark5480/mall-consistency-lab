<template>
  <div class="login-page">
    <el-card class="login-card">
      <h1>Mall 管理后台</h1>
      <p class="hint">仅 ADMIN 角色可进入（演示账号 admin / admin123）</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="72px" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <div class="actions">
          <el-button type="primary" :loading="loading" @click="submit">登录</el-button>
          <el-button :disabled="loading" @click="register">注册</el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { AuthResponse } from '@mall/shared';
import request from '../api/request';

const form = reactive({ username: '', password: '' });
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
};
const loading = ref(false);
const route = useRoute();
const router = useRouter();
const formRef = ref<FormInstance>();

function persistSession(auth: AuthResponse) {
  localStorage.setItem('mall_token', auth.token);
  localStorage.setItem('mall_user', JSON.stringify({
    userId: auth.userId,
    username: auth.username,
    role: auth.role
  }));
}

async function authenticate(path: 'login' | 'register') {
  if (!formRef.value) return;
  const valid = await formRef.value.validate().then(() => true).catch(() => false);
  if (!valid) return;
  loading.value = true;
  try {
    const response = await request.post<never, { data: AuthResponse }>(`/v1/auth/${path}`, form);
    if (response.data.role !== 'ADMIN') {
      ElMessage.error('该账号不是管理员，无法进入管理后台');
      return;
    }
    persistSession(response.data);
    ElMessage.success(path === 'login' ? '登录成功' : '注册成功');
    await router.push((route.query.redirect as string) || '/');
  } finally {
    loading.value = false;
  }
}

async function submit() {
  await authenticate('login');
}

async function register() {
  await authenticate('register');
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: grid; place-items: center; background: #f3f4f6; }
.login-card { width: 400px; }
.login-card h1 { text-align: center; font-size: 22px; margin: 8px 0 8px; }
.hint { text-align: center; color: #909399; font-size: 12px; margin-bottom: 16px; }
.actions { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.actions .el-button { width: 100%; }
</style>
