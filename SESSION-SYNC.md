# 双会话协作交接单

```
DEPLOY-LOCK: none   17:52 部署完成（jar dc8db47f，线上 PID 2861062，0 ERROR）
```

> 用法：谁要部署，先把上面这行改成 `DEPLOY-LOCK: 会话X 14:XX`，部署完改回 `none`。
> 看到不是 `none` 就不要动服务器。

> 生成时间：2026-11-02 14:15（北京时间）
> 生成方：会话 A（负责「登录安全加固」）
> 接收方：会话 B（负责「权限点细化」，从 `Permissions` / `RoleController` / `RoleView` 的改动判断）

---

## 0. 最重要：当前线上登录完全不可用（P0）

> **【15:35 已修复并部署，本节留作记录】**
> 线上 jar `0dc217f3`，PID 2778194，0 ERROR。验证码可正常生成（底图 600×360 + 拼图块），
> 密码登录经滑块验证可用，API 密钥登录经「生成 → 登录 → 撤销」全链路实测通过。
> 以下为当时的故障记录。

```
POST /api/v1/captcha/gen?type=SLIDER
→ {"code":400,"msg":"参数错误：无法读到指定的资源[classpath]Resource(... data=META-INF/cut-image/resource/slider_1/fixed.png ...)"}

POST /api/v1/login  {"username":"admin","password":"..."}
→ {"code":400,"msg":"请先完成验证码"}
```

**登录被验证码挡住了，而验证码生成失败 → 任何人都登不进去，包括 admin。**

### 根因（会话 A 已定位并修好，但**还没打包部署**）

`application.yml` 里我加的 `captcha.default-resource-prefix` **对模板也生效**：

- 期望：模板从 `META-INF/cut-image/template/slider_1/fixed.png` 读
- 实际：被拼成 `META-INF/cut-image/resource/slider_1/fixed.png`（目录错，文件不存在）

修复 = **删掉 `default-resource-prefix` 这一行**（背景图由 `CaptchaResourceConfiguration` 自己注入，不依赖配置）。
本地已改、`mvn -o compile` 通过（会话 A + 会话 B 的代码一起编译成功），但**服务上跑的仍是坏配置**。

**已用字节码确认**（`javap -c SpringImageCaptchaProperties`）：

```
13: ldc           // String classpath:META-INF/cut-image/template
15: putfield      // Field defaultResourcePrefix
```

即不配这一项时，模板会从 `META-INF/cut-image/template/` 读，`slider_1/fixed.png` 确实存在。
背景图不受这个前缀影响（它只拼模板路径），我的显式注册带完整路径，两边互不干扰。

> ⚠️ 仍未实机验证 —— 需要一次部署 + `POST /api/v1/captcha/gen?type=SLIDER` 返回 200 才算数。

### 谁来做这个部署？请二选一

| 方案 | 说明 |
|---|---|
| **A 会话 B 顺手带上** | 你下次打包部署时，把 `application.yml` 的这次改动一起带上即可（我已在工作区改好） |
| **B 会话 A 单独部署** | 我只打一个最小修复包，但会把**你正在改的权限点代码一起打进去**（工作区是共享的，无法只打我的改动） |

> 会话 A 的倾向：**方案 A**。理由见下面第 4 节「部署冲突」。

---

## 1. 会话 A 做了什么（未提交，全在工作区）

### 1.0 17:52 部署：目标跟踪 / 拉框缩放按 2022 标准重写（最新）

线上 jar `dc8db47f`，PID 2861062，启动 6.971s，0 ERROR。

**这三个命令以前是错的，报文发给设备也不会生效**（`CmdType` 写错，设备按未知命令丢弃）。
现已全部按 GB/T 28181—2022 附录 A 重写，并**抓包验证过实际报文**：

| 命令 | 改前（错） | 改后（对） |
|---|---|---|
| `targettrack` | `<CmdType>TargetTrack</CmdType>`，坐标平铺 | `<CmdType>DeviceControl</CmdType>`，坐标嵌 `<TargetArea>`，拼写 `MidPointX` |
| `dragzoomin/out` | 11 字节 PTZCmd `0x81/0x82`（不在标准里，且与预置位同码） | 独立元素 `<DragZoomIn>/<DragZoomOut>` |
| `deviceupgrade` | `<CmdType>DeviceUpgrade</CmdType>`，字段平铺，无 SessionID | `<CmdType>DeviceControl</CmdType>`，字段嵌 `<DeviceUpgrade>`，带 `SessionID` |

实际抓到的报文（发往 `34020000001320000001`）：

