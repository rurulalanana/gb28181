#!/bin/bash
# 在服务器上打包：前端 npm build + Maven 打 linux64 包。
#
# 为什么单独一个脚本文件而不是 PowerShell 里的 here-string：
# PowerShell 5.1 的 here-string 结束标记必须顶格，且默认按 GBK 写文件，
# bash 会报 $'\r': command not found。独立 .sh 用 LF + UTF-8 最省事。
set -e

# The server only has a JRE 21 in /usr/lib/jvm (jdk-21.0.6+7-jre); the full JDKs there
# are Java 8, which cannot compile this project (release=17). So point JAVA_HOME at the
# JDK 21 we unpacked into /opt/tools. Without this Maven fails with
# "No compiler is provided in this environment. Perhaps you are running on a JRE".
JDK_DIR=$(ls -d /opt/tools/jdk-21* 2>/dev/null | head -1)
if [ -n "$JDK_DIR" ] && [ -x "$JDK_DIR/bin/javac" ]; then
    export JAVA_HOME="$JDK_DIR"
    export PATH="$JAVA_HOME/bin:$PATH"
fi
export PATH=/opt/tools/apache-maven-3.9.9/bin:$PATH
SRC_DIR="${1:-/opt/gb28181-src}"
cd "$SRC_DIR"

echo "=== JAVA_HOME: ${JAVA_HOME:-<unset>} ==="
javac -version 2>&1 || true
echo "=== 工作目录: $(pwd) ==="

echo "=== 前端构建 ==="
if [ -d gb28181-platform-starter/frontend ]; then
    cd gb28181-platform-starter/frontend
    # 用 ci 保证依赖版本与 package-lock 一致；锁文件缺失时退回 install
    npm ci --no-audit --no-fund 2>/dev/null || npm install --no-audit --no-fund
    npm run build
    cd "$SRC_DIR"
else
    echo "没有 frontend 目录，跳过"
fi

echo "=== Maven 打包（linux64）==="
# 服务器是 Linux，只打 linux64 包；两个 -D 参数必须同时给，
# 否则会按「当前操作系统」自动选，虽然结果一样但显式写更清楚。
mvn -B clean package -DskipTests -Dgpg.skip=true \
    -Dnetsdk.platform=linux64 -Dhikvision.platform=linux64 \
    2>&1 | grep -E 'BUILD|ERROR|Building jar|SUCCESS|FAILURE|Downloading.*netsdk' || true

JAR=$(ls -t gb28181-platform-starter/target/*.jar 2>/dev/null | grep -v original | head -1)
if [ -z "$JAR" ]; then
    echo "打包失败：没有产出 jar"
    exit 1
fi
echo "=== 产物 ==="
ls -la "$JAR"
echo "JAR_PATH=$JAR"
