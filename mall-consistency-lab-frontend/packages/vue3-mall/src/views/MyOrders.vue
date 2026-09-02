<template>
  <div class="page">
    <van-nav-bar title="我的订单" left-arrow @click-left="$router.push('/')" />
    <van-list v-model:loading="loading" :finished="finished" finished-text="没有更多订单了"
      @update:model-value="load">
      <van-cell-group v-for="order in orders" :key="order.orderNo" inset class="order-card">
        <van-cell :title="`订单号 ${order.orderNo}`" is-link :to="`/orders/${order.orderNo}`">
          <van-tag :type="tagType(order.status)">{{ statusText[order.status] ?? order.status }}</van-tag>
        </van-cell>
        <van-cell title="金额">
          <span class="amount">¥{{ Number(order.totalAmount).toFixed(2) }}</span>
        </van-cell>
        <van-cell v-if="order.receiverName" title="收货" :label="`${order.receiverAddress}`">
          {{ order.receiverName }} {{ order.receiverPhone }}
        </van-cell>
        <van-cell title="下单时间" :value="formatTime(order.createdAt)" />
        <van-cell v-if="order.status === 'PENDING'" class="pay-row">
          <div class="row-actions">
            <van-button size="small" plain round :loading="cancelling === order.orderNo"
              @click="cancel(order)">取消订单</van-button>
            <van-button size="small" type="danger" round :loading="paying === order.orderNo"
              @click="pay(order)">模拟支付</van-button>
          </div>
        </van-cell>
        <van-cell v-if="order.status === 'SHIPPED'" class="pay-row">
          <div class="row-actions">
            <van-button size="small" type="primary" round :loading="completing === order.orderNo"
              @click="complete(order)">确认收货</van-button>
          </div>
        </van-cell>
      </van-cell-group>
    </van-list>
    <van-empty v-if="finished && orders.length === 0" description="还没有订单，去逛逛吧" />
  </div>
</template>

<script setup lang="ts">
import { showConfirmDialog, showFailToast, showSuccessToast } from 'vant';
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { Order } from '@mall/shared';
import { cancelOrder, completeOrder, listOrders, payOrder } from '../api/order';

const router = useRouter();
const orders = ref<Order[]>([]);
const loading = ref(false);
const finished = ref(false);
const page = ref(1);
const paying = ref<string>();
const cancelling = ref<string>();
const completing = ref<string>();

const statusText: Record<string, string> = {
  PENDING: '待支付',
  PAID: '已支付',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
};

function tagType(status: Order['status']): 'primary' | 'success' | 'warning' | 'default' {
  if (status === 'PENDING') return 'warning';
  if (status === 'COMPLETED') return 'success';
  if (status === 'CANCELLED') return 'default';
  return 'primary';
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN');
}

async function load() {
  loading.value = true;
  try {
    const data = await listOrders(page.value, 10);
    orders.value.push(...data.list);
    finished.value = orders.value.length >= data.total;
    page.value += 1;
  } finally {
    loading.value = false;
  }
}

async function pay(order: Order) {
  paying.value = order.orderNo;
  try {
    const updated = await payOrder(order.orderNo);
    applyUpdate(order, updated);
    showSuccessToast('支付成功');
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '支付失败');
  } finally {
    paying.value = undefined;
  }
}

async function cancel(order: Order) {
  await showConfirmDialog({
    title: '取消订单',
    message: `确定取消订单 ${order.orderNo} 吗？库存将自动回补。`
  });
  cancelling.value = order.orderNo;
  try {
    const updated = await cancelOrder(order.orderNo);
    applyUpdate(order, updated);
    showSuccessToast('订单已取消，库存已回补');
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '取消失败');
  } finally {
    cancelling.value = undefined;
  }
}

async function complete(order: Order) {
  await showConfirmDialog({
    title: '确认收货',
    message: `请确认已收到订单 ${order.orderNo} 的商品，确认后交易完成。`
  });
  completing.value = order.orderNo;
  try {
    const updated = await completeOrder(order.orderNo);
    applyUpdate(order, updated);
    showSuccessToast('确认收货成功，交易完成');
  } catch (error) {
    showFailToast((error as { message?: string }).message ?? '确认收货失败');
  } finally {
    completing.value = undefined;
  }
}

function applyUpdate(local: Order, updated: Order) {
  local.status = updated.status;
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
.order-card { margin-top: 12px; }
.amount { color: #ee0a24; font-weight: 600; }
.pay-row { padding-bottom: 8px; }
.row-actions { display: flex; gap: 10px; justify-content: flex-end; }
</style>
