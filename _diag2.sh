#!/bin/bash
# 登录取 token 并排查检测（临时诊断脚本）
set -u
API='http://127.0.0.1:48092/api/v1'

echo "############ 1) 登录 ############"
RESP=$(curl -s -X POST "$API/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}')
echo "$RESP" | head -c 300
echo
TOKEN=$(echo "$RESP" | python3 -c "
import sys,json
try:
    print((json.load(sys.stdin).get('data') or {}).get('token') or '')
except Exception:
    print('')
")
echo "TOKEN 长度: ${#TOKEN}"
if [ -z "$TOKEN" ]; then
  echo "登录失败，退出"
  exit 1
fi
echo "$TOKEN" > /tmp/_tok.txt

echo
echo "############ 2) 视觉总览 ############"
curl -s "$API/vision/status" -H "Authorization: $TOKEN" 2>/dev/null | python3 -m json.tool 2>/dev/null | head -50

echo
echo "############ 3) 检测通道配置 ############"
PGPASSWORD='Li374382471' psql -h 127.0.0.1 -p 5432 -U postgres -d gb28181 -t -A -F'|' \
  -c "select device_id,channel_id,scenario_id,enabled from gb_vision_channel order by device_id,channel_id" 2>/dev/null

echo
echo "############ 4) 温泉县坝的检测结果 ############"
curl -s "$API/vision/detections?channel=50015400001320000050" -H "Authorization: $TOKEN" 2>/dev/null | head -c 600
echo
