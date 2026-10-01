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
    private int pageSize = 24;
    private String sortDefault = "lastPlayed";
}
