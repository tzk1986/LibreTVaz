# LibreTVaz 二维码扫码配置方案

> 目标：解决 TV / 投影仪上用遥控器输入长 URL 极其痛苦的问题。
> APK 屏幕生成二维码 → 手机扫码打开本机 Web 配置页 → 手机粘贴/输入源地址 → 实时同步写回 APK。
> 状态：方案设计（调研日期 2026-10-29），未实施。

## 1. 需求拆解

| 需求 | 说明 |
|---|---|
| 免遥控器输入 | 点播源、直播源、订阅链接三类文本都在手机上录入 |
| 不依赖外网服务 | 与整体「免服务器直连」架构一致，链路只走局域网 |
| 双向确认 | 手机上能看到 TV 当前已配置的源列表，可删除、可看到导入结果 |
| 安全 | 局域网内其他设备不能随意向 TV 写入配置 |
| 失败可退路 | 手机与 TV 不同网段时，仍能通过其他方式完成输入 |

## 2. 方案选型

| 方案 | 结论 | 理由 |
|---|---|---|
| A. APK 内嵌 HTTP 服务器 + 二维码 | ✅ 采用 | 完全本地闭环；TVBox 系客户端（影视仓等）验证过的成熟交互；实现量小 |
| B. 云端中继（自建服务器 / Firebase） | ❌ | 违背免服务器原则，引入部署与隐私成本 |
| C. Google Nearby Connections | ❌ | TV 设备上 Google Play Services 残缺，库依赖重、调试成本高 |
| D. 蓝牙 HID / 剪贴板同步 | ❌ | 系统碎片化，Android TV 无通用通道 |

内嵌服务器与二维码库选型：

| 组件 | 选择 | 备选 | 理由 |
|---|---|---|---|
| HTTP 服务器 | `org.nanohttpd:nanohttpd:2.3.1` | Ktor CIO Server | 单 jar ≈ 60KB、纯 Java 无传递依赖、API 十年未变；被 2200+ 项目使用。缺点是 2016 年后无新发布（安全敏感场景需评估，本场景仅局域网只读配置写入，风险可控）。Ktor 会带入整条 coroutines/serialization 链路，体积与复杂度不值得 |
| 二维码生成 | `com.google.zxing:core:3.5.4`（仅 core） | journeyapps/qr-android、手写 | core 生成 `BitMatrix` 后手绘 `Bitmap` 约 20 行代码；不引入 javase（AWT）模块；无需相机权限；3.5.4 为 2025-11 最新版 |

## 3. 总体交互时序

```
 TV (APK)                          手机浏览器
 ──────                            ─────────
 进入「扫码配置」页
   ├─ 启动 NanoHTTPD :8599
   ├─ 生成一次性 token (6位)
   ├─ 编码 URL 为二维码并展示
   │     http://192.168.1.20:8599/pair?k=A1B2C3
   │            扫码 ──────────────► 打开配置页 (assets/pair.html)
   │                                 GET /pair/state?k=…
   ◄────────────── JSON：当前点播/直播源列表 ────────────
   │                                 粘贴订阅链接 → 点「导入」
   ◄──── POST /pair/import {url} ───┤
   ├─ 复用 SourceStore.importFromUrl │
   ├─ 校验 token → 写 SharedPreferences│
   └─ 返回 {ok, vodAdded, liveAdded, skipped}
   │                                 页面 toast 结果，轮询刷新
   源管理页实时刷新（StateFlow 通知）
 离开页面 / 5 分钟超时 → stop 服务器
```

## 4. App 端设计

### 4.1 模块划分（新增文件）

```
data/PairServer.kt        NanoHTTPD 子类：路由、token 校验、调用 SourceStore
data/NetInfo.kt           局域网 IPv4 枚举（NetworkInterface）
ui/PairScreen.kt          二维码 + 明文地址展示页
ui/PairQr.kt              zxing BitMatrix → Bitmap 工具
assets/pair.html          手机配置页（单文件，内联 CSS/JS，无框架）
```

改动点：

