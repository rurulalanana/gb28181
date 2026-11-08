# GB28181 视频平台 — 工作区说明

GB/T 28181 视频监控平台的六个 Maven 模块，**单 git 仓库 + 父子工程**：
根目录 `pom.xml` 是 parent（`io.github.rurulalanana:gb28181`），六个子目录是它的 module。

## 1. 模块与边界

| 目录 | 版本 | 字节码目标 | 职责 |
|---|---|---|---|
| `gb28181-sip/` | 1.0.14 | Java 17 | SIP 栈、MANSCDP、SDP、级联、集群。**零 Spring 依赖**，只做协议层 |
| `gb28181-sip-zlm/` | 1.0.11 | Java 17 | 内嵌 ZLMediaKit（zlm4j）、RTP 收流、录像检索、回放控制 |
| `device-access/` | 1.0.0 | Java 17 | 厂商无关契约：`DeviceKind` / `DeviceAccessProvider` / `DeviceMediaProvider` / `DeviceRegisterTable` |
| `sdk-dahua/` | 1.0.0 | Java 17 | 大华设备接入：`dahua` 实现 + **原生库** |
| `sdk-hikvision/` | 1.0.0 | Java 17 | 海康 ISUP（EHome）接入：注册/取流/回放/报警/抓图 + **原生库** |
| `gb28181-platform-starter/` | 1.0.2 | Java 17 | Spring Boot 3.2.5 应用、REST、Vue 前端、业务表 |

依赖方向单向：`starter → sip-zlm → sip`，`starter → sip`，
`starter → sdk-dahua → device-access`，`starter → sdk-hikvision → device-access`。

> **命名约定 `sdk-<厂商>`**：接海康就是平级的 `sdk-hikvision`，接宇视就是 `sdk-uniview`。
> 契约包 `io.github.rurulalanana.deviceaccess` 与厂商无关，不随模块名变。
> 所以既不叫 `gb28181-dahua`（平台已不只是国标平台），也不叫 `device-access`
> （那是契约的名字，不是具体实现的）。
> 该模块与国标链路**并列**（不经过 SIP、不用 MANSCDP），
> 将来接海康/宇视时新增实现即可，artifactId 与契约包都不用改。

**改代码前先定位层次**：协议问题改 sip，收流/媒体改 sip-zlm，业务/接口改 starter，
厂商 SDK 问题改 `sdk-<厂商>`，跨厂商契约改 `device-access`。
不要在 sip / sip-zlm 里引入 Spring 或数据库依赖。

## 2. 构建

### 环境（每个新终端都要设）

