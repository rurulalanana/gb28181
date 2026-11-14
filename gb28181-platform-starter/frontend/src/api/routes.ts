/**
 * 动态路由数据源（pure-admin 的 `getAsyncRoutes` 契约）。
 *
 * <p>框架侧 `@/router/utils.ts` 只认「后端返回一棵路由树」，不关心树从哪来。这里把
 * `gb_menu` 的扁平行转成那棵树，于是「菜单管理」页里的增删改才真的会改变侧栏与路由。
 *
 * <p>三条约定与 pure-admin 官方 demo 完全一致，不要改动：
 * <ul>
 *   <li>目录节点挂 <b>RouteShell</b>（只转发 `<router-view/>` 的透明壳）。不能留空：
 *       `addAsyncRoutes` 会用 glob 覆盖每个节点的 component，留空即 undefined，
 *       而 vue-router 匹配到无组件的中间层就渲染不出孙级页面（整页空白）。
 *       也不能填 Layout —— 那是套娃布局。</li>
 *   <li>叶子节点 `component` 只写相对片段（`views/XxxView.vue`）：框架用
 *       `import.meta.glob("/src/views/**")` 的键做「字符串包含」匹配。</li>
 *   <li>排序用 `meta.rank`，`showLink:false` 表示注册路由但不进侧栏。</li>
 * </ul>
 */
import { storageLocal } from "@pureadmin/utils";
import { get, post } from "@biz/api/http";
import {
  DEFAULT_GROUP_ICON,
  GROUP_ICONS,
  MENU_SEED_VERSION,
  buildSeedItems
} from "@biz/menu/seed";

/**
 * 当前账号的权限点。
 *
 * <p>刻意不 import `@/utils/auth` 的 `hasPerms`：它 → `@/store/modules/user` →
 * `@/store/utils` → `@/router/utils` → 本文件，形成模块环，构建后表现为
 * `Uncaught ReferenceError: Cannot access 'xx' before initialization`（白屏）。
 * token/权限本来就落在 localStorage 的 `user-info` 里，直接读它最省事。
 */
const USER_KEY = "user-info";

function currentPermissions(): Array<string> {
  const cached = storageLocal().getItem<{ permissions?: Array<string> }>(USER_KEY);
  return cached?.permissions ?? [];
}

/** 是否具备某个权限点；超管用 `*:*:*` 通配。 */
function hasPerm(code: string): boolean {
  if (!code) return true;
  const perms = currentPermissions();
  return perms.includes("*:*:*") || perms.includes(code);
}

/** `gb_menu` 行（后端 MenuVo 的驼峰化结果，多给的字段忽略）。 */
type MenuRow = {
  menuKey: string;
  parentKey?: string | null;
  menuType?: string;
  title?: string;
  icon?: string | null;
  sortOrder?: number | null;
  path?: string | null;
  component?: string | null;
  perms?: string | null;
  hidden?: boolean | number | null;
  status?: number | null;
};

/**
 * 侧栏的「二级分组」预设。
 *
 * <p>有些目录下的条目语义上还能再分一层（系统：谁在用 / 怎么运维）。数据库只有
 * 「目录 → 菜单」两级，硬塞第三级会让侧栏出现两层折叠菜单，反而更难用。
 * 这里用**分组标题**在二级里画出层次：菜单项仍是平铺的，只是被小标题切成几段。
 *
 * <p>生效条件很保守：只有当该目录下的**菜单键集合与预设完全一致**时才按预设重排并
 * 打上分组标记。运维在「菜单管理」里加了页面或删了页面，键集合就对不上，这里自动退回
 * 数据库顺序、不显示分组标题 —— 预设只负责「已知布局的排版」，不跟菜单管理抢控制权。
 */
const MENU_SECTIONS: Record<string, Array<{ title: string; keys: string[] }>> = {
  "g-system": [
    { title: "身份与权限", keys: ["users", "roles", "menus"] },
    { title: "平台运维", keys: ["oplogs", "settings"] }
  ]
};

