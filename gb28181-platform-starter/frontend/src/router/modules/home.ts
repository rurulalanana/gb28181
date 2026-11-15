import { $t } from "@/plugins/i18n";
const Layout = () => import("@/layout/index.vue");

/**
 * 根路由：布局壳 + 首屏页。
 *
 * <p>`/dashboard` 在这里是**静态**路由且 `showLink:false`：
 * <ul>
 *   <li>静态 —— `/` 的 redirect 不能依赖「动态菜单里恰好有 dashboard 且当前账号有权限」，
 *       否则菜单表被改坏时整站连入口都没有了；</li>
 *   <li>不进侧栏 —— 侧栏里的「总览」由 `gb_menu` 的 M 行提供（挂在「监控」目录下），
 *       静态这条只负责渲染落地页，避免菜单出现两个总览。</li>
 * </ul>
 * 两者路径相同，`handleAsyncRoutes` 检测到已存在同路径会跳过重复注册，不会冲突。
 */
export default {
  path: "/",
  name: "Home",
  component: Layout,
  redirect: "/dashboard",
  meta: {
    icon: "ep/odometer",
    title: $t("gb.dashboard"),
    rank: 0
  },
  children: [
    {
      path: "/dashboard",
      name: "Dashboard",
      component: () => import("@/views/DashboardView.vue"),
      meta: {
        title: $t("gb.dashboard"),
        icon: "ep/odometer",
        showLink: false
      }
    },
    {
      /*
       * 账号安全：不进侧栏（showLink:false），入口在右上角头像下拉。
       * 走静态路由而不是 gb_menu：它属于「当前登录账号」而不是平台功能，
       * 不该被菜单管理删掉，也不该参与权限点过滤 —— 谁登录都能改自己的密码。
       */
      path: "/account/security",
      name: "AccountSecurity",
      component: () => import("@/views/AccountSecurityView.vue"),
      meta: {
        title: "账号安全",
        icon: "ep/lock",
        showLink: false
      }
    }
  ]
} satisfies RouteConfigsTable;
