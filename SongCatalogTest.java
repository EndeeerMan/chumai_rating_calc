import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free tests for the unified, persistent maimai catalogue. */
public final class SongCatalogTest {
    private static int tests;

    private static final String LOCAL_JSON = """
            [
              {"title":"Alpha Song","artist":"Artist","category":"舞萌",
               "image_file":"alpha.png","lev_bas":"4","version":"舞萌DX 2026"},
              {"title":"Help me, ERINNNNNN!!","artist":"Beat Mario",
               "category":"东方Project","image_file":"erin.png",
               "lev_bas":"5","version":"GreeN"}
            ]
            """;

    private static final String SNAPSHOT_JSON = """
            [
              {"id":"10","title":"Alpha Song","type":"SD",
               "ds":[4.0,7.0,10.0,13.0],"level":["4","7","10","13"],
               "charts":[
                 {"notes":[10,2,3,1],"charter":"A"},
                 {"notes":[20,3,4,2],"charter":"B"},
                 {"notes":[30,4,5,3],"charter":"C"},
                 {"notes":[40,5,6,4],"charter":"D"}],
               "basic_info":{"artist":"Artist","genre":"舞萌","bpm":150,
                 "from":"maimai","is_new":false}},
              {"id":"10010","title":"Alpha Song","type":"DX",
               "ds":[5.0,8.0,11.0,14.0],"level":["5","8","11","14"],
               "charts":[
                 {"notes":[11,2,3,4,1],"charter":"DX-A"},
                 {"notes":[21,3,4,5,2],"charter":"DX-B"},
                 {"notes":[31,4,5,6,3],"charter":"DX-C"},
                 {"notes":[41,5,6,7,4],"charter":"DX-D"}],
               "basic_info":{"artist":"Artist","genre":"舞萌","bpm":150,
                 "from":"舞萌DX 2026","is_new":true}},
              {"id":"100700","title":"[宴]Ignored","type":"DX",
               "ds":[14.0],"level":["14?"],
               "basic_info":{"artist":"Nobody","genre":"宴会場"}}
            ]
            """;

    private static final String ONLINE_DIVING_FISH = """
            [
              {"id":10010,"title":"Alpha Song","type":"DX",
               "ds":[5.0,8.0,11.0,14.0],"level":["5","8","11","14"],
               "charts":[
                 {"notes":[11,2,3,4,1],"charter":"DX-A"},
                 {"notes":[21,3,4,5,2],"charter":"DX-B"},
                 {"notes":[31,4,5,6,3],"charter":"DX-C"},
                 {"notes":[41,5,6,7,4],"charter":"DX-D"}],
               "basic_info":{"artist":"Artist","genre":"舞萌","bpm":150,
                 "from":"舞萌DX 2026","is_new":true}},
              {"id":11848,"title":"Overjoy ★ OVERDOSE!!","type":"DX",
               "ds":[5.0,8.0,11.0,14.0],"level":["5","8","11","14"],
               "charts":[
                 {"notes":[10,2,3,4,1],"charter":""},
                 {"notes":[20,3,4,5,2],"charter":""},
                 {"notes":[30,4,5,6,3],"charter":""},
                 {"notes":[40,5,6,7,4],"charter":"OD"}],
               "basic_info":{"artist":"Luna Fozer","genre":"其他游戏",
                 "bpm":200,"from":"舞萌DX 2026","is_new":true}},
              {"id":11849,"title":"DATAERR0R","type":"DX",
               "ds":[5.0,8.0,11.7,14.0],"level":["5","8","11+","14"],
               "charts":[
                 {"notes":[10,2,3,4,1],"charter":""},
                 {"notes":[20,3,4,5,2],"charter":""},
                 {"notes":[30,4,5,6,3],"charter":""},
                 {"notes":[40,5,6,7,4],"charter":"Luxizhel"}],
               "basic_info":{"artist":"Cosmograph","genre":"其他游戏",
                 "bpm":180,"from":"舞萌DX 2026","is_new":true}},
              {"id":11853,"title":"Help me, ERINNNNNN!!","type":"DX",
               "ds":[4.0,7.8,10.7,13.3],"level":["4","7+","10+","13"],
               "charts":[
                 {"notes":[10,2,3,4,1],"charter":""},
                 {"notes":[20,3,4,5,2],"charter":""},
                 {"notes":[30,4,5,6,3],"charter":""},
                 {"notes":[40,5,6,7,4],"charter":"Luxizhel"}],
               "basic_info":{"artist":"Beat Mario","genre":"东方Project",
                 "bpm":185,"from":"舞萌DX 2026","is_new":true}}
            ]
            """;

