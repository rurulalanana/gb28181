#!/bin/bash
# 用有效 token 排查温泉县坝检测（临时诊断脚本）
set -u
API='http://127.0.0.1:48092/api/v1'
TOKEN='b68dc38b-4b84-4b90-a322-8352546a6bd5'

echo "############ 1) 视觉总览 ############"
curl -s "$API/vision/status" -H "Authorization: $TOKEN" 2>/dev/null | python3 -m json.tool 2>/dev/null | head -50

echo
echo "############ 2) 检测通道配置 ############"
PGPASSWORD='Li374382471' psql -h 127.0.0.1 -p 5432 -U postgres -d gb28181 -t -A -F'|' \
  -c "select device_id,channel_id,scenario_id,enabled from gb_vision_channel order by device_id,channel_id" 2>/dev/null

echo
echo "############ 3) 温泉县坝检测结果 ############"
curl -s "$API/vision/detections?channel=50015400001320000050" -H "Authorization: $TOKEN" 2>/dev/null | head -c 700
echo

echo
echo "############ 4) 该通道原始分辨率与码流 ############"
curl -s "$API/stream/info/34020000002000000002/50015400001320000050" -H "Authorization: $TOKEN" 2>/dev/null | head -c 500
echo

echo
echo "############ 5) 拉一帧看实际尺寸 ############"
FLV='http://127.0.0.1:48089/34020000002000000002/50015400001320000050.live.flv'
curl -s -X POST "$API/stream/start" -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"serial":"34020000002000000002","code":"50015400001320000050"}' -o /dev/null
sleep 6
timeout 20 /opt/gb28181/bin/ffmpeg -hide_banner -i "$FLV" -t 2 -f null - 2>&1 \
  | grep -E 'Stream #|Video:' | head -4
echo "  --- 截图一帧原始尺寸 ---"
timeout 20 /opt/gb28181/bin/ffmpeg -hide_banner -y -i "$FLV" -frames:v 1 /tmp/_frame.png 2>&1 | tail -1
if [ -f /tmp/_frame.png ]; then
  python3 -c "
import struct
with open('/tmp/_frame.png','rb') as f:
    d=f.read(33)
w,h=struct.unpack('>II', d[16:24])
print(f'  截图尺寸: {w}x{h}')
"
fi
