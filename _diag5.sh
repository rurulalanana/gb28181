#!/bin/bash
# 拉取温泉县坝实际帧并测分辨率（v2，正确 URL）
set -u
DEV='34020000002000000002'
CH='50015400001320000050'
FLV="http://127.0.0.1:48089/rtp/${DEV}_${CH}.live.flv"

echo "############ FLV: $FLV ############"
timeout 25 /opt/gb28181/bin/ffmpeg -hide_banner -i "$FLV" -t 3 -f null - 2>&1 \
  | grep -E 'Stream #|Video:|Input #' | head -5

echo
echo "############ 截图并测尺寸 ############"
rm -f /tmp/_fwq.png
timeout 25 /opt/gb28181/bin/ffmpeg -hide_banner -y -i "$FLV" -frames:v 1 /tmp/_fwq.png 2>&1 | tail -2
if [ -f /tmp/_fwq.png ]; then
  python3 -c "
import struct
with open('/tmp/_fwq.png','rb') as f: d=f.read(33)
w,h=struct.unpack('>II', d[16:24])
print(f'PNG 尺寸: {w}x{h}')
print(f'若缩到 416: 缩放比 {416/max(w,h):.4f}')
"
fi
