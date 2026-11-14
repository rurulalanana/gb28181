import { $t } from "@/plugins/i18n";
const Layout = () => import("@/layout/index.vue");

/**
 * 不参与菜单的路由。
 *
 * <p>这里有三类：
 * <ul>
 *   <li>框架自带：登录、403、500、重定向中转；</li>
 *   <li>免登录对外页：录像分享 `/share/:token`、直播分享 `/play`、iframe 嵌入测试
 *       `/iframe-test` —— 它们要发给没有账号的人打开，所以既不在 `gb_menu` 里，
 *       也不走登录守卫（白名单见 `@/router/index.ts` 的 `whiteList`）；</li>
 *   <li>404 兜底由 `addPathMatch()` 在动态路由注册完成后追加，不写在这里 ——
 *       否则刷新动态路由页面时会先命中 404。</li>
 * </ul>
 */
export default [
  {
    path: "/login",
    name: "Login",
    component: () => import("@/views/login/index.vue"),
    meta: {
      title: $t("menus.pureLogin"),
      showLink: false
    }
  },
  // 全屏403（无权访问）页面
  {
    path: "/access-denied",
    name: "AccessDenied",
    component: () => import("@/views/error/403.vue"),
    meta: {
      title: $t("menus.pureAccessDenied"),
      showLink: false
    }
  },
  // 全屏500（服务器出错）页面
  {
    path: "/server-error",
    name: "ServerError",
    component: () => import("@/views/error/500.vue"),
    meta: {
      title: $t("menus.pureServerError"),
      showLink: false
    }
  },
  {
    path: "/redirect",
    component: Layout,
    meta: {
      title: $t("status.pureLoad"),
      showLink: false
    },
    children: [
      {
        path: "/redirect/:path(.*)",
        name: "Redirect",
        component: () => import("@/layout/redirect.vue")
      }
    ]
  },
  // ---- 以下免登录，凭签名令牌或纯前端能力访问 ----
  {
    // 录像分享：凭签名令牌观看某段录像
    path: "/share/:token",
    name: "ShareRecord",
    component: () => import("@/views/ShareView.vue"),
    meta: {
      title: "录像分享",
      showLink: false
    }
  },
  {
    // 直播分享：路径对齐 CMS 的 play.html（避免与 /share/:token 动态段混淆）
    path: "/play",
    name: "LiveShare",
    component: () => import("@/views/LiveShareView.vue"),
    meta: {
      title: "直播分享",
      showLink: false
    }
  },
  {
    // iframe 嵌入测试：要在别人的机器上判断「嵌到我站里能不能播」，
    // 且不含敏感数据（通道编码由使用者自己填）
    path: "/iframe-test",
    name: "IframeTest",
    component: () => import("@/views/IframeTestView.vue"),
    meta: {
      title: "iframe 嵌入测试",
      showLink: false
    }
  }
] satisfies Array<RouteConfigsTable>;
