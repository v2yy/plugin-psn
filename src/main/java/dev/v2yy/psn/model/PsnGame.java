package dev.v2yy.psn.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "psn.v2yy.dev", version = "v1alpha1",
    kind = "PsnGame", singular = "psngame", plural = "psngames")
public class PsnGame extends AbstractExtension {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private PsnGameSpec spec;

    @Data
    public static class PsnGameSpec {
        /** 增量判定指纹（仅远端字段参与） */
        private String checksum;
        /** 归并键：小写名|平台组 */
        private String mergeKey;

        private String name;
        private String platform;
        private String platformGroup;
        private String coverUrl;
        private String npGameId;
        private String npCommunicationId;

        private String lastPlayed;
        private double playtimeHours;
        private int progress;
        private int progressEarned;
        private int progressTotal;
        private int platinum;
        private int gold;
        private int silver;
        private int bronze;
        private String trophyLastUpdated;

        /** 详情页增强缓存（按需从镜像拉，同步不覆盖、checksum 不含） */
        private String detailSnapshot;
        private String detailSnapshotAt;

        private boolean inTrophyList;
        private boolean inGameList;

        /** 本地私有：同步永不覆盖 */
        private Boolean pinned;
        private Boolean hidden;
        private Boolean archived;
        private String note;

        private String lastSyncAt;
    }
}
