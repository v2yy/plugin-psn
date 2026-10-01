package dev.v2yy.psn.service;

import dev.v2yy.psn.model.PsnConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 读取 Setting（connection/sync/display）并管理凭据。
 * 交互模型：用户在表单直接粘贴 npsso -> 保存 -> 本服务自动转存进 Halo Secret
 * 并把明文从插件 ConfigMap 抹除；之后仅按 Secret 名称引用。
 */
@Service
public class PsnConfigService {

    private static final Logger log = LoggerFactory.getLogger(PsnConfigService.class);
    public static final String DEFAULT_SECRET_NAME = "psn-npsso";

    private final ReactiveSettingFetcher settings;
    private final ReactiveExtensionClient client;

    public PsnConfigService(ReactiveSettingFetcher settings, ReactiveExtensionClient client) {
        this.settings = settings;
        this.client = client;
    }

    @lombok.Data
    public static class ConnectionForm {
        private String credentialSecretName;
        private String npsso;
        private String onlineId;
        private String region;
    }

    @lombok.Data
    public static class SyncForm {
        private Long syncIntervalMinutes;
        private String syncMode;
        private String missingAction;
        private String provider;
        private String mirrorBaseUrl;
        private String storeRegion;
        private List<String> platformFilter;
        private String hiddenGames;
    }

    @lombok.Data
    public static class DisplayForm {
        private Boolean routeEnabled;
        private String routePath;
        private String templateName;
        private Integer pageSize;
        private String sortDefault;
    }

    public Mono<PsnConfig> load() {
        PsnConfig cached = this.cached;
        if (cached != null && System.currentTimeMillis() - cachedAt < 5_000) {
            return Mono.just(cached);
        }
        return loadFresh();
    }

    /** 绕过缓存：用户刚保存表单后立即同步必须走这里（保证 npsso 转存发生）。 */
    public Mono<PsnConfig> loadFresh() {
        return Mono.fromCallable(this::loadBlocking)
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(c -> {
                this.cached = c;
                this.cachedAt = System.currentTimeMillis();
            })
            .onErrorResume(e -> {
                log.warn("[PSN] 配置加载异常: {}", e.toString());
                return Mono.just(new PsnConfig());
            });
    }

    private volatile PsnConfig cached;
    private volatile long cachedAt;

    /** 阻塞版本仅允许在非 event-loop 线程调用（boundedElastic/启动线程）。 */
    public PsnConfig loadBlocking() {
        PsnConfig cfg = new PsnConfig();
        ConnectionForm conn = fetchForm("connection", ConnectionForm.class);
        applyConnection(conn, cfg);
        SyncForm sync = fetchForm("sync", SyncForm.class);
        applySync(sync, cfg);
        DisplayForm disp = fetchForm("display", DisplayForm.class);
        applyDisplay(disp, cfg);

        // 明文 npsso 自动转存 Secret 并抹除
        if (conn != null && conn.getNpsso() != null && !conn.getNpsso().isBlank()) {
            String secretName = trimTo(cfg.getCredentialSecretName(), DEFAULT_SECRET_NAME);
            try {
                upsertSecret(secretName, conn.getNpsso().trim());
                clearNpssoInConfigMap();
                cfg.setCredentialSecretName(secretName);
                log.info("[PSN] npsso 已转存 Secret {} 并从设置抹除（不记录值）", secretName);
            } catch (Exception e) {
                log.warn("[PSN] npsso 转存失败: {}", e.getClass().getSimpleName());
            }
        }
        if (cfg.getCredentialSecretName().isBlank()) {
            cfg.setCredentialSecretName(DEFAULT_SECRET_NAME);
        }
        return cfg;
    }

    public Mono<String> npsso(String secretName) {
        if (secretName == null || secretName.isBlank()) {
            return Mono.empty();
        }
        return client.fetch(Secret.class, secretName)
            .flatMap(secret -> {
                String raw = null;
                if (secret.getStringData() != null) {
                    raw = secret.getStringData().get("npsso");
                }
                if ((raw == null || raw.isBlank()) && secret.getData() != null) {
                    byte[] b = secret.getData().get("npsso");
                    raw = b == null ? null : new String(Base64.getDecoder().decode(b),
                        StandardCharsets.UTF_8);
                }
                return raw == null || raw.isBlank()
                    ? Mono.empty() : Mono.just(raw.trim());
            });
    }

    public Mono<Void> invalidateSecret(String secretName) {
        return client.fetch(Secret.class, secretName)
            .flatMap(client::delete).then();
    }

    // ---------- 私有 ----------

    private <T> T fetchForm(String group, Class<T> type) {
        try {
            return settings.fetch(group, type).block(Duration.ofSeconds(5));
        } catch (Exception e) {
            return null; // 从未保存过设置
        }
    }

