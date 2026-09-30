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
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** m.np.playstation.com 逆向只读端点（trophy / gamelist / search）。 */
@Component
public class PsnApiClient {

    private static final String TROPHY_BASE = "https://m.np.playstation.com/api/trophy/v1";
    private static final String GAMELIST_BASE = "https://m.np.playstation.com/api/gamelist/v2/users";
    private static final String SEARCH_BASE = "https://m.np.playstation.com/api/search/v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /** 奖杯标题列表（含进度/白金数等），分页。 */
    public JsonNode trophyTitles(String accessToken, String accountId, int limit, int offset) {
        String url = TROPHY_BASE + "/accounts/" + enc(accountId)
            + "/trophyTitles?limit=" + limit + "&offset=" + offset
            + "&npTitleVersion=all";
        return get(accessToken, url);
    }

    /** 游戏库（含时长/最近游玩），分页；他人账号受隐私限制。 */
    public JsonNode playedGames(String accessToken, String accountId, int limit, int offset) {
        String url = GAMELIST_BASE + "/" + enc(accountId)
            + "/titles?sort=-lastPlayedDateTime&limit=" + limit + "&offset=" + offset;
        return get(accessToken, url);
    }

    /** onlineId -> accountId（universal search）。 */
    public String findAccountId(String accessToken, String onlineId) {
        String url = SEARCH_BASE + "/universalSearch/search?searchTerm="
            + enc(onlineId) + "&searchDomain=ACCOUNT&searchPage=LIBRARY";
        JsonNode j = get(accessToken, url);
        JsonNode docs = j.path("searchResults");
        for (JsonNode d : docs) {
            JsonNode acc = d.path("accountInformation").path("account");
            if (onlineId.equalsIgnoreCase(acc.path("onlineId").asText())) {
                return acc.path("accountId").asText();
            }
        }
        // 兜底第一个
        if (docs.isArray() && docs.size() > 0) {
            String id = docs.get(0).path("accountInformation").path("accountId").asText("");
            return id.isEmpty() ? null : id;
        }
        return null;
    }

    private JsonNode get(String accessToken, String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + accessToken)
            .header("Accept", "application/json")
            .GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401) {
                throw new IllegalStateException("PSN_TOKEN_EXPIRED");
            }
            if (res.statusCode() == 403) {
                throw new IllegalStateException("PSN_FORBIDDEN(隐私设置或他人历史)");
            }
            if (res.statusCode() >= 400) {
                throw new IllegalStateException("PSN_HTTP_" + res.statusCode());
            }
            return MAPPER.readTree(res.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PSN_REQUEST_ERROR", e);
        }
    }

    /** 翻页收集全部条目。 */
    public List<JsonNode> collectPages(String accessToken, Fetcher fetcher, String field,
                                       int pageSize, int maxItems) {
        List<JsonNode> all = new ArrayList<>();
        int offset = 0;
        while (all.size() < maxItems) {
            JsonNode root = fetcher.fetch(accessToken, pageSize, offset);
            JsonNode arr = root.path(field);
            if (!arr.isArray() || arr.isEmpty()) {
                break;
            }
            arr.forEach(all::add);
            int total = root.path("totalResults").asInt(all.size());
            offset += pageSize;
            if (offset >= total || arr.size() < pageSize) {
                break;
            }
        }
        return all;
    }

    @FunctionalInterface
    public interface Fetcher {
        JsonNode fetch(String accessToken, int limit, int offset);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
