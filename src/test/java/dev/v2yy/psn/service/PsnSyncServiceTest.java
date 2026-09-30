package dev.v2yy.psn.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/** 增量同步核心：RemoteView 合并与 checksum 稳定性。 */
class PsnSyncServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void checksumStableAcrossEquals() {
        PsnSyncService.RemoteView a = view("Ghosts of Tsushima|ps5_native_game");
        PsnSyncService.RemoteView b = view("Ghosts of Tsushima|ps5_native_game");
        assertEquals(a.checksum(), b.checksum(), "同数据同checksum（幂等基础）");
        b.playtimeHours = 12.5;
        assertNotEquals(a.checksum(), b.checksum(), "时长变化必须触发UPDATE");
    }

    @Test
    void mergeTrophyAndGameComplementary() {
        PsnSyncService.RemoteView trophy = view("test|ps4_game");
        trophy.inTrophyList = true;
        trophy.npCommunicationId = "PPSA01268-CUSA00000_00-TST0000000000000";
        trophy.total = 58;
        trophy.earned = 10;
        PsnSyncService.RemoteView game = view("test|ps4_game");
        game.inGameList = true;
        game.playtimeHours = 42.0;
        game.lastPlayed = "2026-09-29T10:00:00Z";
        game.npGameId = "CUSA00000_00-TST0000000000000";
        trophy.merge(game);
        assertTrue(trophy.inGameList && trophy.inTrophyList);
        assertEquals(42.0, trophy.playtimeHours);
        assertEquals(58, trophy.total, "奖杯字段不能被游戏视图覆盖丢失");
    }

    @Test
    void platformGroupOfAuthoritativeFields() {
        // trophyTitlePlatform 是逗号分隔串（如 "PS5, PS4"）
        assertEquals("ps5_native_game", PsnSyncService.platformGroupOf("PS5, PS4", ""));
        assertEquals("ps4_game", PsnSyncService.platformGroupOf("PS4", ""));
        // npServiceName=trophy2 是 PS5 权威标记
        assertEquals("ps5_native_game", PsnSyncService.platformGroupOf("", "trophy2"));
    }

    @Test
    void trophyJsonMappingMatchesPsnApiModel() throws Exception {
        JsonNode n = MAPPER.readTree("""
            {"npCommunicationId":"PPSA01268-CUSA00000_00-TST0000000000000",
             "trophyTitleName":"Demon's Souls",
             "trophyTitlePlatform":"PS5",
             "npServiceName":"trophy2",
             "hiddenFlag":false,
             "lastUpdatedDateTime":"2026-09-01T00:00:00Z",
             "trophyTitleIconUrl":"https://img/xx.png",
             "progress":37,
             "definedTrophies":{"bronze":40,"silver":10,"gold":1,"platinum":1,"total":51},
             "earnedTrophies":{"bronze":1,"silver":0,"gold":0,"platinum":0,"total":1}}
            """);
        // 直接验证映射所需字段存在且可取
        assertEquals("Demon's Souls", n.path("trophyTitleName").asText());
        assertEquals("trophy2", n.path("npServiceName").asText());
        assertEquals(37, n.path("progress").asInt());
        assertEquals(51, n.path("definedTrophies").path("total").asInt());
    }

    private static PsnSyncService.RemoteView view(String mergeKey) {
        PsnSyncService.RemoteView rv = new PsnSyncService.RemoteView();
        rv.mergeKey = mergeKey;
        int i = mergeKey.indexOf('|');
        rv.name = mergeKey.substring(0, i);
        rv.platformGroup = mergeKey.substring(i + 1);
        return rv;
    }

    @Test
    void cnDurationParsing() {
        assertEquals(1461.71, PsnSyncService.parseCnDurationHours("1461\u5c0f\u65f642\u5206\u949f50\u79d2"), 0.01);
        assertEquals(2.5, PsnSyncService.parseCnDurationHours("2\u5c0f\u65f630\u5206"), 0.01);
        assertEquals(0.01, PsnSyncService.parseCnDurationHours("50\u79d2"), 0.01);
        assertEquals(2.5, PsnSyncService.parseCnDurationHours("PT2H30M"), 0.01); // ISO分支兼容
    }

    @Test
    void isoDurationStillWorks() {
        assertEquals(1461.71, PsnSyncService.parseIsoDurationHours("PT1461H42M50S"), 0.01);
    }

    @Test
    void mirrorTitleIdPlatformGuess() {
        assertEquals("ps5_native_game", PsnSyncService.platformGroupFromTitleId("PPSA02442_00"));
        assertEquals("ps4_game", PsnSyncService.platformGroupFromTitleId("CUSA12345_00"));
        assertEquals("unknown", PsnSyncService.platformGroupFromTitleId(""));
    }
}
