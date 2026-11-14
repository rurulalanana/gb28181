import { defineStore } from "pinia";
import {
  type userType,
  store,
  router,
  resetRouter,
  routerArrays,
  storageLocal
} from "../utils";
import {
  type TokenResult,
  type UserInfoResult,
  getLogin,
  getUserInfo,
  getLogout
} from "@/api/user";
import { useMultiTagsStoreHook } from "./multiTags";
import { type DataInfo, setToken, removeToken, userKey } from "@/utils/auth";

/** 登录接口返回的原始 token（Sa-Token 不透明串，无 refresh 机制）。 */
export type LoginPayload = TokenResult & {
  success: boolean;
};

export const useUserStore = defineStore("pure-user", {
  state: (): userType => ({
    // 头像
    avatar: storageLocal().getItem<DataInfo<number>>(userKey)?.avatar ?? "",
    // 用户名
    username: storageLocal().getItem<DataInfo<number>>(userKey)?.username ?? "",
    // 昵称
    nickname: storageLocal().getItem<DataInfo<number>>(userKey)?.nickname ?? "",
    // 页面级别权限
    roles: storageLocal().getItem<DataInfo<number>>(userKey)?.roles ?? [],
    // 按钮级别权限
    permissions:
      storageLocal().getItem<DataInfo<number>>(userKey)?.permissions ?? [],
    // 是否勾选了登录页的免登录
    isRemembered: false,
    // 登录页的免登录存储几天，默认7天
    loginDay: 7
  }),
  actions: {
    /** 存储头像 */
    SET_AVATAR(avatar: string) {
      this.avatar = avatar;
    },
    /** 存储用户名 */
    SET_USERNAME(username: string) {
      this.username = username;
    },
    /** 存储昵称 */
    SET_NICKNAME(nickname: string) {
      this.nickname = nickname;
    },
    /** 存储角色 */
    SET_ROLES(roles: Array<string>) {
      this.roles = roles;
    },
    /** 存储按钮级别权限 */
    SET_PERMS(permissions: Array<string>) {
      this.permissions = permissions;
    },
    /** 存储是否勾选了登录页的免登录 */
    SET_ISREMEMBERED(bool: boolean) {
      this.isRemembered = bool;
    },
    /** 设置登录页的免登录存储几天 */
    SET_LOGINDAY(value: number) {
      this.loginDay = Number(value);
    },
    /**
     * 登入。
     *
     * <p>顺序不能换：先落 token（后续所有请求靠它带 Authorization），
     * 再拉 userinfo（需要带 token 才拿得到权限点），最后把权限点写回 store——
     * 侧栏与动态路由都读 store 里的 permissions，缺这一步登录后菜单是空的。
     */
    async loginByUsername(data): Promise<LoginPayload> {
      const res = await getLogin(data);
      const accessToken = res?.token || "";
      if (!accessToken) {
        throw new Error("登录失败：服务端未返回 token");
      }
      // Sa-Token 的有效期由服务端滑动过期控制，这里只把 token 落 cookie。
      // expires 给 0 → 会话 cookie，浏览器关掉即失效；两者取先到者。
      setToken({
        accessToken,
        expires: 0 as any,
        refreshToken: ""
      });
      await this.fetchUserInfo(accessToken);
      return { ...res, success: true };
    },
    /**
     * 拉当前用户信息并写进 store + 本地缓存。
     *
     * <p>刷新页面后 store 是从 localStorage 恢复的，但角色/权限点可能已被管理员改过，
     * 所以进入系统时要再拉一次（见 `useAuthStore.loadUser`）。
     */
    async fetchUserInfo(accessToken?: string): Promise<UserInfoResult> {
      const info = await getUserInfo();
      const cached = storageLocal().getItem<DataInfo<number>>(userKey);
      setToken({
        accessToken: accessToken || cached?.accessToken || "",
        expires: (cached?.expires as any) ?? (0 as any),
        refreshToken: "",
        username: info?.name ?? "",
        nickname: info?.nickname || info?.name || "",
        roles: info?.roles ?? [],
        permissions: info?.permissions ?? []
      });
      return info;
    },
    /** 登出：先通知服务端注销 Sa-Token，再清本地状态与路由，最后回登录页。 */
    async logOut() {
      try {
        await getLogout();
      } catch {
        // 服务端注销失败（网络断开 / token 已过期）不能卡住用户退出，
        // 本地状态照清 —— 否则界面上「退不出去」比后端残留一个 token 更糟
      }
      this.username = "";
      this.nickname = "";
      this.roles = [];
      this.permissions = [];
      removeToken();
      useMultiTagsStoreHook().handleTags("equal", [...routerArrays]);
      resetRouter();
      router.push("/login");
    }
  }
});

export function useUserStoreHook() {
  return useUserStore(store);
}
