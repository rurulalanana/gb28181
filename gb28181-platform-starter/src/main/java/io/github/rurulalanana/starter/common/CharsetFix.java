package io.github.rurulalanana.starter.common;

import java.nio.charset.Charset;

/**
 * GBK 字节被误按 UTF-8 解码后的乱码修复。标准 6.10 规定信令字符集为 GB 18030，设备实际多发 GBK；
 * {@code ManscdpXml.decode} 先试 UTF-8、仅当出现 {@code U+FFFD} 才回退 GBK。
 *
 * <p>盲区：<b>GBK 汉字的两字节常恰好是合法 UTF-8</b>。例如「平台」{@code C6 BD CC A8} 按 UTF-8
 * 解出 {@code U+01BD}(ƽ) + {@code U+0328}(̨)，全程无替换字符，永不回退 GBK。
 * 反解：把误解字符串按 UTF-8 编回字节、再用 GBK 解码，即可还原。
 */
public final class CharsetFix {

    private static final Charset GBK = Charset.forName("GBK");

    private CharsetFix() {
    }

    /**
     * 修复「GBK 字节被当 UTF-8 解码」的乱码，只在高置信度时改写：
     * 含非 ASCII；按 UTF-8 编回字节后用 GBK 解码不产生 {@code U+FFFD}；
     * 还原结果不同、不再带乱码特征、不含异常字符且至少含一个汉字。
     */
    public static String repair(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (isAscii(text) || !looksLikeMojibake(text)) {
            return text;
        }
        try {
            byte[] raw = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String gbk = new String(raw, GBK);
            // GBK 解码出现替换字符 → 原始字节不是合法 GBK，放弃
            if (gbk.indexOf('\uFFFD') >= 0) {
                return text;
            }
            // 还原后仍带乱码特征 → 不是这种乱码
            if (looksLikeMojibake(gbk)) {
                return text;
            }
            // 还原出 CJK 兼容形式等异常字符 → 原文并非 GBK 字节
            if (hasGarbage(gbk)) {
                return text;
            }
            // 还原后应至少含一个中日韩汉字，否则可能误伤（如法语、越南语等合法拉丁文本）
            if (!containsCjk(gbk)) {
                return text;
            }
            return gbk;
        } catch (Exception ignore) {
            return text;
        }
    }

    /**
     * 是否具备「GBK 被误读为 UTF-8」的特征。只有 GBK 双字节恰好构成合法 UTF-8 两字节序列
     * 时才会漏检，此时结果落在 {@code U+0080~U+07FF}。上界取 {@code U+036F} 而非 {@code U+07FF}，
     * 是为了排除整串落在误解码区间的合法外文：{@code U+0370~U+03FF} 希腊字母、
     * {@code U+0400~U+04FF} 西里尔字母会被误改成汉字。
     * 另要求全部非 ASCII 都可疑、至少 2 个且为偶数：一个 GBK 汉字误解后必为 2 字符，
     * 这排除 {@code Café}、{@code Müller} 这类单个重音字母的合法名称。
     */
    private static boolean looksLikeMojibake(String text) {
        int nonAscii = 0;
        int suspicious = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 0x7F) {
                continue;
            }
            nonAscii++;
            if (c <= 0x036F) {
                suspicious++;
            }
        }
        return suspicious >= 2 && suspicious == nonAscii && (suspicious & 1) == 0;
    }

    /** 还原结果是否含二次乱码痕迹：非 GBK 文本按 GBK 解码常落在 CJK 兼容区（如 {@code U+FE41 ﹁}）。 */
    private static boolean hasGarbage(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xFE30 && c <= 0xFE6F) {
                return true;
            }
        }
        return false;
    }

    /** 是否至少含一个中日韩统一表意文字。 */
    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    /**
     * 修复<b>相反方向</b>的乱码：<b>UTF-8 字节被误按 GBK 解码</b>。
     *
     * <p>{@link #repair} 处理「GBK 字节被当 UTF-8 解」，这里是它的镜像。
     * 两者来源不同：
     * <ul>
     *   <li>{@code repair}：国标信令（MANSCDP XML）由设备以 GBK 发出</li>
     *   <li>{@code repairUtf8AsGbk}：大华配置项（{@code PtzTour} 等）里的字符串，
     *       设备存的是 UTF-8 字节，却被一条按 GBK 处理的路径读成了乱码</li>
     * </ul>
     *
     * <p>实例：设备 {@code PtzTour[0][2].Name} 实际是「巡航群組3」，
     * 平台读到的却是「宸¤埅缇ょ祫3」—— 正是 UTF-8 字节 {@code E5 B7 A1 ...}
     * 被按 GBK 解的结果。把乱码按 <b>GBK 编回字节、再用 UTF-8 解</b>，即可还原。
     *
     * <h3>为什么必须多重设防</h3>
     * 「按 GBK 编回去」对任何中文都成立，所以不能只看能不能编。
     * 判定为这种乱码必须同时满足四条：
     * <ol>
     *   <li>含非 ASCII，且至少有一个非 ASCII 字符<b>不在</b> CJK 统一表意区 ——
     *       纯中文名（如「巡航群組3」）因此不会被误判，
     *       而 UTF-8→GBK 的乱码必带 {@code ¤}、日文假名等在区块外的字符</li>
     *   <li>按 GBK 能原样编回去（编码会丢字符说明原文不是这种乱码）</li>
     *   <li>编出的字节能通过<b>严格</b> UTF-8 校验 —— 用 REPORT 而不是
     *       {@code new String(bytes, UTF_8)}，后者会把非法字节静默替换成
     *       {@code U+FFFD}，永远"成功"，判不出真假</li>
     *   <li>还原结果含汉字，且确实与原文不同</li>
     * </ol>
     *
     * <p>包级可见是为了单测能直接喂真实乱码校验。
     *
     * @return 还原后的文本；判不准时<b>原样返回</b>（宁可不修，也不能改错）
     */
    public static String repairUtf8AsGbk(String text) {
        if (text == null || text.isEmpty() || isAscii(text)) {
            return text;
        }
        // 条件 1：必须存在 CJK 之外的字符，否则纯中文名会被误判
        if (!hasNonCjkNonAscii(text)) {
            return text;
        }
        try {
            byte[] raw = text.getBytes(GBK);
            // 条件 2：GBK 无法完整表示 → 原文不是这种乱码（不可映射字符会变成 '?'）
            if (!new String(raw, GBK).equals(text)) {
                return text;
            }
            // 条件 3：严格 UTF-8 校验
            String fixed = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw))
                    .toString();
            // 条件 4：还原结果含汉字且确实变了
            if (fixed.equals(text) || !containsCjk(fixed)) {
                return text;
            }
            return fixed;
        } catch (Exception ignore) {
            return text;
        }
    }

    /** 是否至少含一个非 ASCII 且<b>不在</b> CJK 统一表意区的字符（UTF-8 被误读为 GBK 的特征）。 */
    private static boolean hasNonCjkNonAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7F && (c < 0x4E00 || c > 0x9FFF)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }
}