- `MainActivity`：`sealed Screen` 增加 `Pair`，入口放「源管理」页顶部按钮与首页（可选）。
- `SourceStore`：新增 `MutationVersion: MutableStateFlow<Int>`，`mutateVod/mutateLive` 写入后 `value++`；
  `SourcesScreen` 用 `collectAsState` 订阅，实现 TV 端 UI 与手机端操作实时同步（去掉现有手动 `refresh()` 依赖）。
- `app/build.gradle.kts` / `libs.versions.toml`：
  ```toml
  nanohttpd = "2.3.1"
  zxing = "3.5.4"
  nanohttpd = { group = "org.nanohttpd", name = "nanohttpd", version.ref = "nanohttpd" }
  zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }
  ```

### 4.2 PairServer 职责

```kotlin
class PairServer(private val store: SourceStore, port: Int = 8599) : NanoHTTPD(port) {
    val token: String            // 每次进入页面随机生成，SecureRandom，6 位大写字母数字
    override fun serve(session): Response  // 见 4.3 路由表
}
```

- 仅注册 `/pair/*` 路由与静态页；不映射任何文件系统路径（响应体全部内存生成）。
- `serve()` 运行在 NanoHTTPD 自有线程：写 `SharedPreferences` 安全，但更新 Compose 状态必须经 `StateFlow`（线程安全）而非直接赋值 `MutableState`。
- 生命周期：`PairScreen` 的 `DisposableEffect` 中 start/stop；`stop` 同时作废 token。
- 端口：默认 8599，占用则回退随机端口并在 UI 显示实际端口。

### 4.3 API 协议（全部 `k=token` 必带，缺失/不匹配返回 403）

| 方法 路径 | 请求 | 响应 |
|---|---|---|
| `GET /pair` | – | `text/html`（pair.html，内嵌注入 token 供页面 JS 使用） |
| `GET /pair/state` | – | `{name, vod:[{index,name,url,isAdult}], live:[{index,name,url}]}` |
| `POST /pair/vod` | `{name,url}` | `{ok,message}` → `SourceStore.addVod` |
| `POST /pair/live` | `{name,url}` | `{ok,message}` → `addLive` |
| `POST /pair/delete` | `{type:"vod"|"live", index}` | `{ok,message}` |
| `POST /pair/import` | `{url}` | `{ok,vodAdded,liveAdded,skipped,format,name}` → 复用 `importFromUrl` |

约定：`Content-Type: application/json; charset=utf-8`；错误也返回结构化 JSON（`ok:false, message`），HTTP 状态 200/403/400；所有 POST body 限长 4KB，URL 字段服务端二次校验 http(s) 前缀（防注入到后续请求链路）。

**导入在服务端执行还是手机执行？** 选服务端执行：TV 端已有 `importFromUrl`（含三格式解析、Spider 跳过统计），手机页只做录入壳，保证两端行为一致、无 CORS 问题（页面与 API 同源）。

### 4.4 二维码内容与网络地址发现

- 内容：`http://{ip}:{port}/pair?k={token}`。
- IP 枚举：遍历 `NetworkInterface`，取 `siteLocalAddress && isIPv4`，优先 `wlan*` / `eth*` 命名；多网卡时全部列出，UI 默认取第一个并在二维码下方显示所选 IP 可点击切换（切换即重生成码）。
- 二维码：纠错级别 M，边距 2，屏幕渲染 ≥ 260dp；TV 分辨率差异大，`Image` 保持矢量位图按可用空间缩放。

### 4.5 手机页 pair.html 要点

- 视口 meta + 单列卡片布局；三个输入区（订阅导入 / 点播 / 直播）+ 当前源列表（可删除）+ 结果提示。
- 粘贴体验：`input type="url" enterkeyhint="go"`；订阅链接框旁提供「从 TVBox 复制地址」说明文案。
- 列表同步：操作成功后主动 `GET /pair/state`；另每 10s 轮询一次，反映 TV 端遥控器的删除操作。
- 无外网资源（不引 CDN），整页 < 10KB，保证局域网秒开。

## 5. 安全设计