```xml
<Control><CmdType>DeviceControl</CmdType><SN>9</SN>
  <DeviceID>34020000001320000001</DeviceID>
  <TargetTrack>Manual</TargetTrack>
  <TargetArea><Length>1920</Length><Width>1080</Width>
    <MidPointX>960</MidPointX><MidPointY>540</MidPointY>
    <LengthX>300</LengthX><LengthY>200</LengthY></TargetArea></Control>
```

**新增能力**：

| 文件 | 说明 |
|---|---|
| `service/ControlAckService.java` | 新增。按 `通道\|SN` 等设备回 `<Response>`（A.2.6.2），把「已下发」变成「设备说了什么」 |
| `vo/ControlResultVo.java` | 新增。`sent` / `answered` / `result` / `sn` 四态，界面能区分「受理」「拒绝」「没回话」 |
| `service/ControlService.java` | `targetTrack` / `dragZoom` / `deviceUpgrade` 改返回 `ControlResultVo`，等 2.5 秒应答 |
| `service/SipEventBridge.java` | 新增 `DeviceControl` 应答分支 + `DeviceUpgradeResult` 通知（A.2.5.9，MESSAGE 与 NOTIFY 都接） |
| `common/PtzEncoder.java` | **删掉** `dragZoom`。`0x81/0x82` 现在只表示预置位，不可能再被误判 |
| `frontend/src/components/TrackBox.vue` | 新增。播放器上的框选图层（自动扣黑边偏移，换算到视频原始分辨率） |
| `frontend/src/components/PtzPanel.vue` | 高级 Tab 新增「自动跟踪 / 框选跟踪」，`emit('track')` 交给父组件开图层 |
| `frontend/src/views/ChannelView.vue` | 预览弹窗接入框选 |
| `frontend/src/views/LiveView.vue` | 实时预览每格接入框选 + 跟踪按钮行 |

**设备兼容性的实话**（别对客户过度承诺）：

抓包看到目标设备是**级联下级平台 LiveGBS v250103**，它只回 `SIP/2.0 200 OK`，
**不回 MANSCDP `<Response>`** —— 所以接口返回 `answered:false`。
这是正常现象（多数设备对控制命令不做应用层应答），不是 bug。
目标跟踪能否真的锁上目标取决于**球机固件**，标准里没有结果回传通道。

**删除的配置项**：`gb28181.drag-zoom-mode`（用户要求只走 2022 标准，不兼容老设备）。

### 1.1 登录防护（新增）

| 文件 | 说明 |
|---|---|
| `pom.xml` | 新增 `cloud.tianai.captcha:tianai-captcha-springboot-starter:1.5.6` |
| `config/CaptchaResourceConfiguration.java` | 新增。给 `ResourceStore` 注册背景图（先扫 `classpath:captcha-bg/*`，没有则回退核心包 `META-INF/cut-image/resource/1.jpg`） |
| `service/LoginGuardService.java` | 新增。验证码生成/校验 + 账号&IP 双维度失败计数 + 时间制锁定 |
| `web/CaptchaController.java` | 新增。`POST /api/v1/captcha/gen`（生成）、`POST /api/v1/captcha/check`（校验轨迹） |
| `web/SystemController.java` | `login()` 接入：`assertNotLocked` → `assertCaptchaPassed` → `authenticate` → `onSuccess/onFailure`；`LoginRequest` 新增 `captchaId` 字段 |
| `config/SaTokenConfiguration.java` | 白名单加 `/api/v1/captcha/**`（它必须免登录） |
| `frontend/src/components/SlideCaptcha.vue` | 新增。滑块验证码弹窗（自绘，不依赖官方 SDK） |
| `frontend/src/views/LoginView.vue` | 点「登录」先弹验证码，通过后带 `captchaId` 提交 |
| `frontend/src/stores/auth.js` | `login(username, password, captchaId)` |
| `frontend/src/api/index.js` | 新增 `captchaApi.gen / captchaApi.check` |
| `application.yml` | 新增顶层 `captcha:` 配置 + `gb28181.login.*`（验证码总开关是 `gb28181.login.captcha-enabled`） |
| `db/schema.sql` | `gb_user` 新增 `api_key_hash` / `api_key_hint` / `api_key_created_at` + 唯一索引 |
| `service/UserService.java` | 新增 `generateApiKey` / `revokeApiKey` / `apiKeyStatus` / `authenticateByApiKey` / `hashApiKey` |
| `web/UserController.java` | 新增 3 个自助接口 `/api/v1/user/apikey[/generate|/revoke]` |
| `vo/ApiKeyVo.java` | 新增（key 仅生成时返回，其余场景只给 hint） |
| `frontend/src/views/SettingsView.vue` | 「账号安全」新增 API 密钥面板（生成 / 复制 / 撤销） |
| `src/test/java/.../service/ApiKeyHashTest.java` | 新增 4 个用例：哈希稳定性/大小写/无碰撞/密钥长度 |

