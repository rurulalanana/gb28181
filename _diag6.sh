#!/bin/bash
# 裁剪温泉县坝画面中有人区域，实测人体像素尺寸（临时诊断脚本）
set -u
DEV='34020000002000000002'
CH='50015400001320000050'
FLV="http://127.0.0.1:48089/rtp/${DEV}_${CH}.live.flv"
FFMPEG=/opt/gb28181/bin/ffmpeg

echo "############ 1) 抓全帧 ############"
rm -f /tmp/_full.png
timeout 25 "$FFMPEG" -hide_banner -y -i "$FLV" -frames:v 1 /tmp/_full.png 2>&1 | tail -1
ls -la /tmp/_full.png 2>/dev/null

echo
echo "############ 2) 裁剪道路区域（画面中下部，人在那里） ############"
# 1920x1080，道路大致在 x=600..1100, y=700..950
rm -f /tmp/_road.png
timeout 25 "$FFMPEG" -hide_banner -y -i "$FLV" -frames:v 1 \
  -vf "crop=500:250:600:700" /tmp/_road.png 2>&1 | tail -1
ls -la /tmp/_road.png 2>/dev/null

echo
echo "############ 3) 把该区域放大 4 倍（便于目视确认人的像素尺寸）############"
rm -f /tmp/_road4x.png
timeout 25 "$FFMPEG" -hide_banner -y -i /tmp/_road.png \
  -vf "scale=iw*4:ih*4:flags=neighbor" /tmp/_road4x.png 2>&1 | tail -1
ls -la /tmp/_road4x.png 2>/dev/null

echo
echo "############ 4) 模拟模型输入：全帧缩到 416x416 后，人还剩多少像素 ############"
rm -f /tmp/_416.png
timeout 25 "$FFMPEG" -hide_banner -y -i /tmp/_full.png \
  -vf "scale=416:416:force_original_aspect_ratio=decrease,pad=416:416:(ow-iw)/2:(oh-ih)/2" /tmp/_416.png 2>&1 | tail -1
ls -la /tmp/_416.png 2>/dev/null
python3 -c "
import struct
for p in ['/tmp/_full.png','/tmp/_416.png']:
    try:
        with open(p,'rb') as f: d=f.read(33)
        print(f'  {p}: {struct.unpack(\">II\", d[16:24])}')
    except Exception as e:
        print(f'  {p}: {e}')
"
