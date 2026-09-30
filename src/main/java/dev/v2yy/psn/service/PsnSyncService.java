package dev.v2yy.psn.service;

import com.fasterxml.jackson.databind.JsonNode;
import dev.v2yy.psn.client.PsnApiClient;
import dev.v2yy.psn.client.PsnAuthClient;
import dev.v2yy.psn.client.PsnMirrorClient;
import dev.v2yy.psn.model.PsnConfig;
import dev.v2yy.psn.model.PsnGame;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.data.domain.Sort;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 增量同步：拉上游 -> 按 mergeKey 比对 checksum -> 只写差异。
 * syncMode=full-replace 是显式逃生门，默认永远走增量。
 */
@Service
public class PsnSyncService {

    private static final Logger log = LoggerFactory.getLogger(PsnSyncService.class);
    private static final int PAGE = 100;
    private static final int MAX_ITEMS = 2000;

    public static final AtomicReference<SyncStats> LAST = new AtomicReference<>(new SyncStats());

    private final PsnConfigService configService;
    private final PsnAuthClient authClient;
    private final PsnApiClient apiClient;
    private final PsnMirrorClient mirrorClient;
    private final ReactiveExtensionClient client;

    public PsnSyncService(PsnConfigService configService, PsnAuthClient authClient,
                          PsnApiClient apiClient, PsnMirrorClient mirrorClient,
                          ReactiveExtensionClient client) {
        this.configService = configService;
        this.authClient = authClient;
        this.apiClient = apiClient;
        this.mirrorClient = mirrorClient;
        this.client = client;
    }

    public Mono<SyncStats> sync() {
        return configService.loadFresh()
            .flatMap(cfg -> Mono.fromCallable(() -> {
                    String npsso = null;
                    if (!"mirror".equalsIgnoreCase(cfg.getProvider())) {
                        npsso = configService.npsso(cfg.getCredentialSecretName()).block();
                        if (npsso == null || npsso.isBlank()) {
                            throw new IllegalStateException(
                                "PSN_NOT_CONFIGURED: sony 数据源需先在插件设置里粘贴 npsso 并保存");
                        }
                    }
                    return syncWithConfig(cfg, npsso);
                })
                .subscribeOn(Schedulers.boundedElastic()))
            .doOnNext(LAST::set)
            .doOnError(e -> {
                SyncStats s = new SyncStats();
                s.error = e.getMessage();
                s.finishedAt = OffsetDateTime.now().toString();
                LAST.set(s);
                log.warn("[PSN] 同步失败: {}", e.getMessage());
            });
    }