**登录防护的三层**（改之前实测 421ms/次、零限制，单机一天可试 20 万次）：

1. 每次登录都要过滑块（`required-after-failures: 0`）
2. 账号 10 次失败锁 30 分钟
3. 同 IP 50 次失败锁 30 分钟（防「一个口令试一万个用户名」）

计数**只在内存**，重启即清空 —— 这是刻意的：避免攻击者把 admin 永久锁死。

### 1.1.1 机器客户端不能拖滑块（这条很重要，别漏部署）

验证码如果一刀切「每次登录都要」，**业务平台/脚本/Postman/接口文档调试面板全登不进去**。
所以登录接口另开一条通道，用的是**每个用户自己的 API 密钥**：

```
POST /api/v1/login
X-Api-Key: <该用户的密钥>

body: 可省略（密钥已标识账号）
```

- 密钥在「设置 → 账号安全 → API 密钥」里由**用户自己生成**，不写配置文件、不进仓库
- 库里只存 **SHA-256 哈希** + 前 8 位 hint，明文只在生成时返回一次
- 重新生成或撤销后旧密钥立即失效（`gb_user.api_key_hash` 被覆盖/置空）
- 无效密钥按 **IP** 计入失败，狂试会触发 IP 锁定（不计账号维度，因为密钥没对上任何账号）
- 数据库新增 3 列 + 1 个唯一索引，已写进 `db/schema.sql` 的幂等补丁区，启动自动执行

> 部署时**不需要**给 `.env` 加任何密钥变量（上一版方案里的 `GB_API_KEY` 已废弃）。

### 1.2 接口文档默认要口令

`application.yml`：`gb28181.api-docs.protect` 由 `false` 改为 **`true`**。

- 服务器 `/opt/gb28181/.env` 已加 `GB_DOCS_PASSWORD`（随机 20 位，权限 600，会话 A 生成，明文未外传）
- 口令为空时**启动直接失败**（`ApiDocsProtectionFilter` 的构造器校验）
- 需要口令的话：`ssh` 上去 `grep GB_DOCS_PASSWORD /opt/gb28181/.env`

已验证生效：`/v3/api-docs/auth` 与 `/doc.html` 都返回 401。

### 1.3 nginx（已在服务器改好并 reload）

| 文件 | 改动 |
|---|---|
| `/etc/nginx/nginx.conf` | `http {` 内加 `server_tokens off;` —— 响应头不再出现 `nginx/1.14.1` |
| `/etc/nginx/conf.d/gb28181-record.conf` | server 级 + `/record/` location 内各加一套安全头 |
| `/etc/nginx/conf.d/gb28181-ssl.conf` | 加 HSTS + 同一套安全头 |

加的头：

```
X-Content-Type-Options: nosniff
X-Frame-Options: SAMEORIGIN
Referrer-Policy: strict-origin-when-cross-origin
Content-Security-Policy: frame-ancestors 'self'; base-uri 'self'; form-action 'self'
Permissions-Policy: geolocation=(), microphone=(), camera=()
Strict-Transport-Security: max-age=15552000     （仅 48087 HTTPS 端口）
```

**CSP 故意不限制 `script-src`**：Knife4j 的 `/doc.html` 是内联脚本页面，强 CSP 会把文档打不开。

备份都在原目录：`/etc/nginx/nginx.conf.bak.20261102134827`、`gb28181-record.conf.bak.20261102134827`。

> ⚠️ **nginx 版本 1.14.1 本身没升级**。CentOS 8 的 AppStream 只提供 1.14.1-9，升不上去；隐藏版本号只能挡住「按版本号找 CVE」的自动化扫描，挡不住有针对性的利用。真要解决得换 nginx 官方源或加 WAF —— 这是部署决策，会话 A 没动。

---

## 2. 会话 B 做了什么（从时间戳与文件推断，请自行补充纠正）

最近修改的源文件里，不属于会话 A 的：

| 时间 | 文件 |
|---|---|
| 14:00:41 | `frontend/src/views/RoleView.vue` |
| 13:57:19 | `frontend/src/components/PtzPanel.vue` |
| 13:55:27 | `config/WritePermissionInterceptor.java` |
| 13:55:19 | `web/RoleController.java` |
| 13:55:13 | `vo/PermissionVo.java` |
| 13:55:06 | `common/Permissions.java` |

→ 判断为**权限点细化**（新增权限点、角色授权联动、写权限守卫调整、角色页 UI）。

**与会话 A 没有文件重叠**，两边代码已确认可以一起编译通过。

### 需要会话 B 注意一处交集

`WritePermissionInterceptor` 是**默认拒绝**的。会话 A 新增了 `/api/v1/captcha/**`：

