<template>
  <el-config-provider :locale="currentLocale">
    <router-view />
    <ReDialog />
  </el-config-provider>
</template>

<script lang="ts">
import { defineComponent } from "vue";
import { ElConfigProvider } from "element-plus";
import { ReDialog } from "@/components/ReDialog";
import en from "element-plus/es/locale/lang/en";
import zhCn from "element-plus/es/locale/lang/zh-cn";

/**
 * 根组件。
 *
 * <p>`currentLocale` 跟框架的响应式存储（`$storage.locale`）走：顶栏语言切换器改的就是
 * 这个值，写死 zhCn 会让 Element Plus 的内置文案（分页、日期选择器、空状态）永远停在
 * 中文，切换语言只换了业务侧词条。
 *
 * <p>`<ReDialog />` 是框架的全局函数式弹窗容器（`ReDialog` 的 `addDialog`），
 * 业务页面里 `ElMessageBox` 之外的自定义弹窗走它，少了就点不出弹窗。
 */
export default defineComponent({
  name: "app",
  components: {
    [ElConfigProvider.name]: ElConfigProvider,
    ReDialog
  },
  computed: {
    currentLocale() {
      return this.$storage.locale?.locale === "zh" ? zhCn : en;
    }
  }
});
</script>