/** 按预设给 children 排序并写 `meta.section`；不匹配时原样返回。 */
function applySections(
  children: AsyncRoute[],
  sections: Array<{ title: string; keys: string[] }>
): AsyncRoute[] {
  const preset = sections.flatMap(s => s.keys);
  const actual = children.map(c => String(c.name));
  if (
    preset.length !== actual.length ||
    !preset.every(k => actual.includes(k))
  ) {
    return children;
  }
  const ordered: AsyncRoute[] = [];
  for (const section of sections) {
    for (const key of section.keys) {
      const hit = children.find(c => String(c.name) === key);
      if (hit) {
        hit.meta = { ...hit.meta, section: section.title };
        ordered.push(hit);
      }
    }
  }
  return ordered;
}

/**
 * 分组目录的透明壳组件路径。
 *
 * <p>字符串必须能被 `@/router/utils.ts` 里那条 `import.meta.glob` 的键「包含匹配」到
 * （键形如 `/src/views/RouteShell.vue`），所以这里写相对片段而不是绝对路径。
 */
const SHELL_COMPONENT = "views/RouteShell.vue";

type AsyncRoute = {
  path: string;
  name?: string;
  component?: string;
  redirect?: string;
  meta: Record<string, any>;
  children?: AsyncRoute[];
};

/** 停用行不参与路由；`status` 缺省视为启用（老数据没有这一列）。 */
function enabled(row: MenuRow): boolean {
  return Number(row.status ?? 0) !== 1;
}

function bySort(a: MenuRow, b: MenuRow): number {
  const d = (a.sortOrder ?? 0) - (b.sortOrder ?? 0);
  return d !== 0 ? d : String(a.menuKey).localeCompare(String(b.menuKey));
}

/**
 * 菜单图标 → iconify 名。
 *
 * 库里存的是 Element Plus 的组件名（`VideoCamera`），而侧栏渲染走 `useRenderIcon`，
 * 它把「不含冒号的字符串」交给 `IconifyIconOffline`，需要 `ep/video-camera` 这种名字。
 * `offlineIcon.ts` 已把这批名字全部 addIcon 注册过，因此离线部署也能出图标。
 */
const EP_ICON_MAP: Record<string, string> = {
  Odometer: "ep/odometer",
  VideoCamera: "ep/video-camera",
  Monitor: "ep/monitor",
  List: "ep/list",
  Film: "ep/film",
  Cloudy: "ep/cloudy",
  Bell: "ep/bell",
  Share: "ep/share",
  User: "ep/user",
  Key: "ep/key",
  Document: "ep/document",
  Setting: "ep/setting",
  Cpu: "ep/cpu",
  DataLine: "ep/data-line",
  Folder: "ep/folder",
  Files: "ep/files",
  Camera: "ep/camera",
  Location: "ep/location",
  MapLocation: "ep/map-location",
  Lock: "ep/lock",
  Menu: "ep/menu",
  Grid: "ep/grid",
  Histogram: "ep/histogram",
  Picture: "ep/picture",
  Postcard: "ep/postcard",
  Ticket: "ep/ticket",
  TrendCharts: "ep/trend-charts",
  View: "ep/view",
  Warning: "ep/warning",
  Aim: "ep/aim"
};

function toIcon(row: MenuRow, fallback: string): string {
  const raw = (row.icon || "").trim();
  if (!raw) return fallback;
  // 已经是 iconify 名（含冒号或斜杠）就原样用，库里可以直接写 `ep/aim`
  if (raw.includes(":") || raw.includes("/")) return raw;
  return EP_ICON_MAP[raw] || fallback;
}

