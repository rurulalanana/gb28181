<script setup lang="ts">
/**
 * 分组目录的透明壳。
 *
 * <p>`gb_menu` 的分组（监控 / 资源 / 录像 …）在路由里是「目录节点」：它只用来在侧栏
 * 折叠出一层，自身没有页面。但 `@/router/utils.ts` 的 `addAsyncRoutes` 会**无条件**
 * 用 `import.meta.glob("/src/views/**")` 去覆盖每个节点的 `component`，
 * 目录节点拿不到组件就变成 `undefined`。
 *
 * <p>后果不是「少个组件」而是整页空白：vue-router 匹配 `/devices` 时得到
 * `[Layout, 资源(无组件), 设备管理]`，Layout 里的 `<router-view>` 在第二层渲染的是
 * 那个没组件的「资源」，它不渲染就永远轮不到孙级页面。
 *
 * <p>所以给目录挂上这个只转发 `<router-view>` 的壳：层级照旧，页面正常渲染。
 * 它不会被当成业务页面出现在「菜单管理」的组件候选里（见 `@biz/menu/seed.js`）。
 */
defineOptions({
  name: "RouteShell"
});
</script>

<template>
  <router-view />
</template>
