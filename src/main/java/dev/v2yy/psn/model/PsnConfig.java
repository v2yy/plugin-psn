package dev.v2yy.psn.model;

import java.util.List;
import lombok.Data;

/** 配置快照（每次同步开始读取一次，热生效） */
@Data
public class PsnConfig {
    private String credentialSecretName = "";
    private String onlineId = "";
    private String region = "auto";

    private long syncIntervalMinutes = 1440;
    private String syncMode = "incremental";
    private String missingAction = "keep";
    /** 数据源：sony=直连(需npsso,当前上游4102挂起)；mirror=第三方同步站只读JSON */
    private String provider = "mirror";
    private String mirrorBaseUrl = "https://api.psnsgame.com/api/psn/PSN";
    /** 商店链接区域段（store.playstation.com/<region>/product/...）。us=美服明示 */
    private String storeRegion = "us";
    private List<String> platformFilter = List.of();
    private List<String> hiddenGames = List.of();

    private boolean routeEnabled = true;
    private String routePath = "/psn";
    private String templateName = "psn";
    private String detailTemplateName = "psn_game";
    private int pageSize = 24;
    private String sortDefault = "lastPlayed";

    /** 裸区域码(us/hk/jp/tw/gb) → 商店 URL 合法区域段。us/hk 直接拼进 URL 会被索尼 302 叠路径后 404。 */
    public static String storeSearchLocale(String storeRegion) {
        return switch (storeRegion == null ? "us" : storeRegion) {
            case "hk" -> "zh-hant-hk";
            case "jp" -> "ja-jp";
            case "tw" -> "zh-hant-tw";
            case "gb" -> "en-gb";
            default -> "en-us";
        };
    }

    public static String storeRegionLabel(String storeRegion) {
        return switch (storeRegion == null ? "us" : storeRegion) {
            case "hk" -> "港服";
            case "jp" -> "日服";
            case "tw" -> "台服";
            case "gb" -> "英服";
            default -> "美服";
        };
    }

    /**
     * 商店搜索链接：商品直达页需要完整 content ID（Title ID-变体串），镜像数据只有
     * Title ID，直拼 /product/ 必 404（Cannot GET）。搜索页用 Title ID 前 9 位实测可用。
     */
    public static String buildStoreSearchUrl(String storeRegion, String npGameId) {
        if (npGameId == null || npGameId.isBlank()) {
            return null;
        }
        return "https://store.playstation.com/" + storeSearchLocale(storeRegion)
            + "/search/" + npGameId.trim().substring(0, Math.min(9, npGameId.trim().length()));
    }
}