```powershell
$env:JAVA_HOME='C:\Program Files\BellSoft\LibericaJDK-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

Maven：`D:\maven\apache-maven-3.6.3\bin\mvn.cmd`（已在 PATH，直接用 `mvn` 也可）。
本地仓库 `D:\maven\repository`。`-o` = 离线模式。

```powershell
mvn -o install -DskipTests "-Dgpg.skip=true"          # 全部六个模块
mvn -o install -DskipTests -pl gb28181-sip,gb28181-sip-zlm,gb28181-platform-starter   # 跳过 dahua/hikvision
```

`-Dgpg.skip=true` **必须加引号**，否则 PowerShell 会拆开。
构建顺序由 Maven 自动推导，不需要手工排序。

### 大华原生库按平台打包

原生库在 `sdk-dahua/lib/<platform>/`（**不入库**，厂商许可），
按 profile 打进 sdk-dahua 的 jar：

```powershell
mvn -o install -DskipTests                              # 按当前操作系统自动选
mvn -o install -DskipTests "-Dnetsdk.platform=linux64"  # 交叉打包：出 Linux 包
mvn -o install -DskipTests "-Dnetsdk.platform=win64"    # 交叉打包：出 Windows 包
```

⚠️ `-Dnetsdk.platform` **必须加引号**（同 `-Dgpg.skip` 的坑）。
**不要用 `-P` 切平台**：显式 `-P` 与自动激活的 profile 会同时生效，
谁最后求值谁赢，`-Pnetsdk-linux64` 在 Windows 上会静默打出 win64 包。

`sdk-dahua` 需要 `com.netsdk:netsdk` 已 install 到本地仓库（不在中央仓库），
否则构建失败。未安装时用上面的 `-pl` 形式跳过。

### 海康原生库按平台打包

同样的 profile 机制，库在 `sdk-hikvision/lib/<platform>/`：

```powershell
mvn -o install -DskipTests "-Dhikvision.platform=linux64"
mvn -o install -DskipTests "-Dhikvision.platform=win64"
```

### ⚠️ 交叉打包后必须重打 starter（顺序不能反）

starter 的 fat jar **内嵌**了 `sdk-dahua` / `sdk-hikvision` 的 jar。
只 `package -pl gb28181-platform-starter` 会复用本地仓库里上次装的版本，
而它可能是 Windows 默认 profile 打出来的。

部署到 Linux 的正确顺序：

```powershell
mvn -o install -DskipTests "-Dgpg.skip=true" "-Dnetsdk.platform=linux64" "-Dhikvision.platform=linux64" -pl sdk-dahua,sdk-hikvision
mvn -o clean package -DskipTests -pl gb28181-platform-starter
```

jar 大小也是提示：大华 win64 版 ~15MB / linux64 版 ~36MB。
打错平台的表现：启动日志报「原生库缺失：当前平台为 linux64，但 jar 内没有 linux64 目录」，
**大华主动注册监听根本不会启动**，设备侧一直发注册但平台侧没在听。

### 前端

```powershell
npm --prefix gb28181-platform-starter/frontend run dev     # Vite，proxy 到 160.202.254.65
npm --prefix gb28181-platform-starter/frontend run build   # 产物写入 src/main/resources/static/
```

⚠️ `vite.config.js` 里 `emptyOutDir: true`，build 会**清空** `src/main/resources/static/`。
该目录**是入库的**，所以改完前端必须重新 build 并一起提交，否则线上仍是旧页面。

## 3. 硬约束（违反会出线上事故）

- **统一 Java 17**：全工程用 `maven.compiler.release=17`（定义在根 pom 的 `java.version`）。
  各模块不写 `source`/`target`，统一用 `release` —— 它会同时校验语法级别与平台 API，
  能拦住「本地 JDK 21 编译通过、目标 JDK 17 上 NoSuchMethodError」。
  sip / sip-zlm 早期是 Java 8，合并成单仓库后统一到 17；它们的已发布版本 1.0.14 / 1.0.11
  是 Java 8 字节码，**再发版必须提版本号**。
- **不要在子模块写 `java.version`**：父 pom 是唯一来源，子模块写了会覆盖成两套口径。
- **升级模块版本改两处**：根 `pom.xml` 的 `dependencyManagement`（内部模块版本）
  与 `gb28181-platform-starter/pom.xml` 的 `sip.module.version` / `zlm.module.version` 等属性。
- **不要把密码写进 `application.yml`**：它会被打进 jar 并公开。走环境变量
  `GB_DB_PASSWORD` / `GB_DB_URL` / `GB_DB_USERNAME`，本地覆盖写 `application-local.yml`（已 gitignore）。
  提交前跑 `py gb28181-platform-starter/scripts/check-secrets.py` 自查。
- **`lib/` 入库（Git LFS）、`sdk/` 不入库**：`lib/<platform>/` 是为了让 clone 后能直接构建，
  由 `.gitattributes` 把 `*.dll` / `*.so` / `*.jar` 交给 LFS 管理；
  `sdk/`（厂商原始解压包，含全平台+demo，>300MB）只保留在本地，构建不需要。
- **不要删除根目录的 `.git`**：六个模块是这个仓库的子目录，不是嵌套仓库。
  子目录里不要再 `git init`（历史上曾经是六个独立仓库，已合并）。
- **不要用 PowerShell 做文本替换**：`-replace | Set-Content` 按 cp936 解码 UTF-8，中文会变 `?`
  且不可逆。用编辑工具或 Python。

### 端口分配（冲突会直接导致功能不可用）

| 端口 | 协议 | 用途 |
|---|---|---|
| 48092 | TCP | 平台 HTTP（线上；本地 48088） |
| 5060 | UDP/TCP | 国标 SIP |
| 24560 | TCP | ZLM RTP 收流 |
| 49500 | TCP | 大华主动注册 |
| **7660** | TCP+UDP | 海康 ISUP 注册（CMS/DAS） |
| **7665** | TCP | 海康 ISUP 取流（SMS） |
| **7666** | TCP | 海康 ISUP 回放 |
| **7663** | UDP | 海康报警 |

海康的 7660 / 7665 / 7666 是**三个独立监听器**，共用端口时后启动的返回错误码 72（端口占用），
表现为「注册在线但一点播就超时」。曾把 `stream.port` 误配成 7660 并在注释里
写「CMS 与 SMS 共用端口，SDK 按首包区分」——**那是错的**。

## 4. 代码约定

### REST（starter）

- 统一响应 `{"code":0,"msg":"ok","data":{...}}`，**HTTP 状态码恒为 200**，真实结果看 `code`。
- `code`：0 成功 / 400 参数错 / 401 未登录 / 403 无权限 / 500 服务端异常。
- 抛 `BizException.badRequest(...)` / `.forbidden(...)`，由 `GlobalExceptionHandler` 转换。
  **不要**在 Controller 里手写 `ApiResponse.fail`。
- 请求参数 **snake_case**（`streamid`、`check_outputs`），响应字段 **camelCase**。
- 分页是游标语义：`start` 偏移 + `limit` 页大小，`limit=0` 表示不分页。
- 出参一律是 `vo` 包下的强类型对象，**不要用 `Map<String,Object>` 拼**。`null` 字段序列化时省略。
- 接口路径前缀 `/api/v1/`，对齐青瓜视频 GBS 的命名。

### 鉴权

Sa-Token，`Authorization: <token>` 头（也支持请求参数，供 `<video src>` 使用）。
会话持久化在 PostgreSQL `sa_token_store`，重启不掉登录态。

`WritePermissionInterceptor` 是**默认拒绝**：非 GET 请求一律要求写权限，
白名单只放「改自己的东西」。新增接口不用加注解也不会漏，但**不要往白名单里加业务接口**。
角色：`admin` / `operator` / 其它=只读。

### 数据库

PostgreSQL + MyBatis-Plus。表结构在 `src/main/resources/db/schema.sql`，**启动时自动执行**。

该文件是「全新初始化 + 幂等补丁」脚本：`CREATE TABLE IF NOT EXISTS` **不会给已存在的表加列**，
所以给老库加字段必须同时写 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`。
改表结构时两段都要写，否则新库正常、老库启动即报错。

