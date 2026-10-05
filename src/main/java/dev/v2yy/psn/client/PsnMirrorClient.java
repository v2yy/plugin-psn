package dev.v2yy.psn.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 第三方同步站只读 JSON 客户端（provider=mirror）。
 * 无鉴权；只按 PSNID 拉该账号的公开同步结果。默认源 psnsgame（2026-09-30 实测裸GET可用）。
 * 上游是志愿站，接口可能变化：全部错误以 MIRROR_ 前缀异常上抛，由同步层降级。
 */
@Component
public class PsnMirrorClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public JsonNode getRecentlyPlayed(String baseUrl, String psnId) {
        return get(baseUrl, "getRecentlyPlayed?PSNID=" + enc(psnId));
    }

    /** 全量游戏库(分页)：getallgamelife，含每游戏奖杯明细/时长/封面。2026-09-30 实测 total=478 pages=24。 */
    public JsonNode getAllGameLife(String baseUrl, String psnId, int page, int size) {
        return get(baseUrl, "getallgamelife?PSNID=" + enc(psnId)
            + "&page=" + page + "&size=" + size
            + "&sortBy=lastPlayed&searchname=&platform=all&filterType=");
    }

    public JsonNode getTrophySummary(String baseUrl, String psnId) {
        return get(baseUrl, "gettrophySummary?PSNID=" + enc(psnId));
    }

    /** 单游戏逐奖杯明细（参考页 psnsgame GameTrophyDetail 同源接口）：trophyList 按组分 earned/未earn。 */
    public JsonNode getOneUserGameTrophy(String baseUrl, String psnId, String npCommunicationId,
                                         String titleId) {
        return get(baseUrl, "getoneusergametrophy?PSNID=" + enc(psnId)
            + "&npid=" + enc(npCommunicationId) + "&titleids=" + enc(titleId)
            + "&language=zh-Hans&refreshLanguage=false");
    }

    /** 近期游玩记录（按天+会话）。 */
    public JsonNode getGamePlaytimeHistory(String baseUrl, String psnId, String titleId) {
        return get(baseUrl, "getGamePlaytimeHistory?PSNID=" + enc(psnId)
            + "&titleids=" + enc(titleId));
    }

    /** 站方数据新鲜度：accountCountryUpdatedAt 等字段可作最后同步参考。 */
    public JsonNode getCareerSummary(String baseUrl, String psnId) {
        return get(baseUrl, "getCareerSummary?PSNID=" + enc(psnId));
    }

    private JsonNode get(String baseUrl, String pathAndQuery) {
        String url = (baseUrl == null || baseUrl.isBlank()
            ? "https://api.psnsgame.com/api/psn/PSN" : baseUrl) + "/" + pathAndQuery;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new IllegalStateException("MIRROR_HTTP_" + res.statusCode());
            }
            JsonNode j = MAPPER.readTree(res.body());
            // 该站业务错误形如 {"code":"5000","message":"..."}
            if (j.has("code") && !j.has("PSNID") && !j.path("code").asText("").isEmpty()
                && j.has("result") && j.get("result").isNull()) {
                throw new IllegalStateException("MIRROR_BUSINESS_ERROR_"
                    + j.path("code").asText());
            }
            return j;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("MIRROR_REQUEST_ERROR", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