| 威胁 | 对策 |
|---|---|
| 局域网他人向 TV 写配置 | 一次性 token（进入页面生成、离开作废），URL 只存在于二维码中 |
| token 被嗅探 | 会话生命周期 ≤ 5 分钟自动 stop；写接口只接受配置类字段，不产生任意副作用 |
| URL 字段被投毒（SSRF/命令注入面） | 服务端仅存字符串；发起请求前统一 http(s) 校验；导入逻辑复用既有 `SourceStore`（上限 100/50、去重仍生效） |
| JSON 注入 pair 接口 | NanoHTTPD 逐字段 `JSONObject` 解析，不做字符串拼接；body 限长 |
| 明文 HTTP | 局域网内、无敏感数据（不含密码/播放记录），可接受；页面标注「仅配置用途」 |

不新增权限：`INTERNET` 已有；监听 socket 无需 `ACCESS_WIFI_STATE`（用 NetworkInterface 而非 WifiManager，同时覆盖以太网投影仪）。

## 6. 边界与风险

1. **AP 隔离 / 不同 VLAN**：部分公共 WiFi、公司网络禁止设备互访 → 退路：手机热点共享、或 TV 页显示明文 URL 让用户在手机浏览器手动输入（同网段时仍可用）；均失败时保留遥控器输入路径（现有 UI 不删）。
2. **NanoHTTPD 停更**：API 稳定、攻击面极小（仅局域网临时开启），风险可接受；若后续要 TLS/长连接再评估 Ktor。
3. **TV 浏览器扫码兼容**：微信扫码会拦截纯 IP URL → 页内提示「用系统浏览器/相机打开」，多数安卓浏览器与 TV 伴侣类 App 可直接打开。
4. **IPv6-only 网络**：少见但存在 → 优先展示 IPv4；无 IPv4 时展示 `[%ipv6%]:port` 形式（zxing 照常编码），部分手机浏览器对 IPv6 字面量支持良好。
5. **性能**：订阅导入在 TV 侧发起 15s 网络请求，期间手机端 POST 应得到及时响应 → `importFromUrl` 调用放在协程里、加互斥锁（同一时间只允许一个导入，返回 busy 提示）。

## 7. 实施拆分（建议排为 M5，按依赖顺序）

| # | 任务 | 产出 | 预估 |
|---|---|---|---|
| 1 | 依赖接入 + NetInfo + QR 工具 | toml/gradle、单元测试（IPv4 枚举解析、BitMatrix 尺寸） | 0.5d |
| 2 | PairServer 路由与协议 | token 校验、6 个端点，JVM 单测直测 serve()（NanoHTTPD 可 mock session） | 1d |
| 3 | pair.html | 资产页 + 手测（浏览器打开 localhost 服务） | 0.5d |
| 4 | PairScreen + 入口 + SourceStore 变更通知 | TV 页 UI、返回键 stop server | 0.5d |
| 5 | 真机联调 | 手机/TV 同 WiFi 全流程：导入 fty.json + zbds m3u，遥控器端验证实时刷新 | 0.5d |

合计约 3 人日。验收标准：遥控器零字符输入完成「导入订阅 → 搜索 → 播放」全链路；不同网段时有明确错误指引。

## 8. 参考资料

- [NanoHTTPD GitHub / Maven Central（2.3.1，2016 至今仍为最新）](https://github.com/NanoHttpd/nanohttpd) · [org.nanohttpd:nanohttpd @ Sonatype](https://central.sonatype.com/artifact/org.nanohttpd/nanohttpd) · [Baeldung: A Guide to NanoHTTPD](https://www.baeldung.com/nanohttpd)
- [ZXing releases（3.5.4，2025-11）](https://github.com/zxing/zxing/releases) · [com.google.zxing:core @ Sonatype](https://central.sonatype.com/artifact/com.google.zxing/core)
- TVBox 生态同类交互：影视仓/TVBox 客户端的多仓配置即采用「TV 显示二维码 → 手机打开内置配置页」模式，验证了该交互在 TV 场景的可行性（见项目根 README 生态调研节）。
