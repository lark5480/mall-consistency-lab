<template>
  <van-nav-bar title="登录" />
  <van-form @submit="submit">
    <van-cell-group inset>
      <van-field v-model="username" name="username" label="用户名" placeholder="请输入用户名" required />
      <van-field v-model="password" type="password" name="password" label="密码" placeholder="请输入密码" required />
    </van-cell-group>
    <div class="actions">
      <van-button round block type="primary" native-type="submit">登录</van-button>
      <van-button round block plain @click="register">注册</van-button>
    </div>
  </van-form>
</template>

<script setup lang="ts">
import { showFailToast, showSuccessToast } from 'vant';
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import request from '../api/request';

const username = ref('');
const password = ref('');
const router = useRouter();

async function submit() {
  try {
    const result = await request.post<never, { data: { token: string } }>('/v1/auth/login', {
      username: username.value,
      password: password.value
    });
    localStorage.setItem('mall_token', result.data.token);
    showSuccessToast('登录成功');
    await router.push('/');
  } catch {
    showFailToast('登录失败');
  }
}

async function register() {
  try {
    const result = await request.post<never, { data: { token: string } }>('/v1/auth/register', {
      username: username.value,
      password: password.value
    });
    localStorage.setItem('mall_token', result.data.token);
    showSuccessToast('注册成功');
    await router.push('/');
  } catch {
    showFailToast('注册失败，请检查用户名或密码长度');
  }
}
</script>

<style scoped>
.actions { margin: 24px 16px; display: grid; gap: 12px; }
</style>