    private static final String LXNS_SONG_JSON = """
            {"songs":[
              {"id":10,"title":"Alpha Song","artist":"Artist","genre":"舞萌",
               "bpm":150,"version":25500,"difficulties":{"standard":[],"dx":[
                 {"type":"dx","difficulty":0,"level":"5","level_value":5.0,
                  "note_designer":"","version":25501},
                 {"type":"dx","difficulty":1,"level":"8","level_value":8.0,
                  "note_designer":"","version":25501},
                 {"type":"dx","difficulty":2,"level":"11","level_value":11.0,
                  "note_designer":"","version":25501},
                 {"type":"dx","difficulty":3,"level":"14","level_value":14.0,
                  "note_designer":"LXNS","version":25501}]}},
              {"id":1848,"title":"Overjoy ★ OVERDOSE!!","artist":"Luna Fozer",
               "genre":"其他游戏","bpm":200,"version":25501,
               "difficulties":{"standard":[],"dx":[
                 {"type":"dx","difficulty":0,"level":"5","level_value":5.0,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":1,"level":"8","level_value":8.0,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":2,"level":"11","level_value":11.0,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":3,"level":"14","level_value":14.0,"note_designer":"OD","version":25501}]}},
              {"id":1849,"title":"DATAERR0R","artist":"Cosmograph",
               "genre":"其他游戏","bpm":180,"version":25501,
               "difficulties":{"standard":[],"dx":[
                 {"type":"dx","difficulty":0,"level":"5","level_value":5.0,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":1,"level":"8","level_value":8.0,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":2,"level":"11+","level_value":11.7,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":3,"level":"14","level_value":14.0,"note_designer":"Luxizhel","version":25501}]}},
              {"id":1853,"title":"Help me, ERINNNNNN!!","artist":"Beat Mario",
               "genre":"东方Project","bpm":185,"version":25501,
               "difficulties":{"standard":[],"dx":[
                 {"type":"dx","difficulty":0,"level":"4","level_value":4.0,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":1,"level":"7+","level_value":7.8,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":2,"level":"10+","level_value":10.7,"note_designer":"","version":25501},
                 {"type":"dx","difficulty":3,"level":"13","level_value":13.3,"note_designer":"Luxizhel","version":25501}]}},
              {"id":100010,"title":"[宴]Alpha Song","artist":"Nobody",
               "genre":"宴会場","bpm":150,"version":25501,
               "difficulties":{"standard":[],"dx":[]}}
            ],"genres":[],"versions":[
              {"id":23,"title":"舞萌DX 2025","version":25000},
              {"id":24,"title":"舞萌DX 2026","version":25500}]}
            """;

    private static final String LXNS_ALIAS_JSON = """
            {"aliases":[
              {"song_id":10,"aliases":["alpha"]},
              {"song_id":1848,"aliases":["Overjoy OVERDOSE"]},
              {"song_id":1849,"aliases":["data error"]},
              {"song_id":1853,"aliases":["Help me ERINNNNNN"]},
              {"song_id":100010,"aliases":["must not attach to Alpha"]}
            ]}
            """;

    private SongCatalogTest() {
    }

    public static void main(String[] args) throws Exception {
        testBaseSongIdAndChartMerge();
        testMultiSourceRefreshAndProblemTitles();
        testFailureAndAtomicWriteFallback();
        testRefreshTtl();
        testHttpDownloadBoundary();
        testLocalCoverValidation();
        testProductionCatalogue();
        System.out.println("SongCatalogTest: all " + tests + " tests passed.");
    }

