package dev.v2yy.psn.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.v2yy.psn.client.PsnMirrorClient;
import dev.v2yy.psn.model.PsnConfig;
import dev.v2yy.psn.model.PsnGame;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 详情页增强数据：逐奖杯明细 + 近期游玩记录（psnsgame 镜像接口，按需拉取）。
 * 全库 400+ 款不做全量同步，只在有人打开某款详情页时拉一次，结果缓存进
 * PsnGame.spec（detailSnapshot / detailSnapshotAt），TTL 12 小时。
 * 拉取失败绝不影响页面：回退旧缓存，再回退基础统计。
 */
@Service
public class PsnDetailService {

    private static final Logger log = LoggerFactory.getLogger(PsnDetailService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final long CACHE_TTL_SECONDS = 12 * 3600;

    private final PsnMirrorClient mirror;
    private final ReactiveExtensionClient client;

    public PsnDetailService(PsnMirrorClient mirror, ReactiveExtensionClient client) {
        this.mirror = mirror;
        this.client = client;
    }

    /** 返回带上最新快照的 game（可能没变）；任何异常吞掉只打日志。refresh=true 跳过TTL强拉。 */
    public Mono<PsnGame> enrich(PsnGame game, PsnConfig cfg, boolean refresh) {
        if (!"mirror".equalsIgnoreCase(cfg.getProvider())
            || cfg.getOnlineId() == null || cfg.getOnlineId().isBlank()) {
            return Mono.just(game);
        }
        PsnGame.PsnGameSpec s = game.getSpec();
        String commId = trim(s.getNpCommunicationId());
        String titleId = firstTitleId(s.getNpGameId());
        if (commId.isEmpty() || titleId.isEmpty()) {
            return Mono.just(game);
        }
        if (!refresh && isFresh(s.getDetailSnapshotAt()) && s.getDetailSnapshot() != null
            && !s.getDetailSnapshot().isBlank()) {
            return Mono.just(game);
        }
        return Mono.fromCallable(() -> {
                JsonNode trophy = mirror.getOneUserGameTrophy(cfg.getMirrorBaseUrl(),
                    cfg.getOnlineId(), commId, titleId);
                JsonNode playtime = null;
                try {
                    playtime = mirror.getGamePlaytimeHistory(cfg.getMirrorBaseUrl(),
                        cfg.getOnlineId(), titleId);
                } catch (Exception e) {
                    log.debug("[PSN] 游玩记录拉取失败 {}: {}", titleId, e.toString());
                }
                var root = MAPPER.createObjectNode();
                root.set("trophy", trophy);
                if (playtime != null) {
                    root.set("playtime", playtime);
                }
                return MAPPER.writeValueAsString(root);
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(json -> {
                s.setDetailSnapshot(json);
                s.setDetailSnapshotAt(Instant.now().toString());
                return client.update(game)
                    .doOnError(e -> log.warn("[PSN] 详情快照回写失败: {}", e.toString()))
                    .onErrorResume(e -> Mono.just(game));
            })
            .switchIfEmpty(Mono.just(game))
            .onErrorResume(e -> {
                log.warn("[PSN] 详情拉取失败 {}（用缓存/基础视图兜底）: {}",
                    titleId, e.getClass().getSimpleName());
                return Mono.just(game);
            });
    }

    static boolean isFresh(String at) {
        if (at == null || at.isBlank()) {
            return false;
        }
        try {
            return Duration.between(Instant.parse(at), Instant.now())
                .getSeconds() < CACHE_TTL_SECONDS;
        } catch (Exception e) {
            return false;
        }
    }

    static String firstTitleId(String npGameId) {
        String t = trim(npGameId);
        int comma = t.indexOf(',');
        return comma > 0 ? t.substring(0, comma).trim() : t;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
