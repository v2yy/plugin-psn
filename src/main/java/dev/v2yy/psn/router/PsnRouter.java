package dev.v2yy.psn.router;

import dev.v2yy.psn.finder.PsnFinder;
import dev.v2yy.psn.model.PsnConfig;
import dev.v2yy.psn.model.PsnDetailView;
import dev.v2yy.psn.model.PsnListResult;
import dev.v2yy.psn.service.PsnConfigService;
import dev.v2yy.psn.service.PsnDetailService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RequestPredicate;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * 前台路由。铁律（AstraHub 全站 500 事故教训）：
 * 1. 谓词只匹配固定前缀，绝不拦截全站其它请求；
 * 2. 请求处理链内绝不 block()（event-loop 禁止阻塞）；
 * 3. routePath 在插件启动时读一次（改路径需重启插件）；
 *    启用开关/模板/排序/分页每次请求热生效。
 */
@Configuration
public class PsnRouter {

    private static final Logger log = LoggerFactory.getLogger(PsnRouter.class);

    @Bean
    RouterFunction<ServerResponse> psnRoute(PsnConfigService configService, PsnFinder finder,
                                            PsnDetailService detailService) {
        String base = "/psn";
        String detailBase = base + "/game";
        try {
            // 此处运行在插件启动线程（非 event loop），block 合法
            PsnConfig cfg = configService.load().block(java.time.Duration.ofSeconds(10));
            if (cfg != null && cfg.getRoutePath() != null && !cfg.getRoutePath().isBlank()) {
                base = cfg.getRoutePath();
                detailBase = base + "/game";
            }
        } catch (Exception e) {
            log.warn("[PSN] 启动读 routePath 失败，用默认 /psn: {}", e.getClass().getSimpleName());
        }
        RequestPredicate root = RequestPredicates.GET(base);
        RequestPredicate paged = RequestPredicates.path(base + "/page/{*num}");
        // 详情页：GET <base>/game/<扩展名>。谓词是固定前缀，绝不拦全站其它请求。
        RequestPredicate detail = RequestPredicates.path(detailBase + "/{*id}");
        final String prefix = base;
        final String detailPrefix = detailBase;
        return RouterFunctions.route(root.or(paged).or(detail),
            request -> handle(request, finder, configService, detailService, prefix, detailPrefix));
    }

    private Mono<ServerResponse> handle(ServerRequest request, PsnFinder finder,
                                        PsnConfigService configService,
                                        PsnDetailService detailService, String prefix,
                                        String detailPrefix) {
        final String path = request.path();
        if (path.startsWith(detailPrefix + "/")) {
            return handleDetail(request, finder, configService, detailService, detailPrefix);
        }
        return configService.load()
            .flatMap(cfg -> {
                if (!cfg.isRouteEnabled()) {
                    return ServerResponse.notFound().build();
                }
                int page = parsePage(prefix, path);
                String platform = request.queryParam("platform").orElse("");
                String sort = request.queryParam("sort").orElse("");
                Mono<PsnListResult> result = platform.isEmpty()
                    ? finder.list(page, cfg.getPageSize(), sort)
                    : finder.listByPlatform(platform, page, cfg.getPageSize(), sort);
                return result.flatMap(res -> {
                    res.setRoutePath(cfg.getRoutePath());
                    return ServerResponse.ok()
                        .render(cfg.getTemplateName(), Map.of("psnGames", res));
                });
            });
    }

    /** 单游戏详情：奖杯明细 + 游玩时长/最近游玩（快照按需从镜像拉取，失败回退本地数据） */
    private Mono<ServerResponse> handleDetail(ServerRequest request, PsnFinder finder,
                                              PsnConfigService configService,
                                              PsnDetailService detailService, String detailPrefix) {
        String id = request.path().substring((detailPrefix + "/").length()).split("[/?#]")[0];
        final boolean refresh = "1".equals(request.queryParam("refresh").orElse(""));
        return configService.load()
            .flatMap(cfg -> {
                if (!cfg.isRouteEnabled()) {
                    return ServerResponse.notFound().build();
                }
                return finder.byName(id)
                    .flatMap(game -> detailService.enrich(game, cfg, refresh)
                        .flatMap(g -> ServerResponse.ok()
                            .render(cfg.getDetailTemplateName(), Map.of(
                                "psnGame", g,
                                "psnDetail", new PsnDetailView(g, cfg)))))
                    .switchIfEmpty(ServerResponse.notFound().build());
            });
    }

    static int parsePage(String routePath, String path) {
        String p = routePath + "/page/";
        if (path.startsWith(p)) {
            try {
                return Math.max(1, Integer.parseInt(path.substring(p.length()).split("[/?]")[0]));
            } catch (Exception ignored) {
                return 1;
            }
        }
        return 1;
    }
}
