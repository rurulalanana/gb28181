package io.github.rurulalanana.dahua;

import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大华「设备主动注册」会话表的内存实现。
 *
 * <h3>为什么是内存，且必须如此</h3>
 * SERVER 模式下设备换网、重新拨号后会连到不同的地址，注册记录随之失效。
 * 把它落库会带来三个麻烦，且没有任何收益：
 * <ol>
 *   <li>心跳几十秒一次，写库成热点；</li>
 *   <li>进程重启后地址already 过期，读出来反而会去连一个错地址；</li>
 *   <li>设备重连时会立刻重新上报，本来就不需要「记住」。</li>
 * </ol>
 * 因此这里只做进程内缓存，重启后靠设备下一次注册恢复。
 *
 * <h3>为什么要 pruneExpired</h3>
 * 设备断电/断网时不会发「我走了」，只能靠超时判定。
 * 不清的话 {@link #list()} 会一直显示一堆已离线的设备，
 * 而平台会拿这些僵尸地址去连、每次都要等满超时。
 *
 * <h3>为什么不能只看时间戳</h3>
 * 大华设备主动注册后<b>不会周期性重发注册报文</b> —— 它建立一条长连接，
 * 由平台在这条连接上登录，之后靠 TCP 连接本身维持（心跳在协议层，
 * 不回调到 {@code NET_DVR_SERIAL_RETURN}）。
 * 所以「{@code lastSeenAt} 超过阈值」只说明<b>没有新的注册事件</b>，
 * 不等于设备离线。
 *
 * <p>踩过的坑：只按时间戳清理时，设备注册成功、登录成功、报警也能收，
 * 但 180 秒后注册记录被当僵尸删掉，{@code contextOf} 回退到直连 37777 端口，
 * NAT 后不可达 → 设备被判离线 → 界面一直不在线。
 *
 * <p>因此引入 {@link Liveness}：只要有活跃登录会话就保活，
 * 不看时间戳。
 */
public class DahuaRegisterTable implements DeviceRegisterTable {

    private static final Logger log = LoggerFactory.getLogger(DahuaRegisterTable.class);

    /**
     * 「设备是否真的还活着」的额外判据。
     *
     * <p>注册表自己不碰 SDK（那会引入循环依赖），由 {@code DahuaRegisterListener}
     * 所在的模块注入。返回 {@code true} 时该记录<b>不会被过期清理</b>。
     */
    public interface Liveness {
        boolean alive(String serial);
    }

    /** 默认不保活（只用时间戳）。 */
    private volatile Liveness liveness;

    /** 注入活跃会话判定；传 {@code null} 退回纯时间戳语义。 */
    public void setLiveness(Liveness liveness) {
        this.liveness = liveness;
    }

    /**
     * 记录是否已失效。
     *
     * <p>先问 {@link Liveness}：会话还在就是活着，时间戳再旧也不清。
     */
    private boolean isStale(String serial, RegisteredDevice d, long now) {
        Liveness probe = liveness;
        if (probe != null) {
            try {
                if (probe.alive(serial)) {
                    return false;
                }
            } catch (RuntimeException e) {
                // 判定本身出错不能把记录清了：宁可多留一会儿，也不能误删在线设备
                log.debug("大华注册表保活判定异常（按存活处理）: sn={} {}", serial, e.toString());
                return false;
            }
        }
        return now - d.lastSeenAt() > expireMs;
    }

    /** 超过这个时间没有任何注册/心跳就判定失效（毫秒）。 */
    private final long expireMs;
    private final Map<String, RegisteredDevice> bySerial = new ConcurrentHashMap<>();

    /**
     * @param expireMs 失效阈值。大华设备的注册心跳间隔通常是 30~60 秒，
     *                 取 3 倍留出网络抖动与设备卡顿的余量
     */
    public DahuaRegisterTable(long expireMs) {
        this.expireMs = expireMs <= 0 ? 180_000L : expireMs;
    }

    public DahuaRegisterTable() {
        this(180_000L);
    }

    @Override
    public DeviceKind kind() {
        return DeviceKind.DAHUA;
    }

    @Override
    public void register(String serial, String ip, int port, String extra) {
        if (serial == null || serial.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        bySerial.compute(serial, (k, old) -> {
            long first = old == null ? now : old.registeredAt();
            if (old == null) {
                log.info("大华设备主动注册: sn={} ip={}:{}", serial, ip, port);
            } else if (!eq(old.ip(), ip)) {
                // IP 变了必须显式记一笔：这类问题在现场最难查（设备在线但连不上）
                log.info("大华设备注册地址变更: sn={} {}:{} -> {}:{}", serial,
                        old.ip(), old.port(), ip, port);
            } else if (old.port() != port) {
                /*
                 * 只有源端口变了：这是设备重新建了一条 TCP 连接。
                 *
                 * 正常情况下不该频繁发生 —— 只要平台在连接上登录过，设备就会保持这条连接。
                 * 只在验证超时（平台没登录）时才会每分钟重连一次，那时这里是 DEBUG，
                 * 免得刷屏；真要排查用 DEBUG 或看「大华设备主动注册」那条 INFO 的间隔。
                 */
                log.debug("大华设备重新注册（源端口变化）: sn={} {}:{} -> :{}",
                        serial, ip, old.port(), port);
            }
            return new RegisteredDevice(serial, ip, port, first, now, extra);
        });
    }

    @Override
    public RegisteredDevice lookup(String serial) {
        if (serial == null || serial.isBlank()) {
            return null;
        }
        String key = serial.trim();
        RegisteredDevice d = bySerial.get(key);
        if (d == null) {
            return null;
        }
        if (isStale(key, d, System.currentTimeMillis())) {
            // 已过期就不返回：返回一个过期的地址只会让调用方去连死地址
            bySerial.remove(key, d);
            log.info("大华设备注册已过期并移除: sn={} 最后心跳 {} 秒前",
                    serial, (System.currentTimeMillis() - d.lastSeenAt()) / 1000);
            return null;
        }
        return d;
    }

    @Override
    public List<RegisteredDevice> list() {
        pruneExpired();
        return new ArrayList<>(bySerial.values());
    }

    @Override
    public void unregister(String serial) {
        if (serial == null) {
            return;
        }
        if (bySerial.remove(serial.trim()) != null) {
            log.info("大华设备已注销注册: sn={}", serial);
        }
    }

    @Override
    public int pruneExpired() {
        long now = System.currentTimeMillis();
        int[] removed = {0};
        bySerial.entrySet().removeIf(e -> {
            boolean stale = isStale(e.getKey(), e.getValue(), now);
            if (stale) {
                removed[0]++;
                log.info("清理过期的大华注册记录: sn={}（{} 秒无心跳）",
                        e.getKey(), (now - e.getValue().lastSeenAt()) / 1000);
            }
            return stale;
        });
        return removed[0];
    }

    /** 当前记录数（诊断用）。 */
    public int size() {
        return bySerial.size();
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