    private static void testBaseSongIdAndChartMerge() {
        SongCatalog catalog = fixture(endpoint -> {
            throw new AssertionError("offline catalogue must not access network");
        }, null, (path, bytes) -> { }, new AtomicLong(1));
        List<SongCatalog.Song> songs = catalog.catalog().songs();
        expect(1, songs.size(), "STD and DX offset IDs merge into one song");
        SongCatalog.Song song = songs.getFirst();
        expect("10", song.songId(), "base SongID is public identity");
        expect("MIXED", song.chartType(), "merged song exposes both chart types");
        expect(8, song.charts().size(), "all STD and DX charts survive merge");
        expect("10", SongCatalog.canonicalSongId("00010"),
                "leading zeroes are removed");
        expect("10", SongCatalog.canonicalSongId("10010"),
                "Diving-Fish DX offset is removed");
        expectThrows(() -> SongCatalog.canonicalSongId("local-abcd"),
                "local pseudo IDs are rejected");
        expect("10", catalog.resolveSong("10010", "wrong title")
                .orElseThrow().songId(), "known ID wins over title");
        SongCatalog.Chart std = catalog.findChart("10", "standard", "expert")
                .orElseThrow();
        expect(42, std.total(), "STD note total is calculated");
        expect(0, std.touch(), "STD touch count is explicitly zero");
        SongCatalog.Chart dx = catalog.findChart("10010", "dx", "master")
                .orElseThrow();
        expect(63, dx.total(), "DX note total includes touch notes");
    }

    private static void testMultiSourceRefreshAndProblemTitles() throws IOException {
        Path directory = Files.createTempDirectory("maimai-catalog-test-");
        try {
            Path cache = directory.resolve("maimai-catalog.json");
            AtomicInteger calls = new AtomicInteger();
            AtomicLong nanos = new AtomicLong(100);
            SongCatalog catalog = fixture(
                    endpoint -> {
                        calls.incrementAndGet();
                        return source(endpoint).getBytes(StandardCharsets.UTF_8);
                    },
                    cache,
                    SongCatalog::writeAtomically,
                    nanos);
            SongCatalog.RefreshResult refresh = catalog.refreshNow();
            expect(true, refresh.success(), "three-source refresh succeeds");
            expect(3, calls.get(), "every fixed metadata source is fetched once");
            expect(true, Files.isRegularFile(cache), "validated cache is persisted");
            Map<?, ?> cacheRoot = (Map<?, ?>) Json.parse(Files.readString(cache));
            expect("1", cacheRoot.get("schemaVersion").toString(),
                    "cache schema is versioned");
            expect(4, ((List<?>) cacheRoot.get("songs")).size(),
                    "cache contains unique base SongIDs");
            Files.writeString(directory.resolve("maidata.json"), LOCAL_JSON);
            Files.writeString(
                    directory.resolve("diving-fish-music-data.json"), SNAPSHOT_JSON);
            SongCatalog cached = SongCatalog.load(directory, cache);
            expect("/api/maimai/covers/1853.png",
                    cached.findBySongId("1853").orElseThrow().coverUrl(),
                    "cached LXNS rows migrate away from stale title-only covers");
            SongCatalog.Song alpha = catalog.findBySongId("10").orElseThrow();
            expect(List.of("alpha"), alpha.aliases(),
                    "UTAGE alias is excluded before offset canonicalization");

            expect("1848", catalog.resolveSong(null, "Overjoy OVERDOSE")
                    .orElseThrow().songId(),
                    "symbol-insensitive title resolves Overjoy");
            expect("1849", catalog.resolveSong(null, "DATAERR0R")
                    .orElseThrow().songId(), "DATAERR0R resolves from catalogue");
            expect("1853", catalog.resolveSong(null, "Help me ERINNNNNN")
                    .orElseThrow().songId(), "ERIN title alias resolves");
            expect("/api/maimai/covers/1853.png",
                    catalog.findBySongId("1853").orElseThrow().coverUrl(),
                    "SongID-addressed LXNS cover wins over an ambiguous local title cover");
            SongCatalog.Chart master = catalog.findChart("11849", "DX", "MASTER")
                    .orElseThrow();
            expect("current", master.version(),
                    "LXNS chart version maps directly to current pool");
            expect("舞萌DX 2026", master.releaseVersion(),
                    "human release name is retained separately");
            expect(62, master.total(), "Diving-Fish note counts survive LXNS merge");
            expect("Luxizhel", master.charter(), "chart designer is retained");
            expect(true, catalog.catalog().toJson().contains("\"aliases\""),
                    "catalogue JSON exposes aliases");
        } finally {
            deleteTree(directory);
        }
    }

