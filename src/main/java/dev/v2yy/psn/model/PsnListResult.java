package dev.v2yy.psn.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
public class PsnListResult {
    private List<PsnGame> items;
    private int page;
    private int size;
    private long total;
    private String platform;
    private String sort;
    private String routePath = "/psn";
    private Summary summary;
    /** 同步状态视图（插件在组装列表时填入；模板页脚条据此显示源/时间/错误） */
    private PsnStatus status;
    /** 商店链接区域段（us/hk/jp...），模板拼 store 链接用 */
    private String storeRegion = "us";

    public int getTotalPages() {
        return size <= 0 ? 1 : (int) Math.ceil((double) total / size);
    }

    public boolean hasPrevious() {
        return page > 1;
    }

    public boolean hasNext() {
        return (long) page * size < total;
    }

    public String getPrevUrl() {
        return hasPrevious() ? buildUrl(page - 1) : null;
    }

    public String getNextUrl() {
        return hasNext() ? buildUrl(page + 1) : null;
    }

    private String buildUrl(int p) {
        StringBuilder sb = new StringBuilder(routePath);
        if (p > 1) {
            sb.append("/page/").append(p);
        }
        String sep = "?";
        if (platform != null && !platform.isEmpty()) {
            sb.append(sep).append("platform=").append(platform);
            sep = "&";
        }
        if (sort != null && !sort.isEmpty()) {
            sb.append(sep).append("sort=").append(sort);
        }
        return sb.toString();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Summary {
        private long totalGames;
        private double totalPlaytimeHours;
        private long platinum;
        private long gold;
        private long silver;
        private long bronze;
        private long avgProgress;
    }
}
