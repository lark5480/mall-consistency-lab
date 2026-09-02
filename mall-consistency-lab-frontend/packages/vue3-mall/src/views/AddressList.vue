<template>
  <div class="page">
    <van-nav-bar title="收货地址" left-arrow @click-left="$router.back()" />

    <van-swipe-cell v-for="address in addresses" :key="address.id">
      <van-cell :is-link="selectMode" @click="choose(address)">
        <template #title>
          <span class="receiver">{{ address.receiver }} {{ address.phone }}</span>
          <van-tag v-if="address.isDefault" type="primary" class="default-tag">默认</van-tag>
        </template>
        <template #label>{{ fullAddress(address) }}</template>
      </van-cell>
      <template #right>
        <van-button square type="primary" text="编辑" class="swipe-btn" @click="openEdit(address)" />
        <van-button square type="danger" text="删除" class="swipe-btn" @click="remove(address)" />
      </template>
    </van-swipe-cell>

    <van-empty v-if="loaded && addresses.length === 0" description="还没有收货地址，添加一个吧" />

    <div class="footer">
      <van-button block round type="primary" @click="openCreate">新增地址</van-button>
    </div>

    <van-popup :show="showForm" position="bottom" round>
      <div class="sheet">
        <h3>{{ form.id ? '编辑地址' : '新增地址' }}</h3>
        <van-form @submit="submit">
          <van-field v-model="form.receiver" label="收货人" placeholder="姓名"
            :rules="[{ required: true, message: '请输入收货人' }]" />
          <van-field v-model="form.phone" label="联系电话" placeholder="手机号"
            :rules="[{ required: true, message: '请输入联系电话' }, { pattern: /^\d{5,20}$/, message: '电话格式不正确' }]" />
          <van-field v-model="form.province" label="省份" placeholder="省"
            :rules="[{ required: true, message: '请输入省份' }]" />
          <van-field v-model="form.city" label="城市" placeholder="市"
            :rules="[{ required: true, message: '请输入城市' }]" />
          <van-field v-model="form.district" label="区县" placeholder="区/县"
            :rules="[{ required: true, message: '请输入区县' }]" />
          <van-field v-model="form.detail" label="详细地址" placeholder="街道、门牌号"
            :rules="[{ required: true, message: '请输入详细地址' }]" />
          <van-cell title="设为默认地址">
            <van-switch v-model="form.isDefault" size="20" />
          </van-cell>
          <div class="sheet-buttons">
            <van-button block plain @click="showForm = false">取消</van-button>
            <van-button block type="primary" native-type="submit" :loading="saving">保存</van-button>
          </div>
        </van-form>
      </div>
    </van-popup>
  </div>
</template>

<script setup lang="ts">
import { showConfirmDialog, showFailToast, showSuccessToast } from 'vant';
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { Address, AddressSaveRequest } from '@mall/shared';
import { formatAddress } from '@mall/shared';
import { createAddress, deleteAddress, listAddresses, updateAddress } from '../api/user';

const route = useRoute();
const router = useRouter();
// 下单确认页跳转过来时为选择模式：点击条目即回填选中地址
const selectMode = route.query.select === '1';

const addresses = ref<Address[]>([]);
const loaded = ref(false);
const showForm = ref(false);
const saving = ref(false);

const emptyForm = (): AddressSaveRequest & { id?: number } => ({
  id: undefined,
  receiver: '',
  phone: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  isDefault: false
});
const form = reactive<AddressSaveRequest & { id?: number }>(emptyForm());

function fullAddress(address: Address): string {
  return formatAddress(address);
}

function openCreate() {
  Object.assign(form, emptyForm());
  form.isDefault = addresses.value.length === 0;
  showForm.value = true;
}

function openEdit(address: Address) {
  Object.assign(form, {
    id: address.id,
    receiver: address.receiver,
    phone: address.phone,
    province: address.province,
    city: address.city,
    district: address.district,
    detail: address.detail,
    isDefault: address.isDefault
  });
  showForm.value = true;
}

async function load() {
  addresses.value = await listAddresses();
  loaded.value = true;
}

async function submit() {
  saving.value = true;
  try {
    const payload: AddressSaveRequest = {
      receiver: form.receiver,
      phone: form.phone,
      province: form.province,
      city: form.city,
      district: form.district,
      detail: form.detail,
      isDefault: form.isDefault
    };
    if (form.id) {
      await updateAddress(form.id, payload);
    } else {
      await createAddress(payload);
    }
    showForm.value = false;
    showSuccessToast('已保存');
    await load();
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '保存失败');
  } finally {
    saving.value = false;
  }
}

async function remove(address: Address) {
  await showConfirmDialog({ title: '删除地址', message: `确定删除「${address.receiver}」的这条地址吗？` });
  try {
    await deleteAddress(address.id);
    showSuccessToast('已删除');
    await load();
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '删除失败');
  }
}

function choose(address: Address) {
  if (!selectMode) return;
  sessionStorage.setItem('mall_selected_address_id', String(address.id));
  router.back();
}

onMounted(async () => {
  if (!localStorage.getItem('mall_token')) {
    void router.replace('/login');
    return;
  }
  await load();
});
</script>

<style scoped>
.receiver { font-weight: 600; }
.default-tag { margin-left: 8px; vertical-align: 2px; }
.swipe-btn { height: 100%; }
.footer { margin: 24px 16px; }
.sheet { padding: 20px 16px 28px; }
.sheet h3 { margin: 0 0 12px; text-align: center; font-size: 16px; }
.sheet-buttons { display: flex; gap: 12px; margin-top: 16px; }
</style>
