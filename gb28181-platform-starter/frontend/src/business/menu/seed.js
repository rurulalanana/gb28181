/**
 * 菜单种子与组件/图标候选。
 *
 * 菜单树存在后端 gb_menu（D/M/B 三层），侧栏与前端路由都由它决定 —— 由
 * `@/api/routes.ts` 把行转换成 pure-admin 的动态路由树。本文件的种子只在首次部署或
 * MENU_SEED_VERSION 变化时由 /api/v1/menu/sync 补写缺失行，之后以库为准。
 * 种子必须放前端：component 是构建产物路径，只有前端知道，后端内置一份必然漂移。
 * 按钮权限点反过来由后端按 Permissions 常量补写。
 *
 * 加页面：在 SEED_PAGES 加一条并把 MENU_SEED_VERSION 改成新值；只改标题/图标/排序
 * 不用改版本号，直接在「菜单管理」页改。
 */

export const DEFAULT_GROUP_TITLES = {
  'g-monitor': '监控',
  'g-resource': '资源',
  'g-cloud': '录像',
  'g-ops': '运维',
  'g-system': '系统'
}

/**
 * 目录默认图标（iconify 的 element-plus 图标名）。
 *
 * 目录行在 gb_menu 里没有 icon 字段，若前端不给兜底，侧栏分组会是「一级纯文字 + 二级带图标」，
 * 层级看起来反而比全无图标更乱。库里若给目录配了 icon 则优先用库里的。
 */
export const GROUP_ICONS = {
  'g-monitor': 'ep/video-camera',
  'g-resource': 'ep/monitor',
  'g-cloud': 'ep/film',
  'g-ops': 'ep/bell',
  'g-system': 'ep/setting'
}

/** 目录兜底图标：库里没配、映射表也没有时用文件夹。 */
export const DEFAULT_GROUP_ICON = 'ep/folder'

/** 菜单管理页的可选图标（与 offlineIcon.ts 注册的集合一一对应）。 */
export const ICON_OPTIONS = [
  'Odometer', 'VideoCamera', 'Monitor', 'List', 'Film', 'Cloudy', 'Bell', 'Share',
  'User', 'Key', 'Document', 'Setting', 'Cpu', 'DataLine', 'Folder', 'Files',
  'Camera', 'Location', 'MapLocation', 'Lock', 'Menu', 'Grid', 'Histogram',
  'Picture', 'Postcard', 'Ticket', 'TrendCharts', 'View', 'Warning',
  // AI 检测用 Aim（准星）：比 Cpu 更直观地表达「识别目标」，
  // 而 Cpu 在运维人员眼里是「处理器负载」，容易误解成性能监控。
  'Aim'
]

/**
 * 页面清单种子。字段对应 gb_menu 的 M 行：name → menu_key（也是路由 name）、
 * group → parent_key、perm → perms。hidden 的页面不进侧栏，但照常注册路由。
 */
