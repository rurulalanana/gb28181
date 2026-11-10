package io.github.rurulalanana.starter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.rurulalanana.starter.entity.GbVisionChannel;
import org.apache.ibatis.annotations.Mapper;

/** AI 检测通道表 Mapper。 */
@Mapper
public interface GbVisionChannelMapper extends BaseMapper<GbVisionChannel> {
}
