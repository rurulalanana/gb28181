/**
 * 本地图标注册表（在 `@/layout/index.vue` 中加载，避免首屏就拉）。
 *
 * <p>为什么需要它：`gb_menu` 里的图标是**运行期**从库里读出来的字符串（`VideoCamera`），
 * 而 `IconifyIconOffline` 只认已经 `addIcon` 注册过的名字 —— 不注册就是一个个空白方块。
 *
 * <p>两套名字都注册：
 * <ul>
 *   <li>`ep/video-camera` 之类的 iconify 名 —— 框架自身（首页、搜索、403 页）用；</li>
 *   <li>`VideoCamera` 之类的 Element Plus 组件名 —— 库里存的历史值用，
 *       由 `@/api/routes.ts` 的 `EP_ICON_MAP` 转换后命中。</li>
 * </ul>
 *
 * <p>不要换成 `IconifyIconOnline`：本平台部署在内网/离线环境，走 iconify 在线 API
 * 意味着断网后整站菜单没有图标。这里全部是构建期打包进产物的本地 SVG。
 */
import { getSvgInfo } from "@pureadmin/utils";
import { addIcon } from "@iconify/vue/dist/offline";

// ---- 框架自用 ----
import EpHomeFilled from "~icons/ep/home-filled?raw";
import RiSearchLine from "~icons/ri/search-line?raw";
import RiInformationLine from "~icons/ri/information-line?raw";

// ---- 菜单管理页可选图标（与 @biz/menu/seed.js 的 ICON_OPTIONS 一一对应） ----
import EpOdometer from "~icons/ep/odometer?raw";
import EpVideoCamera from "~icons/ep/video-camera?raw";
import EpMonitor from "~icons/ep/monitor?raw";
import EpList from "~icons/ep/list?raw";
import EpFilm from "~icons/ep/film?raw";
import EpCloudy from "~icons/ep/cloudy?raw";
import EpBell from "~icons/ep/bell?raw";
import EpShare from "~icons/ep/share?raw";
import EpUser from "~icons/ep/user?raw";
import EpKey from "~icons/ep/key?raw";
import EpDocument from "~icons/ep/document?raw";
import EpSetting from "~icons/ep/setting?raw";
import EpCpu from "~icons/ep/cpu?raw";
import EpDataLine from "~icons/ep/data-line?raw";
import EpFolder from "~icons/ep/folder?raw";
import EpFiles from "~icons/ep/files?raw";
import EpCamera from "~icons/ep/camera?raw";
import EpLocation from "~icons/ep/location?raw";
import EpMapLocation from "~icons/ep/map-location?raw";
import EpLock from "~icons/ep/lock?raw";
import EpMenu from "~icons/ep/menu?raw";
import EpGrid from "~icons/ep/grid?raw";
import EpHistogram from "~icons/ep/histogram?raw";
import EpPicture from "~icons/ep/picture?raw";
import EpPostcard from "~icons/ep/postcard?raw";
import EpTicket from "~icons/ep/ticket?raw";
import EpTrendCharts from "~icons/ep/trend-charts?raw";
import EpView from "~icons/ep/view?raw";
import EpWarning from "~icons/ep/warning?raw";
import EpAim from "~icons/ep/aim?raw";

// `?raw` 的模块在 vite-svg-loader 的类型里被声明成组件，实际是字符串，
// 所以这里第二列必须是 any —— 写 string 会让每一条 import 都报类型不匹配
const icons: Array<[string, any]> = [
  ["ep/home-filled", EpHomeFilled],
  ["ri/search-line", RiSearchLine],
  ["ri/information-line", RiInformationLine],

  ["ep/odometer", EpOdometer],
  ["ep/video-camera", EpVideoCamera],
  ["ep/monitor", EpMonitor],
  ["ep/list", EpList],
  ["ep/film", EpFilm],
  ["ep/cloudy", EpCloudy],
  ["ep/bell", EpBell],
  ["ep/share", EpShare],
  ["ep/user", EpUser],
  ["ep/key", EpKey],
  ["ep/document", EpDocument],
  ["ep/setting", EpSetting],
  ["ep/cpu", EpCpu],
  ["ep/data-line", EpDataLine],
  ["ep/folder", EpFolder],
  ["ep/files", EpFiles],
  ["ep/camera", EpCamera],
  ["ep/location", EpLocation],
  ["ep/map-location", EpMapLocation],
  ["ep/lock", EpLock],
  ["ep/menu", EpMenu],
  ["ep/grid", EpGrid],
  ["ep/histogram", EpHistogram],
  ["ep/picture", EpPicture],
  ["ep/postcard", EpPostcard],
  ["ep/ticket", EpTicket],
  ["ep/trend-charts", EpTrendCharts],
  ["ep/view", EpView],
  ["ep/warning", EpWarning],
  ["ep/aim", EpAim]
];

/**
 * Element Plus 组件名 → iconify 名。
 *
 * 库里 `gb_menu.icon` 存的是 `VideoCamera`；侧栏渲染只认 iconify 名。同名再注册一份，
 * 这样菜单管理页里手写了个映射表没覆盖的名字时也有图标，不会只剩空方块。
 */
const epNameOf = (iconify: string) => {
  const base = iconify.split("/")[1];
  return base
    .split("-")
    .map(part => part.charAt(0).toUpperCase() + part.slice(1))
    .join("");
};

icons.forEach(([name, icon]) => {
  // `?raw` 的模块在 vite-svg-loader 的类型里被声明成组件，实际是字符串
  const info = getSvgInfo(icon as unknown as string);
  addIcon(name as string, info);
  const epName = epNameOf(name as string);
  // 只在「纯 ep 图标」上补别名；ri/ 的别名没有意义，还会占掉相同的短名
  if (epName && name.startsWith("ep/")) addIcon(epName, info);
});
