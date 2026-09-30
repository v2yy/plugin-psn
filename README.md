# plugin-psn — Halo PSN 游戏库插件

同步 PlayStation 游戏库 / 最近游玩 / 时长 / 奖杯进度到 Halo，前台 `/psn` 游戏墙展示。
**增量同步**（checksum 比对，只写差异），**全配置化**（间隔/模式/缺失处理/平台过滤/黑名单/路由/排序均可热改，下次同步即生效）。

## 架构
- 凭据：npsso 只存 Halo `Secret`，插件按名称引用；值不进设置表单/日志/接口回显。
- 认证：npsso → accessCode → access/refresh token（内存缓存，过期自动续，401 自动重试一次）。
- 数据：`m.np.playstation.com` 逆向只读端点（trophyTitles + gamelist titles），字段对齐社区 psn-api 模型。
- 存储：自定义扩展 `PsnGame`（psn.v2yy.dev/v1alpha1），本地私有字段 pinned/hidden/note 同步永不覆盖。
- 前台：路由/模板名配置化，数据走 `psnFinder`；无任何全局 head 注入（吸取 AstraHub 全站 500 教训），模板缺失只影响本路由。
- 定时：5 分钟 ticker 读配置判断是否到期，间隔改 0 立即停止自动同步。

## 配置 npsso（最简单路径）
后台「PSN 游戏库 → 插件设置 → 连接与凭据」：
1. **获取 npsso**：浏览器登录 https://store.playstation.com（或 www.playstation.com 的 Sign In）→ 同浏览器打开 https://ca.account.sony.com/api/v1/ssocookie → 复制返回 JSON 里 `npsso` 的值（约 2 个月有效；等同登录凭证，勿外传；退出网页登录会失效；国内访问不了 account.playstation.com 时此法依然可用）。
2. 粘贴到「npsso 凭证」→ 保存。插件自动转存进 Halo Secret `psn-npsso` 并从设置数据抹除明文；此项即恢复为空，之后留空即可，换凭证再填。
3. 「立即同步」。高级用户也可自建 Secret（API `PUT /apis/api.console.halo.run/v1alpha1/secrets/<名>` stringData.npsso=<值>）并把名字填进「（高级）凭据 Secret 名称」。

## 构建
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # Halo 2.x 要求 JDK21
./gradlew build          # 产出 build/libs/plugin-psn-*.jar
```
安装：控制台插件页「从 URI 安装」，或把 jar 放可访问位置后走 install-from-uri（直接 cp jar 到目录不会被注册，必须走安装 API）。

## 配置项（Setting 三组，热生效）
| 组 | 键 | 默认 | 说明 |
|---|---|---|---|
| 连接 | credentialSecretName | 空 | 必填：Secret 名称 |
| 连接 | onlineId | 空 | 留空=自己；他人=仅公开数据 |
| 同步 | syncIntervalMinutes | 1440 | 0=关定时 |
| 同步 | syncMode | incremental | full-replace=显式逃生门 |
| 同步 | missingAction | keep | keep/archive/delete |
| 同步 | platformFilter | 全部 | ps4_game/ps5_native_game/pspc_game |
| 同步 | hiddenGames | 空 | 每行一个 npGameId/名称 |
| 展示 | routeEnabled / routePath / templateName | true / /psn / psn | 主题需 templates/psn.html |
| 展示 | pageSize / sortDefault | 24 / lastPlayed | lastPlayed/playtime/trophyProgress/name |

## 手动同步与状态
```bash
# 触发增量同步（首次 created=N；第二次应 created=0,updated=0,skipped=N —— 增量幂等证明）
curl -X POST "https://v2yy.com/apis/api.plugin.halo.run/v1alpha1/psn/-/sync" -H "Authorization: Bearer $TOKEN"
curl -s  "https://v2yy.com/apis/api.plugin.halo.run/v1alpha1/psn/-/status" -H "Authorization: Bearer $TOKEN"
```
状态响应只含统计，无凭据。

## 主题模板
moesora 放 `templates/psn.html`（本仓库附样例 `psn-theme-template.html`），变量 `psnGames`。主题升级会覆盖自定义模板——重新拷入 + 重启容器（同 bangumis 流程）。

## 已知边界
- m.np 为逆向接口，索尼改版可能失效；插件按错误降级，绝不拖垮全站。
- 他人游戏库/游玩历史受隐私限制（403 自动降级为仅奖杯）。
- 未配置凭据时 sync 返回 400 提示，前台 /psn 显示空态而不是 500。

## 开发
fork 自 halo-dev/plugin-starter（JDK21 + Gradle；ui 模块本插件 v1 不用已移除）。
`docs/DESIGN.md` 为设计+验收标准（AC1-AC9）。
