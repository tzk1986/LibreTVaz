# LibreTVaz

LibreTVaz 是 [LibreTV](https://github.com/LibreSpark/LibreTV) 的独立衍生项目：在保留其 Next.js 影视聚合服务端全部能力的同时，研发可安装于 **Android TV 与安卓投影仪** 的原生客户端（APK），作为独立项目长期维护。

## 项目来源

- 分叉自 LibreSpark/LibreTV **v2.21.1**（Next.js 重构版），分叉提交 `29cbeb3`（2026-10-27）。
- 本仓库（`tzk1986/LibreTVaz` 的 `master`）为唯一主线，**不再向原上游仓库合并同步**；上游后续更新仅作为参考选择性移植。
- 许可证沿用上库 **AGPL-3.0-or-later**，衍生开发同样以 AGPL 开源。
- 上游文档仍可参考：[Wiki](https://libretv.is-an.org/wiki/) · [部署](https://libretv.is-an.org/wiki/Deployment.html) · [配置](https://libretv.is-an.org/wiki/Configuration.html)。

## 后续计划：Android TV / 投影仪 APK

### 技术路线（基于生态调研结论）

| 层 | 选型 | 依据 |
| --- | --- | --- |
| UI | Kotlin + Compose for TV | Selene-TV / AndroidTV 官方范式，D-pad 焦点体验上限最高；WebView 套壳在遥控导航、倍速、性能上体验差，不作主路线 |
| 播放 | Media3 / ExoPlayer（HLS 直连，预留软解兜底） | 与 DpadTV 一致，低配投影仪上性能与兼容性最优 |
| 服务端 | **本项目现有 API，零改动起步** | 调研确认现有 `/api/search`、`/api/detail`、`/api/proxy`、`/api/live`、`/api/status` 契约已可完整支撑原生 TV 客户端 |
| 数据模型 | 进度/收藏沿用客户端本地存储（IndexedDB → 客户端持久层） | 保持 LibreTV「服务端无账号」的自托管模型 |

借鉴对象：DpadTV（API 契约与播放/续播层）、Selene-TV（焦点与双内核范式）、LibreTV-for-HarmonyOS（逻辑层/UI 层解耦、m3u8 广告过滤、页面拆分）、MQTV（`/api/status` 下发启用源快照，客户端免鉴权自举）、WarHutTV（流式搜索首屏秒出）。

### 阶段路线

- **M1**：`android/` 工程骨架（Compose for TV + Leanback 启动器，兼容普通安卓投影仪），服务器地址配置页 + `/api/status` 连通性自检。
- **M2**：核心观影闭环——搜索（聚合源勾选）→ 详情/选集/换源 → ExoPlayer 播放（倍速、跳过片头片尾、跨集续播）。
- **M3**：直播 / IPTV（M3U 订阅、EPG）、源管理与数据源订阅、观看历史与收藏。
- **M4**：体验完善——广告分片过滤、画质测速换源、弹幕（客户端拉取式）、遥控器交互打磨、安装包体积与低端设备优化。

服务端侧按需为客户端补充：GET 形态的 `search?q=` 聚合入口、`/api/server-config` 等轻量兼容层（不改既有 Web 行为）。

## 网页端核心特性（承自上游）

- **聚合搜索**：多采集站服务端并行搜索，跨源同名影片合并为一张卡片
- **HLS 播放**：ArtPlayer + hls.js，广告分片过滤、跳过片段标记、自动连播、倍速、换源测速
- **直播 / IPTV**：M3U 订阅解析、XMLTV 节目单（EPG）、频道收藏，经 `/api/live/stream` 长连接代理转发
- **源管理**：一键探活、失败源阶梯自动停用；订阅 LibreTV-SourceList JSON / TVBOX 配置 / 接口目录，可导出分享
- **首页推荐**：豆瓣 / Bangumi 新番 / 影视榜单（60s API），服务端直连 + 缓存
- **进度与历史**：本机 IndexedDB 精确到秒续播；PWA 可安装
- **安全**：服务端密码鉴权 + 登录限速，代理内置 SSRF 防护（拒绝内网/保留地址）

## 部署（Web 服务端）

### Docker（推荐）

```bash
echo "PASSWORD=your-password" > .env
docker compose pull && docker compose up -d    # 或 docker compose up -d --build
```

> ⚠️ 上游发布镜像 `ghcr.io/librespark/libretv` 属上游版本；本项目镜像需自行构建（`docker compose up -d --build`）。

### 免 Docker

```bash
npm install
PASSWORD=your-password npm run build
PASSWORD=your-password npm start   # 监听 8080
```

### 常用环境变量

| 变量 | 必填 | 说明 |
| --- | --- | --- |
| `PASSWORD` | 是 | 访问密码 |
| `PROXY_SECRET` | 否 | 会话/代理签名密钥（多实例建议设置） |
| `DEFAULT_SOURCES` | 否 | 预置采集站 JSON 数组 |
| `DEFAULT_LIVE_SOURCES` | 否 | 预置直播源（M3U），含可选 `epg` |
| `DEFAULT_SUBSCRIPTIONS` | 否 | 预置数据源订阅（SourceList / TVBOX / 接口目录），按 `refreshDays` 自动刷新 |
| `DEFAULT_RECOMMEND_SOURCE` | 否 | 首页推荐默认值（`douban` / `bangumi` / `hot-list`） |
| `LIVE_ALLOW_PRIVATE` | 否 | 设 `1` 允许直播代理访问内网源（默认关闭） |
| `COOKIE_SECURE` | 否 | 覆盖会话 cookie 的 `Secure` 标记（默认按 `x-forwarded-proto` 推导） |

完整变量表与订阅格式说明见[上游文档](https://libretv.is-an.org/wiki/Data-Sources.html)。

> 对外提供服务建议套 HTTPS（反向代理 Nginx/Caddy/Traefik 均可）；登录不依赖 HTTPS，`http://IP:端口` 亦可正常登录。

## 开发

Web 端：

```bash
npm install
PASSWORD=dev-password npm run dev   # http://localhost:8080
npm test && npm run typecheck
```

TV 客户端（规划于 `android/` 目录，独立 Gradle 工程）：

- JDK 17+（本机已配 Temurin 25）
- Android SDK：`platform-tools` / `platforms;android-35` / `build-tools;35.0.0`（`ANDROID_HOME` 指向 SDK 根目录）
- Gradle 8.13+ / 9.x

## 免责声明

本项目不存储、不制作任何视频内容，仅提供第三方公开接口的聚合与播放能力，内容的合法性由对应数据源负责。

## 致谢

感谢 [LibreSpark/LibreTV](https://github.com/LibreSpark/LibreTV) 及所有生态项目（DpadTV、Selene-TV、OrionTV、LunaTV、MoonCakeTV、MQTV、LibreTV-for-HarmonyOS 等）的开源工作。
