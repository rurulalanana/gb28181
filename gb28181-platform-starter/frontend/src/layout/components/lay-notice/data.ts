import { $t } from "@/plugins/i18n";

export interface ListItem {
  avatar: string;
  title: string;
  datetime: string;
  type: string;
  description: string;
  status?: "primary" | "success" | "warning" | "info" | "danger";
  extra?: string;
}

export interface TabItem {
  key: string;
  name: string;
  list: ListItem[];
  emptyText: string;
}

/**
 * 顶栏通知中心的三个页签。
 *
 * <p>三个 `list` 都是空的，这是**刻意**的：框架示例里塞的是「7 条演示通知」，
 * 直接搬过来会在一个真实的监控平台上显示根本不存在的待办，值班人员会去找。
 * 等后端有了报警推送（`/alarm/list` 的未确认项）再把真实数据填进来 ——
 * 结构保持不变，`LayNotice` 会自动渲染空态。
 */
export const noticesData: TabItem[] = [
  {
    key: "1",
    name: $t("status.pureNotify"),
    list: [],
    emptyText: $t("status.pureNoNotify")
  },
  {
    key: "2",
    name: $t("status.pureMessage"),
    list: [],
    emptyText: $t("status.pureNoMessage")
  },
  {
    key: "3",
    name: $t("status.pureTodo"),
    list: [],
    emptyText: $t("status.pureNoTodo")
  }
];
