package dev.v2yy.psn.router;

import dev.v2yy.psn.finder.PsnFinder;
import dev.v2yy.psn.model.PsnConfig;
import dev.v2yy.psn.model.PsnListResult;
import dev.v2yy.psn.service.PsnConfigService;
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
    RouterFunction<ServerResponse> psnRoute(PsnConfigService configService, PsnFinder finder) {
        String base = "/psn";
        try {
            // 此处运行在插件启动线程（非 event loop），block 合法
            PsnConfig cfg = configService.load().block(java.time.Duration.ofSeconds(10));
            if (cfg != null && cfg.getRoutePath() != null && !cfg.getRoutePath().isBlank()) {
                base = cfg.getRoutePath();
            }
        } catch (Exception e) {
            log.warn("[PSN] 启动读 routePath 失败，用默认 /psn: {}", e.getClass().getSimpleName());
        }
        RequestPredicate root = RequestPredicates.GET(base);
        RequestPredicate paged = RequestPredicates.path(base + "/page/{*num}");
        final String prefix = base;
        return RouterFunctions.route(root.or(paged),
            request -> handle(request, finder, configService, prefix));
    }

    private Mono<ServerResponse> handle(ServerRequest request, PsnFinder finder,
                                        PsnConfigService configService, String prefix) {
        final String path = request.path();
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
