<template>
  <!-- 聚合搜索框：一个输入框本地匹配多个字段（数据量小，无需后端分页查询） -->
  <el-input
    v-model="keyword"
    class="keyword-filter"
    clearable
    :placeholder="placeholder"
    :style="{ width }"
  >
    <template #prefix>
      <el-icon aria-hidden="true"><Search /></el-icon>
    </template>
  </el-input>
</template>

<script setup>
defineProps({
  /** 提示语里直接写清能搜哪些字段，避免用户猜。 */
  placeholder: { type: String, default: '搜索' },
  /** 默认宽度与其它筛选控件对齐（见 main.css 的「筛选栏控件统一」）。 */
  width: { type: String, default: '240px' }
})

const keyword = defineModel({ type: String, default: '' })
</script>

<style scoped>
/* 圆角/高度与 .filter-bar 里的其它控件保持一致；高度由 main.css 统一给 32px */
.keyword-filter :deep(.el-input__wrapper) {
  border-radius: var(--r-sm);
}
</style>
