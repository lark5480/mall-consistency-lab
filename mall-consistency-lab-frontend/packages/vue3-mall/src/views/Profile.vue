<template>
  <div class="page">
    <van-nav-bar title="我的" left-arrow left-text="首页" @click-left="$router.push('/')" />

    <div class="user-card">
      <img class="avatar" :src="profile?.avatar || defaultAvatar" alt="头像" />
      <div class="meta">
        <div class="username">
          {{ profile?.username ?? '未登录' }}
          <van-tag v-if="profile?.role === 'ADMIN'" type="warning">管理员</van-tag>
        </div>
        <div class="sub">ID: {{ profile?.userId ?? '-' }}</div>
      </div>
    </div>

    <van-cell-group inset class="group">
      <van-cell title="编辑资料" is-link @click="openProfileEdit" />
      <van-cell title="修改密码" is-link @click="openPasswordEdit" />
      <van-cell title="收货地址" is-link to="/addresses" />
    </van-cell-group>

    <van-cell-group inset class="group">
      <van-cell title="注册时间" :value="formatTime(profile?.createdAt)" />
    </van-cell-group>

    <div class="logout-wrap">
      <van-button block round type="danger" plain @click="logout">退出登录</van-button>
    </div>

    <!-- 编辑资料 -->
    <van-popup :show="showProfileEdit" position="bottom" round>
      <div class="sheet">
        <h3>编辑资料</h3>
        <van-form @submit="submitProfile">
          <van-field v-model="profileForm.phone" name="phone" label="手机号"
            placeholder="选填" :rules="[{ validator: phoneRule, message: '手机号格式不正确' }]" />
          <van-field v-model="profileForm.email" name="email" label="邮箱"
            placeholder="选填" :rules="[{ validator: emailRule, message: '邮箱格式不正确' }]" />
          <van-field v-model="profileForm.avatar" name="avatar" label="头像 URL" placeholder="图片链接，选填" />
          <div class="sheet-buttons">
            <van-button block plain @click="showProfileEdit = false">取消</van-button>
            <van-button block type="primary" native-type="submit" :loading="savingProfile">保存</van-button>
          </div>
        </van-form>
      </div>
    </van-popup>

    <!-- 修改密码 -->
    <van-popup :show="showPasswordEdit" position="bottom" round>
      <div class="sheet">
        <h3>修改密码</h3>
        <van-form @submit="submitPassword">
          <van-field v-model="passwordForm.oldPassword" type="password" name="oldPassword"
            label="旧密码" placeholder="至少 6 位"
            :rules="[{ required: true, message: '请输入旧密码' }]" />
          <van-field v-model="passwordForm.newPassword" type="password" name="newPassword"
            label="新密码" placeholder="6-64 位"
            :rules="[{ required: true, message: '请输入新密码' }, { validator: newPasswordRule, message: '长度需在 6-64 位之间' }]" />
          <van-field v-model="passwordForm.confirm" type="password" name="confirm"
            label="确认新密码" :rules="[{ validator: confirmRule, message: '两次输入不一致' }]" />
          <div class="sheet-buttons">
            <van-button block plain @click="showPasswordEdit = false">取消</van-button>
            <van-button block type="primary" native-type="submit" :loading="savingPassword">确认修改</van-button>
          </div>
        </van-form>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { showConfirmDialog, showFailToast, showSuccessToast } from 'vant';
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { UserProfile } from '@mall/shared';
import { changePassword, getProfile, updateProfile } from '../api/user';

const router = useRouter();
const profile = ref<UserProfile>();
const defaultAvatar = 'https://fastly.jsdelivr.net/npm/@vant/assets/cat.jpeg';

const showProfileEdit = ref(false);
const showPasswordEdit = ref(false);
const savingProfile = ref(false);
const savingPassword = ref(false);

const profileForm = reactive({ phone: '', email: '', avatar: '' });
const passwordForm = reactive({ oldPassword: '', newPassword: '', confirm: '' });

function phoneRule(value: string): boolean {
  return !value || /^1\d{10}$|^\d{5,20}$/.test(value);
}

function emailRule(value: string): boolean {
  return !value || /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
}

function confirmRule(value: string): boolean {
  return value === passwordForm.newPassword;
}

function newPasswordRule(value: string): boolean {
  return value.length >= 6 && value.length <= 64;
}

function openProfileEdit() {
  if (!profile.value) return;
  profileForm.phone = profile.value.phone ?? '';
  profileForm.email = profile.value.email ?? '';
  profileForm.avatar = profile.value.avatar ?? '';
  showProfileEdit.value = true;
}

function openPasswordEdit() {
  passwordForm.oldPassword = '';
  passwordForm.newPassword = '';
  passwordForm.confirm = '';
  showPasswordEdit.value = true;
}

async function submitProfile() {
  savingProfile.value = true;
  try {
    profile.value = await updateProfile({
      phone: profileForm.phone || null,
      email: profileForm.email || null,
      avatar: profileForm.avatar || null
    });
    showProfileEdit.value = false;
    showSuccessToast('资料已更新');
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '保存失败');
  } finally {
    savingProfile.value = false;
  }
}

async function submitPassword() {
  savingPassword.value = true;
  try {
    await changePassword({ oldPassword: passwordForm.oldPassword, newPassword: passwordForm.newPassword });
    showPasswordEdit.value = false;
    passwordForm.oldPassword = passwordForm.newPassword = passwordForm.confirm = '';
    showSuccessToast('密码已修改');
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '修改失败');
  } finally {
    savingPassword.value = false;
  }
}

function formatTime(value?: string): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleDateString('zh-CN');
}

async function logout() {
  await showConfirmDialog({ title: '退出登录', message: '确定要退出当前账号吗？' });
  localStorage.removeItem('mall_token');
  localStorage.removeItem('mall_user');
  await router.replace('/login');
}

onMounted(async () => {
  if (!localStorage.getItem('mall_token')) {
    void router.replace('/login');
    return;
  }
  try {
    profile.value = await getProfile();
  } catch {
    // 401 已由拦截器跳转登录页
  }
});
</script>

<style scoped>
.user-card {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 12px 16px;
  padding: 18px 16px;
  border-radius: 10px;
  background: linear-gradient(135deg, #1989fa, #39b9ff);
  color: #fff;
}
.avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid rgba(255, 255, 255, 0.7);
}
.username { font-size: 17px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
.sub { margin-top: 4px; font-size: 12px; opacity: 0.85; }
.group { margin-top: 12px; }
.logout-wrap { margin: 24px 16px; }
.sheet { padding: 20px 16px 28px; }
.sheet h3 { margin: 0 0 12px; text-align: center; font-size: 16px; }
.sheet-buttons { display: flex; gap: 12px; margin-top: 16px; }
</style>
