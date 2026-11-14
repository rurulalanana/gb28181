/**
 * 国际化。
 *
 * <p>语言只有 `zh` / `en` 两个键（与框架的 `$storage.locale.locale` 对齐），
 * 词条来自根目录 `locales/*.yaml`，由 `@intlify/unplugin-vue-i18n` 在构建期编译。
 *
 * <p>`transformI18n` 是本项目的关键小工具：框架侧栏、面包屑、标签页、搜索都用它渲染
 * 路由 `meta.title`，而 `meta.title` 有两种来源 ——
 * <ul>
 *   <li>框架静态路由：`$t("menus.pureLogin")` 这种<b>词条键</b>，要翻译；</li>
 *   <li>`gb_menu` 动态路由：库里存的中文，如「总览」「AI 检测」，<b>原样显示</b>。</li>
 * </ul>
 * 所以判据不能是「含点号就当键」—— 标题里带点（`v1.2 通道`）会被吞成 undefined，
 * 侧栏直接变空白。这里用 `te()` 实际查一次词条是否存在，存在才翻译。
 */
import { type I18n, createI18n } from "vue-i18n";
import type { App, WritableComputedRef } from "vue";
import { storageLocal } from "@pureadmin/utils";
import { responsiveStorageNameSpace } from "@/config";

/** locales/*.yaml → { "zh-CN": {...}, en: {...} }。 */
const modules = import.meta.glob("../../locales/*.y(a)?ml", { eager: true });

function siphonI18n(name: string) {
  const hit = Object.entries(modules).find(
    ([key]) => key.match(/([A-Za-z0-9-_]+)\./i)?.[1] === name
  );
  return (hit?.[1] as any)?.default ?? {};
}

export const localesConfigs = {
  zh: siphonI18n("zh-CN"),
  en: siphonI18n("en")
};

type LocaleName = "zh" | "en";

/** 与 `injectResponsiveStorage` 用同一个 localStorage 键，刷新后语言不丢。 */
function readLocale(): LocaleName {
  const saved = storageLocal().getItem<{ locale?: string }>(
    `${responsiveStorageNameSpace()}locale`
  )?.locale;
  return saved === "en" ? "en" : "zh";
}

/** legacy:false 下 `locale` 是 WritableComputedRef；避开 vue-i18n 的联合类型声明。 */
const localeRef = () => i18n.global.locale as WritableComputedRef<string>;

export const i18n: I18n = createI18n({
  legacy: false,
  locale: readLocale(),
  fallbackLocale: "zh",
  messages: localesConfigs
});

/**
 * 国际化转换（词条键 → 文案；普通中文原样返回）。
 *
 * @param message 词条键、`{zh, en}` 对象，或已经是成品文案的普通字符串
 */
export function transformI18n(message: any = ""): string {
  if (!message) return "";
  const locale = localeRef().value;
  if (typeof message === "object") {
    return message[locale] ?? message.zh ?? message["zh-CN"] ?? "";
  }
  const key = String(message);
  // 只对「像词条键」的串查词条：纯中文标题匹配不上这个正则，直接原样返回
  if (/^[A-Za-z][A-Za-z0-9_-]*(\.[A-Za-z0-9_-]+)*$/.test(key)) {
    if (i18n.global.te(key, locale)) {
      // legacy:false 时 global.t 是 Composer 的重载，联合类型签名 TS 认不出来
      return (i18n.global as any).t(key) as string;
    }
  }
  return key;
}

/** 供 i18n Ally 等工具做智能提示，运行期只负责把键原样带出去。 */
export const $t = (key: string) => key;

/** Vue 插件：`app.use(useI18n)`。 */
export function useI18n(app: App) {
  app.use(i18n);
  document.documentElement.lang = readLocale() === "en" ? "en" : "zh-CN";
}
