<template>
  <div class="page">
    <van-nav-bar title="订单详情" left-arrow @click-left="$router.back()" />

    <template v-if="order">
      <van-cell-group inset class="block">
        <van-cell title="状态">
          <van-tag :type="tagType(order.status)">{{ statusText[order.status] ?? order.status }}</van-tag>
        </van-cell>
        <van-cell title="订单号" :value="order.orderNo" />
        <van-cell title="下单时间" :value="formatTime(order.createdAt)" />
      </van-cell-group>

      <van-cell-group inset class="block" v-if="order.receiverName">
        <van-cell title="收货人" :value="`${order.receiverName} ${order.receiverPhone ?? ''}`" />
        <van-cell title="收货地址" :label="order.receiverAddress ?? ''" />
      </van-cell-group>

      <van-cell-group inset class="block">
        <van-cell title="商品明细" />
        <van-card v-for="(item, index) in order.items ?? []" :key="index"
          :title="item.productName" :num="item.count" :price="Number(item.price).toFixed(2)"
          :thumb="item.imageUrl ?? `https://picsum.photos/seed/product-${item.productId}/100/100`" />
        <van-cell title="合计">
          <span class="amount">¥{{ Number(order.totalAmount).toFixed(2) }}</span>
        </van-cell>
      </van-cell-group>

      <div class="actions" v-if="order.status === 'PENDING'">
        <van-button block round plain :loading="cancelling" @click="cancel">取消订单</van-button>
        <van-button block round type="danger" :loading="paying" @click="pay">模拟支付</van-button>
      </div>
      <div class="actions" v-if="order.status === 'SHIPPED'">
        <van-button block round type="primary" :loading="completing" @click="complete">确认收货</van-button>
      </div>
    </template>
    <van-loading v-else class="loading" vertical>加载中</van-loading>
  </div>
</template>

<script setup lang="ts">
import { showConfirmDialog, showFailToast, showSuccessToast } from 'vant';
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import type { Order, OrderStatus } from '@mall/shared';
import { cancelOrder, completeOrder, getOrder, payOrder } from '../api/order';

const route = useRoute();
const router = useRouter();
const orderNo = String(route.params.orderNo);

const order = ref<Order>();
const paying = ref(false);
const cancelling = ref(false);
const completing = ref(false);

const statusText: Record<string, string> = {
  PENDING: '待支付',
  PAID: '已支付',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
};

function tagType(status: OrderStatus): 'primary' | 'success' | 'warning' | 'default' {
  if (status === 'PENDING') return 'warning';
  if (status === 'COMPLETED') return 'success';
  if (status === 'CANCELLED') return 'default';
  return 'primary';
}

function formatTime(value?: string): string {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN');
}

async function load() {
  try {
    order.value = await getOrder(orderNo);
  } catch {
    showFailToast('订单不存在或已下架');
    void router.replace('/orders');
  }
}

async function pay() {
  paying.value = true;
  try {
    const updated = await payOrder(orderNo);
    order.value = { ...updated, items: order.value?.items ?? null };
    showSuccessToast('支付成功');
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '支付失败');
  } finally {
    paying.value = false;
  }
}

async function cancel() {
  await showConfirmDialog({ title: '取消订单', message: '取消后库存将自动回补，确定吗？' });
  cancelling.value = true;
  try {
    const updated = await cancelOrder(orderNo);
    order.value = { ...updated, items: order.value?.items ?? null };
    showSuccessToast('订单已取消，库存已回补');
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '取消失败');
  } finally {
    cancelling.value = false;
  }
}

async function complete() {
  await showConfirmDialog({ title: '确认收货', message: '请确认已收到商品，确认后交易完成。' });
  completing.value = true;
  try {
    const updated = await completeOrder(orderNo);
    order.value = { ...updated, items: order.value?.items ?? null };
    showSuccessToast('确认收货成功，交易完成');
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '确认收货失败');
  } finally {
    completing.value = false;
  }
}

onMounted(() => {
  if (!localStorage.getItem('mall_token')) {
    void router.replace('/login');
    return;
  }
  void load();
});
</script>

<style scoped>
.block { margin-top: 12px; }
.amount { color: #ee0a24; font-weight: 600; }
.actions { display: flex; gap: 12px; margin: 20px 16px; }
.loading { margin-top: 40vh; }
</style>
