package io.github.rurulalanana.starter.service;

import io.github.rurulalanana.dahua.ext.DahuaAlarmCodes;
import io.github.rurulalanana.deviceaccess.DeviceAlarmSink;
import io.github.rurulalanana.deviceaccess.DeviceKind;
import org.springframework.stereotype.Component;

/**
 * 大华报警翻译：事件码 → 平台报警。
 *
 * <p>两道过滤都在这里，{@code AlarmService} 不再认识任何大华事件码：
 * <ul>
 *   <li>{@link DahuaAlarmCodes#isAlarm} 挡掉状态上报（云台位置 {@code 0x2123}、
 *       订阅完成 {@code 0x300C}），不挡会刷出上千条假报警；</li>
 *   <li>{@link DahuaAlarmCodes#hasState} + {@code state == 0} 识别「报警结束」，
 *       订阅瞬间设备会推一份「全部已结束」的体检报告。</li>
 * </ul>
 *
 * <p>优先级方向要换算：大华 3=最紧急 / 1=提示，国标 1=一级最紧急，两者相反。
 */
@Component
public class DahuaAlarmSink implements DeviceAlarmSink {

    @Override
    public DeviceKind kind() {
        return DeviceKind.DAHUA;
    }

    @Override
    public DeviceAlarm normalize(RawAlarm raw) {
        int code = raw.code();
        if (!DahuaAlarmCodes.isAlarm(code)) {
            return null;
        }
        // 只有 hasState 认的事件码，首字段才是真的 dwState
        boolean ended = DahuaAlarmCodes.hasState(code) && raw.state() == 0;
        // 大华 3=紧急/2=一般/1=提示 → 国标 1=一级/2=二级/3=三级
        int priority = 4 - DahuaAlarmCodes.urgency(code);
        return new DeviceAlarm(
                raw.deviceId(),
                // SDK 事件不带通道号（要解各事件自己的结构体才知道），统一记设备级
                null,
                priority,
                code,
                DahuaAlarmCodes.name(code),
                "SDK 直连事件 0x" + String.format("%04X", code),
                ended);
    }
}