- 它不是 GET，理论上会被写权限拦截器拦
- 会话 A 已在 `SaTokenConfiguration` 的**登录**白名单里放行 `/api/v1/captcha/**`
- 但**写权限拦截器自己还有一份白名单**（`SELF_SERVICE_PATHS` 之类）—— 如果里面没有 captcha，`/captcha/gen` 会不会被 403，取决于你的拦截器有没有对「免登录路径」统一放行

**请在改 `WritePermissionInterceptor` 时确认这一点**，或者告诉我该往哪个常量里加，我来加（避免两人同时改同一个文件）。

---

## 3. 服务器共享状态（两个会话都会碰到）

| 项目 | 值 |
|---|---|
| 部署目录 | `/opt/gb28181` |
| jar | `gb28181-platform-starter-1.0.2.jar`，当前 MD5 `cc913006d352fea8fa137fb7be57b91f`（14:07，**会话 B 打的包**） |
| 当前进程 | JVM PID **2727239**（14:09 启动，运行中，正在正常点播出流） |
| 启停 | `cd /opt/gb28181 && ./gb.sh restart\|start\|stop\|status` |
| 日志 | `/opt/gb28181/logs/console.log` |
| .env | 600 权限，键：`GB_DB_PASSWORD` / `GB_APP_PORT` / `GB_DOCS_PASSWORD` |
| 备份 jar | `gb28181-platform-starter-1.0.2.jar.bak.*`，共 5 个，**占约 500MB，磁盘 84%**，可择机清理 |

**重启必然等 20 秒超时后强杀**（ZLM 原生析构卡住，已知问题），不是故障。

---

## 4. 部署冲突记录（这就是建议「方案 A」的原因）

```
13:51  会话 A 上传 b16be73...  重启 → 验证码 500（YAML 前缀写错）
13:54  会话 A 上传 5f53bbb...  重启 → 验证码 500（资源仍未加载）
14:05  会话 A 上传 b104e2bb... 重启 → 背景图注册成功，但模板路径错 → 400
14:07  会话 B 上传 cc913006... 覆盖 jar（含会话 A 的类 + 会话 B 的权限改动）
14:08  会话 A 尝试 start，恰好落在会话 B 的 stop/start 空档 → 误判「服务停了」
14:09  会话 B 的实例起来（PID 2727239），工作正常
```

两个会话**共用同一个本地工作区、同一个 Maven 仓库、同一台服务器、同一个 jar 路径**。
任何一方 `mvn package` 都会把**对方未提交的改动一起打进去**；任何一方 `gb.sh restart` 都会**踢掉对方的进程**。

**结论：不要在对方部署期间操作。** 建议按第 0 节选一个人统一部署。

---

## 5. 待办清单

### 会话 A 侧

- [x] 定位验证码失败根因（`default-resource-prefix` 污染模板路径）
- [x] 本地修复（删掉该配置），`mvn compile` 通过
- [ ] **等部署**（第 0 节的方案 A 或 B）
- [ ] 部署后验证：`captcha/gen` 返回 200 + 底图/拼图块 base64；登录能过；失败 10 次锁定；
      **在「设置 → 账号安全」生成密钥后，带 `X-Api-Key` 登录能拿到 token**
- [ ] 两个仓库都**尚未提交**（见下）

### 会话 B 侧（请补充）

- [ ] 权限点改动收尾
- [ ] 确认 `/api/v1/captcha/**` 不被写权限拦截器误拦
- [ ] 部署时带上 `application.yml` 的 captcha 修复（第 0 节）

### 共同

- [ ] `gb28181-platform-starter` 未提交改动约 240 项（含重新构建的 `static/assets`）
- [ ] 建议提交信息拆分：① 注释精简（-50%）② 登录安全加固 ③ 权限点细化

---

## 6. 会话 A 想确认的三件事

1. **第 0 节的部署由谁做？** 我改好了但没部署，因为不想把你正在改的权限点代码推上去。
2. **`WritePermissionInterceptor` 的免登白名单**：`/api/v1/captcha/**` 要不要加、加在哪个常量里？告诉我我来加，或者你加。
3. **`Permissions.java` 会话 A 之前也改过**（注释精简，纯注释）。你 13:55 又改了它 —— 如果你是在我的版本上改的就没问题；如果是并发编辑，请确认没丢注释精简的部分（`git diff` 看非注释行是否一致即可）。

---

## 7. 一个建议

这类「两个会话同时改同一个项目」的协作，成本主要在**部署**而不是写代码。建议约定：

- 本地工作区**可以共用**（编译互不影响）
- **部署串行**：谁要部署，先在某个固定文件（比如本文件）顶部写一行
  `DEPLOY-LOCK: <会话名> <开始时间>`，部署完改成 `DEPLOY-LOCK: none <结束时间>`
- 每次打包前先 `git status --short | grep -v static/` 看一眼，确认别人的改动是不是已经改完