    private SyncStats syncWithConfig(PsnConfig cfg, String npsso) {
        SyncStats stats = new SyncStats();
        stats.mode = cfg.getSyncMode();

        Map<String, RemoteView> remote = "mirror".equalsIgnoreCase(cfg.getProvider())
            ? fetchMirror(cfg)
            : fetchSony(cfg, npsso);
        stats.upstream = remote.size();

        if (!cfg.getPlatformFilter().isEmpty()) {
            remote.values().removeIf(rv -> !cfg.getPlatformFilter().contains(rv.platformGroup));
        }
        List<String> hidden = cfg.getHiddenGames();
        if (hidden != null) {
            for (String h : hidden) {
                String norm = h.trim().toLowerCase();
                remote.keySet().removeIf(k -> k.startsWith(norm + "|") || k.equalsIgnoreCase(h));
            }
        }

        List<PsnGame> locals = client.listAll(PsnGame.class, ListOptions.builder().build(),
                Sort.by("metadata.name")).collectList().block();
        if (locals == null) {
            locals = List.of();
        }
        Map<String, PsnGame> localByKey = new HashMap<>();
        for (PsnGame g : locals) {
            String key = g.getSpec().getMergeKey() != null
                ? g.getSpec().getMergeKey() : g.getMetadata().getName();
            localByKey.put(key, g);
        }

        String now = OffsetDateTime.now().toString();

        if ("full-replace".equals(cfg.getSyncMode())) {
            for (PsnGame g : locals) {
                client.delete(g).block();
                stats.deleted++;
            }
            localByKey.clear();
        }

        for (RemoteView rv : remote.values()) {
            PsnGame local = localByKey.get(rv.mergeKey);
            if (local == null) {
                client.create(build(rv, now)).block();
                stats.created++;
            } else if (!rv.checksum().equals(nullSafe(local.getSpec().getChecksum()))) {
                applyRemote(local, rv, now);
                client.update(local).block();
                stats.updated++;
            } else {
                stats.skipped++;
            }
        }

        if (!"full-replace".equals(cfg.getSyncMode())) {
            for (Map.Entry<String, PsnGame> en : localByKey.entrySet()) {
                if (remote.containsKey(en.getKey())) {
                    continue;
                }
                PsnGame g = en.getValue();
                switch (cfg.getMissingAction()) {
                    case "delete" -> {
                        client.delete(g).block();
                        stats.deleted++;
                    }
                    case "archive" -> {
                        if (!Boolean.TRUE.equals(g.getSpec().getArchived())) {
                            g.getSpec().setArchived(true);
                            client.update(g).block();
                        }
                        stats.archived++;
                    }
                    default -> stats.kept++;
                }
            }
        }

        stats.finishedAt = now;
        log.info("[PSN] 同步完成 mode={} upstream={} created={} updated={} skipped={} archived={} deleted={} kept={}",
            stats.mode, stats.upstream, stats.created, stats.updated, stats.skipped,
            stats.archived, stats.deleted, stats.kept);
        return stats;
    }

    private Map<String, RemoteView> fetchSony(PsnConfig cfg, String npsso) {
        String accountId = "me";
        if (!cfg.getOnlineId().isBlank()) {
            String found = callWithRetry(npsso,
                t -> apiClient.findAccountId(t, cfg.getOnlineId()));
            if (found == null || found.isBlank()) {
                throw new IllegalStateException("PSN_USER_NOT_FOUND");
            }
            accountId = found;
        }
        final String acc = accountId;

        List<JsonNode> trophyNodes = callWithRetry(npsso, t ->
            apiClient.collectPages(t, (tk, l, o) -> apiClient.trophyTitles(tk, acc, l, o),
                "trophyTitles", PAGE, MAX_ITEMS));
        List<JsonNode> gameNodes;
        try {
            gameNodes = callWithRetry(npsso, t ->
                apiClient.collectPages(t, (tk, l, o) -> apiClient.playedGames(tk, acc, l, o),
                    "titles", PAGE, MAX_ITEMS));
        } catch (Exception e) {
            log.warn("[PSN] 游戏库不可用（隐私/权限），仅同步奖杯: {}", e.getClass().getSimpleName());
            gameNodes = List.of();
        }

        Map<String, RemoteView> remote = new HashMap<>();
        for (JsonNode n : trophyNodes) {
            RemoteView rv = fromTrophy(n);
            if (rv != null) {
                remote.merge(rv.mergeKey, rv, RemoteView::merge);
            }
        }
        for (JsonNode n : gameNodes) {
            RemoteView rv = fromGame(n);
            if (rv != null) {
                remote.merge(rv.mergeKey, rv, RemoteView::merge);
            }
        }
        return remote;
    }

