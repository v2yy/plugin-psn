package dev.v2yy.psn.endpoint;

import dev.v2yy.psn.service.PsnSyncService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.fn.builders.apiresponse.Builder;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 管理端 REST：手动同步 + 状态查询。
 * 状态响应绝不回显凭据/token 值。
 */
@Component
public class PsnSyncEndpoint implements CustomEndpoint {

    private final PsnSyncService syncService;

    public PsnSyncEndpoint(PsnSyncService syncService) {
        this.syncService = syncService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final String tag = "api.console.halo.run/v1alpha1/PSN";
        return SpringdocRouteBuilder.route()
            .POST("psn/-/sync", this::sync, b -> b.operationId("SyncPsn")
                .tag(tag).description("立即执行增量同步")
                .response(Builder.responseBuilder().description("同步统计")))
            .GET("psn/-/status", this::status, b -> b.operationId("PsnStatus")
                .tag(tag).description("最近一次同步状态")
                .response(Builder.responseBuilder().description("状态")))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.plugin.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> sync(ServerRequest request) {
        return syncService.sync()
            .flatMap(stats -> ok(statsView(stats)))
            .onErrorResume(e -> ServerResponse.badRequest()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("success", false,
                    "message", String.valueOf(e.getMessage()))));
    }

    private Mono<ServerResponse> status(ServerRequest request) {
        return ok(statsView(PsnSyncService.LAST.get()));
    }

    private Map<String, Object> statsView(PsnSyncService.SyncStats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        String kind = s.classifiedKind();
        m.put("success", s.error == null);
        m.put("errorKind", kind);
        m.put("running", PsnSyncService.RUNNING_SINCE.get() != null);
        m.put("provider", s.provider);
        m.put("degraded", s.fellBack);
        m.put("startedAt", s.startedAt);
        m.put("mode", s.mode);
        m.put("upstream", s.upstream);
        m.put("created", s.created);
        m.put("updated", s.updated);
        m.put("skipped", s.skipped);
        m.put("archived", s.archived);
        m.put("deleted", s.deleted);
        m.put("kept", s.kept);
        m.put("finishedAt", s.finishedAt);
        m.put("lastSuccessAt", PsnSyncService.LAST_OK.get() == null
            ? null : PsnSyncService.LAST_OK.get().finishedAt);
        m.put("error", s.error);
        return m;
    }

    private Mono<ServerResponse> ok(Map<String, Object> body) {
        return ServerResponse.ok()
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(body);
    }
}
