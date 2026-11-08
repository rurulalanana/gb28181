# sdk-dahua

大华设备接入实现：**厂商无关的接入契约** + **大华 NetSDK 实现**。

与国标链路（GB/T 28181）**并列**，不是它的子模块 —— 本模块不经过 SIP、不用 MANSCDP、
不需要平台 20 位编码。它的存在意义是覆盖「国标做不到、必须走厂商私有协议」的那部分能力。

---

## 1. 为什么叫 `sdk-dahua`

命名约定是 `sdk-<厂商>`：

| 厂商 | 模块 |
|---|---|
| 大华 | `sdk-dahua` |
| 海康 | `sdk-hikvision`（待建） |
| 宇视 | `sdk-uniview`（待建） |

契约包 `io.github.rurulalanana.deviceaccess` 与厂商无关，**不随模块名变**。
所以既不叫 `gb28181-dahua`（平台已不只是国标平台），也不叫 `device-access`
（那是契约的名字，不是具体实现的）。

---

## 2. 结构

```
src/main/java/io/github/rurulalanana/
  deviceaccess/                     契约（与厂商无关，可被任何 sdk-* 复用）
    DeviceKind.java                 接入方式枚举（GB28181 / DAHUA / …）
    ConnectMode.java                连接模式（DIRECT / SERVER）
    DeviceContext.java              设备连接上下文（主机、端口、账号、序列号）
    DeviceAccessProvider.java       SPI 主接口（6 个方法，见 §3）
    DeviceRegisterTable.java        主动注册设备表
    RegisterListener.java           注册/注销回调

  dahua/                            大华实现
    DahuaDeviceAccessProvider.java  契约实现：探测 / 目录 / 流地址 / 云台
    DahuaRegisterListener.java      CLIENT_ListenServer 主动注册监听
    DahuaRegisterTable.java         注册表的线程安全实现
    internal/DahuaSdk.java          SDK 生命周期 + 登录会话（SN / host:port 双索引）
    internal/DahuaNativeLibrary.java 原生库平台探测 + 可操作的中文报错
    internal/DahuaPtzCommand.java   方向词 → SDK 指令码（纯逻辑，可单测）
```

---

## 3. 契约

`DeviceAccessProvider` 只描述协议差异，**不碰业务**：不读写数据库、不决定业务规则、不依赖 Spring。

| 方法 | 说明 |
|---|---|
| `kind()` | 本实现对应的接入方式 |
| `supportsServerMode()` | 是否支持「平台监听、设备主动注册」 |
| `probe(ctx)` | 探测设备可达（通常是一次登录/登出） |
| `listChannels(ctx)` | 拉取通道目录；不可达返回空列表而非抛异常 |
| `liveStreamUri(ctx, ch, n)` | 实时流拉流地址（`n`：0 主码流 / 1 子码流） |
| `playbackStreamUri(ctx, ch, s, e)` | 历史回放拉流地址 |
| `ptz(ctx, ch, cmd, speed)` | 云台控制，`speed` 0~255（与国标入参口径一致） |

**为什么返回拉流地址而不是直接推流**：拉流地址这条路复用现有的 FLV/WS-FLV/HLS/WebRTC
输出与前端播放器，平台侧不需要解析 PS 流；SDK 取码流那条路要自己做解复用与时间戳重排，
风险高得多。所以预览与回放统一走 RTSP。

**为什么不把国标也做成 provider**：GB28181 已是平台主链路，再包一层 SPI 只会多一次跳转。
本 SPI 只描述「国标做不到」的能力。

---

## 4. 原生库

大华 SDK 的 `.so` / `.dll` **随仓库携带**（`lib/linux64/`、`lib/win64/`，约 130 MB），
做到 clone 即可构建。版权归浙江大华技术股份有限公司所有，仅限在已获授权的设备接入场景使用。

官方 SDK 原始解压包 `sdk/`（约 700 MB）不入库，只有需要重新裁剪时才用：

```powershell
# 1. 把官方 SDK 解压到 sdk/dahua/（不入库）
# 2. 按 docs/原生库打包.md §1 裁剪出 lib/<platform>/
# 3. 按平台打包
mvn install "-Dnetsdk.platform=linux64"   # 出 Linux 包
mvn install "-Dnetsdk.platform=win64"     # 出 Windows 包
mvn install                                # 按当前操作系统自动选
```

⚠️ `-Dnetsdk.platform` **必须加引号**，否则 PowerShell 会拆开。

⚠️ **不要用 `-P` 切平台**：显式 `-P` 与自动激活的 profile 会同时生效，
`-Pnetsdk-linux64` 在 Windows 上会静默打出 win64 包。

详见 [`docs/原生库打包.md`](docs/原生库打包.md)。

---

## 5. 构建

`com.netsdk:netsdk` 不在中央仓库，大华也不提供公开 Maven 仓库。
本仓库把它放在 `lib/netsdk-repo/`（标准 Maven 目录布局，4.7 MB，只含 Java class），
以 `file://` 仓库的形式提供。

```powershell
# 全新 clone：第一次必须不带 -o
mvn install

# 之后可以离线
mvn -o install
```

⚠️ **`-o` 会拒绝 `file://` 仓库**（Maven 把本地文件仓库也视为远程）。
所以新电脑第一次构建必须不带 `-o`；成功一次后 jar 会缓存进本地仓库，之后 `-o` 正常。

`netsdk` 与 `jna` 在本模块是 **`provided`**，不会传递给使用方 ——
因此没装 SDK 的机器也能正常编译与运行国标链路。
需要大华功能时，由应用侧显式声明 `com.netsdk:netsdk` 的 compile 依赖
（`gb28181-platform-starter` 已这么做，并在自己的 pom 里声明了同一个 `file://` 仓库）。

---

## 6. 单测

```powershell
mvn test
```

26 个用例，覆盖 `DeviceKind` 兜底、`ConnectMode` 别名、`DeviceContext.connectable` 双判据、
PTZ 方向词映射（含 `upleft` / `leftup` / `up-left` / `UP_LEFT` 变体）、通道号反解、
流地址格式与 URL 编码。

全部是纯逻辑测试，**不需要真实设备，也不需要原生库**。

---

## 7. 边界

本模块**不做**这些事，做了就是越界：

- 读写数据库（设备/通道持久化由平台 Service 负责）
- 决定业务规则（能不能点播、是否超限由 Service 层判断）
- 依赖 Spring 容器（保证可用桩对象单测）
- 解析码流（统一交给媒体服务）

---

## 8. License

[Apache License 2.0](LICENSE)

大华 NetSDK 本身受大华厂商许可约束，**不包含在本仓库中**。
