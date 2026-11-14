import { ref } from "vue";
import { useTimeoutFn } from "@vueuse/core";
import type { DialogOptions } from "./type";

/**
 * ReDialog 的共享状态与操作。
 *
 * <p>从 `index.ts` 拆出来是为了**断开循环依赖**：原来是
 * `index.ts -> index.vue -> index.ts`，而 `unplugin-vue-components` 会把模板里的
 * `<ReDialog />` 直接解析到 `index.vue`，于是求值从 `index.vue` 开始 ——
 * 它回头 import `index.ts`，`index.ts` 再去取 `index.vue` 的默认导出时
 * 那个导出还没初始化，构建后表现为
 * `Uncaught ReferenceError: Cannot access 'reDialog' before initialization`（整站白屏）。
 * 状态挪到不含 SFC 的模块后，两条路径都不再互相等待。
 */
export const dialogStore = ref<Array<DialogOptions>>([]);

/** 打开弹框 */
export const addDialog = (options: DialogOptions) => {
  const open = () =>
    dialogStore.value.push(Object.assign(options, { visible: true }));
  if (options?.openDelay) {
    useTimeoutFn(() => {
      open();
    }, options.openDelay);
  } else {
    open();
  }
};

/** 关闭弹框 */
export const closeDialog = (
  options: DialogOptions,
  index: number,
  args?: any
) => {
  dialogStore.value[index].visible = false;
  options.closeCallBack && options.closeCallBack({ options, index, args });

  const closeDelay = options?.closeDelay ?? 200;
  useTimeoutFn(() => {
    dialogStore.value.splice(index, 1);
  }, closeDelay);
};

/** 更改弹框自身属性值 */
export const updateDialog = (value: any, key = "title", index = 0) => {
  dialogStore.value[index][key] = value;
};

/** 关闭所有弹框 */
export const closeAllDialog = () => {
  dialogStore.value = [];
};