export const SEED_PAGES = [
  { name: 'dashboard', path: '/dashboard', component: 'views/DashboardView.vue', title: '总览', icon: 'Odometer', group: 'g-monitor', perm: 'device:read' },
  { name: 'live', path: '/live', component: 'views/LiveView.vue', title: '实时预览', icon: 'VideoCamera', group: 'g-monitor', perm: 'play:live' },
  { name: 'devices', path: '/devices', component: 'views/DeviceView.vue', title: '设备管理', icon: 'Monitor', group: 'g-resource', perm: 'device:read' },
  { name: 'channels', path: '/channels', component: 'views/ChannelView.vue', title: '通道管理', icon: 'List', group: 'g-resource', perm: 'channel:read' },
  { name: 'playback', path: '/playback', component: 'views/PlaybackView.vue', title: '设备录像', icon: 'Film', group: 'g-cloud', perm: 'play:record' },
  { name: 'cloud', path: '/cloud', component: 'views/CloudRecordChannelView.vue', title: '云端录像', icon: 'Cloudy', group: 'g-cloud', perm: 'cloud:read' },
  { name: 'cloud-detail', path: '/cloud/detail', component: 'views/CloudRecordDetailView.vue', title: '云端录像详情', group: 'g-cloud', perm: 'cloud:read', hidden: true },
  { name: 'cloud-detail-list', path: '/cloud/detail/list', component: 'views/CloudRecordListView.vue', title: '云端录像列表', group: 'g-cloud', perm: 'cloud:read', hidden: true },
  { name: 'cloud-plans', path: '/cloud/plans', component: 'views/CloudRecordPlanView.vue', title: '录像计划', group: 'g-cloud', perm: 'cloud:read', hidden: true },
  { name: 'alarms', path: '/alarms', component: 'views/AlarmView.vue', title: '报警管理', icon: 'Bell', group: 'g-ops', perm: 'alarm:read' },
  { name: 'vision', path: '/vision', component: 'views/VisionView.vue', title: 'AI 检测', icon: 'Aim', group: 'g-ops', perm: 'system:read' },
  { name: 'cascade', path: '/cascade', component: 'views/CascadeView.vue', title: '级联配置', icon: 'Share', group: 'g-ops', perm: 'cascade:read' },
  { name: 'users', path: '/users', component: 'views/UserView.vue', title: '用户管理', icon: 'User', group: 'g-system', perm: 'user:read' },
  { name: 'roles', path: '/roles', component: 'views/RoleView.vue', title: '角色权限', icon: 'Key', group: 'g-system', perm: 'user:read' },
  { name: 'menus', path: '/menus', component: 'views/MenuView.vue', title: '菜单管理', icon: 'Menu', group: 'g-system', perm: 'system:read' },
  { name: 'oplogs', path: '/oplogs', component: 'views/OpLogView.vue', title: '操作日志', icon: 'Document', group: 'g-system', perm: 'oplog:read' },
  { name: 'settings', path: '/settings', component: 'views/SettingsView.vue', title: '系统设置', icon: 'Setting', group: 'g-system', perm: 'system:read' }
]

/**
 * 种子版本。只在 SEED_PAGES / DEFAULT_GROUP_TITLES 增删条目时改：版本一致时
 * /menu/sync 是空操作，不会把运维删掉的行种回来。改标题/图标/排序请在菜单管理页操作。
 */
export const MENU_SEED_VERSION = '2026-11-10.1'

/**
 * 把种子摊平成 gb_menu 行（目录 + 菜单，不含按钮）。
 * sortOrder 按同级从 1 编号：界面直接显示这个数字，全局序号会让人以为排序跳着用。
 */
export function buildSeedItems() {
  const dirs = Object.entries(DEFAULT_GROUP_TITLES).map(([code, title], idx) => ({
    menuKey: code,
    parentKey: null,
    menuType: 'D',
    title,
    sortOrder: idx + 1
  }))
  const counters = new Map()
  const pages = SEED_PAGES.map((p) => {
    const n = (counters.get(p.group) || 0) + 1
    counters.set(p.group, n)
    return {
      menuKey: p.name,
      parentKey: p.group,
      menuType: 'M',
      title: p.title,
      icon: p.icon || null,
      sortOrder: n,
      path: p.path,
      component: p.component,
      perms: p.perm || null,
      hidden: !!p.hidden
    }
  })
  return [...dirs, ...pages]
}

/**
 * 菜单管理页里可选的组件路径。
 *
 * 页面按 pure-admin 约定放在 src/views 下，这里产出 `views/XxxView.vue` 形式，
 * 与库里 gb_menu.component 的存法一致（`@/router/utils.ts` 的 addAsyncRoutes 靠
 * 字符串包含关系去 import.meta.glob 里匹配组件）。
 */
const viewModules = import.meta.glob('../../views/*.vue')

/** 分组目录用的透明壳，不是可选业务页面，别出现在「菜单管理」的组件下拉里。 */
const NON_PAGE_COMPONENT = 'views/RouteShell.vue'

export const COMPONENT_OPTIONS = Object.keys(viewModules)
  .map((k) => k.replace('../../', ''))
  .filter((k) => k !== NON_PAGE_COMPONENT)
  .sort()
