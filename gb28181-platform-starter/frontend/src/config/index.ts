/**
 * 平台运行期配置（pure-admin 的 getPlatformConfig 契约）。
 *
 * <p>本项目不需要异步拉 public/platform-config.json（那会让入口 mount 依赖一次 HTTP，
 * 静态资源一旦缺失就整站白屏）。这里内置默认值并暴露 setConfig 供热更新，
 * 同时保留 pure-admin 组件依赖的 ResponsiveStorageNameSpace。
 */
import type { App } from "vue";

/**
 * 平台默认配置，字段与框架的 `public/platform-config.json` 一一对应。
 *
 * <p>`EpThemeColor` 不能省：`useDataThemeChange` 的 `setLayoutThemeColor` 直接把它
 * 交给 `darken()/lighten()`，值为 undefined 时内部会 `undefined.replace(...)`，
 * 进首页就抛 `TypeError: Cannot read properties of undefined (reading 'replace')`。
 * `injectResponsiveStorage` 里其他字段都有 `?? 默认值` 兜底，这里仍全列出来，
 * 免得以后哪个组件直接读 `getConfig().Xxx` 又是一次白屏。
 */
const defaults = {
  Title: "GB28181 视频平台",
  // 与 locales/*.yaml 的语言键保持一致（zh / en）：写 "zh-CN" 会让
  // `$storage.locale.locale === "zh"` 判断失败，Element Plus 内置文案会变成英文
  Locale: "zh",
  Layout: "vertical",
  Theme: "light",
  DarkMode: false,
  OverallStyle: "light",
  SidebarStatus: true,
  EpThemeColor: "#409EFF",
  /**
   * 固定页头 + 标签页，内容区自己滚动。
   *
   * <p>不能省：`settings.ts` 直接取 `getConfig().FixedHeader`，缺省是 `undefined`，
   * 布局会走「整页一个大滚动条」的分支 —— 表现是表格页永远铺不满、页面右侧和内容区
   * 各出一条滚动条。设 true 后 Header/Tags 固定，`.app-main` 成为独立滚动容器，
   * 列表页才能把表格撑到视口底部、只在表格内部滚动（框架默认值也是 true）。
   */
  FixedHeader: true,
  HiddenSideBar: false,
  KeepAlive: false,
  Grey: false,
  Weak: false,
  HideTabs: false,
  HideFooter: true,
  ShowLogo: true,
  ShowModel: "smart",
  MultiTagsCache: false,
  Stretch: false,
  MenuArrowIconNoTransition: false,
  MenuSearchHistory: 6,
  TooltipEffect: "light",
  // 动态路由不落本地缓存：菜单以 gb_menu 为准，缓存会让「菜单管理」改完不刷新也生效，
  // 排查时反而更难判断到底是库里的问题还是缓存的问题
  CachingAsyncRoutes: false,
  ResponsiveStorageNameSpace: "gb28181-"
};

let config: Record<string, any> = { ...defaults };

export const setConfig = (cfg?: Record<string, any>) => {
  config = Object.assign(config, cfg || {});
};

export const getConfig = (key?: string): any => {
  if (typeof key === "string") {
    return key.split(".").reduce((acc, part) => (acc ? acc[part] : undefined), config as any);
  }
  return config;
};

export const responsiveStorageNameSpace = () => getConfig("ResponsiveStorageNameSpace");

/** 保持 pure-admin 入口契约；同步返回，不阻塞 mount。 */
export const getPlatformConfig = async (app: App): Promise<any> => {
  app.config.globalProperties.$config = getConfig();
  return getConfig();
};
