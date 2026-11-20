package tenacity.spotify;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value = EnvType.CLIENT)
final class LyricsMatcher {
    static final double DURATION_TOLERANCE_SECONDS = 3.0;
    private static final int TITLE_EXACT = 40;
    private static final int TITLE_BASE = 30;
    private static final int TITLE_CONTAINS = 12;
    private static final int ARTIST_EXACT = 30;
    private static final int ARTIST_SHARED = 20;
    private static final int ARTIST_CONTAINS = 10;
    private static final int DURATION_MAX = 20;
    private static final int SYNCED = 25;
    private static final int INSTRUMENTAL = 8;
    private static final int PLAIN = 5;
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern APOSTROPHES = Pattern.compile("['\u2019`\u00b4]");
    private static final Pattern BRACKETED = Pattern.compile("\\s*[(\\[][^)\\]]*\\b(feat|ft|featuring|with|remaster(ed)?|explicit|clean|mono|stereo|bonus|deluxe|anniversary|edition|single version|album version)\\b[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern DASHED = Pattern.compile("\\s+[-\u2013\u2014]\\s+[^-\u2013\u2014]*\\b(remaster(ed)?|explicit|clean|mono|stereo|bonus track|deluxe|anniversary|edition|single version|album version)\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEATURING = Pattern.compile("\\s+(feat\\.?|ft\\.|featuring)\\s+.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_SPLIT = Pattern.compile("\\s*(?:,|;|/|&|\u2022|\\+|\\bx\\b|\\band\\b|\\bwith\\b|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bfeaturing\\b)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT_SPLIT = Pattern.compile("\\s*[,;\u2022]\\s*|\\s+(?:&|x|feat\\.?|ft\\.?|featuring|with)\\s+", Pattern.CASE_INSENSITIVE);

    private LyricsMatcher() {
    }

    static LrclibTrack best(List<LrclibTrack> candidates, LyricsQuery query) {
        if (candidates == null || candidates.isEmpty() || query == null) {
            return null;
        }
        LrclibTrack best = null;
        int bestScore = -1;
        double bestDrift = Double.MAX_VALUE;
        for (LrclibTrack candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            int score = score(candidate, query);
            if (score < 0) {
                continue;
            }
            double drift = drift(candidate, query);
            if (score > bestScore || (score == bestScore && drift < bestDrift)) {
                best = candidate;
                bestScore = score;
                bestDrift = drift;
            }
        }
        return best;
    }

    static int score(LrclibTrack candidate, LyricsQuery query) {
        if (!candidate.usable()) {
            return -1;
        }
        int duration = durationScore(candidate, query);
        if (duration < 0) {
            return -1;
        }
        int title = titleScore(query.title(), candidate.trackName());
        if (title <= 0) {
            return -1;
        }
        int artist = artistScore(query.artist(), candidate.artistName());
        if (artist <= 0) {
            return -1;
        }
        int content = candidate.hasSynced() ? SYNCED : (candidate.hasPlain() ? PLAIN : INSTRUMENTAL);
        return title + artist + duration + content;
    }

    private static int durationScore(LrclibTrack candidate, LyricsQuery query) {
        if (!query.hasDuration() || candidate.duration() <= 0.0) {
            return 0;
        }
        double drift = Math.abs(candidate.duration() - (double) query.durationSeconds());
        if (drift > DURATION_TOLERANCE_SECONDS) {
            return -1;
        }
        return (int) Math.round((double) DURATION_MAX * (1.0 - drift / 6.0));
    }

    private static double drift(LrclibTrack candidate, LyricsQuery query) {
        if (!query.hasDuration() || candidate.duration() <= 0.0) {
            return DURATION_TOLERANCE_SECONDS;
        }
        return Math.abs(candidate.duration() - (double) query.durationSeconds());
    }

    static int titleScore(String wanted, String offered) {
        String a = normalize(wanted);
        String b = normalize(offered);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return TITLE_EXACT;
        }
        String baseA = normalize(baseTitle(wanted));
        String baseB = normalize(baseTitle(offered));
        if (!baseA.isEmpty() && baseA.equals(baseB)) {
            return TITLE_BASE;
        }
        if (containsWords(baseA, baseB) || containsWords(baseB, baseA)) {
            return TITLE_CONTAINS;
        }
        return 0;
    }

    static int artistScore(String wanted, String offered) {
        String a = normalize(wanted);
        String b = normalize(offered);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return ARTIST_EXACT;
        }
        Set<String> wantedArtists = artists(wanted);
        Set<String> offeredArtists = artists(offered);
        for (String artist : wantedArtists) {
            if (offeredArtists.contains(artist)) {
                return ARTIST_SHARED;
            }
        }
        String first = wantedArtists.isEmpty() ? a : wantedArtists.iterator().next();
        if (containsWords(b, first) || containsWords(a, b)) {
            return ARTIST_CONTAINS;
        }
        return 0;
    }

    static String baseTitle(String title) {
        if (title == null) {
            return "";
        }
        String base = BRACKETED.matcher(title).replaceAll("");
        base = DASHED.matcher(base).replaceFirst("");
        base = FEATURING.matcher(base).replaceFirst("");
        base = base.strip();
        return base.isEmpty() ? title.strip() : base;
    }

    static String primaryArtist(String artist) {
        if (artist == null) {
            return "";
        }
        for (String part : CREDIT_SPLIT.split(artist.strip())) {
            if (!part.isBlank()) {
                return part.strip();
            }
        }
        return artist.strip();
    }

    static String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String folded = text.toLowerCase(Locale.ROOT).replace('\u0131', 'i').replace("\u00df", "ss").replace("\u00e6", "ae").replace("\u0153", "oe").replace('\u00f8', 'o').replace('\u0142', 'l').replace('\u0111', 'd').replace("&", " and ");
        folded = MARKS.matcher(Normalizer.normalize(folded, Normalizer.Form.NFD)).replaceAll("");
        folded = APOSTROPHES.matcher(folded).replaceAll("");
        return NON_WORD.matcher(folded).replaceAll(" ").strip();
    }

    private static Set<String> artists(String credit) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String part : ARTIST_SPLIT.split(credit)) {
            String name = normalize(part);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static boolean containsWords(String haystack, String needle) {
        if (needle.length() < 3 || haystack.length() < needle.length()) {
            return false;
        }
        return (" " + haystack + " ").contains(" " + needle + " ");
    }

    static List<String[]> searchTerms(LyricsQuery query) {
        ArrayList<String[]> terms = new ArrayList<>(2);
        terms.add(new String[]{query.title(), query.artist()});
        String title = baseTitle(query.title());
        String artist = primaryArtist(query.artist());
        if (!title.equals(query.title()) || !artist.equals(query.artist())) {
            terms.add(new String[]{title, artist});
        }
        return terms;
    }
}
