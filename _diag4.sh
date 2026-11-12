#!/bin/bash
# 拉取温泉县坝实际帧并测分辨率（临时诊断脚本）
set -u
API='http://127.0.0.1:48092/api/v1'
TOKEN='b68dc38b-4b84-4b90-a322-8352546a6bd5'
DEV='34020000002000000002'
CH='50015400001320000050'

echo "############ 1) 点播后取流信息 ############"
curl -s -X POST "$API/stream/start" -H "Authorization: $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"serial\":\"$DEV\",\"code\":\"$CH\"}" | head -c 700
echo
sleep 6

echo
echo "############ 2) stream/info ############"
curl -s "$API/stream/info?streamid=$DEV%2F$CH" -H "Authorization: $TOKEN" 2>/dev/null | head -c 600
echo

echo
echo "############ 3) 直接从 ZLM 拉帧测分辨率 ############"
FLV="http://127.0.0.1:48089/$DEV/$CH.live.flv"
timeout 25 /opt/gb28181/bin/ffmpeg -hide_banner -i "$FLV" -t 3 -f null - 2>&1 \
  | grep -E 'Stream #|Video:' | head -4

echo
echo "############ 4) 截图并测尺寸 ############"
rm -f /tmp/_fwq.png
timeout 25 /opt/gb28181/bin/ffmpeg -hide_banner -y -i "$FLV" -frames:v 1 /tmp/_fwq.png 2>&1 | tail -2
if [ -f /tmp/_fwq.png ]; then
  ls -la /tmp/_fwq.png
  python3 -c "
import struct
with open('/tmp/_fwq.png','rb') as f: d=f.read(33)
print('PNG 尺寸:', struct.unpack('>II', d[16:24]))
"
fi
