<template>
  <div>
    <van-nav-bar title="商品详情" left-arrow @click-left="$router.back()" />
    <template v-if="product">
      <van-image width="100%" height="260" fit="cover" :src="product.imageUrl ?? FALLBACK_IMAGE" />
      <div class="content">
        <h2>{{ product.name }}</h2>
        <p class="price">¥{{ product.price.toFixed(2) }}</p>
        <van-cell-group inset>
          <van-cell title="库存" :value="product.stock > 0 ? `${product.stock} 件` : '缺货'" />
          <van-cell title="商品明细" :label="product.description || '暂无描述'" />
        </van-cell-group>
        <div class="buy-bar">
          <van-button type="primary" block :disabled="product.stock === 0" @click="buy">立即购买</van-button>
        </div>
      </div>
    </template>
    <van-loading v-else-if="loading" class="loading" vertical>加载中</van-loading>
    <van-empty v-else image="error" description="商品不存在或加载失败">
      <van-button round type="primary" class="retry" @click="load">重新加载</van-button>
    </van-empty>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { showFailToast } from 'vant';
import type { Product } from '@mall/shared';
import { getProduct } from '../api/order';

const FALLBACK_IMAGE = 'https://picsum.photos/seed/mall/800/500';

const route = useRoute();
const router = useRouter();
const product = ref<Product>();
const loading = ref(true);

function buy() {
  void router.push(`/order/confirm/${route.params.id}`);
}

async function load() {
  loading.value = true;
  try {
    product.value = await getProduct(Number(route.params.id));
  } catch (error) {
    // 401 已由拦截器跳登录；其余情况给出空态与重试入口，避免整页空白
    if ((error as { code?: number }).code !== 401) showFailToast('商品信息加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.content { padding: 16px; }
.price { color: #ee0a24; font-size: 20px; font-weight: 700; }
.buy-bar { margin-top: 20px; }
.retry { margin-top: 8px; }
.loading { margin-top: 40vh; }
</style>
