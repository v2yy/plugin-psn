# PSN 数据源策略（provider 配置化）

## provider=sony（默认，直连）
m.np.playstation.com 直连，需 npsso → 社区公共 client。
现状：2026-09-30 起 token 交换 4102 Bad client credentials（索尼掐公共 client，见 upstream issue psn-api#245），此路暂停可用。

## provider=mirror（第三方同步站只读 JSON）
2026-09-30 实测：psnsgame 站（api.psnsgame.com）整套接口**无鉴权裸 GET**，
且其服务端认证池活着（用户数据可实时增量更新）。

Base: `{mirrorBaseUrl}` 默认 `https://api.psnsgame.com/api/psn/PSN`
| 端点 | 返回 | 用途 |
|---|---|---|
| getRecentlyPlayed?PSNID={id} | array[20] 固定，**无分页**：name/imageUrl/titleid/playDuration(ISO8601 PT)/lastPlayedDateTime | 主数据源→PsnGame |
| gettrophySummary | 账号奖杯等级+总金银铜铂 | 展示扩展(暂不入实体) |
| getuserplatform | [{platform_single,game_count}] | 平台库规模 |
| getCareerSummary | totalItemCount/totalPlaytime(中文串)/aveprogress | 汇总卡 |
| getUserProfile | avatars/地区/onlineId | 展示 |

mirror 限制（诚实记录）：
- 只有常玩 TOP20，没有全库每游戏奖杯进度 → 前台按「最近常玩」展示
- 平台由 titleid 前缀推断：PPSA→ps5，CUSA→ps4，PCAS/RPGA→pc，其余 unknown
- 依赖第三方站存续与其隐私合规（数据本来就来自用户自己公开/授权的同步）；UI 不显示来源链接但 README 注明

## 增量语义对两种 provider 一致
checksum 只含远端字段；mirror 字段集更小所以 checksum 分量不同，切 provider 不会互相污染（不同 mergeKey 命名空间？不，同名会重算 checksum 触发一次 UPDATE，可接受）。

## 配置
sync.provider: sony | mirror （热生效）
sync.mirrorBaseUrl: 默认 psnsgame，留空用内置
onlineId: mirror 模式必填（站接口按 PSNID 查询）
