package dev.v2yy.psn.scheduler;

import dev.v2yy.psn.model.PsnConfig;
import dev.v2yy.psn.service.PsnConfigService;
import dev.v2yy.psn.service.PsnSyncService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 单线程 ticker：每5分钟检查一次配置；syncIntervalMinutes>0 且距上次完成超过间隔才触发。
 * 配置热生效，无需重启。
 */
@Component
public class PsnSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PsnSyncScheduler.class);
    private static final long TICK_MINUTES = 5;

    private final PsnConfigService configService;
    private final PsnSyncService syncService;
    private ScheduledExecutorService executor;

    public PsnSyncScheduler(PsnConfigService configService, PsnSyncService syncService) {
        this.configService = configService;
        this.syncService = syncService;
    }

    @PostConstruct
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "psn-sync-ticker");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tick, TICK_MINUTES, TICK_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void tick() {
        try {
            PsnConfig cfg = configService.load().block();
            if (cfg == null || cfg.getSyncIntervalMinutes() <= 0) {
                return;
            }
            var last = PsnSyncService.LAST.get();
            long lastMs = last.finishedAt == null ? 0 : java.time.OffsetDateTime
                .parse(last.finishedAt).toInstant().toEpochMilli();
            long dueMs = lastMs + cfg.getSyncIntervalMinutes() * 60_000L;
            if (lastMs != 0 && System.currentTimeMillis() < dueMs) {
                return;
            }
            if (cfg.getCredentialSecretName().isBlank()) {
                return; // 未配置凭据不空跑
            }
            log.info("[PSN] 定时同步触发");
            syncService.sync().subscribe();
        } catch (Exception e) {
            log.warn("[PSN] ticker异常: {}", e.getClass().getSimpleName());
        }
    }
}
