package tenacity.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds staff members in the player list by rank words in their tab name / team prefix. */
public final class StaffScan {
    /** {@code dev} is true for developers; everybody else with a staff rank is plain staff. */
    public record Entry(String name, String rank, boolean dev, int seniority) {
        public String tag() {
            return dev ? "Dev" : "Staff";
        }
    }

    private static final StaffMatcher MATCHER = new StaffMatcher(StaffMatcher.MODE_RANKS, StaffMatcher.DEFAULT_RANK_WORDS,
            StaffMatcher.DEFAULT_MARKERS, false, StaffMatcher.DEFAULT_TEAM_RANK_MAX, "", "");

    private static volatile List<Entry> cached = List.of();
    private static int clock;

    private StaffScan() {
    }

    public static List<Entry> current() {
        return cached;
    }

    /** Call every client tick; rescans twice a second. */
    public static void tick(MinecraftClient mc) {
        if (clock++ % 10 != 0) return;
        if (mc.getNetworkHandler() == null || mc.player == null) {
            cached = List.of();
            return;
        }
        List<Entry> out = new ArrayList<>();
        for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
            String name = e.getProfile().name();
            if (name == null || name.isEmpty()) continue;
            Team team = e.getScoreboardTeam();
            String teamName = team == null ? "" : team.getName();
            String prefix = team == null ? "" : team.getPrefix().getString() + " " + team.getSuffix().getString();
            Text display = e.getDisplayName();
            StaffMatcher.Match m = MATCHER.match(name, teamName, display == null ? "" : display.getString(), prefix);
            if (m == null) continue;
            boolean dev = m.rank().equalsIgnoreCase("Dev") || m.rank().equalsIgnoreCase("Developer");
            out.add(new Entry(name, m.rank(), dev, m.seniority()));
        }
        out.sort(Comparator.comparing((Entry x) -> !x.dev()).thenComparingInt(Entry::seniority).thenComparing(Entry::name));
        cached = List.copyOf(out);
    }
}
