package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaExtAccess;
import io.github.rurulalanana.dahua.ext.DahuaSdkException;
import io.github.rurulalanana.deviceaccess.DeviceDiscovery;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import io.github.rurulalanana.deviceaccess.DevicePresetList;
import io.github.rurulalanana.deviceaccess.DeviceRecordSearch;
import io.github.rurulalanana.deviceaccess.DeviceSession;
import io.github.rurulalanana.deviceaccess.DeviceSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 大华的扩展能力：抓图 / 录像检索 / 预置位清单 / 局域网发现 / 会话释放。
 *
 * <p>多个接口一个实现：{@code DeviceExtensionRegistry} 会按接口分别登记，
 * 拆成多个类只会重复会话解析代码。
 *
 * <p>能力不可用时返回空值（不是抛异常）：抓图回落拉流截帧、录像检索报「无录像」、
 * 预置位显示空表 —— 这几处调用方都有自己的降级路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DahuaDeviceExtensions implements DeviceSnapshot, DeviceRecordSearch,
        DevicePresetList, DeviceDiscovery, DeviceSession {

    private final DahuaCapabilityService capability;

    @Override
    public DeviceKind kind() {
        return DeviceKind.DAHUA;
    }

    // ==================== 抓图 ====================

    @Override
    public boolean available(String deviceId) {
        return capability.session(deviceId).isPresent();
    }

    @Override
    public byte[] jpeg(String deviceId, String channelId) {
        var session = capability.requireSession(deviceId);
        int sdkChannel = capability.sdkChannel(session, deviceId, channelId);
        try {
            return capability.ext().snapshot(session, sdkChannel);
        } catch (DahuaSdkException e) {
            throw capability.translate("抓图", e);
        }
    }

    // ==================== 录像检索 ====================

    @Override
    public List<FoundRecord> search(String deviceId, String channelId,
                                    String startTime, String endTime) {
        var session = capability.requireSession(deviceId);
        int sdkChannel = capability.sdkChannel(session, deviceId, channelId);
        List<DahuaExtAccess.RecordFile> files;
        try {
            files = capability.ext().queryRecordFile(session, sdkChannel, startTime, endTime);
        } catch (DahuaSdkException e) {
            /*
             * 「设备没有录像能力」与「真故障」要分开说：
             * 实测 IPC（无 SD 卡）调 CLIENT_QueryRecordFile 直接失败（0x80000018 / 0x8000004F），
             * 报「设备拒绝」就够了，不该让运维去查网络。其余错误码照实透出。
             */
            int code = e.sdkError();
            if (code == 0x80000018 || code == 0x8000004F) {
                throw new IllegalStateException("该型号不支持设备录像检索，或未安装存储卡："
                        + channelId + "（SDK 错误码 0x" + Integer.toHexString(code) + "）");
            }
            throw new IllegalStateException(e.getMessage());
        }
        List<FoundRecord> out = new ArrayList<>(files.size());
        for (DahuaExtAccess.RecordFile f : files) {
            out.add(new FoundRecord(f.fileName(), f.start(), f.end(), f.bytes()));
        }
        return out;
    }

    // ==================== 预置位清单 ====================

    @Override
    public List<PresetInfo> list(String deviceId, String channelId) {
        List<PresetInfo> out = new ArrayList<>();
        try {
            var session = capability.requireSession(deviceId);
            int sdkChannel = capability.sdkChannel(session, deviceId, channelId);
            for (DahuaExtAccess.Preset p : capability.ext().listPresets(session, sdkChannel)) {
                out.add(new PresetInfo(p.index(), p.name(),
                        p.pan(), p.tilt(), p.zoom(), p.staySeconds()));
            }
        } catch (DahuaSdkException e) {
            /*
             * 读不到清单只有两种原因：型号不支持这几条接口，或会话失效。
             * 按契约返回空表并记日志，而不是编一份数据出来。
             */
            log.info("读不到设备预置点清单: device={} channel={} {}",
                    deviceId, channelId, e.getMessage());
        }
        return out;
    }

    // ==================== 局域网发现 ====================

    @Override
    public List<Found> discover(String localIp, long timeoutMs) {
        List<DahuaExtAccess.Discovered> raw = capability.discoverLan(localIp, timeoutMs);
        List<Found> out = new ArrayList<>(raw.size());
        for (DahuaExtAccess.Discovered d : raw) {
            out.add(new Found(d.serial(), d.ip(), d.sdkPort(), d.httpPort(),
                    d.model(), d.firmware(), d.channels()));
        }
        return out;
    }

    // ==================== 会话释放 ====================

    @Override
    public void release(String deviceId) {
        capability.logoutQuietly(deviceId);
    }
}