function toLeaf(row: MenuRow): AsyncRoute {
  return {
    path: row.path as string,
    name: row.menuKey,
    component: row.component as string,
    meta: {
      title: row.title || row.menuKey,
      icon: toIcon(row, "ep/document"),
      rank: row.sortOrder ?? 99,
      // hidden 行照常注册路由（详情页要靠地址直达），但不进侧栏
      showLink: !row.hidden,
      // 页内按钮级权限点；配合 v-auth / hasAuth 使用
      auths: row.perms ? [row.perms] : undefined
    }
  };
}

/**
 * 扁平行 → 路由树。
 *
 * <p>权限在<b>这里</b>过滤而不是依赖框架的 `meta.roles`：本平台角色是「权限点并集」，
 * 一个自定义角色（如巡检员）没有任何 `admin/common` 这种静态角色名可比，只能按
 * `perms` 判断。无权限的菜单行直接不进路由树，侧栏与路由天然一致。
 */
export function buildRouteTree(rows: MenuRow[]): AsyncRoute[] {
  const list = (rows || []).filter(enabled);
  return list
    .filter((r) => r.menuType === "D")
    .sort(bySort)
    .map((dir) => {
      const children: AsyncRoute[] = list
        .filter(
          (r) =>
            r.menuType === "M" &&
            (r.parentKey || "") === dir.menuKey &&
            r.path &&
            r.component &&
            // 没配 perms 的页面对所有登录用户可见（hasPerm 传空串会返回 false，
            // 直接过滤会把这类页面整体弄丢）
            (!r.perms || hasPerm(r.perms))
        )
        .sort(bySort)
        .map((r) => toLeaf(r));
      const sections = MENU_SECTIONS[dir.menuKey];
      const ordered = sections ? applySections(children, sections) : children;
      return {
        path: "/" + dir.menuKey,
        name: dir.menuKey,
        // 目录只做分组，真实页面在 children 里；见文件头注释
        component: SHELL_COMPONENT,
        redirect: children[0]?.path,
        meta: {
          title: dir.title || dir.menuKey,
          icon: toIcon(dir, GROUP_ICONS[dir.menuKey] || DEFAULT_GROUP_ICON),
          rank: dir.sortOrder ?? 99
        },
        children: ordered
      } as AsyncRoute;
    })
    // 目录下没有任何「有权限且启用」的页面时整个目录都不出现：
    // 留一个空目录，用户点开是白屏，比不显示更糟
    .filter((dir) => (dir.children || []).length > 0);
}

/** 供 `useAuthStore.loadMenuRows()` 复用的原始行拉取。 */
export const fetchMenuRows = (): Promise<{ items?: MenuRow[] }> =>
  get("/menu/config") as unknown as Promise<{ items?: MenuRow[] }>;

/** 本地记录已同步的种子版本，省掉每次刷新的 POST。 */
const SEED_KEY = "gb_menu_seed_version";

/**
 * 补写本版本缺失的目录/菜单行。
 *
 * <p>放在路由装配前而不是登录接口里：新部署的库是空的，谁先登录都得有菜单可用。
 * 版本号一致时是空操作，所以运维删掉的菜单不会因重启又冒出来。
 * 失败直接吞掉 —— 没有 `system:read` 的账号调不通 `/menu/sync`，
 * 但库里已有的菜单照样能用，不能因为补种失败就白屏。
 */
async function ensureMenuSeed(): Promise<void> {
  if (localStorage.getItem(SEED_KEY) === MENU_SEED_VERSION) return;
  try {
    await post(
      "/menu/sync",
      null,
      { version: MENU_SEED_VERSION, items: buildSeedItems() },
      20000
    );
    localStorage.setItem(SEED_KEY, MENU_SEED_VERSION);
  } catch (e) {
    console.warn("[menu] 菜单种子补写失败，按库中现有菜单渲染", e);
  }
}

/** pure-admin 的 `initRouter()` 通过它拿后端路由。 */
export const getAsyncRoutes = async () => {
  await ensureMenuSeed();
  const res = await fetchMenuRows();
  return {
    success: true,
    data: buildRouteTree(res?.items || [])
  };
};