    /**
     * mirror 数据源：第三方同步站公开 JSON（默认 psnsgame）。
     * 主源 getallgamelife 分页=全量库(名/封面/titleids/奖杯明细/时长/进度)；
     * 失败时回退 getRecentlyPlayed(TOP20，无奖杯明细)。
     */
    private Map<String, RemoteView> fetchMirror(PsnConfig cfg) {
        String psnId = cfg.getOnlineId();
        if (psnId.isBlank()) {
            throw new IllegalStateException(
                "MIRROR_NEED_ONLINE_ID: mirror 数据源需在「要同步的 PSN ID」填写自己的 PSNID");
        }
        Map<String, RemoteView> remote = new HashMap<>();
        try {
            int page = 1;
            int pages = 1;
            do {
                JsonNode d = mirrorClient.getAllGameLife(cfg.getMirrorBaseUrl(), psnId, page, 100);
                JsonNode records = d.path("records");
                if (!records.isArray()) {
                    throw new IllegalStateException("MIRROR_BAD_LIBRARY_SHAPE");
                }
                for (JsonNode n : records) {
                    RemoteView rv = fromMirrorLibrary(n);
                    if (rv != null) {
                        remote.merge(rv.mergeKey, rv, RemoteView::merge);
                    }
                }
                pages = d.path("pages").asInt(1);
                page++;
            } while (page <= pages && page <= 40);
        } catch (Exception e) {
            log.warn("[PSN] mirror 全量库接口失败({})，回退 RecentlyPlayed TOP20",
                e.getClass().getSimpleName());
            JsonNode arr = mirrorClient.getRecentlyPlayed(cfg.getMirrorBaseUrl(), psnId);
            if (!arr.isArray()) {
                throw new IllegalStateException("MIRROR_BAD_RESPONSE: 期望数组，实际 " + arr.getNodeType());
            }
            for (JsonNode n : arr) {
                RemoteView rv = fromMirror(n);
                if (rv != null) {
                    remote.merge(rv.mergeKey, rv, RemoteView::merge);
                }
            }
        }
        if (remote.isEmpty()) {
            throw new IllegalStateException("MIRROR_EMPTY: mirror 未返回任何游戏（PSNID 是否正确？）");
        }
        return remote;
    }

    private RemoteView fromMirrorLibrary(JsonNode n) {
        String name = firstNonBlank(n.path("cnname").asText(""),
            n.path("trophyTitleName").asText(""));
        String commId = n.path("npCommunicationId").asText("");
        if (name.isEmpty() || commId.isEmpty()) {
            return null;
        }
        RemoteView rv = new RemoteView();
        rv.name = name;
        rv.platform = n.path("trophyTitlePlatform").asText("");
        String titleIds = n.path("titleids").asText("");
        rv.platformGroup = !rv.platform.isBlank() || !titleIds.isBlank()
            ? platformGroupOf(rv.platform, n.path("npServiceName").asText(""))
            : platformGroupFromTitleId(titleIds);
        if ("unknown".equals(rv.platformGroup)) {
            rv.platformGroup = platformGroupFromTitleId(titleIds);
        }
        if (rv.platform.isEmpty()) {
            rv.platform = switch (rv.platformGroup) {
                case "ps5_native_game" -> "PS5";
                case "ps4_game" -> "PS4";
                case "pspc_game" -> "PC";
                default -> "";
            };
        }
        rv.mergeKey = key(name, rv.platformGroup);
        rv.npCommunicationId = commId;
        rv.npGameId = titleIds;
        rv.coverUrl = n.path("trophyTitleIconUrl").asText("");
        rv.trophyLastUpdated = n.path("lastUpdatedDateTime").asText("");
        rv.lastPlayed = n.path("lastUpdatedDateTime").asText("");
        rv.progress = n.path("progress").asInt(0);
        rv.earned = parseIntSafe(n.path("earnedTrophies").asText(""));
        rv.total = parseIntSafe(n.path("definedTrophies").asText(""));
        rv.platinum = parseIntSafe(n.path("earnedPlatinum").asText(""));
        rv.gold = parseIntSafe(n.path("earnedGold").asText(""));
        rv.silver = parseIntSafe(n.path("earnedSilver").asText(""));
        rv.bronze = parseIntSafe(n.path("earnedBronze").asText(""));
        rv.playtimeHours = parseCnDurationHours(n.path("duration_seconds").asText(""));
        rv.inTrophyList = true;
        rv.inGameList = true;
        return rv;
    }