### 配置前缀

- `gb28181.*` —— **国标标准相关**（SIP、媒体、录像、平台编码）
- `gb28181.oss.*` —— 对象存储
- `dahua.*` —— **大华 SDK 直连**（与国标并列的第二条接入通道，不经过 SIP）

厂商私有参数**不要**挂到 `gb28181` 下：那会暗示「这是国标子项」。
将来接海康/宇视应当是并列的 `hikvision.*` / `uniview.*`。

### 前端

Vue 3 + Vite 6 + Element Plus + Pinia（自动导入，无需手写 `import { ref }`）。
`@` 别名指向 `frontend/src`。API 客户端在 `api/http.js`：拦截器已经把
`{code,msg,data}` 拆成 `data` 直接返回，并把错误转成中文 `Error.message`，
调用方统一 `ElMessage.error(e.message || '保存失败')`。

## 5. 排障纪律（最重要的一节）

`gb28181-platform-starter/docs/排障实录.md` §1.1 记录了**三次「以为设备不配合，最后都是平台 bug」**：

| 现象 | 误判 | 真因 |
|---|---|---|
| 录像检索永远超时 | 设备不支持 RecordInfo | 时间格式用了空格而非 `T` |
| 进度条拖动无效 | 设备不支持 seek | INFO 的 From/To tag 反了 |
| 点播黑屏 | 设备推流慢 | 等轨超时太短，ZLM 永久丢弃 H264 轨 |

