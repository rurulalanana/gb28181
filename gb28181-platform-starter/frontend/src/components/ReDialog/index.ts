import reDialog from "./index.vue";
import { withInstall } from "@pureadmin/utils";
import type {
  EventType,
  ArgsType,
  DialogProps,
  ButtonProps,
  DialogOptions
} from "./type";

/** 千万别忘了在 App.vue 里引入并注册，放心注册，不使用 `addDialog` 调用就不会被挂载 */
const ReDialog = withInstall(reDialog);

export type { EventType, ArgsType, DialogProps, ButtonProps, DialogOptions };
export { ReDialog };
// 状态与操作放在 ./core：index.ts 会 import index.vue，index.vue 若再回头 import
// index.ts 就是循环依赖，取默认导出时会撞 TDZ（详见 core.ts 的注释）
export {
  dialogStore,
  addDialog,
  closeDialog,
  updateDialog,
  closeAllDialog
} from "./core";
