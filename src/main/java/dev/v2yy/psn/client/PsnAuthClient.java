package dev.v2yy.psn.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * npsso -> accessCode -> access/refresh token。
 * 端点与参数对齐社区 psn-api（achievements-app），非索尼官方文档接口。
 * 令牌仅存内存，绝不写日志。
 */
@Component
public class PsnAuthClient {

    private static final String AUTH_BASE_URL = "https://ca.account.sony.com/api/authz/v3/oauth";
    private static final String CLIENT_ID = "09515159-7237-4370-9b40-3806e67c0891";
    private static final String REDIRECT_URI = "com.scee.psxandroid.scecompcall://redirect";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private volatile Cached cached;

    private record Cached(String accessToken, String refreshToken, long accessExpiryEpochMs,
                          long refreshExpiryEpochMs) {
    }

    /** 获取有效 access token；缓存失效则用 refresh token 续期，再不行走 npsso 全链路。 */
    public synchronized String accessToken(String npsso) {
        long now = System.currentTimeMillis();
        if (cached != null && cached.accessExpiryEpochMs() - 60_000 > now) {
            return cached.accessToken();
        }
        if (cached != null && cached.refreshExpiryEpochMs() - 60_000 > now) {
            try {
                Token t = requestTokens(formBody(Map.of(
                    "grant_type", "refresh_token",
                    "refresh_token", cached.refreshToken())));
                cached = new Token(t.accessToken(), t.refreshToken(), t.expiresIn(),
                        t.refreshTokenExpiresIn()).toCached();
                return cached.accessToken();
            } catch (Exception ignored) {
                cached = null; // refresh 失效，回退到 npsso 全链路
            }
        }
        String code = exchangeNpssoForAccessCode(npsso);
        Token t = requestTokens(formBody(Map.of(
            "grant_type", "authorization_code",
            "code", code,
            "redirect_uri", REDIRECT_URI,
            "token_format", "jwt")));
        cached = new Token(t.accessToken(), t.refreshToken(), t.expiresIn(),
                t.refreshTokenExpiresIn()).toCached();
        return cached.accessToken();
    }

    public synchronized void invalidate() {
        cached = null;
    }

    private String exchangeNpssoForAccessCode(String npsso) {
        String url = AUTH_BASE_URL + "/authorize?access_type=offline"
            + "&client_id=" + CLIENT_ID
            + "&redirect_uri=" + REDIRECT_URI
            + "&response_type=code"
            + "&scope=psn%3Amobile.v2.core%20psn%3Aclientapp";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Cookie", "npsso=" + npsso)
            .GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                throw new IllegalStateException("NPSSO_INVALID");
            }
            String location = res.headers().firstValue("location").orElse("");
            int idx = location.indexOf("code=");
            if (idx < 0) {
                // 有的实现直接返回 query；也兜底解析 body
                throw new IllegalStateException("NO_ACCESS_CODE (status " + res.statusCode() + ")");
            }
            String code = location.substring(idx + 5);
            int amp = code.indexOf('&');
            return amp >= 0 ? code.substring(0, amp) : code;
        } catch (Exception e) {
            throw new IllegalStateException("PSN_AUTH_FAILED: " + e.getClass().getSimpleName(), e);
        }
    }

    private Token requestTokens(String body) {
        String basic = Base64.getEncoder().encodeToString(
            (CLIENT_ID + ":ucPjka5tntB2KqsJP").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(AUTH_BASE_URL + "/token"))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Authorization", "Basic " + basic)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                throw new IllegalStateException("TOKEN_EXCHANGE_FAILED " + res.statusCode());
            }
            JsonNode j = MAPPER.readTree(res.body());
            return new Token(j.path("access_token").asText(), j.path("refresh_token").asText(),
                j.path("expires_in").asLong(7200), j.path("refresh_token_expires_in").asLong(7200));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("TOKEN_EXCHANGE_ERROR", e);
        }
    }

    private static String formBody(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }

    private record Token(String accessToken, String refreshToken, long expiresIn,
                         long refreshTokenExpiresIn) {
        Cached toCached() {
            long now = System.currentTimeMillis();
            return new Cached(accessToken, refreshToken, now + expiresIn * 1000,
                now + refreshTokenExpiresIn * 1000);
        }
    }
}