**没有 SIP 报文 / RTP 包 / ZLM 日志三者之一作为证据，不算找到根因。**
仅凭「试了一下还是不行」就改代码，会在错误方向上越走越远。

另外两条容易误判的：
- 「平台没收到应答」≠「设备没收到请求」；「设备回 200 OK」≠「设备执行了」。判断要看副作用。
- 服务重启后第一次播放黑屏 → 先查 `net.core.rmem_max`（`docs/部署说明.md` §2.1，必做项）。

## 6. 已知的坑（踩过的）

- **停机必然等超时**：`gb.sh restart` 停止阶段总是 20 秒超时后强杀，这是 ZLM 原生析构的已知问题，
  不是故障。`Gb28181PlatformApplication` 里有 30 秒 halt 看门狗兜底。
- **jain-sip 硬依赖 log4j 1.x**：`log4j-over-slf4j` 不提供 `Layout`，排除后启动直接抛
  `PeerUnavailableException`。`gb28181-sip` 必须保留 `log4j` 依赖。
- **ZLM 原生库会 SIGABRT**：流异常断开时 C++ 析构抛异常 → `std::terminate()`。Java 侧兜不住。
  缓解手段是缩短分片（`record-max-second: 1800` = 一次崩溃最多丢 30 分钟录像）。
- **jessibuca 而不是 mpegts.js**：国标设备推 PCMA/PCMU 音频，MSE 不支持，
  必须用 WASM 软解码。且不能给 jessibuca 的容器加尺寸/缩放样式，否则画面缩到左上角。
- **不要覆盖 xgplayer 的 `play`/`pause`**：播放器内部也调它们，会把内部调用误判成用户操作
  （曾因此向设备发 PAUSE 导致反复断流）。只有 `playPause` 是真实用户入口。
- **Maven 增量编译会伪装成编译通过**：日志只有 `Nothing to compile` 时并未真正编译。
  验证真伪要删掉 `target/classes` 强制全量。

## 7. 提交与部署

### 提交信息编码

PowerShell 管道会把中文变 `?`，`Out-File -Encoding utf8` 会写 BOM。可靠写法：

```powershell
$msg = @"
feat: 标题
"@
$p = Join-Path $env:TEMP '_m.txt'
[IO.File]::WriteAllText($p, ($msg -replace "`r`n","`n"), [Text.UTF8Encoding]::new($false))
git commit -F $p --quiet
```

### 部署

```powershell
cd gb28181-platform-starter
mvn -o clean package -DskipTests
# scp target/*.jar 到 /opt/gb28181/ ，再 ./gb.sh restart
```

`gb.sh start|stop|restart|status|jar`。jar 文件名不固定，按 glob 找最新的。
连接信息在 `.vscode/mcp.json`（已 gitignore）。

## 8. 关键文件地图

```
gb28181-sip/src/main/java/.../sip/
  Gb28181SipServer.java      服务端入口（builder）
  Gb28181SipClient.java      主动下发命令（queryCatalog/invitePlay/ptzControl/…）
  event/SipCallback.java     所有回调接口（注册/心跳/MANSCDP/INVITE/BYE/级联）
  manscdp/                   MANSCDP XML 编解码
  security/                  Digest 鉴权、黑白名单 SPI
  cluster/                   Redis 设备表与信令转发（可选）

gb28181-sip-zlm/src/main/java/.../sip/zlm/
  Gb28181MediaPlatform.java  信令 + 媒体的装配体（starter 的 @Bean 就是它）
  Gb28181MediaService.java   play / playback / playbackControl / download
  ZlmMediaServer.java        ZLM C API 封装（原生库加载、配置、钩子）
  RtpSessionManager.java     收流会话生命周期 + SessionListener
  RecordingService.java      平台侧录像

