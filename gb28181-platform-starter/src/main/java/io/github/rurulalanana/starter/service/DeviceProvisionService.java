package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.gb28181.sip.manscdp.CatalogItem;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.deviceaccess.DeviceAccessProvider;

import io.github.rurulalanana.deviceaccess.DeviceContext;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DeviceRegisterTable;
import io.github.rurulalanana.deviceaccess.ConnectMode;
import io.github.rurulalanana.starter.entity.GbDevice;
import io.github.rurulalanana.starter.mapper.GbDeviceMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 非国标设备（SDK 直连）的纳管与目录同步。
 *
 * <h3>为什么单独一个服务，而不是塞进 DeviceService</h3>
 * {@code DeviceService} 的入口是 <b>SIP 事件</b>（注册/心跳/目录应答），
 * 数据是设备自己推上来的；而 SDK 直连是<b>平台主动去问</b>，
 * 触发方式、错误语义、重试策略都不同。混在一起会让
 * 「设备主动注册」和「平台主动探测」两条路径互相污染。
 *
 * <h3>复用点：一切落库都走既有实现</h3>
 * 本服务<b>不直接写通道表</b>：目录同步一律转成 {@link CatalogItem}，
 * 交给 {@link DeviceService#saveCatalog} —— 那里面已经处理好了
 * 「新增/更新/幽灵通道清理/设备自身通道」的全部边界情况。
 * 这样大华设备和国标设备在库里长得完全一样，
 * 前端的通道列表、点播、云台、录像接口一行都不用改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceProvisionService {

    private final GbDeviceMapper deviceMapper;
    private final DeviceService deviceService;
    private final DeviceAccessRegistry accessRegistry;
    private final DeviceRegisterRegistry registerRegistry;

    /**
     * 曾经通过主动注册出现过的 SN。
     *
     * <p>只增不减，进程重启后从空开始 —— 这不影响正确性：重启后设备会重新注册一次
     * （实测每次平台重启后 1 分钟内必到），在那之前即使被误判一次离线，
     * 注册到达后 {@code markOnlineQuietly} 会立刻纠正。
     *
     * <p>用 {@link java.util.concurrent.ConcurrentHashMap} 存而不是普通 Set：
     * 注册回调在 SDK 的 IO 线程上、巡检在调度线程上，两者并发访问。
     */
    private final java.util.Set<String> registeredSerials = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 纳管一台 SDK 直连设备。
     *
     * <p>只落设备行，<b>不立即拉目录</b> —— 调用方通常在纳管后马上要返回、
     * 而拉目录可能耗时数秒（设备慢时十几秒）。目录由 {@link #refreshCatalog}
     * 单独触发，失败也不会把「设备已添加」这件事回滚掉。
     *
     * @param deviceId 平台内编号。允许沿用国标 20 位编码（推荐），
     *                 也可以直接用设备序列号 —— 它只是 {@code gb_device} 的主键语义，
     *                 不参与 SIP 寻址，因此不要求符合 GB 编码规则
     * @param host     设备地址
     * @param port     SDK 端口（大华默认 37777）
     * @param username 登录用户名
     * @param password 登录密码
     * @param name     显示名，可为空（默认用 deviceId）
     */
    @Transactional
    public GbDevice addDahua(String deviceId, String host, int port,
                             String username, String password, String name) {
        String id = trimToNull(deviceId);
        if (id == null) {
            throw BizException.badRequest("缺少设备编号 deviceId");
        }
        String ip = trimToNull(host);
        if (ip == null) {
            throw BizException.badRequest("缺少设备地址 host");
        }
        if (port <= 0 || port > 65535) {
            throw BizException.badRequest("设备端口不合法：" + port);
        }
        if (trimToNull(username) == null || trimToNull(password) == null) {
            throw BizException.badRequest("SDK 直连需要用户名与密码");
        }
        GbDevice existing = deviceService.findDevice(id);
        if (existing != null && DeviceKind.of(existing.getSource()) == DeviceKind.GB28181) {
            // 不允许把国标设备无声改成 SDK 直连：它的通道、会话、级联关系
            // 都建立在 SIP 之上，改了会让那些数据全部悬空。
            throw BizException.badRequest("设备 " + id + " 已通过国标接入，"
                    + "不能改为 SDK 直连；请换一个设备编号，或先删除原设备");
        }
        // 提前校验实现是否可用，避免写了一条永远连不上的设备记录
        accessRegistry.require(DeviceKind.DAHUA);

        GbDevice d = existing == null ? new GbDevice() : existing;
        d.setDeviceId(id);
        d.setName(name == null || name.isBlank() ? id : name.trim());
        d.setSource(DeviceKind.DAHUA.code());
        d.setRemoteIp(ip);
        d.setRemotePort(port);
        d.setUsername(username.trim());
        d.setPassword(password);
        d.setTransport("UDP");
        d.setUpdateTime(LocalDateTime.now());
        if (existing == null) {
            d.setStatus("OFF");
            d.setOnline(false);
            d.setCreateTime(LocalDateTime.now());
            deviceMapper.insert(d);
        } else {
            deviceMapper.updateById(d);
        }
        log.info("已纳管 SDK 直连设备: {}@{}:{} (source={})", id, ip, port, d.getSource());
        return d;
    }

    /**
     * 探测设备可达性并同步目录。
     *
     * <p>目录同步复用 {@link DeviceService#saveCatalog}：把 provider 报的通道
     * 转成 {@link CatalogItem} 后交给它做增删改，边界情况（幽灵通道、
     * 设备自身通道）都已在那里处理过。
     *
     * @return 本次同步到的通道数
     */
    @Transactional
    public int refreshCatalog(String deviceId) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            throw BizException.notFound("设备不存在：" + deviceId);
        }
        DeviceKind kind = DeviceKind.of(device.getSource());
        if (kind == DeviceKind.GB28181) {
            // 国标设备的目录由设备应答驱动，这里不能主动「拉」
            throw BizException.badRequest("国标设备的目录请用 /device/fetchcatalog 下发查询");
        }
        DeviceAccessProvider provider = accessRegistry.require(kind);
        DeviceContext ctx = contextOf(device);
        if (!ctx.connectable()) {
            throw BizException.badRequest("设备连接信息不完整（host/port）：" + deviceId);
        }

        boolean online = provider.probe(ctx);
        device.setOnline(online);
        device.setStatus(online ? "ON" : "OFF");
        device.setUpdateTime(LocalDateTime.now());
        if (online) {
            device.setKeepaliveTime(LocalDateTime.now());
        }
        deviceMapper.updateById(device);
        if (!online) {
            log.warn("SDK 设备探测失败（不可达或凭据无效）: {}", ctx);
            return 0;
        }

        List<DeviceAccessProvider.ChannelInfo> channels = provider.listChannels(ctx);
        List<CatalogItem> items = toCatalogItems(channels);
        // fullSnapshot=true：provider 返回的是设备当前完整目录，
        // 因此设备上已删掉的通道也要跟着清掉
        deviceService.saveCatalog(deviceId, items, true, items.size(), 1);
        log.info("SDK 设备目录已同步: {} 通道数={}", deviceId, items.size());
        return items.size();
    }

    /**
     * provider 的通道信息 → 国标目录项。
     *
     * <p>只填 {@code saveCatalog} 实际会消费的字段：编号、名称、在离线。
     * 其余（经纬度、厂商…）留空 —— 大华 SDK 侧拿这些要额外调用，
     * 而它们在平台侧只用于展示，缺了不影响功能，后续需要再补。
     */
    private static List<CatalogItem> toCatalogItems(
            List<DeviceAccessProvider.ChannelInfo> channels) {
        List<CatalogItem> items = new ArrayList<>();
        if (channels == null) {
            return items;
        }
        for (DeviceAccessProvider.ChannelInfo c : channels) {
            if (c == null || c.channelCode() == null || c.channelCode().isBlank()) {
                continue;
            }
            CatalogItem item = CatalogItem.builder()
                    .deviceId(c.channelCode())
                    .name(c.name())
                    .status(c.online() ? "ON" : "OFF")
                    .build();
            items.add(item);
        }
        return items;
    }

    /**
     * 只探测可达性并刷新在线状态，<b>不拉目录</b>。
     *
     * <p>给定时巡检用：{@link #refreshCatalog} 会顺带做一次完整目录同步
     * （逐通道 upsert），每 60 秒跑一遍对数据库和设备都是浪费；
     * 而 SDK 直连设备不发心跳，必须靠这一步主动探测才能维持在线状态
     * （否则会被心跳巡检误判离线，见 {@code DeviceService#offlineSilentDevices}）。
     *
     * <p><b>刻意不加 {@code @Transactional}</b>：SDK 登录是网络操作，
     * 慢的时候几秒。放在事务里会让 {@code gb_device} 的行锁被长时间持有，
     * 与自动接入线程的「更新设备 + upsert 通道」形成交叉等待 ——
     * 实测直接触发 PostgreSQL {@code deadlock detected}。
     * 状态写入交给 {@code setDeviceAndChannelsStatus}（它自带短事务）。
     *
     * <h3>「连不上」不等于「离线」—— 只在 CLIENT 模式下才敢判离线</h3>
     * 一台设备可能被<b>多个平台实例</b>同时纳管（共库部署，或排障时本地起了
     * 一份连生产库的实例）。此时只有<b>持有主动注册会话的那个实例</b>能连上设备，
     * 其余实例按 {@code gb_device.remote_ip:remote_port} 直连必然失败 ——
     * 那是设备的 NAT 出口地址，从外部不可达。
     * <p>实测症状：两个实例各跑一次 60 秒巡检，一个写 {@code online=true}
     * （注册会话探测成功）、另一个 20 秒后写 {@code online=false}，
     * 库里 {@code online} 每分钟翻转两次，界面一直显示离线。
     * <p>因此：<b>SERVER 模式探测失败时保持原状</b>（既不改 true 也不改 false）——
     * 连不上只说明「我没有那条连接」，不构成设备离线的证据；
     * 真正的离线由持有会话的实例在会话失效后判定。
     *
     * <h3>还有一条更隐蔽的空窗：注册会话尚未建立的那几十秒</h3>
     * 设备重启 / 平台重启后，设备要过一会儿才重新主动注册。
     * 这段空窗里 {@link #contextOf} 在注册表查不到它，会退化成 CLIENT 模式，
     * 于是按 {@code remote_ip:37777} 直连 —— 而那是 NAT 出口地址，必然失败，
     * 就把一台完全正常的设备写成离线。
     * <p>所以还要区分「这台设备的档案地址本来就不可达」与「设备真的离线」：
     * 只要该 SN <b>曾经通过主动注册出现过</b>（记在 {@link #registeredSerials}），
     * 就说明它的接入方式是 SERVER，CLIENT 直连失败没有判定意义。
     *
     * @return 设备当前是否在线；设备不存在、实现缺失、
     *         或无法确认时返回 {@code false}
     */
    public boolean probeOnly(String deviceId) {
        GbDevice device = deviceService.findDevice(deviceId);
        if (device == null) {
            return false;
        }
        DeviceKind kind = DeviceKind.of(device.getSource());
        if (kind == DeviceKind.GB28181) {
            // 国标设备由 SIP 心跳维持在线，不能在这里抢着改状态
            return false;
        }
        DeviceAccessProvider provider = accessRegistry.provider(kind).orElse(null);
        if (provider == null) {
            return false;
        }
        DeviceContext ctx = contextOf(device);
        if (!ctx.connectable()) {
            return false;
        }
        boolean online;
        try {
            online = provider.probe(ctx);
        } catch (RuntimeException e) {
            // 探测失败是现场常态（设备断电、网络抖动），不能把巡检循环打挂
            log.debug("SDK 设备探测异常: {} {}", deviceId, e.toString());
            online = false;
        }
        if (online) {
            deviceService.setDeviceAndChannelsStatus(deviceId, true);
            return true;
        }
        if (!cannotJudgeOffline(device, ctx)) {
            // 这个观察点看不到它，且证据足以判定离线（纯局域网直连部署）
            deviceService.setDeviceAndChannelsStatus(deviceId, false);
            return false;
        }
        /*
         * 探测失败但不足以判定离线。
         *
         * <p>SERVER 模式下还有一层：注册表里查得到它，这本身就是比探测更强的
         * 存活证据 —— 设备刚把连接交到我们手上。此时应当<b>主动置为在线</b>，
         * 而不是「保留原状」：如果原状是离线（上一轮被别的实例写坏），
         * 保留就等于让错误状态一直显示。
         */
        if (ctx.connectMode() == ConnectMode.SERVER) {
            deviceService.setDeviceAndChannelsStatus(deviceId, true);
            return true;
        }
        log.debug("SDK 设备探测失败但不足以判定离线，保留原状态: {} {}", deviceId, ctx);
        return false;
    }

    /**
     * 探测失败时，是否<b>不足以</b>判定设备离线。
     *
     * <p>核心思想：<b>「我这个观察点看不到它」不等于「它离线了」</b>。
     * 一台设备可能被多个平台实例同时纳管，只有持有主动注册会话的那个能连上，
     * 其余实例按档案地址直连必然失败（那是 NAT 出口地址，从外部不可达）。
     * 让这些实例写 {@code online=false}，就会与持有会话的实例互相覆盖。
     *
     * <p>分三种情况：
     * <ol>
     *   <li><b>SERVER 模式</b>（注册表里查得到它）：设备正主动注册着，
     *       这是比任何探测都强的存活证据 —— 永不判离线。
     *       探测失败只说明「我没拿到那条会话」，与设备状态无关。</li>
     *   <li><b>CLIENT 模式 + 曾主动注册过</b>：档案地址是 NAT 出口、本来就不可直连，
     *       直连失败没有判定意义。<b>但不能永远不判</b> —— 设备真断电后没人刷新
     *       {@code keepalive_time}，所以用一个较长的窗口兜底（见
     *       {@link #STALE_AFTER_SECONDS}）。</li>
     *   <li><b>CLIENT 模式 + 从未注册过</b>：纯局域网直连部署，
     *       直连失败就是离线的可靠证据。</li>
     * </ol>
     *
     * @return {@code true} 表示证据不足，应保留原状态
     */
    private boolean cannotJudgeOffline(GbDevice device, DeviceContext ctx) {
        return cannotJudgeOffline(ctx.connectMode(),
                registeredSerials.contains(device.getDeviceId()),
                device.getKeepaliveTime(), LocalDateTime.now());
    }

    /**
     * {@link #cannotJudgeOffline(GbDevice, DeviceContext)} 的纯函数版本。
     *
     * <p>抽出来是为了能单测：这段逻辑决定「界面显示在线还是离线」，
     * 而它踩过的坑（多实例互相覆盖、重连空窗误判）都不是一眼能看出来的。
     *
     * @param mode          本次探测用的接入模式
     * @param everRegistered 本进程内是否见过该 SN 主动注册
     * @param lastSeen      最后一次确认在线的时间，可为 {@code null}
     * @param now           当前时间
     * @return {@code true} 表示证据不足，应保留原状态
     */
    static boolean cannotJudgeOffline(ConnectMode mode, boolean everRegistered,
                                      LocalDateTime lastSeen, LocalDateTime now) {
        if (mode == ConnectMode.SERVER) {
            // 注册表里有它 = 设备正主动注册着，比任何探测都强的存活证据
            return true;
        }
        if (!everRegistered) {
            // 纯局域网直连部署：直连失败就是离线
            return false;
        }
        if (lastSeen == null) {
            /*
             * 走过主动注册、但库里还没写过一次「在线」。
             * 典型场景：平台刚重启，本进程刚收到注册（内存记下了），
             * 但 keepalive_time 还是空。此时直连必然失败（NAT 出口），
             * 判离线只会让界面在重启后立刻闪一下离线。
             */
            return true;
        }
        return lastSeen.isAfter(now.minusSeconds(STALE_AFTER_SECONDS));
    }

    /**
     * 「曾主动注册过的设备」在 CLIENT 模式下判离线的时间窗口（秒）。
     *
     * <p>这类设备的档案地址不可直连，只能靠 {@code keepalive_time} 判断死活。
     * 取 5 分钟：既跨得过设备重连的抖动（实测平台重启后约 1 分钟内重新注册），
     * 又不会让「设备真断电」的界面迟迟不更新。
     */
    private static final long STALE_AFTER_SECONDS = 300L;

    /**
     * 设备记录 → 接入上下文。
     *
     * <h3>大华设备：此刻有主动注册会话时，必须走 SERVER 模式</h3>
     * {@code gb_device.remote_ip/remote_port} 存的是纳管时按配置写的
     * <b>设备服务端口</b>（大华默认 37777）。设备位于 NAT / 4G 之后时，
     * 这个端口从平台侧<b>不可达</b>，直连必然失败 —— 实测线上表现就是
     * 「主动注册上来了、也纳管了，但没有通道，随后变离线」：
     * <pre>
     *   00:00:23  大华设备登录成功: 111.198.60.180:15178（主动注册 SN=10001）  ← 真正可用的连接
     *   00:01:42  大华设备登录失败: 111.198.60.180:37777 错误码=9              ← 拿建档地址直连
     * </pre>
     *
     * <p>而设备主动注册时，平台已经在<b>那条连接</b>上登录过，
     * 会话就在内存里。所以这里只要判断「该 SN 当前是否在注册表里」：
     * <ul>
     *   <li>在 → SERVER 模式，复用那条连接；</li>
     *   <li>不在（局域网直连部署，或主动注册已断开）→ CLIENT 模式，按配置地址直连。</li>
     * </ul>
     *
     * <p>不在 {@code gb_device} 上加 {@code connectMode} 列：那样只能记下
     * 「纳管时的方式」，而设备换网、主动注册掉线重连之后这个静态值会过期。
     * 用<b>实时会话</b>判断永远反映当下，且不需要迁移表结构。
     */
    private DeviceContext contextOf(GbDevice d) {
        int channelTotal = d.getChannelCount() == null ? 0 : d.getChannelCount();
        /*
         * 实时注册表里查得到 = 设备正主动注册着（SERVER 模式），直接用那条连接的地址；
         * 查不到（局域网直连部署，或主动注册已断开）= CLIENT 模式，按配置地址直连。
         */
        if (DeviceKind.of(d.getSource()) != DeviceKind.GB28181) {
            DeviceRegisterTable.RegisteredDevice live = registerRegistry.lookupAny(d.getDeviceId());
            if (live != null) {
                registeredSerials.add(d.getDeviceId());
                return new DeviceContext(d.getDeviceId(),
                        live.ip(), live.port(),
                        d.getUsername(), d.getPassword(),
                        ConnectMode.SERVER,
                        d.getDeviceId(), channelTotal);
            }
        }
        return new DeviceContext(d.getDeviceId(),
                d.getRemoteIp(),
                d.getRemotePort() == null ? 0 : d.getRemotePort(),
                d.getUsername(),
                d.getPassword(),
                channelTotal);
    }

    /**
     * 登记「这个 SN 通过主动注册出现过」。
     *
     * <p>由主动注册登录成功后的回调调用（见 {@code DahuaConfiguration}）。
     * 记下来之后，即使注册会话短暂消失（设备重连空窗），
     * {@link #probeOnly} 也不会因为「按档案地址直连失败」而把设备判成离线。
     *
     * @param serial 设备序列号
     */
    public void noteRegistered(String serial) {
        if (serial != null && !serial.isBlank()) {
            registeredSerials.add(serial);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 删除设备时一并清理（复用既有实现，避免两套删除逻辑走偏）。 */
    public long countDahua() {
        return deviceMapper.selectCount(Wrappers.<GbDevice>lambdaQuery()
                .eq(GbDevice::getSource, DeviceKind.DAHUA.code()));
    }

    /**
     * 列出所有非国标（SDK 直连）设备编号，供定时巡检主动探测。
     *
     * <p>与 {@link #countDahua()} 同样用 SQL 判据 {@code source}；
     * 未知值按国标处理（与 {@code DeviceKind.of} 一致），所以这里只取明确属于 SDK 的。
     */
    public java.util.List<String> sdkDeviceIds() {
        return deviceMapper.selectList(Wrappers.<GbDevice>lambdaQuery()
                        .select(GbDevice::getDeviceId)
                        .apply("lower(trim(coalesce(source, ''))) in ('dahua', 'netsdk')"))
                .stream().map(GbDevice::getDeviceId).toList();
    }
}
