package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbAlarm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 报警表 Mapper。 */
@Mapper
public interface GbAlarmMapper extends BaseMapper<GbAlarm> {

    /**
     * 取全部<b>事件截图</b>的路径（用于清理磁盘上的孤儿文件）。
     *
     * <p>只要 {@code /snap/alarm/} 下的：通道当前快照（{@code /snap/{serial}/{code}.jpg}）
     * 靠 1 小时过期自清，不参与这套「按库对账」的清理，混进来只会让 SQL 白跑。
     *
     * <p><b>为什么用 {@code LIKE} 而不是加一列标记</b>：路径本身就是唯一来源，
     * 多一列就多一处可能与它不一致的地方。这里只是定期清理，全表扫可接受
     * （有 {@code start_time} 索引，但这里用不上，靠的是报警表总量可控）。
     *
     * @return 形如 {@code /api/v1/cloudrecord/snap/alarm/{serial}/{alarmId}.jpg}
     */
    @Select("SELECT snap_path FROM gb_alarm "
            + "WHERE snap_path IS NOT NULL AND snap_path LIKE '%/snap/alarm/%'")
    List<String> selectEventSnapPaths();
}