sdk-dahua/src/main/java/.../
  deviceaccess/                契约：DeviceKind / ConnectMode / DeviceContext
                               / DeviceAccessProvider / DeviceRegisterTable / RegisterListener
  dahua/DahuaDeviceAccessProvider.java  契约实现：探测/目录/流地址/云台
  dahua/DahuaRegisterListener.java      CLIENT_ListenServer 主动注册监听
  dahua/internal/DahuaSdk.java          SDK 生命周期 + 登录会话（SN / host:port 双索引）
  dahua/internal/DahuaNativeLibrary.java 原生库平台探测 + 可操作的中文报错
  dahua/internal/DahuaPtzCommand.java   方向词 → SDK 指令码（纯逻辑，可单测）

sdk-hikvision/src/main/java/.../hikvision/
  HikvisionDeviceAccessProvider.java  契约实现：探测/目录/流地址
  HikvisionMediaProvider.java          取流 / 回放（ISUP SMS）
  HikvisionCmsListener.java            NET_ECMS_StartListen 注册监听
  HikvisionStreamListener.java         NET_ESTREAM_StartListen 取流监听
  HikvisionPlaybackListener.java       回放监听
  HikvisionAlarmListener.java          NET_EALARM_StartListen 报警监听
  HikvisionRegisterTable.java          注册表（含保活，同大华）
  HikvisionIsapi.java                  ISAPI 配置读写（编码/图像/设备名/录像计划）
  internal/HikvisionSdk.java           SDK 生命周期 + RTLD_DEEPBIND 加载 OpenSSL
  internal/HikvisionNativeLibrary.java 原生库布局（user.dir/lib）+ 硬链接归一
  internal/ElfRpathPatcher.java        给 .so 补 $ORIGIN RUNPATH

gb28181-platform-starter/src/main/java/.../starter/
  config/Gb28181PlatformConfiguration.java   装配 Gb28181MediaPlatform（看这个理解启动流程）
  config/DahuaConfiguration.java             装配大华 provider / 监听 / 关闭钩子（懒加载）
  config/DahuaProperties.java                大华配置（dahua.*），与国标配置分开
  config/Gb28181Properties.java              所有 gb28181.* 配置项，含大量注释
  service/SipEventBridge.java                实现 SipCallback，SIP 事件 → 业务
  service/StreamEventBridge.java             实现 SessionListener，流事件 → gb_stream
  service/DeviceAccessRegistry.java          ObjectProvider 延迟解析 provider（SDK 缺失不影响启动）
  service/DeviceProvisionService.java        SDK 设备纳管 + 目录同步（复用 saveCatalog）
  service/PlatformScheduler.java             14 个定时任务（心跳巡检/录像对账/磁盘清理…）
  web/*.java                                 19 个 Controller，路径对齐 GBS
  common/                                    ApiResponse / BizException / Numbers / TimeFormats
```

## 9. 文档索引

| 文档 | 什么时候读 |
|---|---|
| `gb28181-platform-starter/docs/排障实录.md` | **改动前必读**。已修复问题的根因与证据 |
| `gb28181-platform-starter/docs/部署说明.md` | 部署、内核参数（必做）、端口、验收清单 |
| `sdk-dahua/docs/原生库打包.md` | 原生库怎么生成、按平台打包、切换平台为何不残留 |
| `sdk-hikvision/README.md` | 海康 ISUP 接入、五条链路端口、原生库部署布局、**与 ZLM 的 OpenSSL 符号冲突（必读）** |
| `gb28181-platform-starter/docs/大华SDK接入.md` | 大华 SDK 形态、库裁剪、平台侧接入方式 |
| `gb28181-platform-starter/docs/功能对照与查漏补缺.md` | 对标 WVP-GB28181-pro 的差距 |
| `gb28181-platform-starter/README.md` | REST API 全表、配置项、SIP 鉴权实现 |
| 根 `README.md` | 模块关系、构建命令、当前 git 状态明细 |
