# gb28181

GB/T 28181 视频监控平台。**单仓库 + Maven 父子工程**：根 `pom.xml` 是 parent，
六个子目录是它的 module，由 Maven 自动推导构建顺序。

仓库地址：<https://github.com/rurulalanana/gb28181>

> 原先六个模块是六个独立仓库，已合并到本仓库（旧仓库已归档，只读保留）。

---

## 1. 目录结构

```
C:\Users\lixianzhao\IdeaProjects\gb28181\
├── pom.xml                       parent pom（modules + 统一 java.version=17 + 统一版本 2.0.0）
├── README.md                     本文件
├── .gitignore / .gitattributes   根级忽略规则与 Git LFS 规则
├── .github\                      Copilot 工作区说明（copilot-instructions.md）
├── .vscode\                      工作区级配置（tasks / mcp / settings，已 gitignore）
│
├── gb28181-sip\                  信令层            v2.0.0
├── gb28181-sip-zlm\              媒体层            v2.0.0
├── device-access\                厂商无关接入契约  v2.0.0
├── sdk-dahua\                    大华设备接入      v2.0.0   （lib/ 走 Git LFS）
├── sdk-hikvision\                海康设备接入      v2.0.0   （lib/ 走 Git LFS）
├── vision-api\                   视觉契约层        v2.0.0
├── vision-onnx\                  视觉 ONNX 实现    v2.0.0
├── vision-pipeline\              视觉流水线        v2.0.0
├── models\                       视觉模型文件（独立于代码，可单独替换）
└── gb28181-platform-starter\     平台应用          v2.0.0
```

| 模块 | artifactId | 版本 | 职责 |
|---|---|---|---|
| sip | `gb28181-sip` | 2.0.0 | SIP 栈、MANSCDP 编解码、SDP、级联 |
| sip-zlm | `gb28181-sip-zlm` | 2.0.0 | 内嵌 ZLMediaKit（zlm4j）、RTP 收流、录像检索、回放控制 |
| device-access | `device-access` | 2.0.0 | 厂商无关契约：`DeviceKind` / `DeviceAccessProvider` / `DeviceMediaProvider` / `DeviceRegisterTable` |
| sdk-dahua | `sdk-dahua` | 2.0.0 | 大华 NetSDK 接入：直连 + 主动注册 |
| sdk-hikvision | `sdk-hikvision` | 2.0.0 | 海康 ISUP（EHome）接入：注册/取流/回放/报警/抓图 |
| vision-api | `vision-api` | 2.0.0 | 视觉契约层：`Frame` / `Detection` / `VisionDetector` / `DetectionSink`，零依赖 |
| vision-onnx | `vision-onnx` | 2.0.0 | ONNX Runtime 实现：YOLOX / YOLOv8 输出解析、预处理、NMS |
| vision-pipeline | `vision-pipeline` | 2.0.0 | 流水线：帧泵 → 推理 → 去抖聚合 → 出口（不认识具体模型/设备） |
| starter | `gb28181-platform-starter` | 2.0.0 | Spring Boot 应用、REST、Vue 前端、业务表 |

> **命名约定 `sdk-<厂商>`**：接海康就是平级的 `sdk-hikvision`，接宇视就是 `sdk-uniview`。
> 契约包 `io.github.rurulalanana.deviceaccess` 与厂商无关，不随模块名变。
> 所以既不叫 `gb28181-dahua`（平台已不只是国标平台），也不叫 `device-access`
> （那是契约的名字，不是具体实现的）。
> 这些模块是**与国标链路并列的第二、第三条接入通道**（不经过 SIP、不用 MANSCDP），
> 将来接宇视时新增实现即可，artifactId 与契约包都不用改。

依赖方向（单向，无环）：

```
gb28181-platform-starter
   ├── gb28181-sip-zlm ──► gb28181-sip
   ├── gb28181-sip
   ├── sdk-dahua      ──► device-access
   ├── sdk-hikvision  ──► device-access
   ├── vision-pipeline ──► vision-api
   └── vision-onnx     ──► vision-api
```

---

## 2. 构建

### 2.1 环境（每次开新终端都要设，PowerShell 不继承）

