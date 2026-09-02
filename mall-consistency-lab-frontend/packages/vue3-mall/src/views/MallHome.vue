<template>
  <div>
    <van-nav-bar title="商城首页" />
    <van-search v-model="keyword" placeholder="搜索商品" @search="load" />
    <van-list v-model:loading="loading" :finished="finished" finished-text="没有更多了" @update:model-value="load">
      <van-cell v-for="product in products" :key="product.id" :title="product.name"
        :label="`¥${product.price.toFixed(2)} · 库存 ${product.stock}`" is-link
        :to="`/product/${product.id}`">
        <template #icon>
          <!-- 与详情页同源的图片，保证列表/详情/订单三处一致 -->
          <van-image width="56" height="56" radius="6" fit="cover" class="item-thumb"
            :src="product.imageUrl ?? 'https://picsum.photos/seed/mall/800/500'" />
        </template>
      </van-cell>
    </van-list>
    <van-tabbar route placeholder>
      <van-tabbar-item replace to="/" icon="home-o">首页</van-tabbar-item>
      <van-tabbar-item replace to="/orders" icon="orders-o">我的订单</van-tabbar-item>
      <van-tabbar-item replace to="/profile" icon="user-o">我的</van-tabbar-item>
    </van-tabbar>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import type { PageResult, Product } from '@mall/shared';
import request from '../api/request';

const products = ref<Product[]>([]);
const loading = ref(false);
const finished = ref(false);
const page = ref(1);
const keyword = ref('');

async function load() {
  loading.value = true;
  try {
    const response = await request.get<never, { data: PageResult<Product> }>('/v1/products', {
      params: { page: page.value, size: 10, keyword: keyword.value || undefined }
    });
    products.value.push(...response.data.list);
    finished.value = products.value.length >= response.data.total;
    page.value += 1;
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  if (products.value.length === 0) void load();
});
</script>

<style scoped>
.item-thumb { margin-right: 10px; align-self: center; }
</style>