    private static void testFailureAndAtomicWriteFallback() throws IOException {
        Path directory = Files.createTempDirectory("maimai-catalog-fallback-");
        try {
            Path cache = directory.resolve("maimai-catalog.json");
            byte[] sentinel = "last-known-good".getBytes(StandardCharsets.UTF_8);
            Files.write(cache, sentinel);
            AtomicInteger writes = new AtomicInteger();
            SongCatalog catalog = fixture(
                    endpoint -> source(endpoint).getBytes(StandardCharsets.UTF_8),
                    cache,
                    (path, bytes) -> {
                        writes.incrementAndGet();
                        throw new IOException("simulated disk failure");
                    },
                    new AtomicLong(10));
            List<SongCatalog.Song> before = catalog.catalog().songs();
            SongCatalog.RefreshResult failed = catalog.refreshNow();
            expect(false, failed.success(), "cache write failure fails publication");
            expect(1, writes.get(), "one atomic publication was attempted");
            expect(before, catalog.catalog().songs(),
                    "in-memory last-known-good survives write failure");
            expect(true, java.util.Arrays.equals(sentinel, Files.readAllBytes(cache)),
                    "on-disk last-known-good remains byte-for-byte unchanged");
            expect("failed", catalog.syncStatus().state(),
                    "failure is visible in sync status");

            AtomicInteger calls = new AtomicInteger();
            SongCatalog networkFailure = fixture(endpoint -> {
                calls.incrementAndGet();
                throw new IOException("offline");
            }, null, (path, bytes) -> { }, new AtomicLong(20));
            failed = networkFailure.refreshNow();
            expect(false, failed.success(), "network failure is reported");
            expect(1, calls.get(), "refresh stops after first failed source");
            expect(1, networkFailure.catalog().songs().size(),
                    "bundled snapshot remains available");
            expect(true, networkFailure.catalog().warning().contains("offline"),
                    "fallback warning explains the failure");
        } finally {
            deleteTree(directory);
        }
    }

    private static void testRefreshTtl() {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong(100);
        SongCatalog catalog = fixture(endpoint -> {
            calls.incrementAndGet();
            return source(endpoint).getBytes(StandardCharsets.UTF_8);
        }, null, (path, bytes) -> { }, clock);
        expect("online", catalog.search("DATAERR0R", 10, true).source(),
                "online search publishes refreshed catalogue");
        expect(3, calls.get(), "first online search fetches all sources");
        catalog.search("DATAERR0R", 10, true);
        expect(3, calls.get(), "second search inside TTL performs no I/O");
        clock.addAndGet(Duration.ofMinutes(31).toNanos());
        catalog.search("DATAERR0R", 10, true);
        expect(6, calls.get(), "search refreshes again after TTL");
    }

    private static void testHttpDownloadBoundary() throws IOException {
        HttpRequest request = SongCatalog.buildRemoteRequest(
                SongCatalog.DIVING_FISH_ENDPOINT);
        expect(Duration.ofSeconds(30), request.timeout().orElse(null),
                "remote request bounds complete body delivery");
        expect("https", request.uri().getScheme(), "remote source uses HTTPS");
        expectThrowsIo(() -> SongCatalog.buildRemoteRequest(
                URI.create("https://example.com/music_data")),
                "untrusted HTTPS source is rejected");
        byte[] maximum = feedLimitedBody(SongCatalog.MAX_REMOTE_BYTES - 1, 1);
        expect(SongCatalog.MAX_REMOTE_BYTES, maximum.length,
                "exactly 2 MiB is accepted");
        tests++;
        try {
            feedLimitedBody(SongCatalog.MAX_REMOTE_BYTES, 1);
            throw new AssertionError("oversized response must fail");
        } catch (CompletionException | IllegalArgumentException expected) {
            // Expected from the limiting subscriber.
        }
    }

