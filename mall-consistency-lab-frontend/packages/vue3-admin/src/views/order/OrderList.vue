<template>
  <el-card>
    <div class="toolbar">
      <el-select v-model="status" placeholder="订单状态" clearable class="status-select">
        <el-option v-for="(label, value) in STATUS_TEXT" :key="value" :label="label" :value="value" />
      </el-select>
      <el-input v-model="keyword" placeholder="订单号" clearable class="search"
        @keyup.enter="load" />
      <el-button type="primary" @click="load">搜索</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <el-table :data="orders" v-loading="loading">
      <el-table-column prop="orderNo" label="订单号" min-width="220" />
      <el-table-column prop="userId" label="买家 ID" width="90" />
      <el-table-column label="金额" width="110">
        <template #default="{ row }">¥{{ Number(row.totalAmount).toFixed(2) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ STATUS_TEXT[row.status] ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下单时间" width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row.orderNo)">详情</el-button>
          <el-button v-if="row.status === 'PAID'" size="small" type="primary"
            :loading="shipping === row.orderNo" @click="ship(row)">发货</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pagination" layout="prev, pager, next, total" :total="total"
      :current-page="page" :page-size="size" @current-change="changePage" />
  </el-card>

  <el-drawer v-model="detailVisible" title="订单详情" size="420px">
    <template v-if="detail">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="订单号">{{ detail.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusTagType(detail.status)">{{ STATUS_TEXT[detail.status] ?? detail.status }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="买家 ID">{{ detail.userId }}</el-descriptions-item>
        <el-descriptions-item label="收货人">{{ detail.receiverName ?? '-' }}
          {{ detail.receiverPhone ?? '' }}</el-descriptions-item>
        <el-descriptions-item label="收货地址">{{ detail.receiverAddress ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="下单时间">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
      </el-descriptions>

      <h4 class="items-title">商品明细</h4>
      <el-table :data="detail.items ?? []" size="small">
        <el-table-column prop="productName" label="商品" min-width="140" />
        <el-table-column label="单价" width="90">
          <template #default="{ row }">¥{{ Number(row.price).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column prop="count" label="数量" width="70" />
      </el-table>
      <div class="total-row">合计：<span class="amount">¥{{ Number(detail.totalAmount).toFixed(2) }}</span></div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { ElMessage } from 'element-plus';
import { onMounted, ref } from 'vue';
import type { Order, OrderStatus } from '@mall/shared';
import { getAdminOrder, listAllOrders, shipOrder } from '../../api/admin';

const STATUS_TEXT: Record<string, string> = {
  PENDING: '待支付',
  PAID: '已支付',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
};

const orders = ref<Order[]>([]);
const loading = ref(false);
const total = ref(0);
const page = ref(1);
const size = 10;
const status = ref<OrderStatus | ''>('');
const keyword = ref('');
const shipping = ref<string>();

const detailVisible = ref(false);
const detail = ref<Order>();

function statusTagType(status: OrderStatus): 'warning' | 'success' | 'primary' | 'info' {
  if (status === 'PENDING') return 'warning';
  if (status === 'COMPLETED') return 'success';
  if (status === 'CANCELLED') return 'info';
  return 'primary';
}

function formatTime(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN');
}

async function load() {
  loading.value = true;
  try {
    const data = await listAllOrders({
      page: page.value,
      size,
      status: status.value || undefined,
      keyword: keyword.value || undefined
    });
    orders.value = data.list;
    total.value = data.total;
  } finally {
    loading.value = false;
  }
}

function reset() {
  status.value = '';
  keyword.value = '';
  page.value = 1;
  void load();
}

function changePage(newPage: number) {
  page.value = newPage;
  void load();
}

async function openDetail(orderNo: string) {
  detail.value = await getAdminOrder(orderNo);
  detailVisible.value = true;
}

async function ship(order: Order) {
  shipping.value = order.orderNo;
  try {
    const updated = await shipOrder(order.orderNo);
    order.status = updated.status;
    ElMessage.success(`订单 ${order.orderNo} 已发货`);
  } catch (error) {
    // request 拦截器已弹出错误信息
    console.error('Ship failed', error);
  } finally {
    shipping.value = undefined;
  }
}

onMounted(load);
</script>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 14px; }
.status-select { width: 140px; }
.search { width: 220px; }
.pagination { margin-top: 14px; justify-content: flex-end; }
.items-title { margin: 16px 0 8px; }
.total-row { text-align: right; margin-top: 10px; }
.amount { color: var(--el-color-danger); font-weight: 600; }
</style>