    /** 中文时长「1461小时42分钟50秒」/ ISO PT 双兼容 -> 小时 */
    static double parseCnDurationHours(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        if (s.startsWith("PT")) {
            return parseIsoDurationHours(s);
        }
        try {
            double h = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*小时").matcher(s);
            if (m.find()) {
                h += Double.parseDouble(m.group(1));
            }
            m = java.util.regex.Pattern.compile("(\\d+)\\s*分").matcher(s);
            if (m.find()) {
                h += Double.parseDouble(m.group(1)) / 60.0;
            }
            m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*秒").matcher(s);
            if (m.find()) {
                h += Double.parseDouble(m.group(1)) / 3600.0;
            }
            if (h == 0) {
                // 可能直接是秒数
                double secs = Double.parseDouble(s.trim());
                h = secs / 3600.0;
            }
            return Math.round(h * 100) / 100.0;
        } catch (Exception e) {
            return 0;
        }
    }

    static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private RemoteView fromMirror(JsonNode n) {
        String name = n.path("name").asText("");
        if (name.isEmpty()) {
            return null;
        }
        RemoteView rv = new RemoteView();
        String titleId = n.path("titleid").asText("");
        rv.platformGroup = platformGroupFromTitleId(titleId);
        rv.platform = switch (rv.platformGroup) {
            case "ps5_native_game" -> "PS5";
            case "ps4_game" -> "PS4";
            case "pspc_game" -> "PC";
            default -> "";
        };
        rv.mergeKey = key(name, rv.platformGroup);
        rv.name = name;
        rv.npGameId = titleId;
        rv.coverUrl = n.path("imageUrl").asText("");
        rv.lastPlayed = n.path("lastPlayedDateTime").asText("");
        rv.playtimeHours = parseIsoDurationHours(n.path("playDuration").asText(""));
        rv.inGameList = true;
        return rv;
    }

