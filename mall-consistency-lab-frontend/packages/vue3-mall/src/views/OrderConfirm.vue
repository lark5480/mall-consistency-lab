<template>
  <div>
    <van-nav-bar title="确认订单" left-arrow @click-left="$router.back()" />
    <template v-if="product">
      <van-card :title="product.name" :desc="`库存 ${product.stock}`" :price="product.price.toString()"
        :thumb="product.imageUrl ?? 'https://picsum.photos/seed/mall-order/200/200'" />
      <van-cell title="购买数量">
        <van-stepper v-model="count" :min="1" :max="Math.max(product.stock, 1)" integer />
      </van-cell>

      <!-- 收货地址：真实地址簿 -->
      <van-cell v-if="selectedAddress" title="收货地址" :label="fullAddress(selectedAddress)" is-link
        @click="showAddresses = true">
        {{ selectedAddress.receiver }} {{ selectedAddress.phone }}
      </van-cell>
      <van-cell v-else title="收货地址" label="暂无地址，请先添加" is-link clickable
        @click="$router.push('/addresses')">
        <span class="missing">去添加</span>
      </van-cell>

      <van-submit-bar :price="totalPrice" button-text="提交订单" @submit="submit" />

      <van-popup v-model:show="showAddresses" position="bottom" round>
        <div class="address-picker">
          <div class="picker-title">选择收货地址</div>
          <van-cell v-for="address in addresses" :key="address.id" clickable
            @click="choose(address.id)">
            <template #title>
              <span class="receiver">{{ address.receiver }} {{ address.phone }}</span>
              <van-tag v-if="address.isDefault" type="primary" class="default-tag">默认</van-tag>
            </template>
            <template #label>{{ fullAddress(address) }}</template>
            <template #right-icon>
              <van-icon v-if="address.id === addressId" name="success" color="#1989fa" />
            </template>
          </van-cell>
          <div class="picker-footer">
            <van-button block plain type="primary" @click="manageAddresses">管理地址</van-button>
          </div>
        </div>
      </van-popup>
    </template>
    <van-loading v-else class="loading" vertical>加载中</van-loading>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { showFailToast, showSuccessToast } from 'vant';
import { useRoute, useRouter } from 'vue-router';
import type { Address, Product } from '@mall/shared';
import { formatAddress } from '@mall/shared';
import { createOrder, getProduct } from '../api/order';
import { listAddresses } from '../api/user';

const SELECTED_ADDRESS_KEY = 'mall_selected_address_id';

const route = useRoute();
const router = useRouter();
const productId = Number(route.params.productId);
const product = ref<Product>();
const count = ref(1);
const addressId = ref<number>();
const submitting = ref(false);
const showAddresses = ref(false);
const addresses = ref<Address[]>([]);

const selectedAddress = computed(() => addresses.value.find(item => item.id === addressId.value));
const totalPrice = computed(() => Math.round((product.value?.price ?? 0) * count.value * 100));

function fullAddress(address: Address): string {
  return formatAddress(address);
}

function choose(id: number) {
  addressId.value = id;
  showAddresses.value = false;
}

function manageAddresses() {
  void router.push('/addresses?select=1');
}

async function submit() {
  if (!localStorage.getItem('mall_token')) {
    await router.push('/login');
    return;
  }
  if (!addressId.value) {
    showFailToast('请先选择收货地址');
    return;
  }
  if (submitting.value) return;
  submitting.value = true;
  try {
    const order = await createOrder(productId, count.value, addressId.value);
    showSuccessToast(`下单成功：${order.orderNo}`);
    await router.push('/orders');
  } catch (error) {
    const code = (error as { code?: number }).code;
    if (code === 40001) showFailToast('库存不足');
  } finally {
    submitting.value = false;
  }
}

onMounted(async () => {
  // 地址选择页回传的选中项（若有）
  const stored = sessionStorage.getItem(SELECTED_ADDRESS_KEY);
  if (stored) {
    addressId.value = Number(stored);
    sessionStorage.removeItem(SELECTED_ADDRESS_KEY);
  }
  try {
    const [productData, addressList] = await Promise.all([getProduct(productId), listAddresses()]);
    product.value = productData;
    count.value = Math.min(1, Math.max(productData.stock, 0)) || 0;
    addresses.value = addressList;
    // 默认选中：上次选择 > 默认地址 > 第一条
    if (!addressId.value || !addressList.some(item => item.id === addressId.value)) {
      addressId.value = addressList.find(item => item.isDefault)?.id ?? addressList[0]?.id;
    }
  } catch (error) {
    // 401 已由拦截器处理；其余情况提示后返回详情页
    if ((error as { code?: number }).code !== 401) showFailToast('订单确认页加载失败');
  }
});
</script>

<style scoped>
.missing { color: #ee0a24; }
.loading { margin-top: 40vh; }
.receiver { font-weight: 600; }
.default-tag { margin-left: 8px; vertical-align: 2px; }
.address-picker { padding-bottom: 12px; }
.picker-title { text-align: center; padding: 14px 0 6px; font-size: 15px; font-weight: 600; }
.picker-footer { padding: 12px 16px 4px; }
</style>
