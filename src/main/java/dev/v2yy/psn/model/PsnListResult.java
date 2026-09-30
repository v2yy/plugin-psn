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
