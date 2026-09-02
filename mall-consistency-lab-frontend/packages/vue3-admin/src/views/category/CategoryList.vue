<template>
  <el-card>
    <div class="toolbar">
      <el-button type="primary" @click="openCreate">新增分类</el-button>
    </div>
    <el-table :data="categories" v-loading="loading">
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="name" label="分类名称" min-width="200" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button size="small" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" type="danger" :loading="removing === row.id"
            @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="form.id ? '编辑分类' : '新增分类'" width="420px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" maxlength="50" placeholder="不超过 50 个字符" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';
import type { Category } from '@mall/shared';
import { createCategory, deleteCategory, listCategories, updateCategory } from '../../api/admin';

const categories = ref<Category[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const saving = ref(false);
const removing = ref<number>();
const formRef = ref<FormInstance>();
const form = reactive<{ id?: number; name: string }>({ id: undefined, name: '' });

const rules: FormRules = {
  name: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
};

async function load() {
  loading.value = true;
  try {
    categories.value = await listCategories();
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  form.id = undefined;
  form.name = '';
  dialogVisible.value = true;
}

function openEdit(category: Category) {
  form.id = category.id;
  form.name = category.name;
  dialogVisible.value = true;
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  saving.value = true;
  try {
    if (form.id) {
      await updateCategory(form.id, form.name.trim());
    } else {
      await createCategory(form.name.trim());
    }
    dialogVisible.value = false;
    ElMessage.success('已保存');
    await load();
  } catch (error) {
    console.error('Save category failed', error);
  } finally {
    saving.value = false;
  }
}

async function remove(category: Category) {
  await ElMessageBox.confirm(
    `确定删除分类「${category.name}」吗？若分类下仍有商品将无法删除。`,
    '删除确认',
    { type: 'warning' }
  );
  removing.value = category.id;
  try {
    await deleteCategory(category.id);
    ElMessage.success('已删除');
    await load();
  } catch (error) {
    console.error('Delete category failed', error);
  } finally {
    removing.value = undefined;
  }
}

onMounted(load);
</script>

<style scoped>
.toolbar { margin-bottom: 14px; }
</style>
