package dev.v2yy.psn.model;

/**
 * 同步状态的对外视图（前台/后台共用）。
 *
 * 设计约束（主人定案，勿违背）：
 * 1. 上游 4102 invalid_client 只代表「当前客户端认证被拒绝」，
 *    不得表述为「索尼已轮换/吊销凭据」——errorKind=auth_rejected 的中性文案。
 * 2. 空结果必须三分：未配置 / 认证失败 / 确实为空（+从未同步/同步中）。
 * 3. mirror 是降级方案，永远标注「非官方源」；sony 直连标注「官方源」。
 */
public class PsnStatus {

    /** idle=从未同步 | running=进行中 | ok=成功 | error=上次失败 */
    public String state = "idle";
    /** 实际生效数据源: sony(官方) | mirror(镜像/非官方) */
    public String provider = "mirror";
    /** 是否为降级到 RecentlyPlayed TOP20 的结果 */
    public boolean degraded;
    /** 上次成功时间(ISO) */
    public String lastSuccessAt;
    /** 上次失败时间(ISO) */
    public String lastFailureAt;
    /** 错误归类: none|not_configured|auth_rejected|mirror_unreachable|mirror_empty|privacy_blocked|network|internal */
    public String errorKind = "none";
    /** 人类可读原因（前台直接显示；绝不含凭据值） */
    public String errorText = "";
    /** 进行中时的开始时间 */
    public String runningSince;

    /** 数据条数（最近一次成功入库的 upstream，或库存数） */
    public int itemCount;
    /** 最近一次成功同步统计 */
    public Integer created;
    public Integer updated;
    public Integer skipped;

    // ---- Thymeleaf/SpEL getters ----
    public String getState() { return state; }
    public String getProvider() { return provider; }
    public boolean isDegraded() { return degraded; }
    public String getLastSuccessAt() { return lastSuccessAt; }
    public String getErrorKind() { return errorKind; }
    public String getErrorText() { return errorText; }
    public int getItemCount() { return itemCount; }
    public String getRunningSince() { return runningSince; }

    public boolean isNeverSynced() {
        return "idle".equals(state) && lastSuccessAt == null && lastFailureAt == null;
    }

    /** 数据源徽章：mirror（psnsgame 等第三方）永远标注非官方。 */
    public String getSourceBadgeZh() {
        if ("sony".equalsIgnoreCase(provider)) {
            return "数据源：官方 PlayStation Network";
        }
        return "数据源：第三方镜像（非官方）" + (degraded ? " · 已降级到最近游玩 TOP20" : " · 全量库");
    }

    public String getStateZh() {
        return switch (state) {
            case "running" -> "同步中";
            case "ok" -> "同步正常";
            case "error" -> "同步异常";
            default -> "尚未同步";
        };
    }

    /** ISO 时间 → 可读（截断到分钟）。 */
    public static String fmt(String iso) {
        if (iso == null || iso.length() < 16) {
            return "—";
        }
        return iso.substring(0, 10) + " " + iso.substring(11, 16);
    }

    public String getLastSuccessText() {
        return fmt(lastSuccessAt);
    }

    /** 空态三分判定的语义键：never|running|unconfigured|auth_failed|upstream_down|truly_empty|unknown */
    public String emptyReason() {
        if (isNeverSynced()) {
            return "never";
        }
        if ("running".equals(state)) {
            return "running";
        }
        if ("not_configured".equals(errorKind)) {
            return "unconfigured";
        }
        if ("auth_rejected".equals(errorKind) || "privacy_blocked".equals(errorKind)) {
            return "auth_failed";
        }
        if ("mirror_unreachable".equals(errorKind) || "network".equals(errorKind)) {
            return "upstream_down";
        }
        if ("mirror_empty".equals(errorKind)) {
            return "truly_empty";
        }
        if (itemCount == 0 && "ok".equals(state)) {
            return "truly_empty";
        }
        return "unknown";
    }

    public String getEmptyReason() {
        return emptyReason();
    }

    /** 空态中性中文提示（对 4102 绝不断言索尼吊销凭据）。 */
    public String emptyHintZh() {
        return switch (emptyReason()) {
            case "never" -> "尚未执行过同步。定时任务会自动开始，也可在后台插件里手动触发。";
            case "running" -> "正在同步中…稍后刷新本页即可看到数据。";
            case "unconfigured" -> "未配置：镜像源需在插件设置填「PSN ID」；官方源需粘贴 npsso。";
            case "auth_failed" ->
                "当前客户端认证被上游拒绝（如 HTTP 4102 invalid_client）。这只是本次 token 交换被拒的记录，"
                + "不代表索尼已轮换或吊销凭据——npsso 是否有效以 authorize 能否出 code 为准。建议暂用镜像源。";
            case "upstream_down" -> "数据源暂时连不上（网络异常或接口改版）。已有数据仍正常展示，稍后自动重试。";
            case "truly_empty" -> "上游确实返回 0 条游戏（账号没玩过游戏，或 PSN ID 拼错了）。";
            default -> "暂无数据。";
        };
    }

    public String getEmptyHintZh() {
        return emptyHintZh();
    }
}