    private static void testLocalCoverValidation() throws IOException {
        Path directory = Files.createTempDirectory("maimai-local-cover-");
        try {
            Files.writeString(directory.resolve("maidata.json"), LOCAL_JSON);
            Files.writeString(
                    directory.resolve("diving-fish-music-data.json"), SNAPSHOT_JSON);
            Path covers = Files.createDirectories(directory.resolve("cover"));
            Path cache = directory.resolve("catalog-cache.json");

            SongCatalog missing = SongCatalog.load(directory, cache);
            expect("/api/maimai/covers/10.png",
                    missing.catalog().songs().getFirst().coverUrl(),
                    "missing bundled PNG falls back to the same-origin cache endpoint");

            Files.writeString(covers.resolve("alpha.png"), "not a png");
            SongCatalog invalid = SongCatalog.load(directory, cache);
            expect("/api/maimai/covers/10.png",
                    invalid.catalog().songs().getFirst().coverUrl(),
                    "invalid bundled PNG falls back to the same-origin cache endpoint");

            Files.write(covers.resolve("alpha.png"), new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            });
            SongCatalog valid = SongCatalog.load(directory, cache);
            expect("/song-catalog/cover/alpha.png",
                    valid.catalog().songs().getFirst().coverUrl(),
                    "valid bundled PNG remains a static same-origin asset");
        } finally {
            deleteTree(directory);
        }
    }

    private static void testProductionCatalogue() throws IOException {
        SongCatalog catalog = SongCatalog.load(
                Path.of("web", "song-catalog"),
                Path.of("missing-test-cache", "maimai-catalog.json"));
        List<SongCatalog.Song> songs = catalog.catalog().songs();
        expect(true, songs.size() >= 1_200,
                "bundled snapshot contains a substantial official catalogue");
        Set<String> ids = new HashSet<>();
        expect(true, songs.stream().allMatch(song -> ids.add(song.songId())),
                "production catalogue has one row per base SongID");
        expect(true, songs.stream().allMatch(song -> song.songId().matches("[0-9]+")),
                "production catalogue contains no local pseudo IDs");
        expect(true, songs.stream().allMatch(song -> !song.charts().isEmpty()),
                "every production song has selectable charts");
    }

    private static String source(URI endpoint) {
        if (endpoint.equals(SongCatalog.DIVING_FISH_ENDPOINT)) {
            return ONLINE_DIVING_FISH;
        }
        if (endpoint.equals(SongCatalog.LXNS_SONG_ENDPOINT)) {
            return LXNS_SONG_JSON;
        }
        if (endpoint.equals(SongCatalog.LXNS_ALIAS_ENDPOINT)) {
            return LXNS_ALIAS_JSON;
        }
        throw new AssertionError("unexpected endpoint: " + endpoint);
    }

    private static SongCatalog fixture(
            SongCatalog.RemoteFetcher fetcher,
            Path cache,
            SongCatalog.CacheWriter writer,
            AtomicLong nanoClock) {
        return SongCatalog.fromJson(
                LOCAL_JSON,
                SNAPSHOT_JSON,
                fetcher,
                cache,
                writer,
                Duration.ofMinutes(30),
                nanoClock::get,
                Clock.fixed(Instant.parse("2026-07-16T04:00:00Z"), ZoneOffset.UTC));
    }

    private static byte[] feedLimitedBody(int... chunkSizes) {
        HttpResponse.ResponseInfo responseInfo = new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (name, value) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
        HttpResponse.BodySubscriber<byte[]> subscriber =
                SongCatalog.limitedRemoteBodyHandler().apply(responseInfo);
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
            }

            @Override
            public void cancel() {
            }
        });
        for (int size : chunkSizes) {
            subscriber.onNext(List.of(ByteBuffer.allocate(size)));
        }
        subscriber.onComplete();
        return subscriber.getBody().toCompletableFuture().join();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void expectThrows(Runnable action, String label) {
        tests++;
        try {
            action.run();
            throw new AssertionError(label + ": expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectThrowsIo(IoAction action, String label) {
        tests++;
        try {
            action.run();
            throw new AssertionError(label + ": expected IOException");
        } catch (IOException expected) {
            // Expected.
        }
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