```powershell
$env:JAVA_HOME='C:\Program Files\BellSoft\LibericaJDK-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

Maven 在 `D:\maven\apache-maven-3.6.3\bin\mvn.cmd`，本地仓库 `D:\maven\repository`。
`-o` 是离线模式（这台机器网络不稳时靠它）。

### 2.2 一次构建全部

```powershell
cd C:\Users\lixianzhao\IdeaProjects\gb28181
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o install -DskipTests "-Dgpg.skip=true"
```

构建顺序自动推导：`sip → sip-zlm → sdk-dahua → starter`。
`-Dgpg.skip=true` **必须加引号**，否则 PowerShell 会把它拆开。

跳过 sdk-dahua（没装 netsdk 时）：

```powershell
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o install -DskipTests `
    -pl gb28181-sip,gb28181-sip-zlm,gb28181-platform-starter
```

### 2.2.1 大华原生库按平台打包

原生库在 `sdk-dahua/lib/<platform>/`（**不入库**，厂商许可），
按 profile 打进 sdk-dahua 的 jar：

```powershell
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o install -DskipTests                              # 按当前系统自动选
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o install -DskipTests "-Dnetsdk.platform=linux64"  # 出 Linux 包
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o install -DskipTests "-Dnetsdk.platform=win64"    # 出 Windows 包
```

⚠️ `-Dnetsdk.platform` **必须加引号**（同 `-Dgpg.skip` 的坑）。
**不要用 `-P` 切平台**：显式 `-P` 与自动激活的 profile 会同时生效，
`-Pnetsdk-linux64` 在 Windows 上会静默打出 win64 包。
详见 `sdk-dahua/docs/原生库打包.md`。

### 2.2.2 海康原生库按平台打包

同样的机制，库在 `sdk-hikvision/lib/<platform>/`（**不入库**，厂商许可）：

```powershell
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o install -DskipTests "-Dhikvision.platform=linux64"
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o install -DskipTests "-Dhikvision.platform=win64"
```

⚠️ **在 Windows 上打 Linux 包必须显式传 `-Dhikvision.platform=linux64`** ——
默认值是 `linux64`，但 Windows 上会自动激活 `win64` profile 覆盖它。

### 2.2.3 ⚠️ 交叉打包后必须重新打 starter（顺序不能反）

`starter` 的 fat jar 里**内嵌**了 `sdk-dahua` / `sdk-hikvision` 的 jar。
只 `mvn package -pl gb28181-platform-starter` 会复用本地仓库里上次装的版本，
而它可能是 Windows 上默认 profile 打出来的。

部署到 Linux 时的正确顺序：

```powershell
mvn -o install -DskipTests "-Dgpg.skip=true" "-Dnetsdk.platform=linux64" "-Dhikvision.platform=linux64" -pl sdk-dahua,sdk-hikvision
mvn -o clean package -DskipTests -pl gb28181-platform-starter
```

验证（jar 大小也是提示：win64 版大华 ~15MB，linux64 版 ~36MB）：

```bash
unzip -l gb28181-platform-starter-2.0.0.jar | grep sdk-dahua    # 内嵌 jar
# 再解开内嵌 jar 看是 linux64/ 还是 win64/
```

打包错了的表现：启动日志报「原生库缺失：当前平台为 linux64，但 jar 内没有 linux64 目录」，
**大华主动注册监听根本不会启动**，设备侧一直发注册但平台侧没在听。

### 2.3 单独构建某个模块（发布时用）

```powershell
cd gb28181-sip
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o deploy "-Dgpg.skip=true"
```

与聚合构建等价。注意版本号现在由根 pom 统一（子模块不再各自发版），
单独 deploy 发布的是同一个 2.0.0，不会再出现「子模块版本和 fat jar 里的不一致」。

### 2.4 前端

```powershell
cd gb28181-platform-starter
npm --prefix frontend run dev          # 开发（Vite，热更新）
npm --prefix frontend run build        # 产物直接写进 src/main/resources/static/
```

⚠️ `vite.config.js` 里 `emptyOutDir: true`，`build` 会**清空** `src/main/resources/static/`。
前端产物是**入库的**，所以改完前端要一起提交，否则线上还是旧页面。

---

