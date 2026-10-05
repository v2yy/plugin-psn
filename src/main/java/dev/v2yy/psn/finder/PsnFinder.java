package dev.v2yy.psn.finder;

import dev.v2yy.psn.model.PsnConfig;
import dev.v2yy.psn.model.PsnGame;
import dev.v2yy.psn.model.PsnListResult;
import dev.v2yy.psn.service.PsnConfigService;
import dev.v2yy.psn.service.PsnSyncService;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.theme.finders.Finder;

/**
 * 模板变量 psnFinder（同步 listAll + 内存过滤分页；个人站规模 <2000 条目足够）。
 * 主题用法：${psnFinder.list(1, 24)} / ${psnFinder.listByPlatform('ps5_native_game', 1, 24)}
 */
@Finder("psnFinder")
public class PsnFinder {

    private final ReactiveExtensionClient client;
    private final PsnConfigService configService;

    public PsnFinder(ReactiveExtensionClient client, PsnConfigService configService) {
        this.client = client;
        this.configService = configService;
    }

    public Mono<PsnListResult> list(int page, int size) {
        return listInner(null, page, size, null);
    }

    public Mono<PsnListResult> list(int page, int size, String sort) {
        return listInner(null, page, size, sort);
    }

    public Mono<PsnListResult> listByPlatform(String platformGroup, int page, int size) {
        return listInner(platformGroup, page, size, null);
    }

    public Mono<PsnListResult> listByPlatform(String platformGroup, int page, int size, String sort) {
        return listInner(platformGroup, page, size, sort);
    }

    /** 详情页：按扩展名（psn-<sha1前20位>，列表卡片 href 携带）取单个游戏；不可见/不存在返回 empty */
    public Mono<PsnGame> byName(String metadataName) {
        if (metadataName == null || metadataName.isBlank()
            || !metadataName.matches("[a-z0-9-]{1,64}")) {
            return Mono.empty();
        }
        return client.fetch(PsnGame.class, metadataName)
            .filter(g -> !Boolean.TRUE.equals(g.getSpec().getHidden())
                && !Boolean.TRUE.equals(g.getSpec().getArchived()));
    }

    private Mono<PsnListResult> listInner(String platformGroup, int page, int size, String sort) {
        int p = Math.max(page, 1);
        int s = size <= 0 ? 24 : size;
        return client.listAll(PsnGame.class, ListOptions.builder().build(),
                Sort.by("metadata.name"))
            .collectList()
            .flatMap(all -> {
                List<PsnGame> filtered = platformGroup == null || platformGroup.isBlank()
                    ? all
                    : all.stream().filter(g -> platformGroup.equals(g.getSpec().getPlatformGroup()))
                        .toList();
                return configService.load().defaultIfEmpty(new PsnConfig())
                    .map(cfg -> {
                        PsnListResult res = toResult(filtered, cfg, p, s, platformGroup, sort);
                        String maxSync = all.stream()
                            .map(g -> nullSafe(g.getSpec().getLastSyncAt()))
                            .filter(x -> !x.isEmpty())
                            .max(String::compareTo)
                            .orElse(null);
                        res.setStatus(PsnSyncService.statusView(cfg, filtered.size(), maxSync));
                        res.setStoreRegion(cfg.getStoreRegion());
                        return res;
                    });
            });
    }

    private PsnListResult toResult(List<PsnGame> all, PsnConfig cfg,
                                   int p, int s, String platformGroup, String sort) {
        List<PsnGame> visible = all.stream()
            .filter(g -> !Boolean.TRUE.equals(g.getSpec().getHidden())
                && !Boolean.TRUE.equals(g.getSpec().getArchived()))
            .toList();

        String key = sort == null || sort.isBlank() ? cfg.getSortDefault() : sort;
        Comparator<PsnGame> byKey = switch (key) {
            case "playtime" ->
                Comparator.comparingDouble((PsnGame g) -> g.getSpec().getPlaytimeHours()).reversed();
            case "trophyProgress" ->
                Comparator.comparingInt((PsnGame g) -> g.getSpec().getProgress()).reversed();
            case "name" -> Comparator.comparing(g -> nullSafe(g.getSpec().getName()));
            default -> Comparator.comparing((PsnGame g) -> nullSafe(g.getSpec().getLastPlayed())).reversed();
        };
        // pinned 优先，其次 key
        Comparator<PsnGame> cmp = Comparator
            .comparing((PsnGame g) -> !Boolean.TRUE.equals(g.getSpec().getPinned()))
            .thenComparing(byKey);
        List<PsnGame> sorted = visible.stream().sorted(cmp).toList();

        int from = Math.min((p - 1) * s, sorted.size());
        int to = Math.min(from + s, sorted.size());

        PsnListResult r = new PsnListResult();
        r.setPage(p);
        r.setSize(s);
        r.setTotal(sorted.size());
        r.setPlatform(platformGroup == null ? "" : platformGroup);
        r.setSort(key);
        r.setRoutePath(cfg.getRoutePath());
        r.setItems(sorted.subList(from, to));

        PsnListResult.Summary sum = new PsnListResult.Summary();
        double hours = 0;
        long pt = 0, gd = 0, sv = 0, br = 0;
        int prog = 0;
        for (PsnGame g : sorted) {
            hours += g.getSpec().getPlaytimeHours();
            pt += g.getSpec().getPlatinum();
            gd += g.getSpec().getGold();
            sv += g.getSpec().getSilver();
            br += g.getSpec().getBronze();
            prog += g.getSpec().getProgress();
        }
        sum.setTotalGames(sorted.size());
        sum.setTotalPlaytimeHours(Math.round(hours * 10) / 10.0);
        sum.setPlatinum(pt);
        sum.setGold(gd);
        sum.setSilver(sv);
        sum.setBronze(br);
        sum.setAvgProgress(sorted.isEmpty() ? 0 : prog / sorted.size());
        r.setSummary(sum);
        return r;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
