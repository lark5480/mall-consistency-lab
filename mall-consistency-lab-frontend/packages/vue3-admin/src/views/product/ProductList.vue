<template>
  <el-card>
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="商品名称" clearable class="search" />
      <el-select v-model="categoryId" placeholder="分类" clearable class="category">
        <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.id" />
      </el-select>
      <el-button type="primary" @click="load">搜索</el-button>
      <el-button @click="openCreate">新增</el-button>
    </div>
    <el-table :data="products" v-loading="loading">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="名称" />
      <el-table-column prop="price" label="价格" width="100"><template #default="{ row }">{{ row.price.toFixed(2) }}</template></el-table-column>
      <el-table-column prop="stock" label="库存" width="90" />
      <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 'ON_SALE' ? '上架' : '下架' }}</template></el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pagination" layout="prev, pager, next, total" :total="total"
      :current-page="page" :page-size="size" @current-change="changePage" />
  </el-card>

  <el-dialog v-model="dialogVisible" :title="form.id ? '编辑商品' : '新增商品'" width="520px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="名称" prop="name"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="描述"><el-input v-model="form.description" type="textarea" /></el-form-item>
      <el-form-item label="价格" prop="price"><el-input-number v-model="form.price" :min="0" :precision="2" /></el-form-item>
      <el-form-item label="库存" prop="stock"><el-input-number v-model="form.stock" :min="0" /></el-form-item>
      <el-form-item label="分类" prop="categoryId">
        <el-select v-model="form.categoryId">
          <el-option v-for="category in categories" :key="category.id" :label="category.name" :value="category.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="图片"><el-input v-model="form.imageUrl" placeholder="图片 URL" /></el-form-item>
      <el-form-item label="状态"><el-switch v-model="onSale" active-text="上架" inactive-text="下架" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';
import type { Category, Product } from '@mall/shared';
import { listCategories } from '../../api/admin';
import { deleteProduct, listProducts, saveProduct } from '../../api/product';

const products = ref<Product[]>([]);
const categories = ref<Category[]>([]);
const loading = ref(false);
const keyword = ref('');
const categoryId = ref<number>();
const page = ref(1);
const size = 10;
const total = ref(0);
const dialogVisible = ref(false);
const formRef = ref<FormInstance>();
const onSale = ref(true);
const form = reactive<{ id?: number; name: string; description: string; price: number; stock: number; categoryId: number; imageUrl: string }>({
  name: '', description: '', price: 0, stock: 0, categoryId: 1, imageUrl: ''
});
const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }]
};

async function load() {
  loading.value = true;
  try {
    const result = await listProducts({ page: page.value, size, categoryId: categoryId.value, keyword: keyword.value || undefined });
    products.value = result.list;
    total.value = result.total;
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  Object.assign(form, { id: undefined, name: '', description: '', price: 0, stock: 0, categoryId: 1, imageUrl: '' });
  onSale.value = true;
  dialogVisible.value = true;
}

function openEdit(product: Product) {
  Object.assign(form, product);
  onSale.value = product.status === 'ON_SALE';
  dialogVisible.value = true;
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  await saveProduct({ ...form, status: onSale.value ? 'ON_SALE' : 'OFF_SALE' });
  ElMessage.success('保存成功');
  dialogVisible.value = false;
  await load();
}

async function remove(id: number) {
  await ElMessageBox.confirm('删除后不可恢复，是否继续？', '删除确认', { type: 'warning' });
  await deleteProduct(id);
  ElMessage.success('删除成功');
  await load();
}

function changePage(value: number) {
  page.value = value;
  void load();
}

onMounted(async () => {
  categories.value = await listCategories();
  await load();
});
</script>

<style scoped>
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.search { max-width: 220px; }
.category { width: 140px; }
.pagination { margin-top: 16px; justify-content: flex-end; }
</style>
