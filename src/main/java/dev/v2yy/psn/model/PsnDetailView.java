package dev.v2yy.psn.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 详情页视图：把「需要计算的东西」全部在 Java 侧算好，模板只做直读——
 * 避免 Thymeleaf 嵌套三元炸全站 500 的历史坑。
 * 数据源：spec 基础字段（永远有）+ detailSnapshot（按需从镜像拉的逐奖杯明细，可能缺）。
 */
public class PsnDetailView {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PT = Pattern.compile("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?");

    private final PsnGame game;
    private final String routePath;
    private final String storeRegion;
    private JsonNode trophyRoot;   // snapshot.trophy
    private JsonNode userdata;     // snapshot.trophy.userdata
    private List<GroupView> groups = List.of();
    private List<RarityBand> bands = List.of();
    private List<TrophyView> rarest = List.of();
    private List<DayView> playDays = List.of();
    private boolean hasDetail;
    private String firstTrophyText = "";
    private String platinumTrophyText = "";
    private String platinumDaysText = "";
    private String activeDaysText = "0";
    private boolean hasPlatinum;

    public PsnDetailView(PsnGame game, PsnConfig cfg) {
        this.game = game;
        this.routePath = cfg.getRoutePath() == null || cfg.getRoutePath().isBlank()
            ? "/psn" : cfg.getRoutePath();
        this.storeRegion = cfg.getStoreRegion();
        parseSnapshot();
    }

    // ---------- 基础 ----------

    public PsnGame getGame() {
        return game;
    }

    public String getPermalink() {
        return routePath + "/game/" + game.getMetadata().getName();
    }

    public String getRefreshUrl() {
        return getPermalink() + "?refresh=1";
    }

    public String getListUrl() {
        return routePath;
    }

    public String getStoreSearchUrl() {
        return PsnConfig.buildStoreSearchUrl(storeRegion, game.getSpec().getNpGameId());
    }

    public String getStoreRegionLabel() {
        return PsnConfig.storeRegionLabel(storeRegion);
    }

    public String getLastPlayedText() {
        String t = fmtTime(game.getSpec().getLastPlayed());
        if (t.equals("—") && userdata != null) {
            t = fmtTime(userdata.path("last_played_datetime").asText(""));
        }
        return t;
    }

    public String getFirstPlayedText() {
        String t = fmtTime(game.getSpec().getLastPlayed());
        if (userdata != null) {
            t = fmtTime(userdata.path("first_played_datetime").asText(t));
        }
        return t;
    }

    public String getTrophyUpdatedText() {
        return fmtTime(game.getSpec().getTrophyLastUpdated());
    }

    public String getLastSyncText() {
        return fmtTime(game.getSpec().getLastSyncAt());
    }

    public String getSnapshotTimeText() {
        return fmtZoned(game.getSpec().getDetailSnapshotAt());
    }

