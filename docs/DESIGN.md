# halo-plugin-psn 设计文档（含验收标准）

Halo 2.26 插件：同步 PlayStation 账号数据（游戏库/最近游玩/时长/奖杯），配置化驱动，**增量同步**。

## 架构

```
Setting表单(全配置化) ──> ConfigMap plugin-psn-configmap
Secret(npsso)        ──> 凭据引用(secretName只存名字,值绝不落配置/git/聊天)
PsnAuthService       ──> npsso→accessCode→access/refresh token(内存缓存,过期自动refresh)
PsnApiService        ──> m.np.playstation.com 逆向端点: trophyTitles / gamelist(titles) / profile / search
PsnSyncService       ──> 增量 upsert(见下), 写 Extension PsnGame(psn.v2yy.dev/v1alpha1)
PsnFinder("psnFinder")> 模板取数(排序/过滤/分页在模板或参数里)
/admin REST          ──> POST .../psn/-/sync-now, GET .../psn/-/status
前台路由             ──> GET {routePath}(默认/psn) render(templateName)(默认 psn), 模板由主题提供, 仿 /bangumis
定时                 ──> 单线程调度, 间隔 syncIntervalMinutes(0=关闭自动同步)
```

## 增量同步算法（主人核心要求：禁止每次全量替换）

1. 拉上游两个集合：`trophyTitles`(奖杯进度) + `playedGames`(时长/最近游玩)，按 `npGameId(name+platform 归并键)` join 成 `remoteMap`。
2. 读本地全部 PsnGame 成 `localMap`。
3. 对每个 remote 项：
   - local 不存在 → **CREATE**
   - 存在且 `checksum`(规范化字段 SHA-256：name/platform/lastPlayed/playtime/earned/total/trophyLevel/cover) 不同 → **UPDATE**（保留本地 createdAt、保留本地私有字段 pinned/hidden/note）
   - checksum 相同 → **SKIP**（不写库、不 bump version）
4. 对 local 存在但 remote 消失的项，按配置 `missingAction`：
   - `keep`(默认，不动) / `archive`(spec.archived=true,前台默认不显示) / `delete`
5. 写入 `lastSyncAt/lastSyncStats{created,updated,skipped,archived,deleted}` 到 ConfigMap runtime key；失败记录 lastError，token 过期错误自动走 refresh 重试一次。
6. 幂等：同数据连续两次同步 = 第二次 created/updated 全 0。

## 配置键（Setting 表单，全部热生效，下次同步读取）

- credentialSecretName（存 npsso 的 Halo Secret 名）
- onlineId（留空=用凭据账号自己）
- syncIntervalMinutes（默认 1440，0=关）
- syncMode: incremental(默认) / full-replace（保留逃生门但非默认）
- missingAction: keep / archive / delete
- platformFilter: 多选 ps4_game,ps5_native_game,pspc_game(默认全)
- hiddenGames: 逗号分隔 npGameId 黑名单（同步跳过）
- routeEnabled / routePath(默认/psn) / templateName(默认 psn)
- pageSize(前台默认 24)
- sortDefault: lastPlayed / playtime / trophyProgress

## 安全

- npsso = 账号级凭据：**只允许**存在于 VPS 上的 Halo Secret；插件仅按 secretName 引用读取；任何 API/日志/导出不得回显其值（status 只回"token 有效/失效"）。Alice 不代填、不经聊天传输；主人本地用现成 CLI/后台创建 Secret，插件提供《如何创建 Secret》help 文案。

## 验收标准（AC）

- AC1 `./gradlew build` 产出 plugin-psn-*.jar；install-from-uri 安装成功，phase=STARTED。
- AC2 未配置凭据时 sync-now 返回 400+提示，**前台不 500**（/psn 显示空态）。
- AC3 配好凭据后 sync-now：首次 created=N>0；第二次 created=0,updated=0,skipped=N（幂等证明增量）。
- AC4 改一个上游数据（如新奖杯）→ 下一轮 updated=1 且该项 checksum 变化；其余 skipped。
- AC5 missingAction=archive：remote 删除项转 archived 非物理删；keep：不动。
- AC6 status GET 响应不含 npsso/token 明文（grep 验证）。
- AC7 routeEnabled=false 时 /psn 404；theme 无模板时模板缺失错误只影响该路由、首页等全站 200（对照 bangumi 事故教训：绝不进全局 head 注入）。
- AC8 定时同步在配置的间隔触发（日志可见 sync tick），interval=0 不触发。
- AC9 插件禁用/删除后，全站 200 无残留 500（不挂全局扩展点）。

## 范围外（v1 不做）

- Console 自定义管理页签（用表单+REST 代替；v2 再上 Vue）
- PSN 好友/消息等写操作
