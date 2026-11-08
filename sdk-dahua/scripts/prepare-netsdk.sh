#!/usr/bin/env bash
#
# 把随仓库携带的 netsdk 装进本地 Maven 仓库，并构建安装 sdk-dahua。
#
# 背景（2026-10-28 方案调整）：
#   原先 netsdk jar 与原生库都不入库，换机器要手工解压厂商 SDK。
#   现在两者已随仓库携带：
#     lib/netsdk-repo/  —— com.netsdk:netsdk:3.061 的 Java class（4.7 MB）
#     lib/linux64/      —— 4 个 .so（84.8 MB）
#     lib/win64/        —— 5 个 .dll（45.1 MB）
#
#   sdk-dahua 自己的 pom 配了 file:// 仓库指向 lib/netsdk-repo，
#   能解决**自身**的依赖解析；但 <repositories> 不会传递给依赖方，
#   starter 直接依赖 com.netsdk:netsdk，必须由本脚本 install 到 ~/.m2。
#
# 用法：
#   ./scripts/prepare-netsdk.sh                    # 自动选平台
#   ./scripts/prepare-netsdk.sh --platform linux64 # 交叉打包
#
# 幂等：可重复执行。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

NETSDK_VERSION="3.061"
PLATFORM=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform) PLATFORM="$2"; shift 2 ;;
    -h|--help)  sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

info() { printf '\033[0;36m[信息]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[警告]\033[0m %s\n' "$*"; }
err()  { printf '\033[0;31m[错误]\033[0m %s\n' "$*" >&2; }

# ---------------------------------------------------------------- 1. JDK 版本
# netsdk jar 的 class 版本是 65（Java 21），低版本 JDK 读不了：
#   类文件具有错误的版本 65.0, 应为 61.0
detect_jdk21() {
  if [[ -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    /usr/libexec/java_home -v 21
  elif [[ -n "${JAVA_HOME:-}" ]] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"21'; then
    echo "$JAVA_HOME"
  fi
}

JDK21="$(detect_jdk21 || true)"
if [[ -z "$JDK21" ]]; then
  err "需要 JDK 21（netsdk jar 的 class 版本是 65，JDK 17 会报「类文件具有错误的版本 65.0」）"
  err "macOS: /usr/libexec/java_home -v 21   |   Linux: 请安装 openjdk-21"
  exit 1
fi
export JAVA_HOME="$JDK21"
info "使用 JDK: $JAVA_HOME"

# ---------------------------------------------------------------- 2. 平台
detect_platform() {
  case "$(uname -s)" in
    Darwin) echo "darwin" ;;
    Linux)  echo "linux64" ;;
    MINGW*|MSYS*|CYGWIN*) echo "win64" ;;
    *) echo "" ;;
  esac
}

if [[ -z "$PLATFORM" ]]; then
  PLATFORM="$(detect_platform)"
  info "按当前系统推断平台: $PLATFORM"
fi

case "$PLATFORM" in
  linux64|win64) ;;
  darwin)
    warn "官方 NetSDK 没有 darwin 原生库，打出的 jar 不含 .so；"
    warn "本机可编译，但调用大华功能需在 Linux/Windows 上运行。"
    ;;
  *) err "未知平台: $PLATFORM（支持 linux64 / win64）"; exit 2 ;;
esac

# ---------------------------------------------------------------- 3. netsdk → ~/.m2
REPO_DIR="lib/netsdk-repo/com/netsdk/netsdk/$NETSDK_VERSION"
JAR="$REPO_DIR/netsdk-$NETSDK_VERSION.jar"
POM="$REPO_DIR/netsdk-$NETSDK_VERSION.pom"

[[ -f "$JAR" ]] || { err "netsdk jar 缺失: $JAR"; exit 1; }
[[ -f "$POM" ]] || { err "netsdk pom 缺失: $POM"; exit 1; }

TARGET="$HOME/.m2/repository/com/netsdk/netsdk/$NETSDK_VERSION/netsdk-$NETSDK_VERSION.jar"
if [[ -f "$TARGET" ]] && cmp -s "$JAR" "$TARGET"; then
  info "netsdk:$NETSDK_VERSION 已在本地仓库且内容一致，跳过安装"
else
  info "安装 com.netsdk:netsdk:$NETSDK_VERSION 到本地仓库"
  mvn -q install:install-file -Dfile="$JAR" -DpomFile="$POM"
fi

# ---------------------------------------------------------------- 4. 原生库
NATIVE_DIR="lib/$PLATFORM"
if [[ -d "$NATIVE_DIR" ]] && [[ -n "$(ls -A "$NATIVE_DIR" 2>/dev/null)" ]]; then
  info "原生库就位: $NATIVE_DIR"
  ls -1 "$NATIVE_DIR" | sed 's/^/    /'
else
  warn "$NATIVE_DIR 为空 —— 打包出的 jar 不含原生库"
  warn "调用大华功能时会因找不到 libdhnetsdk 失败（dynamic-lib-load.xml 是白名单，"
  warn "缺任一库整个提取会中断，后面的 dhnetsdk 根本不会被提取）"
fi

# ---------------------------------------------------------------- 5. 构建
info "构建并安装 io.github.rurulalanana:sdk-dahua"
mvn -q clean install -DskipTests "-Dnetsdk.platform=$PLATFORM"

info "完成。可用以下坐标引用："
cat <<EOF

    <dependency>
      <groupId>io.github.rurulalanana</groupId>
      <artifactId>sdk-dahua</artifactId>
      <version>1.0.0</version>
    </dependency>

EOF
