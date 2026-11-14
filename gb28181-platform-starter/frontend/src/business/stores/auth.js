import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { systemApi } from '@biz/api'
import { fetchMenuRows } from '@/api/routes'
import { resetRouter } from '@/router'
import { initRouter } from '@/router/utils'
import { hasPerms } from '@/utils/auth'
import { useUserStoreHook } from '@/store/modules/user'

/**
 * 业务侧的用户/权限/服务信息门面。
 *
 * <p>登录态、角色、权限点的**唯一真相**在 pure-admin 的 `store/modules/user` 里
 * （它同时负责 cookie 与 localStorage 的读写）；这里只做两件事：
 * <ul>
 *   <li>把框架的能力翻译成业务页面用惯的形状（`can` / `canAny` / `readonly` / `isAdmin`），
 *       22 个页面里的 44 处 `auth.can('device:write')` 因此一行都不用改；</li>
 *   <li>装业务专属的运行期数据：`serverInfo`（顶栏读数）与 `menuRows`（菜单管理页要编辑的原始行）。</li>
 * </ul>
 *
 * <p>不要再往这里加 token 字段或登录方法 —— 两份登录态一定会不同步。
 */
export const useAuthStore = defineStore('auth', () => {
  const userStore = useUserStoreHook()

  /** 服务信息（`/getserverinfo`）：设备数、通道数、推流数、SIP 编码等。 */
  const serverInfo = ref(null)
  /** `gb_menu` 全量行（D/M/B），菜单管理页直接编辑它。 */
  const menuRows = ref([])
  /** 最近一次 userinfo 的完整返回（页面里偶尔要看登录时间、手机号等）。 */
  const userInfo = ref(null)

  /** 兼容旧页面的 `auth.user`：登录名/昵称/角色/权限点。 */
  const user = computed(() => ({
    username: userStore.username,
    nickname: userStore.nickname,
    roles: userStore.roles,
    permissions: userStore.permissions
  }))

  const permissions = computed(() => new Set(userStore.permissions || []))

  /**
   * 是否具备某个权限点。只用于界面显隐：真正的边界永远在后端
   * （`WritePermissionInterceptor`）。
   *
   * @param {string} code 如 'device:write'
   * @returns {boolean} 没传 code 时返回 true（便于模板里的可选判断）
   */
  function can(code) {
    if (!code) return true
    // 走框架的 hasPerms：它认 `*:*:*` 通配（超管），自己写 Set.has 会把超管挡在外面
    return hasPerms(code)
  }

  /** 具备其中任意一项即可。 */
  function canAny(...codes) {
    const flat = codes.flat()
    if (!flat.length) return true
    return flat.some((c) => can(c))
  }

  /** 是否只读账号（没有任何修改类权限）：用于整页禁用，而不是逐个按钮判断。 */
  const readonly = computed(() => {
    const perms = userStore.permissions
    if (!perms) return false
    // 除 :read 之外都算改动能力（含按钮级的 :sync / :reset / :mark 等）
    return !perms.some((p) => !String(p).endsWith(':read'))
  })

  /**
   * 是否管理员。后端返回 roles:["admin"] 数组。
   * 不要用它控制业务按钮，用 `can()`：界面显隐取决于「有没有这项权限」，
   * 按身份判断会把「巡检员」这类自定义角色挡在功能之外。
   */
  function isAdmin() {
    return (userStore.roles || []).some(
      (r) => String(r).toLowerCase() === 'admin'
    )
  }

  /** 拉当前用户信息（角色/权限点已改时刷新界面用）。 */
  async function loadUser() {
    userInfo.value = await userStore.fetchUserInfo()
    return userInfo.value
  }

  /**
   * 重新拉菜单结构并重建动态路由（「菜单管理」页保存后调用）。
   *
   * <p>必须 `resetRouter()` 再 `initRouter()`，不能只追加：删掉一个菜单行后，
   * 只追加的话旧路由会一直留着，侧栏也还在 —— 表现为「删了没反应」。
   */
  async function loadMenuRows() {
    menuRows.value = (await fetchMenuRows())?.items || []
    resetRouter()
    await initRouter()
    return menuRows.value
  }

  async function loadServerInfo() {
    serverInfo.value = await systemApi.serverinfo()
    return serverInfo.value
  }

  /** 登出：交给框架 —— 它会注销 Sa-Token、清 cookie/localStorage、重置路由、回登录页。 */
  async function logout() {
    await userStore.logOut()
  }

  return {
    user,
    userInfo,
    serverInfo,
    menuRows,
    permissions,
    readonly,
    can,
    canAny,
    isAdmin,
    loadUser,
    loadMenuRows,
    loadServerInfo,
    logout
  }
})