    /** PPSA->PS5, CUSA->PS4, PCAS/RPGA->PC, NPQA0(PS VR经典)等归PS4；未知->unknown */
    static String platformGroupFromTitleId(String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return "unknown";
        }
        String t = titleId.toUpperCase();
        if (t.startsWith("PPSA") || t.startsWith("PPRJ") && false) {
            return "ps5_native_game";
        }
        if (t.startsWith("CUSA") || t.startsWith("PCAS") || t.startsWith("NPQA")
            || t.startsWith("PLAS") || t.startsWith("JM")) {
            return "ps4_game";
        }
        if (t.startsWith("PCRA") || t.startsWith("RPG")) {
            return "pspc_game";
        }
        return "unknown";
    }

    /** ISO8601 时长 PT1461H42M50S -> 小时(小数) */
    static double parseIsoDurationHours(String iso) {
        if (iso == null || !iso.startsWith("PT")) {
            return 0;
        }
        try {
            String s = iso.substring(2);
            double hours = 0;
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)H").matcher(s);
            if (m.find()) {
                hours += Double.parseDouble(m.group(1));
            }
            m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)M").matcher(s);
            if (m.find()) {
                hours += Double.parseDouble(m.group(1)) / 60.0;
            }
            m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)S").matcher(s);
            if (m.find()) {
                hours += Double.parseDouble(m.group(1)) / 3600.0;
            }
            return Math.round(hours * 100) / 100.0;
        } catch (Exception e) {
            return 0;
        }
    }

    private <T> T callWithRetry(String npsso, Function<String, T> call) {
        try {
            return call.apply(authClient.accessToken(npsso));
        } catch (IllegalStateException e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.startsWith("PSN_TOKEN_EXPIRED") || msg.startsWith("PSN_HTTP_401")) {
                authClient.invalidate();
                return call.apply(authClient.accessToken(npsso));
            }
            throw e;
        }
    }

    // ---------- 上游映射（字段名对齐社区 psn-api 模型） ----------

    private RemoteView fromTrophy(JsonNode n) {
        String name = n.path("trophyTitleName").asText("");
        String commId = n.path("npCommunicationId").asText("");
        if (name.isEmpty() || commId.isEmpty()) {
            return null;
        }
        RemoteView rv = new RemoteView();
        rv.name = name;
        rv.platform = n.path("trophyTitlePlatform").asText("");
        rv.platformGroup = platformGroupOf(rv.platform, n.path("npServiceName").asText(""));
        rv.mergeKey = key(name, rv.platformGroup);
        rv.npCommunicationId = commId;
        rv.coverUrl = n.path("trophyTitleIconUrl").asText("");
        rv.trophyLastUpdated = n.path("lastUpdatedDateTime").asText("");
        rv.progress = n.path("progress").asInt(0);
        JsonNode earned = n.path("earnedTrophies");
        JsonNode defined = n.path("definedTrophies");
        rv.earned = earned.path("total").asInt(0);
        rv.total = defined.path("total").asInt(0);
        rv.platinum = earned.path("platinum").asInt(0);
        rv.gold = earned.path("gold").asInt(0);
        rv.silver = earned.path("silver").asInt(0);
        rv.bronze = earned.path("bronze").asInt(0);
        rv.hiddenFlag = n.path("hiddenFlag").asBoolean(false);
        rv.inTrophyList = true;
        return rv;
    }

    private RemoteView fromGame(JsonNode n) {
        String name = firstNonBlank(n.path("localizedName").asText(""), n.path("name").asText(""));
        if (name.isEmpty()) {
            return null;
        }
        RemoteView rv = new RemoteView();
        String category = n.path("category").asText("");
        rv.platformGroup = category.isEmpty() ? "unknown" : category;
        rv.mergeKey = key(name, rv.platformGroup);
        rv.name = name;
        rv.platform = switch (category) {
            case "ps5_native_game", "ps5_game" -> "PS5";
            case "ps4_game" -> "PS4";
            case "pspc_game" -> "PC";
            default -> category;
        };
        rv.npGameId = n.path("titleId").asText("");
        com.fasterxml.jackson.databind.JsonNode img = n.path("imageUrl");
        rv.coverUrl = img.isArray() && !img.isEmpty() ? img.get(0).path("url").asText("")
            : img.asText("");
        rv.lastPlayed = n.path("lastPlayedDateTime").asText("");
        rv.playtimeHours = n.path("totalPlaytime").asDouble(0);
        rv.inGameList = true;
        return rv;
    }

    static String platformGroupOf(String rawPlatform, String npServiceName) {
        String s = rawPlatform.toUpperCase();
        if (s.contains("PS5") || "trophy2".equals(npServiceName)) {
            return "ps5_native_game";
        }
        if (s.contains("PS4")) {
            return "ps4_game";
        }
        if (s.contains("PC")) {
            return "pspc_game";
        }
        return "unknown";
    }

    static String key(String name, String platformGroup) {
        return name.trim().toLowerCase() + "|" + platformGroup;
    }

    private PsnGame build(RemoteView rv, String now) {
        PsnGame g = new PsnGame();
        Metadata m = new Metadata();
        m.setName("psn-" + sha1(rv.mergeKey).substring(0, 20));
        g.setMetadata(m);
        g.setSpec(new PsnGame.PsnGameSpec());
        applyRemote(g, rv, now);
        PsnGame.PsnGameSpec s = g.getSpec();
        s.setPinned(false);
        s.setHidden(rv.hiddenFlag);
        s.setArchived(false);
        return g;
    }

    private void applyRemote(PsnGame g, RemoteView rv, String now) {
        PsnGame.PsnGameSpec s = g.getSpec();
        s.setMergeKey(rv.mergeKey);
        s.setName(rv.name);
        s.setPlatform(rv.platform);
        s.setPlatformGroup(rv.platformGroup);
        s.setCoverUrl(rv.coverUrl);
        s.setNpGameId(rv.npGameId);
        s.setNpCommunicationId(rv.npCommunicationId);
        s.setLastPlayed(rv.lastPlayed);
        s.setPlaytimeHours(rv.playtimeHours);
        s.setProgress(rv.progress);
        s.setProgressEarned(rv.earned);
        s.setProgressTotal(rv.total);
        s.setPlatinum(rv.platinum);
        s.setGold(rv.gold);
        s.setSilver(rv.silver);
        s.setBronze(rv.bronze);
        s.setTrophyLastUpdated(rv.trophyLastUpdated);
        s.setInTrophyList(rv.inTrophyList);
        s.setInGameList(rv.inGameList);
        s.setChecksum(rv.checksum());
        s.setLastSyncAt(now);
        if (rv.hiddenFlag) {
            s.setHidden(true);
        }
    }

    static class RemoteView {
        String mergeKey = "";
        String name = "";
        String platform = "";
        String platformGroup = "unknown";
        String npGameId = "";
        String npCommunicationId = "";
        String coverUrl = "";
        String lastPlayed = "";
        String trophyLastUpdated = "";
        double playtimeHours;
        int progress;
        int earned, total, platinum, gold, silver, bronze;
        boolean inTrophyList, inGameList, hiddenFlag;

        RemoteView merge(RemoteView other) {
            if (inTrophyList && other.inGameList) {
                copyGamePart(other);
            } else if (inGameList && other.inTrophyList) {
                copyTrophyPart(other);
            } else if (inGameList && other.inGameList) {
                copyGamePart(other);
            } else if (inTrophyList && other.inTrophyList) {
                copyTrophyPart(other);
            }
            return this;
        }

        void copyGamePart(RemoteView o) {
            if (!o.npGameId.isEmpty()) {
                npGameId = o.npGameId;
            }
            if (!o.coverUrl.isEmpty()) {
                coverUrl = o.coverUrl;
            }
            if (!o.lastPlayed.isEmpty()) {
                lastPlayed = o.lastPlayed;
            }
            if (o.playtimeHours > 0) {
                playtimeHours = o.playtimeHours;
            }
            inGameList = true;
            if (platform.isEmpty()) {
                platform = o.platform;
            }
        }

        void copyTrophyPart(RemoteView o) {
            npCommunicationId = o.npCommunicationId;
            trophyLastUpdated = o.trophyLastUpdated;
            progress = o.progress;
            earned = o.earned;
            total = o.total;
            platinum = o.platinum;
            gold = o.gold;
            silver = o.silver;
            bronze = o.bronze;
            hiddenFlag = o.hiddenFlag;
            inTrophyList = true;
            if (platform.isEmpty()) {
                platform = o.platform;
            }
            if (coverUrl.isEmpty()) {
                coverUrl = o.coverUrl;
            }
        }

        /** 只哈希远端可见字段：本地私有字段(pinned/note)不参与。 */
        String checksum() {
            String canon = String.join("\u0001", platform, npGameId, npCommunicationId,
                coverUrl, lastPlayed, String.valueOf(playtimeHours),
                String.valueOf(progress), String.valueOf(earned), String.valueOf(total),
                String.valueOf(platinum), String.valueOf(gold), String.valueOf(silver),
                String.valueOf(bronze), trophyLastUpdated, String.valueOf(hiddenFlag),
                String.valueOf(inTrophyList), String.valueOf(inGameList));
            return "sha256:" + sha256(canon);
        }
    }

    public static class SyncStats {
        public String mode = "incremental";
        public int upstream;
        public int created;
        public int updated;
        public int skipped;
        public int archived;
        public int deleted;
        public int kept;
        public String finishedAt;
        public String error;
    }

    static String sha1(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-1")
                .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String s) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