    private void applyConnection(ConnectionForm f, PsnConfig cfg) {
        if (f == null) {
            return;
        }
        cfg.setCredentialSecretName(trimTo(f.getCredentialSecretName(), ""));
        cfg.setOnlineId(trimTo(f.getOnlineId(), ""));
        cfg.setRegion(trimTo(f.getRegion(), "auto"));
    }

    private void applySync(SyncForm f, PsnConfig cfg) {
        if (f == null) {
            cfg.setHiddenGames(List.of());
            return;
        }
        cfg.setSyncIntervalMinutes(f.getSyncIntervalMinutes() == null ? 1440 : f.getSyncIntervalMinutes());
        cfg.setSyncMode(trimTo(f.getSyncMode(), "incremental"));
        cfg.setMissingAction(trimTo(f.getMissingAction(), "keep"));
        cfg.setProvider(trimTo(f.getProvider(), "mirror"));
        cfg.setMirrorBaseUrl(trimTo(f.getMirrorBaseUrl(), "https://api.psnsgame.com/api/psn/PSN"));
        cfg.setStoreRegion(normalizeStoreRegion(f.getStoreRegion(), cfg.getRegion()));
        cfg.setPlatformFilter(f.getPlatformFilter() == null ? List.of() : f.getPlatformFilter());
        List<String> hl = new ArrayList<>();
        if (f.getHiddenGames() != null) {
            for (String s : f.getHiddenGames().split("[,\n\r]+")) {
                if (!s.isBlank()) {
                    hl.add(s.trim());
                }
            }
        }
        cfg.setHiddenGames(hl);
    }

    private void applyDisplay(DisplayForm f, PsnConfig cfg) {
        if (f == null) {
            return;
        }
        cfg.setRouteEnabled(f.getRouteEnabled() == null || f.getRouteEnabled());
        cfg.setRoutePath(trimTo(f.getRoutePath(), "/psn"));
        cfg.setTemplateName(trimTo(f.getTemplateName(), "psn"));
        cfg.setPageSize(f.getPageSize() == null || f.getPageSize() <= 0 ? 24 : f.getPageSize());
        cfg.setSortDefault(trimTo(f.getSortDefault(), "lastPlayed"));
    }

    /** 商店区域：显式 storeRegion 优先；否则从 region(auto/hk/us/jp/...)推导；兜底 us。 */
    static String normalizeStoreRegion(String storeRegion, String region) {
        String s = storeRegion == null ? "" : storeRegion.trim().toLowerCase();
        if (s.matches("[a-z]{2}")) {
            return s;
        }
        String r = region == null ? "" : region.trim().toLowerCase();
        if (r.matches("[a-z]{2}")) {
            return r;
        }
        return "us";
    }

    private void upsertSecret(String name, String npssoValue) {
        Secret existing = client.fetch(Secret.class, name).block(Duration.ofSeconds(5));
        byte[] encoded = Base64.getEncoder().encode(npssoValue.getBytes(StandardCharsets.UTF_8));
        if (existing == null) {
            Secret s = new Secret();
            Metadata m = new Metadata();
            m.setName(name);
            s.setMetadata(m);
            s.setType(Secret.SECRET_TYPE_OPAQUE);
            s.setData(java.util.Map.of("npsso", encoded));
            client.create(s).block(Duration.ofSeconds(5));
        } else {
            existing.setData(existing.getData() == null
                ? java.util.Map.of("npsso", encoded)
                : new java.util.HashMap<>(existing.getData()));
            if (!(existing.getData() instanceof java.util.HashMap)) {
                existing.setData(new java.util.HashMap<>(existing.getData()));
            }
            existing.getData().put("npsso", encoded);
            existing.setStringData(null);
            client.update(existing).block(Duration.ofSeconds(5));
        }
    }

    /** 从插件自己的 ConfigMap 里抹掉 connection.npsso 明文。 */
    private void clearNpssoInConfigMap() {
        try {
            ConfigMap cm = client.fetch(ConfigMap.class, "plugin-psn-configmap")
                .block(Duration.ofSeconds(5));
            if (cm == null || cm.getData() == null) {
                return;
            }
            String conn = cm.getData().get("connection");
            if (conn == null || !conn.contains("npsso")) {
                return;
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(conn);
            if (node.has("npsso")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).putNull("npsso");
                cm.getData().put("connection",
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(node));
                client.update(cm).block(Duration.ofSeconds(5));
            }
        } catch (Exception e) {
            log.warn("[PSN] 抹除ConfigMap明文失败: {}", e.getClass().getSimpleName());
        }
    }

    private static String trimTo(String s, String def) {
        return s == null || s.isBlank() ? def : s.trim();
    }
}
