/**
 * 应用入口：Vue Pure Admin（thin i18n）工程底座 + GB28181 业务应用。
 *
 * <p>装配顺序照抄框架：先样式、再全局组件/指令、最后 store → router → 插件 → mount。
 * `router.isReady()` 必须等：`injectResponsiveStorage` 注入的布局偏好要在首屏渲染前就位，
 * 否则首帧会按默认布局画一次再跳变（刷新时侧栏「闪一下」）。
 *
 * <p>业务代码（`@biz/*`）只提供页面、请求客户端、播放器与设计系统 token；
 * 布局、侧栏、顶栏、标签页、主题全部来自框架的 `@/layout`。
 */
import App from "./App.vue";
import router from "./router";
import { setupStore } from "@/store";
import { useI18n } from "@/plugins/i18n";
import { getPlatformConfig } from "./config";
import { MotionPlugin } from "@vueuse/motion";
import { createApp, type Directive } from "vue";
import { useElementPlus } from "@/plugins/elementPlus";
import { injectResponsiveStorage } from "@/utils/responsive";
import * as ElementPlusIconsVue from "@element-plus/icons-vue";

import Table from "@pureadmin/table";
import VueTippy from "vue-tippy";

// 引入重置样式
import "./style/reset.scss";
// 导入公共样式
import "./style/index.scss";
// 一定要在 main.ts 中导入 tailwind.css，防止 vite 每次 hmr 都会请求 src/style/index.scss 整体 css
import "./style/tailwind.css";
import "element-plus/dist/index.css";
// 导入字体图标
import "./assets/iconfont/iconfont.css";
import "tippy.js/dist/tippy.css";
import "tippy.js/themes/light.css";
// 业务设计系统放最后：它覆写 Element Plus 的圆角/字号/描边等 token，
// 必须晚于 element-plus/dist/index.css 才能生效
import "@biz/styles/main.css";
// 业务深色模式：只做令牌反相，必须晚于 main.css（同权重选择器靠顺序决定胜负）
import "@biz/styles/dark.css";
// 侧栏菜单视觉系统：晚于框架 sidebar.scss，且选择器带 .sidebar-container 抬权重
import "@biz/styles/menu.css";

const app = createApp(App);

// 自定义指令
import * as directives from "@/directives";
Object.keys(directives).forEach(key => {
  app.directive(key, (directives as { [key: string]: Directive })[key]);
});

// 全局注册 @iconify/vue 图标库
import {
  IconifyIconOffline,
  IconifyIconOnline,
  FontIcon
} from "./components/ReIcon";
app.component("IconifyIconOffline", IconifyIconOffline);
app.component("IconifyIconOnline", IconifyIconOnline);
app.component("FontIcon", FontIcon);

// 全局注册按钮级别权限组件
import { Auth } from "@/components/ReAuth";
import { Perms } from "@/components/RePerms";
app.component("Auth", Auth);
app.component("Perms", Perms);

// 业务 20 个页面里大量 <el-icon><VideoCamera /></el-icon>，必须全量注册图标。
// 名字与 @element-plus/icons-vue 的导出名一致，缺一个页面就少一个图标。
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component as any);
}

getPlatformConfig(app).then(async config => {
  setupStore(app);
  app.use(router);
  await router.isReady();
  injectResponsiveStorage(app, config);
  app.use(MotionPlugin).use(useI18n).use(useElementPlus).use(Table);
  app.use(VueTippy);
  app.mount("#app");
});
