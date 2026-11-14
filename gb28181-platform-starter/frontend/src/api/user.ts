/**
 * 登录与当前用户（pure-admin `store/modules/user.ts` 的数据源）。
 *
 * <p>后端是 Sa-Token：`POST /api/v1/login` 直接返回一个不透明 token 字符串，
 * 没有 JWT 那套 `accessToken / refreshToken / expires` 三段式，所以这里没有
 * `refreshTokenApi` —— token 续期由 Sa-Token 的滑动过期在服务端完成，
 * 前端只在收到 401 时退回登录页（见 `@biz/api/http.ts`）。
 *
 * <p>鉴权头沿用平台约定 `Authorization: <token>`（不带 `Bearer` 前缀）。
 */
import { get, post } from "@biz/api/http";

/** `/login` 的返回（后端 TokenVo）。 */
export type TokenResult = {
  token: string;
  authToken?: string;
  cookieToken?: string;
  urlToken?: string;
  /** 有效期（秒）。 */
  tokenTimeout?: number;
};

/** `/userinfo` 的返回（后端 UserInfoVo）。 */
export type UserInfoResult = {
  id: number;
  /** 登录名。 */
  name: string;
  nickname?: string;
  roles?: Array<string>;
  /** 角色展开后的权限点并集。 */
  permissions?: Array<string>;
  phoneNumber?: string;
  email?: string;
  description?: string;
  hasAllChannel?: boolean;
  loginAt?: string;
};

/** `pure-admin` 的 user store 只认 `{ success, data }` 信封。 */
export type UserResult = {
  success: boolean;
  data: TokenResult;
};

/** 登录（账号密码 + 可选滑块凭证；带 `X-Api-Key` 时可省略 body）。 */
export const getLogin = (data?: object): Promise<TokenResult> =>
  post("/login", null, data) as unknown as Promise<TokenResult>;

/** 当前登录用户信息。 */
export const getUserInfo = (): Promise<UserInfoResult> =>
  get("/userinfo") as unknown as Promise<UserInfoResult>;

/** 登出（Sa-Token 注销当前 token）。 */
export const getLogout = (): Promise<any> => get("/logout");
