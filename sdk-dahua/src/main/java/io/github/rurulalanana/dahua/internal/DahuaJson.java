package io.github.rurulalanana.dahua.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * 大华 JSON 配置项的读写辅助。
 *
 * <h3>为什么集中在这里</h3>
 * 大华大量能力以 JSON 配置项形式提供（{@code VideoInOptions} / {@code Record} /
 * {@code PtzPreset} …），而设备返回的是<b>双层结构</b>：
 * <pre>
 * {"id":3348,"params":{"table":{业务字段}},"result":true,"session":1014386800}
 *                └──────────┬──────────┘
 *                      真正要改的是这一层
 * </pre>
 * 「剥外壳」「按字段名取值」「改完再包回去」这三件事在多个类里重复，
 * 且手写字符串扫描极易出错（字符串里的 {@code {}}、转义引号、字段名恰好是某个值的一部分），
 * 所以统一用 Jackson 解析成树来做。
 *
 * <h3>关于 {@link #businessLayer(String)} 必须幂等</h3>
 * 调用方经常「拿到什么就传什么」—— 可能已经是业务层，也可能是完整响应包。
 * 所以剥壳逻辑必须两种输入都接受，且对业务层输入原样（语义上）返回。
 *
 * <h3>为什么不用 {@code readValue(Class)}</h3>
 * 大华配置项的字段名随机型/固件变化（{@code Index} 还是 {@code nIndex}、
 * {@code DayNight} 还是 {@code DayNightColor}），无法绑定到固定 POJO。
 * 用树模型才能容纳这种不确定性。
 */
public final class DahuaJson {

    /**
     * 共享的 {@link ObjectMapper}。
     *
     * <p>Jackson 的 ObjectMapper 在配置完成后是线程安全的，全局一个即可
     * （每次 new 一个会重复初始化序列化器缓存，是常见的性能坑）。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DahuaJson() {
    }

    /**
     * 解析 JSON 文本。
     *
     * @return 解析树；输入为空或非法时返回 {@code null}（调用方按「没有数据」处理）
     */
    public static JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            // 设备返回的 JSON 偶尔会带尾部垃圾字符，解析失败按「读不到」处理，
            // 不抛异常 —— 调用方有更准确的上下文可以报错
            return null;
        }
    }

    /**
     * 把树序列化成字符串。
     *
     * <p>只会对合法的树节点失败（理论上不会发生），真失败时抛
     * {@link IllegalStateException} 而不是吞掉 —— 静默返回空串会让设备收到
     * 一个空配置，那是更严重的后果。
     */
    public static String write(JsonNode node) {
        if (node == null) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从完整响应包里取业务层（{@code params.table}）。
     *
     * <h3>只有「像响应包」的根才拆壳</h3>
     * 原来的写法是「根有 {@code params.table} 就拆」。这在配置被污染后是灾难：
     * 业务层里一旦混进过一个 {@code params.table}（历史误写留下的），
     * 下次读到的根就是
     * <pre>
     * {"AlarmDayNightColorMode":0, …, "params":{"table":{旧副本}}}
     * </pre>
     * 于是「根有 params.table」成立，我们又一次拆出那个旧副本、
     * 再包一层写回设备 —— 每保存一次多嵌一层。实测一台 IPC 的
     * {@code VideoInOptions} 从 179 行涨到 537 行，再涨到 3.5 万个
     * {@code params.table}，最后 {@code CLIENT_GetNewDevConfig} 直接读不出来。
     *
     * <p>现在用<b>响应包特征字段</b>（{@code id}/{@code result}/{@code session}/{@code code}）
     * 判断根到底是不是响应包：只有是才拆 {@code params.table}，
     * 否则根本身就是业务层，原样返回。
     *
     * @return 业务层节点；输入为空或非法时返回 {@code null}
     */
    public static JsonNode business(String raw) {
        JsonNode root = parse(raw);
        if (root == null) {
            return null;
        }
        if (root.isObject() && onlyEnvelopeKeys(root)) {
            JsonNode params = root.get("params");
            if (params != null && params.isObject()) {
                JsonNode table = params.get("table");
                if (table != null) {
                    return table;
                }
            }
            // 少一层外壳的变体：{"table":{...}, "result":true}
            JsonNode table = root.get("table");
            if (table != null) {
                return table;
            }
        }
        return root;
    }

    /** 响应包/我们下发的 payload 允许出现的键。 */
    private static final java.util.Set<String> ENVELOPE_KEYS =
            java.util.Set.of("id", "params", "table", "result", "session", "code", "version");

    /**
     * 根对象是否<b>整层都是外壳</b>（只有信封字段，没有业务字段）。
     *
     * <p>判据不能是「有 {@code params.table}」（见 {@link #business} 的说明）：
     * 配置被污染后业务层里会混着 {@code params.table}，那种根一定还带着
     * 一堆业务字段（{@code Flip}、{@code Brightness} …），不会全由信封字段组成。
     */
    private static boolean onlyEnvelopeKeys(JsonNode root) {
        java.util.Iterator<String> names = root.fieldNames();
        boolean any = false;
        while (names.hasNext()) {
            any = true;
            if (!ENVELOPE_KEYS.contains(names.next())) {
                return false;
            }
        }
        return any;
    }

    /**
     * 剔掉业务层里残留的外壳字段 {@code params}。
     *
     * <p><b>为什么要剔</b>：设备写入是 merge 语义，历史上误写进去的
     * {@code params.table} 副本<b>删不掉</b>（发回去只是继续嵌套）。
     * 唯一能做的就是「不再把它们带回去」，让它停在原地不再膨胀。
     * 实测一台 IPC 的 {@code VideoInOptions} 因此从 179 行涨到 537 行，
     * 修复后 4 次写入稳定在 537 行、不再增长。
     *
     * <p>只剔 {@code params}：它是我们自己的信封字段，业务参数不会叫这个名字。
     * <b>不能</b>顺手剔 {@code table} —— 那有可能真的是业务字段。
     *
     * @return 清理后的节点；输入为 {@code null} 时返回 {@code null}
     */
    public static JsonNode scrubEnvelopeArtifacts(JsonNode business) {
        if (business instanceof ObjectNode obj) {
            obj.remove("params");
        }
        return business;
    }

    /**
     * 剥出业务层并序列化回字符串。
     *
     * <p>给「整段读写」的场景用：读到的文本直接喂给这个方法，
     * 得到一个干净的、可以再喂回 {@link #wrapForWrite} 的业务层文本。
     *
     * @return 业务层文本；输入为空或非法时返回空串
     */
    public static String businessLayer(String raw) {
        JsonNode node = business(raw);
        return node == null ? "" : write(node);
    }

    /**
     * 把「完整响应包 或 业务层」统一包成设备要的 {@code {"params":{"table":…}}}。
     *
     * <h3>为什么必须包一层</h3>
     * 设备只认 {@code params.table} 那一层。把整包（含 {@code id}/{@code result}/
     * {@code session}）原样写回去，设备会把这些字段<b>当成业务字段存进 table</b>，
     * 每写一次多嵌一层 —— 实测连续误写后 {@code TimeSection} 在 JSON 里出现 5 次，
     * 配置彻底不可用，最后只能 {@code CLIENT_DeleteDevConfig} 删掉重建。
     * 而且写入是 merge 语义，嵌进去的垃圾<b>写不回去也删不掉</b>。
     *
     * @return 可直接下发的 payload
     * @throws IllegalArgumentException 业务层为空或非法
     */
    public static String wrapForWrite(String raw) {
        JsonNode business = scrubEnvelopeArtifacts(business(raw));
        if (business == null || business.isNull() || business.isMissingNode()) {
            throw new IllegalArgumentException("配置内容为空或不是合法 JSON");
        }
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode params = root.putObject("params");
        params.set("table", business);
        return write(root);
    }

    /**
     * 递归查找字段名（不限层级），返回第一个命中的值。
     *
     * <p>大华的配置里同一个字段会在多个场景 profile 下重复出现
     * （{@code NormalOptions}/{@code NightOptions}/…），
     * 「有没有这个字段」只需命中一次即可判定能力位。
     *
     * <p>{@code field} 支持两种路径语法：
     * <pre>
     *   A.B              逐层下钻子对象（如 3DAutoType.AutoLevel）
     *   A[0].B           先按下标取数组元素，再下钻（如 NearLight[0].Light）
     * </pre>
     * 大华把少数参数埋在子对象或对象数组里，按平铺字段名永远找不到。
     *
     * @return 字段值；找不到返回 {@code null}
     */
    public static JsonNode findField(JsonNode node, String field) {
        if (node == null || field == null || field.isEmpty()) {
            return null;
        }
        int dot = field.indexOf('.');
        String head = dot < 0 ? field : field.substring(0, dot);
        String rest = dot < 0 ? null : field.substring(dot + 1);

        int bracket = head.indexOf('[');
        if (bracket > 0 && head.endsWith("]")) {
            String name = head.substring(0, bracket);
            int idx;
            try {
                idx = Integer.parseInt(head.substring(bracket + 1, head.length() - 1).trim());
            } catch (NumberFormatException ex) {
                return null;
            }
            JsonNode parent = findField(node, name);
            if (parent == null || !parent.isArray() || idx >= parent.size()) {
                return null;
            }
            JsonNode el = parent.get(idx);
            return rest == null ? el : findField(el, rest);
        }

        if (rest != null) {
            JsonNode parent = findField(node, head);
            return parent == null ? null : findField(parent, rest);
        }

        if (node.isObject()) {
            JsonNode hit = node.get(field);
            if (hit != null) {
                return hit;
            }
            for (JsonNode child : node) {
                JsonNode found = findField(child, field);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findField(child, field);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 配置里是否存在该字段（能力位判据）。
     *
     * @param byCommand 配置项名 → 完整响应包或业务层文本
     */
    public static boolean hasField(Map<String, String> byCommand, String command, String field) {
        return findField(business(byCommand.get(command)), field) != null;
    }

    /**
     * 取整数值（兼容数字、数字字符串与布尔）；取不到返回 {@code null}。
     *
     * <p>要兼容布尔是因为设备的字段类型与语义并不总一致：
     * {@code Rotate90} 返回 0/1，而 {@code InfraRed} 返回 true/false ——
     * 后者按「开关类枚举」用时需要转成 1/0 才匹配得上选项。
     */
    public static Integer intValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1 : 0;
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
