#!/bin/bash
# 排查温泉县坝通道检测不到人（临时诊断脚本）
set -u
API='http://127.0.0.1:48092/api/v1'
TOKEN='cb13b9ed-4ec6-4fa0-a59e-b339ca3bb58c'

echo "############ 1) 视觉总览 ############"
curl -s "$API/vision/status" -H "Authorization: $TOKEN" 2>/dev/null | python3 -m json.tool 2>/dev/null | head -60

echo
echo "############ 2) 检测通道配置（含场景）############"
PGPASSWORD='Li374382471' psql -h 127.0.0.1 -p 5432 -U postgres -d gb28181 -t -A -F'|' \
  -c "select channel_id,device_id,scenario_id,enabled from gb_vision_channel order by channel_id" 2>/dev/null

echo
echo "############ 3) 温泉县坝通道实测检测结果 ############"
curl -s "$API/vision/detections?channel=50015400001320000050" -H "Authorization: $TOKEN" 2>/dev/null | head -c 800
echo

echo
echo "############ 4) 模型与场景配置（application-vision.yml）############"
sed -n '1,120p' /opt/gb28181/application-vision.yml 2>/dev/null

echo
echo "############ 5) 检测相关日志（最近 30 条）############"
grep -E '视觉|推理|inferred|检测' /opt/gb28181/logs/console.log 2>/dev/null | tail -30 | cut -c1-170
