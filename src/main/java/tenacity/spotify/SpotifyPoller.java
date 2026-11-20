package tenacity.spotify;

import java.util.Locale;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class SpotifyPoller {

    private static volatile SpotifyPoller instance;

    private final Object publishLock = new Object();
    private final LyricsService lyricsService = new LyricsService();
    private volatile NowPlaying nowPlaying = NowPlaying.WAITING;
    private volatile TrackLyrics published;
    private LyricsQuery lyricsCandidate;
    private int lyricsCandidatePolls;
    private LyricsQuery lyricsQuery;
    private MediaSession media;
    private boolean mediaFailed;
    private SpotifyWindow window;
    private boolean windowFailed;
    private long nextWindowReadAt;
    private AlbumArt art;
    private int artVersion;
    private boolean artDecoded;
    private int sessionFailures;
    private ScheduledThreadPoolExecutor executor;

    public static SpotifyPoller get() {
        SpotifyPoller poller = instance;
        if (poller == null) {
            synchronized (SpotifyPoller.class) {
                poller = instance;
                if (poller == null) {
                    poller = new SpotifyPoller();
                    instance = poller;
                }
            }
        }
        poller.ensureStarted();
        return poller;
    }

    private SpotifyPoller() {
    }

    public NowPlaying nowPlaying() {
        return nowPlaying;
    }

    public enum Control { PREVIOUS, PLAY_PAUSE, NEXT }

    /**
     * Transport control. Prefers the Spotify SMTC session (TrySkipPrevious / TryTogglePlayPause / TrySkipNext, which
     * targets Spotify specifically); falls back to media virtual keys via SendInput. Runs on the poller thread (the WinRT
     * objects live there). Play/pause is applied optimistically to the published state.
     */
    public void control(Control c) {
        if (c == Control.PLAY_PAUSE) {
            synchronized (publishLock) {
                nowPlaying = nowPlaying.toggled();
            }
        }
        ScheduledThreadPoolExecutor ex = executor;
        if (ex == null) return;
        ex.execute(() -> {
            boolean ok = false;
            try {
                if (!mediaFailed && media != null && !media.isUnsupported()) {
                    ok = switch (c) {
                        case PREVIOUS -> media.skipPrevious();
                        case PLAY_PAUSE -> media.togglePlayPause();
                        case NEXT -> media.skipNext();
                    };
                }
            } catch (LinkageError | RuntimeException ignored) {
            }
            if (!ok) {
                try {
                    SpotifyWindow.tap(switch (c) {
                        case PREVIOUS -> SpotifyWindow.MediaKey.PREVIOUS;
                        case PLAY_PAUSE -> SpotifyWindow.MediaKey.PLAY_PAUSE;
                        case NEXT -> SpotifyWindow.MediaKey.NEXT;
                    });
                } catch (LinkageError | RuntimeException ignored) {
                }
            }
        });
    }

    private synchronized void ensureStarted() {
        if (executor != null) {
            return;
        }
        if (!isWindows()) {
            nowPlaying = NowPlaying.UNSUPPORTED;
            return;
        }
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "Tenacity-Spotify");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.scheduleWithFixedDelay(this::poll, 0L, 500L, TimeUnit.MILLISECONDS);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private void poll() {
        try {
            NowPlaying next = readSession();
            if (next == null) {
                if (sessionFailures > 0 && sessionFailures <= 2 && nowPlaying.source() == NowPlaying.Source.SESSION) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (nowPlaying.source() != NowPlaying.Source.SESSION && now < nextWindowReadAt) {
                    return;
                }
                nextWindowReadAt = now + 1000L;
                next = readWindow();
            }
            if (next != null) {
                published = trackLyricsFor(next);
                synchronized (publishLock) {
                    nowPlaying = next;
                }
            }
        } catch (LinkageError | RuntimeException ignored) {
        }
    }

    public Lyrics lyricsFor(NowPlaying now) {
        TrackLyrics current = published;
        if (current == null || now == null || !now.lyricsEligible()) {
            return null;
        }
        if (!current.title().equals(now.title()) || !current.artist().equals(now.artist())) {
            return null;
        }
        try {
            return lyricsService.lookup(current.query());
        } catch (LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    private TrackLyrics trackLyricsFor(NowPlaying now) {
        if (now == null || !now.lyricsEligible()) {
            lyricsCandidate = null;
            lyricsCandidatePolls = 0;
            lyricsQuery = null;
            published = null;
            return null;
        }
        LyricsQuery query = new LyricsQuery(now.title(), now.artist(), now.album(), 0).withDurationMillis(now.durationMs());
        if (query.equals(lyricsCandidate)) {
            lyricsCandidatePolls++;
        } else {
            lyricsCandidate = query;
            lyricsCandidatePolls = 1;
        }
        if (lyricsCandidatePolls >= 2) {
            lyricsQuery = lyricsCandidate;
        }
        if (lyricsQuery == null || !lyricsQuery.title().equals(now.title()) || !lyricsQuery.artist().equals(now.artist())) {
            return published;
        }
        if (published == null || !published.query().equals(lyricsQuery) || !published.title().equals(now.title()) || !published.artist().equals(now.artist())) {
            published = new TrackLyrics(now.title(), now.artist(), lyricsQuery);
        }
        return published;
    }

    private record TrackLyrics(String title, String artist, LyricsQuery query) {
    }

    private NowPlaying readSession() {
        if (mediaFailed) {
            return null;
        }
        if (media == null) {
            try {
                media = MediaSession.spotify();
            } catch (LinkageError failure) {
                mediaFailed = true;
                return null;
            }
        }
        if (media.isUnsupported()) {
            return null;
        }
        MediaSession.Snapshot snapshot = media.poll();
        if (snapshot == null) {
            String error = media.lastError();
            sessionFailures = error == null || media.isUnsupported() ? 0 : sessionFailures + 1;
            return null;
        }
        sessionFailures = 0;
        return NowPlaying.fromSession(snapshot, artFor(snapshot), nowPlaying);
    }

    private AlbumArt artFor(MediaSession.Snapshot snapshot) {
        if (!artDecoded || snapshot.thumbnailVersion() != artVersion) {
            artDecoded = true;
            artVersion = snapshot.thumbnailVersion();
            art = null;
            if (snapshot.hasThumbnail()) {
                try {
                    art = AlbumArt.decode(snapshot.thumbnail(), 256);
                } catch (LinkageError | RuntimeException ignored) {
                }
            }
        }
        return art;
    }

    private NowPlaying readWindow() {
        if (!windowFailed && window == null) {
            try {
                window = new SpotifyWindow();
            } catch (LinkageError failure) {
                windowFailed = true;
            }
        }
        if (window != null) {
            try {
                return NowPlaying.fromWindowTitle(window.readTitle(), nowPlaying);
            } catch (LinkageError failure) {
                window = null;
                windowFailed = true;
            }
        }
        if (media != null && !mediaFailed && !media.isUnsupported()) {
            return NowPlaying.NOT_RUNNING;
        }
        return NowPlaying.UNSUPPORTED;
    }
}
