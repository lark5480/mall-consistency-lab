<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6"><el-card><el-statistic title="今日订单数" :value="stats?.todayCount ?? 0" /></el-card></el-col>
      <el-col :span="6"><el-card><el-statistic title="今日 GMV (¥)" :value="stats?.todayGmv ?? 0" :precision="2" /></el-card></el-col>
      <el-col :span="6"><el-card><el-statistic title="待发货订单" :value="stats?.pendingShipCount ?? 0" /></el-card></el-col>
      <el-col :span="6"><el-card><el-statistic title="低库存商品" :value="productStats?.lowStockCount ?? 0" /></el-card></el-col>
    </el-row>

    <el-card class="chart-card">
      <template #header>近 7 日下单趋势（不含已取消）</template>
      <div ref="chartRef" class="chart" />
    </el-card>

    <el-row :gutter="16">
      <el-col :span="8"><el-card><el-statistic title="累计订单数（有效）" :value="stats?.totalCount ?? 0" /></el-card></el-col>
      <el-col :span="8"><el-card><el-statistic title="累计 GMV (¥)" :value="stats?.totalGmv ?? 0" :precision="2" /></el-card></el-col>
      <el-col :span="8">
        <el-card>
          <el-statistic title="在售 / 下架商品" :value="productStats?.onSaleCount ?? 0">
            <template #suffix>/ {{ productStats?.offSaleCount ?? 0 }}</template>
          </el-statistic>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import * as echarts from 'echarts';
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { getProductStats, getOrderStats, type OrderStats, type ProductStats } from '../api/admin';

const stats = ref<OrderStats>();
const productStats = ref<ProductStats>();
const chartRef = ref<HTMLDivElement>();
let chart: echarts.ECharts | undefined;

function renderChart(data: OrderStats['trend']) {
  if (!chartRef.value) return;
  chart = chart ?? echarts.init(chartRef.value);
  chart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: data.map(point => point.date.slice(5)) },
    yAxis: [
      { type: 'value', name: '订单数', minInterval: 1 },
      { type: 'value', name: 'GMV (¥)' }
    ],
    series: [
      { name: '订单数', type: 'line', smooth: true, data: data.map(point => point.count) },
      { name: 'GMV', type: 'line', smooth: true, yAxisIndex: 1, data: data.map(point => point.gmv) }
    ],
    grid: { left: 48, right: 56, top: 40, bottom: 28 }
  });
}

async function load() {
  const [orderStats, productStatData] = await Promise.all([getOrderStats(), getProductStats()]);
  stats.value = orderStats;
  productStats.value = productStatData;
  renderChart(orderStats.trend);
}

onMounted(() => {
  void load();
});

onBeforeUnmount(() => {
  chart?.dispose();
  chart = undefined;
});
</script>

<style scoped>
.chart-card { margin: 16px 0; }
.chart { height: 320px; }
.el-col + .el-col .el-card { margin-left: 0; }
</style>