    /** Instant(带Z) 转北京时间显示；其余走 fmtTime */
    static String fmtZoned(String raw) {
        if (raw == null || raw.isBlank()) {
            return "—";
        }
        try {
            if (raw.endsWith("Z")) {
                return java.time.ZonedDateTime.ofInstant(Instant.parse(raw),
                    java.time.ZoneId.of("Asia/Shanghai"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            }
        } catch (Exception ignored) {
        }
        return fmtTime(raw);
    }

    public String getTrophySummaryText() {
        PsnGame.PsnGameSpec s = game.getSpec();
        if (s.getProgressTotal() <= 0) {
            return "无奖杯数据";
        }
        return s.getProgressEarned() + " / " + s.getProgressTotal()
            + "（" + s.getProgress() + "%）";
    }

    public double getTrophyRatio() {
        PsnGame.PsnGameSpec s = game.getSpec();
        if (s.getProgress() > 0) {
            return s.getProgress() / 100.0;
        }
        return s.getProgressTotal() > 0 ? (double) s.getProgressEarned() / s.getProgressTotal() : 0;
    }

    public String getPlaytimeText() {
        if (userdata != null) {
            String h = ptToText(userdata.path("play_duration").asText(""));
            if (!h.isEmpty()) {
                return h;
            }
        }
        double h = game.getSpec().getPlaytimeHours();
        if (h <= 0) {
            return "—";
        }
        if (h < 1) {
            return Math.round(h * 60) + " 分钟";
        }
        return String.format("%.1f 小时", h);
    }

    public String getPlayCountText() {
        long n = userdata == null ? -1 : userdata.path("play_count").asLong(-1);
        return n > 0 ? String.valueOf(n) : "";
    }

    // ---------- 增强（快照解析结果） ----------

    public boolean isHasDetail() {
        return hasDetail;
    }

    public List<GroupView> getGroups() {
        return groups;
    }

    public List<RarityBand> getBands() {
        return bands;
    }

    public List<TrophyView> getRarest() {
        return rarest;
    }

    public List<DayView> getPlayDays() {
        return playDays;
    }

    public boolean isHasPlatinum() {
        return hasPlatinum;
    }

    public String getFirstTrophyText() {
        return firstTrophyText;
    }

    public String getPlatinumTrophyText() {
        return platinumTrophyText;
    }

    public String getPlatinumDaysText() {
        return platinumDaysText;
    }

    public String getActiveDaysText() {
        return activeDaysText;
    }

    public String getGameVersionText() {
        String v = trophyRoot == null ? ""
            : trophyRoot.path("game").path("trophy_set_version").asText("");
        return v.isBlank() ? "" : v;
    }

    public String getRegionText() {
        return trophyRoot == null ? "" : trophyRoot.path("game").path("region").asText("");
    }

    // ---------- 内嵌视图 ----------

    public static class GroupView {
        public String name;
        public String iconUrl;
        public int earned;
        public int total;
        public int percent;
        public List<TrophyView> trophies = new ArrayList<>();
        public String getName() { return name; }
        public String getIconUrl() { return iconUrl; }
        public int getEarned() { return earned; }
        public int getTotal() { return total; }
        public int getPercent() { return percent; }
        public List<TrophyView> getTrophies() { return trophies; }
    }

    public static class TrophyView {
        public String name;
        public String detail;
        public String iconUrl;
        public String typeIcon;
        public String rateText;
        public Double rateNum;
        public boolean earned;
        public String earnedText;
        public String groupName;
        public String getName() { return name; }
        public String getDetail() { return detail; }
        public String getIconUrl() { return iconUrl; }
        public String getTypeIcon() { return typeIcon; }
        public String getRateText() { return rateText; }
        public boolean isEarned() { return earned; }
        public String getEarnedText() { return earnedText; }
        public String getGroupName() { return groupName; }
    }

    public static class RarityBand {
        public String label;
        public int earned;
        public int total;
        public int sharePercent;
        public String getLabel() { return label; }
        public int getEarned() { return earned; }
        public int getTotal() { return total; }
        public int getSharePercent() { return sharePercent; }
    }

    public static class DayView {
        public String date;
        public String hoursText;
        public int sessionCount;
        public String platformText;
        public String getDate() { return date; }
        public String getHoursText() { return hoursText; }
        public int getSessionCount() { return sessionCount; }
        public String getPlatformText() { return platformText; }
    }

    // ---------- 解析 ----------

    private void parseSnapshot() {
        String snap = game.getSpec().getDetailSnapshot();
        if (snap == null || snap.isBlank()) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(snap);
            trophyRoot = root.path("trophy");
            if (trophyRoot.isMissingNode() || !trophyRoot.has("trophyList")) {
                trophyRoot = null;
                return;
            }
            userdata = trophyRoot.path("userdata");
            hasDetail = true;

            List<TrophyView> allEarned = new ArrayList<>();
            List<TrophyView> allTrophies = new ArrayList<>();
            Map<Integer, int[]> bandMap = new LinkedHashMap<>();
            List<GroupView> gs = new ArrayList<>();
            for (JsonNode g : trophyRoot.path("trophyList")) {
                GroupView gv = new GroupView();
                gv.name = g.path("groupName").asText("");
                gv.iconUrl = g.path("groupIconUrl").asText("");
                List<TrophyView> list = new ArrayList<>();
                for (JsonNode t : g.path("earntrophyInGroup")) {
                    TrophyView tv = toTrophy(t, true, gv.name);
                    gv.earned++;
                    list.add(tv);
                    allEarned.add(tv);
                    allTrophies.add(tv);
                    accumulateBand(bandMap, t, true);
                }
                for (JsonNode t : g.path("notearntrophyInGroup")) {
                    TrophyView tv = toTrophy(t, false, gv.name);
                    list.add(tv);
                    allTrophies.add(tv);
                    accumulateBand(bandMap, t, false);
                }
                list.sort(Comparator.comparing((TrophyView tv) -> !tv.earned)
                    .thenComparing(tv -> tv.rateNum == null ? 999 : tv.rateNum));
                gv.total = gv.earned + g.path("notearntrophyInGroup").size();
                gv.percent = gv.total > 0 ? (int) Math.round(gv.earned * 100.0 / gv.total) : 0;
                gv.trophies = list;
                gs.add(gv);
            }
            // 主组在前：按奖杯总数降序（default 组一般最大）
            gs.sort(Comparator.comparing((GroupView x) -> x.total).reversed());
            groups = gs;

            int grandTotal = 0;
            for (int[] v : bandMap.values()) {
                grandTotal += v[1];
            }
            List<RarityBand> bs = new ArrayList<>();
            List<Integer> rareKeys = new ArrayList<>(bandMap.keySet());
            rareKeys.sort(Comparator.naturalOrder()); // 0=极为珍贵 在最稀有档
            for (Integer k : rareKeys) {
                RarityBand b = new RarityBand();
                b.label = bandLabel(k);
                b.earned = bandMap.get(k)[0];
                b.total = bandMap.get(k)[1];
                b.sharePercent = grandTotal > 0
                    ? (int) Math.round(bandMap.get(k)[1] * 100.0 / grandTotal) : 0;
                bs.add(b);
            }
            bands = bs;

            allEarned.sort(Comparator.comparing(tv -> nullSafe(tv.earnedText)));
            Set<String> days = new TreeSet<>();
            String firstDate = null;
            for (TrophyView tv : allEarned) {
                if (tv.earnedText != null && tv.earnedText.length() >= 10
                    && !tv.earnedText.startsWith("未")) {
                    if (firstDate == null) {
                        firstDate = tv.earnedText;
                    }
                    days.add(tv.earnedText.substring(0, 10));
                }
            }
            activeDaysText = String.valueOf(days.size());
            if (!allEarned.isEmpty()) {
                firstTrophyText = allEarned.get(0).name + " · " + allEarned.get(0).earnedText;
            }
            for (TrophyView tv : allTrophies) {
                if ("💎".equals(tv.typeIcon) && tv.earned) {
                    hasPlatinum = true;
                    platinumTrophyText = tv.name + " · " + tv.earnedText;
                    if (firstDate != null) {
                        try {
                            long d = ChronoUnit.DAYS.between(LocalDate.parse(firstDate.substring(0, 10)),
                                LocalDate.parse(tv.earnedText.substring(0, 10)));
                            platinumDaysText = String.valueOf(Math.max(d, 0));
                        } catch (Exception ignored) {
                            platinumDaysText = "";
                        }
                    }
                }
            }

            rarest = allTrophies.stream()
                .filter(tv -> tv.rateNum != null)
                .sorted(Comparator.comparing(tv -> tv.rateNum))
                .limit(3)
                .toList();

            JsonNode ptNode = root.path("playtime");
            List<DayView> ds = new ArrayList<>();
            if (ptNode.has("dailyData")) {
                for (JsonNode d : ptNode.path("dailyData")) {
                    DayView dv = new DayView();
                    dv.date = d.path("date").asText("");
                    dv.hoursText = msToText(d.path("playtimeMs").asLong(0));
                    dv.sessionCount = d.path("sessionCount").asInt(0);
                    StringBuilder pf = new StringBuilder();
                    for (JsonNode p : d.path("platforms")) {
                        if (pf.length() > 0) {
                            pf.append('/');
                        }
                        pf.append(p.asText(""));
                    }
                    dv.platformText = pf.toString();
                    ds.add(dv);
                }
                ds.sort(Comparator.comparing((DayView x) -> x.date).reversed());
            }
            playDays = ds;
        } catch (Exception e) {
            hasDetail = false;
            trophyRoot = null;
        }
    }

    private TrophyView toTrophy(JsonNode t, boolean earned, String groupName) {
        TrophyView tv = new TrophyView();
        tv.name = t.path("trophyName").asText("");
        tv.detail = t.path("trophyDetail").asText("");
        tv.iconUrl = t.path("trophyIconUrl").asText("");
        tv.typeIcon = switch (t.path("trophyType").asText("")) {
            case "platinum" -> "💎";
            case "gold" -> "🥇";
            case "silver" -> "🥈";
            default -> "🥉";
        };
        double rate = t.path("trophyEarnedRate").asDouble(-1);
        tv.rateNum = rate >= 0 ? rate : null;
        tv.rateText = rate >= 0 ? trimNum(rate) + "%" : "—";
        tv.earned = earned;
        tv.earnedText = earned ? fmtTime(t.path("earnedDateTime").asText("")) : "未获得";
        tv.groupName = groupName;
        return tv;
    }

    private void accumulateBand(Map<Integer, int[]> bandMap, JsonNode t, boolean earned) {
        int rare = t.path("trophyRare").asInt(3);
        if (rare < 0 || rare > 3) {
            rare = 3;
        }
        int[] v = bandMap.computeIfAbsent(rare, k -> new int[2]);
        v[1]++;
        if (earned) {
            v[0]++;
        }
    }

    public static String bandLabel(int rare) {
        return switch (rare) {
            case 0 -> "极为珍贵";
            case 1 -> "非常珍贵";
            case 2 -> "珍贵";
            default -> "普通";
        };
    }

    public static String ptToText(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        Matcher m = PT.matcher(iso);
        if (!m.matches()) {
            return "";
        }
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int mi = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 0) {
            return h + " 小时" + (mi > 0 ? " " + mi + " 分" : "");
        }
        if (mi > 0) {
            return mi + " 分钟";
        }
        return "不足 1 分钟";
    }

    static String msToText(long ms) {
        long min = Math.round(ms / 60000.0);
        if (min < 60) {
            return Math.max(min, 1) + " 分";
        }
        return (min / 60) + " 时 " + (min % 60) + " 分";
    }

    private static String trimNum(double d) {
        if (d == Math.floor(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(Math.round(d * 10) / 10.0);
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    static String fmtTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return "—";
        }
        String s = raw.trim().replace('T', ' ');
        if (s.length() >= 16 && s.charAt(4) == '-' && s.charAt(7) == '-' && s.charAt(10) == ' '
            && s.charAt(13) == ':') {
            return s.substring(0, 16);
        }
        if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') {
            return s.substring(0, 10);
        }
        return s;
    }
}