## 3. 父子工程与版本号

根 `pom.xml` 既是聚合（`<modules>`）又是 parent，六个模块都继承它。

### 3.1 版本号只有一个来源

**全工程共用一个版本号，写在根 `pom.xml` 的 `<version>`。** 子模块不写自己的 `<version>`，
模块之间的依赖也不写硬编码版本（根 pom 的 `dependencyManagement` 用 `${project.version}` 给出）。

```
根 pom:      <version>2.0.0</version>            ← 唯一版本号
子 pom:      <parent><version>2.0.0</version></parent>
             没有自己的 <version>
starter 依赖: <artifactId>gb28181-sip</artifactId>  ← 不写 <version>
```

升级版本：改根 pom 那一行 → `mvn clean install` → 完成。

**为什么改成这样**：以前版本号散在 11 处（根 pom 五个 `dependencyManagement` +
starter 五个 `xxx.module.version` 属性 + 各模块自己的 `<version>`），
发一次版要全部改对；漏改一处**不会报错**，只会在运行时表现为「fat jar 里嵌的是旧库」。

**为什么是 2.0.0**：`gb28181-sip` 在 Maven Central 上已发布到 1.0.6、`gb28181-sip-zlm` 到 1.0.4，
那些都是 Java 8 字节码。本轮统一到 Java 17（字节码 52 → 61）是破坏性变更，
不能复用 1.0.x 坐标，否则下游会拿到「版本号一样、JDK 加载不了」的东西。

### 3.2 starter 为什么不继承 spring-boot-starter-parent

Java 只允许一个 `<parent>`，而本项目已经用 `gb28181` 做父工程。等价替代：

| 原来靠 starter-parent | 现在 |
|---|---|
| 依赖版本管理 | `<dependencyManagement>` import `spring-boot-dependencies:3.2.5` 的 BOM |
| `-parameters` 编译参数 | `maven-compiler-plugin` 显式开 `<parameters>true</parameters>` |
| `spring-boot-maven-plugin` repackage 绑定 | 显式写 `<executions>` |

⚠️ `-parameters` 不能漏：Spring MVC 靠它从字节码读参数名，
漏了会让 `@RequestParam` 省略 value 时启动报 `Name for argument of type ... not specified`。

---

## 4. 当前状态（2026-10-28）

### 4.1 已部署并验收通过

线上环境 `160.202.254.65`（部署细节见 `gb28181-platform-starter/docs/部署说明.md`）。

已修复并实测验证的问题，**根因记录在 `gb28181-platform-starter/docs/排障实录.md`**：

- 播放反复 `appendBuffer InvalidStateError`（内部 pause 触发 SIP PAUSE，设备停推）
- 录像查询 66 次超时 vs 33 次成功（同一通道被并发查两次，SIP pager 单槽覆盖）
- 「设备未应答」被显示成「当日无录像」（空列表无法区分两种语义）
- 5.1% 点播丢 H264 轨（`wait_track_ready_ms=10000` 不够）
- `SIP 事务超时: server=true` ×1612（INVITE 缺 Contact，jain-sip 直接拒绝建事务）
- 录像检索永远 400 超时（时间用空格而非 `T`）
- 进度条拖动无效（INFO 的 From/To tag 反了，设备认为不在对话内）
- 通道预览弹窗大空白 / 滚动条（三轮迭代才定稿）
- 公网扫描器污染设备表

### 4.2 大华接入：框架完成，未接真机

- 已验证：原生库加载 + `CLIENT_Init` 成功（SDK 版本 2.40.63）
- 原生库从 186.5 MB 裁到 **84.8 MB**（只留 `dhnetsdk` / `dhconfigsdk` / `crypto` / `ssl`）
- 代码框架：`DeviceAccessProvider` 接口 + 大华实现 + 设备注册监听，**尚无真机联调**
- 详见 `gb28181-platform-starter/docs/大华SDK接入.md`

### 4.3 未完成 / 待决策

| # | 事项 | 说明 |
|---|---|---|
| 1 | ~~版本号散落在 11 处~~ | 已统一为 **2.0.0**，由根 `pom.xml` 的 `<version>` 单点决定 |
| 2 | sip-zlm 3 个单测失败 | `Gb28181MediaPlatformE2ETest` 2× `403 Forbidden`、`RecordPlaybackUnitTest` 1× SDP 属性缺失；已用旧 pom 基线验证，**属重构前既有问题**，与合并无关 |
| 3 | 旧目录待删 | `IdeaProjects\gb28181-platform-starter`（1 GB）切换工作区后可删 |
| 4 | ~~六个独立仓库~~ | 已合并为单仓库 [rurulalanana/gb28181](https://github.com/rurulalanana/gb28181)，旧仓库已全部归档 |
| 5 | ~~Python 检查脚本硬编码口令~~ | 已改为从环境变量 `GB_KNOWN_SECRETS` 注入 |

---

## 5. git 状态明细

```
gb28181-sip                45ee754  干净，与 origin/main 同步
gb28181-sip-zlm            32cae67  1 处未跟踪：www/（本地调试页面）
sdk-dahua                  afa98f3  已推送 origin/main（2026-10 由 gb28181-dahua 改名）
gb28181-platform-starter   708fbc6  领先 origin/main 7 个提交 + 6 个文件未提交
```

starter 未推送的 7 个提交：

```
708fbc6  build: 重新构建前端产物
8990ac0  fix(playback): 拖动定位改用 SIP seek，不再停流重开
ae7834b  fix(channel): 去掉「上屏」按钮；预览弹窗改成无滚动布局
d9dfd8c  fix(channel): 云台面板撑高导致播放器下方大空白
b2b03bd  build: 重新构建前端产物
e7a6d85  refactor: 收敛四处重复代码（-101 行净）
aac5548  fix(sip): 拒绝公网扫描器的非法设备编码；/stream/info 认会话键
```

starter 未提交的 6 个文件（大华接入）：

```
 M .gitignore                                        sdk/ 排除（厂商版权）
 M pom.xml                                           加 sdk-dahua 依赖
 M src/.../entity/GbDevice.java                      加 username 字段
 M src/.../service/ControlService.java               按设备类型路由云台
 M src/.../web/DeviceController.java                 /adddahua /refreshsdk
 M src/main/resources/db/schema.sql                  username 列 + 迁移语句
?? .agents/skills/agent-architecture/
?? docs/大华SDK接入.md
?? src/.../service/DeviceAccessRegistry.java
?? src/.../service/DeviceProvisionService.java
```

### 提交信息编码（这个坑踩过两次）

PowerShell 管道会把中文变成 `?`，`Out-File -Encoding utf8` 会写 BOM。**唯一可靠写法**：

```powershell
$msg = @"
feat: 标题
"@
$p = Join-Path $env:TEMP '_m.txt'
[IO.File]::WriteAllText($p, ($msg -replace "`r`n","`n"), [Text.UTF8Encoding]::new($false))
git commit -F $p --quiet
```

提交后用 Python 验证（PowerShell 控制台显示乱码可能只是代码页问题）：

```powershell
py -c "import subprocess;print(subprocess.run(['git','log','--format=%s','-1'],capture_output=True).stdout.decode('utf-8'))"
```

---

## 6. 常用操作

### 部署到服务器

```powershell
cd gb28181-platform-starter
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' -o clean package -DskipTests
# 上传 target/*.jar 到 /opt/gb28181/
ssh root@160.202.254.65 -p 10103
cd /opt/gb28181 && ./gb.sh restart
```

`gb.sh start|stop|restart|status|jar`。
⚠️ **停止总是等 20 秒超时后强杀** —— 这是 ZLM 原生析构的已知问题，不是故障。

连接信息在 `.vscode/mcp.json`（已 gitignore，不入库）。

### 日志

```bash
tail -f /opt/gb28181/logs/console.log
grep -E 'Track not ready|Cached frame of unready track' /opt/gb28181/logs/console.log
grep 'All track ready use' /opt/gb28181/logs/console.log | tail -1
```

### 数据库

```bash
psql -h 127.0.0.1 -U postgres -d gb28181
```

表结构在 `src/main/resources/db/schema.sql`，启动时自动执行（幂等，含 `ALTER ... IF NOT EXISTS` 迁移）。

### 端口一览

| 端口 | 协议 | 用途 | 配置项 |
|---|---|---|---|
| 48092 | TCP | 平台 HTTP（线上；本地 48088） | `server.port` |
| 5060 | UDP/TCP | 国标 SIP | `gb28181.sip-port` |
| 24560 | TCP | ZLM RTP 收流 | `gb28181.zlm.rtp-single-port-number` |
| 8081 / 11935 / 18554 / 18000 | TCP | ZLM HTTP / RTMP / RTSP / WebRTC | `gb28181.zlm.*` |
| 49500 | TCP | 大华主动注册 | `dahua.server-mode.port` |
| **7660** | TCP+UDP | 海康 ISUP 注册（CMS/DAS） | `hikvision.cms.port` |
| **7665** | TCP | 海康 ISUP 取流（SMS） | `hikvision.stream.port` |
| **7666** | TCP | 海康 ISUP 回放 | `hikvision.playback.port` |
| **7663** | UDP | 海康报警 | `hikvision.alarm.port` |
| 7664 | TCP | 海康抓图直传（默认关闭） | `hikvision.picture.port` |

> 海康的 7660 / 7665 / 7666 是**三个独立监听器**，共用端口时后启动的返回
> 错误码 72（端口占用），表现为「注册在线但一点播就超时」。

---

## 7. 注意事项

- **不要在本目录 `git init`**。六个子目录各有自己的仓库，在这里建仓库会把它们全变成嵌套仓库。
- **不要创建 tag 或 GitHub Release 而不先确认**。
- **大华 / 海康 SDK 不能入库**：`sdk/` 与 `lib/` 已在各模块 `.gitignore` 里，厂商许可不允许随公开仓库分发。
- **`src/main/resources/static/` 是入库的**，改前端必须重新 build 并一起提交。
- 服务器 `/` 分区只有 30 GB，录像和日志都在这。已用到 84%，清理靠 `record.disk-max-used-percent`。
- `target/`、`node_modules/`、`sdk/` 已在工作区 `.vscode/settings.json` 里排除出搜索和文件监听，
  否则 5 万多个文件会把搜索拖死。

---

## 8. 文档索引

| 文档 | 位置 | 内容 |
|---|---|---|
| 部署说明 | `gb28181-platform-starter/docs/部署说明.md` | 服务器要求、内核参数（**必做**）、端口、首次/升级部署、验收清单 |
| **排障实录** | `gb28181-platform-starter/docs/排障实录.md` | **改动前必读**。已修复问题的根因与证据，含三次"怪设备实际是平台 bug"的教训 |
| 原生库打包（大华） | `sdk-dahua/docs/原生库打包.md` | 双平台库的生成、profile 切换、为何切换不残留 |
| **海康 ISUP 接入** | `sdk-hikvision/README.md` | 五条链路与端口、原生库部署布局、**与 ZLM 的 OpenSSL 符号冲突（必读）**、初始化顺序 |
| 大华 SDK 接入 | `gb28181-platform-starter/docs/大华SDK接入.md` | SDK 形态、原生库裁剪、踩坑、已落地代码结构、未解决项 |
| 功能对照 | `gb28181-platform-starter/docs/功能对照与查漏补缺.md` | 对标 WVP-GB28181-pro 的差距与补齐优先级 |
| 商用化差距 | `gb28181-platform-starter/docs/商用化差距分析.md` | 交付前必须补齐的能力 |
| GB/T 35114 | `gb28181-platform-starter/docs/GB35114-评估.md` | 安全接入标准评估 |
| 平台 README | `gb28181-platform-starter/README.md` | REST API、配置项、数据库、SIP 鉴权实现 |
| 设计稿 | `gb28181-platform-starter/design-plans/` | 前端交互改动的前后对比与取舍 |

### 动手前先看这两节

1. **`排障实录.md` §1.1** —— 三次"以为设备不配合"最后都是平台 bug。没有
   SIP 报文 / RTP 包 / ZLM 日志三者之一作为证据，就不算找到根因。
2. **`部署说明.md` §2.1** —— `net.core.rmem_max` 是必做项，漏掉会直接导致
   "重启后第一次播放黑屏"，而且日志看起来一切正常。
