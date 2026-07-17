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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Dependency-free offline tests for {@link ChunithmCatalog}. */
public final class ChunithmCatalogTest {
    private static int tests;

    private static final String VERSION_JSON = """
            {"version":["CHUNITHM VERSE","CHUNITHM LUMINOUS PLUS"]}
            """;

    private static final String MUSIC_JSON = """
            [
              {
                "id":3,"title":"Ｂ．Ｂ．Ｋ．Ｋ．Ｂ．Ｋ．Ｋ．",
                "ds":[3.0,5.0,10.0,12.5],
                "level":["3","5","10","12+"],
                "cids":[1,2,3,4],
                "charts":[
                  {"combo":333,"charter":"Maker A"},
                  {"combo":541,"charter":"Maker B"},
                  {"combo":1051,"charter":"Maker C"},
                  {"combo":960,"charter":"Maker D"}
                ],
                "basic_info":{
                  "title":"Ｂ．Ｂ．Ｋ．Ｋ．Ｂ．Ｋ．Ｋ．","artist":"nora2r",
                  "genre":"其他游戏","bpm":170,"from":"CHUNITHM VERSE"
                }
              },
              {
                "id":"20","title":"Ultima Tune",
                "ds":[3.0,6.0,9.5,13.8,15.0],
                "level":["3","6","9+","13+","15"],
                "cids":[18,19,20,21,5540],
                "charts":[
                  {"combo":437,"charter":"A"},{"combo":741,"charter":"B"},
                  {"combo":1166,"charter":"C"},{"combo":1645,"charter":"D"},
                  {"combo":1700,"charter":"ULTIMA Maker"}
                ],
                "basic_info":{
                  "title":"Ultima Tune","artist":"Artist U","genre":"原创",
                  "bpm":180,"from":"CHUNITHM SUN"
                }
              },
              {
                "id":8000,"title":"[止]World End",
                "ds":[0,0,0,0,0,0],"level":["-","-","-","-","-","止"],
                "cids":[100,101,102,103,104,105],
                "charts":[
                  {"combo":0,"charter":"-"},{"combo":0,"charter":"-"},
                  {"combo":0,"charter":"-"},{"combo":0,"charter":"-"},
                  {"combo":0,"charter":"-"},{"combo":2525,"charter":"WE Maker"}
                ],
                "basic_info":{
                  "title":"[止]World End","artist":"Artist W","genre":"原创",
                  "bpm":210,"from":"CHUNITHM PLUS"
                }
              },
              {
                "id":8025,"title":"[嘘]April Tune",
                "ds":[0],"level":["嘘"],"cids":[6258],
                "charts":[{"combo":999,"charter":"April Maker"}],
                "basic_info":{
                  "title":"[嘘]April Tune","artist":"Artist A","genre":"彩绿",
                  "bpm":183,"from":"CHUNITHM LUMINOUS PLUS"
                }
              }
            ]
            """;

    private static final String LXNS_SONG_JSON = """
            {
              "songs":[
                {"id":33,"difficulties":[
                  {"difficulty":0,"version":23000},
                  {"difficulty":1,"version":23000},
                  {"difficulty":2,"version":23000},
                  {"difficulty":3,"version":23000}
                ]},
                {"id":20,"disabled":true,"difficulties":[
                  {"difficulty":0,"version":21000},
                  {"difficulty":1,"version":21000},
                  {"difficulty":2,"version":21000},
                  {"difficulty":3,"version":21000},
                  {"difficulty":4,"version":23000}
                ]},
                {"id":8000,"difficulties":[
                  {"difficulty":5,"version":10500,"origin_id":33}
                ]},
                {"id":8025,"difficulties":[
                  {"difficulty":5,"version":22500,"origin_id":20}
                ]}
              ],
              "genres":[],
              "versions":[
                {"id":1,"title":"CHUNITHM PLUS","version":10500},
                {"id":14,"title":"CHUNITHM SUN","version":21000},
                {"id":17,"title":"CHUNITHM LUMINOUS PLUS","version":22500},
                {"id":18,"title":"CHUNITHM VERSE","version":23000}
              ]
            }
            """;

    private static final String LXNS_ALIAS_JSON = """
            {"aliases":[
              {"song_id":33,"aliases":["bbkkbkk","BK"]},
              {"song_id":20,"aliases":["ultima alias"]}
            ]}
            """;

    private ChunithmCatalogTest() {
    }

    public static void main(String[] args) throws Exception {
        testParsingAndDifficultyMapping();
        testLegacyWorldsEndCoverFallback();
        testSearchAndSerialization();
        testOnlineRefreshTtlAndFallback();
        testStrictPersistentRefresh();
        testStrictValidation();
        testHttpDownloadBoundary();
        testProductionCatalogue();
        System.out.println(
                "ChunithmCatalogTest: all " + tests + " tests passed.");
    }

    private static void testParsingAndDifficultyMapping() {
        ChunithmCatalog catalog = fixture(endpoint -> {
            throw new AssertionError("offline catalogue must not access the network");
        }, new AtomicLong(1));
        ChunithmCatalog.CatalogResult result = catalog.catalog();
        expect("snapshot", result.source(), "snapshot source");
        expect(null, result.warning(), "healthy snapshot has no warning");
        expect(2, result.latestVersions().size(), "latest versions retained");
        expect(4, result.songs().size(), "all songs parsed");

        ChunithmCatalog.Song normal = result.songs().get(0);
        expect("3", normal.songId(), "numeric ID canonicalized to a string");
        expect(true, normal.isNew(), "latest-version song is new");
        expect("/api/chunithm/covers/3.png", normal.coverUrl(),
                "cover uses the local proxy");
        expect(4, normal.charts().size(), "normal song has four charts");
        expect("MASTER", normal.charts().get(3).difficulty(),
                "fourth difficulty is MASTER");
        expect("12.5", normal.charts().get(3).constant().toPlainString(),
                "constant is exact decimal data");
        expect("4", normal.charts().get(3).cid(), "chart ID is retained");
        expect("CHUNITHM VERSE", normal.charts().get(3).version(),
                "offline chart version falls back to the song version");

        ChunithmCatalog.Song ultima = result.songs().get(1);
        expect(false, ultima.isNew(), "older-version song is not new");
        expect("ULTIMA", ultima.charts().get(4).difficulty(),
                "fifth difficulty is ULTIMA");
        expect(4, ultima.charts().get(4).difficultyIndex(),
                "ULTIMA has stable index 4");

        ChunithmCatalog.Song sixEntryWorldEnd = result.songs().get(2);
        expect(1, sixEntryWorldEnd.charts().size(),
                "World's End placeholders are not selectable");
        expect("WORLD'S END", sixEntryWorldEnd.charts().getFirst().difficulty(),
                "sixth difficulty is World's End");
        expect(5, sixEntryWorldEnd.charts().getFirst().difficultyIndex(),
                "World's End has stable index 5");
        expect("105", sixEntryWorldEnd.charts().getFirst().cid(),
                "sixth chart ID is selected");
        expect("/song-catalog/cover-placeholder.svg",
                sixEntryWorldEnd.coverUrl(),
                "World's End without a unique standard title stays unresolved");

        ChunithmCatalog.Song oneEntryWorldEnd = result.songs().get(3);
        expect(5, oneEntryWorldEnd.charts().getFirst().difficultyIndex(),
                "one-entry World's End also maps to index 5");
        expect(true, oneEntryWorldEnd.isNew(),
                "either latest version marks a song as new");

        expect("https://assets2.lxns.net/chunithm/jacket/3.png",
                ChunithmCatalog.remoteCoverUriFor("3").toString(),
                "fixed upstream cover URI");
        expectThrows(() -> ChunithmCatalog.remoteCoverUriFor("../secret"),
                "cover proxy rejects path traversal");
    }

    private static void testLegacyWorldsEndCoverFallback() {
        String musicJson = "[" + String.join(",",
                normalCoverSongJson(10, "Ｐａｑｑｉｎ"),
                normalCoverSongJson(20, "Same"),
                normalCoverSongJson(21, "Ｓａｍｅ"),
                legacyWorldsEndSongJson(
                        8000, "[止]Paqqin", "-", "-", "-", "-", "-"),
                legacyWorldsEndSongJson(
                        8001, "[狂]Paqqin", "-", "-", "-", "-", "-"),
                legacyWorldsEndSongJson(
                        8002, "[蔵]Same", "-", "-", "-", "-", "-"),
                legacyWorldsEndSongJson(
                        8003, "[止]Missing", "-", "-", "-", "-", "-"),
                legacyWorldsEndSongJson(
                        8004, "[止][狂]Paqqin", "-", "-", "-", "-", "-"),
                legacyWorldsEndSongJson(
                        8005, "[狂暴]Paqqin", "-", "-", "-", "-", "-"),
                legacyWorldsEndSongJson(
                        8006, "[止]Paqqin", "1", "-", "-", "-", "-"),
                oneEntryWorldsEndSongJson(8007, "[嘘]Paqqin")) + "]";

        ChunithmCatalog offline = ChunithmCatalog.fromJson(
                musicJson,
                VERSION_JSON,
                endpoint -> {
                    throw new AssertionError(
                            "legacy fallback must not access the network");
                },
                Duration.ofNanos(100),
                () -> 1L);
        ChunithmCatalog.CatalogResult offlineResult = offline.catalog();
        expect("10", findSong(offlineResult, "8000").coverSongId(),
                "strict legacy row reuses the unique NFKC title cover");
        expect("10", findSong(offlineResult, "8001").coverSongId(),
                "multiple legacy rows may reuse one standard jacket");
        expect("", findSong(offlineResult, "8002").coverSongId(),
                "NFKC title ambiguity retains the placeholder");
        expect("", findSong(offlineResult, "8003").coverSongId(),
                "missing exact title retains the placeholder");
        expect("", findSong(offlineResult, "8004").coverSongId(),
                "multiple leading markers do not qualify for fallback");
        expect("", findSong(offlineResult, "8005").coverSongId(),
                "multi-code-point markers do not qualify for fallback");
        expect("", findSong(offlineResult, "8006").coverSongId(),
                "non-placeholder legacy levels do not qualify for fallback");
        expect("", findSong(offlineResult, "8007").coverSongId(),
                "one-entry World's End rows require LXNS origin_id");

        String lxnsSongJson = """
                {
                  "songs":[{"id":8000,"difficulties":[
                    {"difficulty":5,"version":23000,"origin_id":999}
                  ]}],
                  "genres":[],
                  "versions":[
                    {"id":18,"title":"CHUNITHM VERSE","version":23000}
                  ]
                }
                """;
        ChunithmCatalog enriched = ChunithmCatalog.fromJson(
                musicJson,
                VERSION_JSON,
                endpoint -> {
                    if (endpoint.equals(ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT)) {
                        return musicJson.getBytes(StandardCharsets.UTF_8);
                    }
                    if (endpoint.equals(ChunithmCatalog.DIVING_FISH_VERSION_ENDPOINT)) {
                        return VERSION_JSON.getBytes(StandardCharsets.UTF_8);
                    }
                    if (endpoint.equals(ChunithmCatalog.LXNS_SONG_ENDPOINT)) {
                        return lxnsSongJson.getBytes(StandardCharsets.UTF_8);
                    }
                    if (endpoint.equals(ChunithmCatalog.LXNS_ALIAS_ENDPOINT)) {
                        return "{\"aliases\":[]}".getBytes(StandardCharsets.UTF_8);
                    }
                    throw new AssertionError("unexpected endpoint: " + endpoint);
                },
                Duration.ofNanos(100),
                () -> 1L);
        ChunithmCatalog.CatalogResult enrichedResult = enriched.catalog(true);
        expect("999", findSong(enrichedResult, "8000").coverSongId(),
                "LXNS origin_id overrides the strict title fallback");
        expect("10", findSong(enrichedResult, "8001").coverSongId(),
                "strict fallback survives when LXNS has no metadata row");
    }

    private static void testSearchAndSerialization() {
        ChunithmCatalog catalog = fixture(endpoint -> new byte[0],
                new AtomicLong(1));
        ChunithmCatalog.CatalogResult result = catalog.search(
                "b.b.k.k.b.k.k.", 10, false);
        expect(1, result.songs().size(),
                "search applies NFKC normalization");
        result = catalog.search("ULTIMA Maker", 10, false);
        expect("20", result.songs().getFirst().songId(),
                "search includes chart designer");
        result = catalog.search("6258", 10, false);
        expect("8025", result.songs().getFirst().songId(),
                "exact chart ID is searchable");
        result = catalog.search("artist", 2, false);
        expect(2, result.songs().size(), "search limit is enforced");

        Object parsed = Json.parse(catalog.catalog().toJson());
        expect(true, parsed instanceof Map<?, ?>,
                "catalogue response is valid strict JSON");
        Map<?, ?> envelope = (Map<?, ?>) parsed;
        List<?> songs = (List<?>) envelope.get("songs");
        Map<?, ?> song = (Map<?, ?>) songs.getFirst();
        expect("3", song.get("songId"), "serialized main ID field");
        expect("3", song.get("id"), "serialized Diving-Fish ID alias");
        expect(List.of("1", "2", "3", "4"), song.get("cids"),
                "serialized cids preserve chart order");
        expect("3", song.get("coverSongId"),
                "serialized cover source ID is explicit");
        expect(false, song.get("disabled"),
                "missing LXNS disabled flag defaults to false");
        expect(List.of(), song.get("aliases"),
                "offline catalogue has an empty alias list");
        expectThrows(() -> catalog.search(" ", 10, false),
                "blank search rejected");
        expectThrows(() -> catalog.search("song", 101, false),
                "oversized result limit rejected");
    }

    private static void testOnlineRefreshTtlAndFallback() {
        AtomicLong clock = new AtomicLong(100);
        AtomicInteger calls = new AtomicInteger();
        String onlineMusic = MUSIC_JSON.replace(
                "\"id\":3", "\"id\":33");
        ChunithmCatalog catalog = fixture(endpoint -> {
            calls.incrementAndGet();
            expect("https", endpoint.getScheme(), "refresh endpoint uses HTTPS");
            if (endpoint.equals(ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT)) {
                return onlineMusic.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.DIVING_FISH_VERSION_ENDPOINT)) {
                return VERSION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.LXNS_SONG_ENDPOINT)) {
                return LXNS_SONG_JSON.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.LXNS_ALIAS_ENDPOINT)) {
                return LXNS_ALIAS_JSON.getBytes(StandardCharsets.UTF_8);
            }
            throw new AssertionError("unexpected endpoint: " + endpoint);
        }, clock);

        ChunithmCatalog.CatalogResult refreshed = catalog.search(
                "b.b.k", 10, true);
        expect("online", refreshed.source(), "successful refresh source");
        expect("33", refreshed.songs().getFirst().songId(),
                "online music data is used");
        expect(4, calls.get(), "core data and LXNS enrichment are fetched once");
        expect("33", catalog.search("bbkkbkk", 10, true)
                        .songs().getFirst().songId(),
                "LXNS aliases participate in online search");
        expect("20", catalog.search("ultima-alias", 10, true)
                        .songs().getFirst().songId(),
                "compact punctuation normalization applies to aliases");
        expect("20", catalog.search("ultima aliaz", 10, true)
                        .songs().getFirst().songId(),
                "conservative edit distance applies to aliases");
        ChunithmCatalog.CatalogResult current = catalog.currentCatalog();
        ChunithmCatalog.Song disabled = current.songs().stream()
                .filter(song -> song.songId().equals("20"))
                .findFirst().orElseThrow();
        expect(true, disabled.disabled(),
                "LXNS disabled metadata is retained");
        expect("CHUNITHM VERSE", disabled.charts().get(4).version(),
                "per-chart LXNS version overrides the song's first version");
        ChunithmCatalog.Song worldsEnd = current.songs().stream()
                .filter(song -> song.songId().equals("8000"))
                .findFirst().orElseThrow();
        expect("33", worldsEnd.coverSongId(),
                "World's End cover uses LXNS origin_id");
        expect("/api/chunithm/covers/33.png", worldsEnd.coverUrl(),
                "World's End cover stays behind the local cache proxy");
        catalog.catalog(true);
        expect(4, calls.get(), "refresh is cached inside TTL");
        clock.addAndGet(100);
        catalog.catalog(true);
        expect(8, calls.get(), "all resources refresh after TTL");

        AtomicInteger failures = new AtomicInteger();
        ChunithmCatalog failing = fixture(endpoint -> {
            failures.incrementAndGet();
            throw new IOException("offline");
        }, new AtomicLong(500));
        ChunithmCatalog.CatalogResult fallback = failing.catalog(true);
        expect("snapshot", fallback.source(),
                "failed refresh falls back to snapshot");
        expect(true, fallback.warning().contains("offline"),
                "fallback exposes a safe warning");
        expect("3", fallback.songs().getFirst().songId(),
                "snapshot survives failure");
        failing.catalog(true);
        expect(1, failures.get(), "failed refresh is also TTL limited");

        ChunithmCatalog invalidVersion = fixture(endpoint ->
                endpoint.equals(ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT)
                        ? MUSIC_JSON.getBytes(StandardCharsets.UTF_8)
                        : "{}".getBytes(StandardCharsets.UTF_8),
                new AtomicLong(900));
        fallback = invalidVersion.catalog(true);
        expect("snapshot", fallback.source(),
                "invalid latest-version response cannot mix datasets");

        ChunithmCatalog oversized = fixture(endpoint ->
                new byte[ChunithmCatalog.MAX_REMOTE_BYTES + 1],
                new AtomicLong(1_000));
        expect("snapshot", oversized.catalog(true).source(),
                "oversized response safely falls back");

        AtomicInteger enhancementCalls = new AtomicInteger();
        ChunithmCatalog enhancementFailure = fixture(endpoint -> {
            enhancementCalls.incrementAndGet();
            if (endpoint.equals(ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT)) {
                return MUSIC_JSON.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.DIVING_FISH_VERSION_ENDPOINT)) {
                return VERSION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            throw new IOException("LXNS offline");
        }, new AtomicLong(1_100));
        ChunithmCatalog.CatalogResult coreOnly = enhancementFailure.catalog(true);
        expect("online", coreOnly.source(),
                "LXNS failure does not discard a valid Diving-Fish refresh");
        expect(true, coreOnly.warning().contains("LXNS offline"),
                "independent enrichment failure is reported safely");
        expect(3, enhancementCalls.get(),
                "failed first LXNS request stops the optional enrichment");

        AtomicLong resilientClock = new AtomicLong(2_000);
        AtomicBoolean coreAvailable = new AtomicBoolean(true);
        ChunithmCatalog resilient = fixture(endpoint -> {
            if (!coreAvailable.get()
                    && endpoint.equals(ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT)) {
                throw new IOException("Diving-Fish transient failure");
            }
            if (endpoint.equals(ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT)) {
                return onlineMusic.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.DIVING_FISH_VERSION_ENDPOINT)) {
                return VERSION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.LXNS_SONG_ENDPOINT)) {
                return LXNS_SONG_JSON.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.LXNS_ALIAS_ENDPOINT)) {
                return LXNS_ALIAS_JSON.getBytes(StandardCharsets.UTF_8);
            }
            throw new AssertionError("unexpected endpoint: " + endpoint);
        }, resilientClock);
        expect("33", resilient.search("bbkkbkk", 10, true)
                        .songs().getFirst().songId(),
                "initial online enhancement is available");
        coreAvailable.set(false);
        resilientClock.addAndGet(100);
        ChunithmCatalog.CatalogResult retained = resilient.catalog(true);
        expect("online", retained.source(),
                "core refresh failure retains the last valid online dataset");
        expect("33", retained.songs().stream()
                        .filter(song -> song.aliases().contains("bbkkbkk"))
                        .findFirst().orElseThrow().songId(),
                "last valid aliases survive a transient core failure");
        expect(true, retained.warning().contains("transient failure"),
                "retained dataset reports the failed refresh");
    }

    private static void testStrictValidation() {
        expectThrows(() -> ChunithmCatalog.fromJson(
                MUSIC_JSON,
                "{\"version\":[]}",
                endpoint -> new byte[0],
                Duration.ofMinutes(1),
                System::nanoTime),
                "empty latest-version list rejected");
        expectThrows(() -> ChunithmCatalog.fromJson(
                MUSIC_JSON.replace(
                        "\"cids\":[1,2,3,4]", "\"cids\":[1,2,3]"),
                VERSION_JSON,
                endpoint -> new byte[0],
                Duration.ofMinutes(1),
                System::nanoTime),
                "mismatched chart arrays rejected");
        expectThrows(() -> ChunithmCatalog.fromJson(
                MUSIC_JSON.replaceFirst("\"id\":3", "\"id\":\"../3\""),
                VERSION_JSON,
                endpoint -> new byte[0],
                Duration.ofMinutes(1),
                System::nanoTime),
                "non-numeric song ID rejected");
        expectThrows(() -> ChunithmCatalog.fromJson(
                MUSIC_JSON,
                VERSION_JSON,
                endpoint -> new byte[0],
                Duration.ZERO,
                System::nanoTime),
                "non-positive TTL rejected");
        expectThrows(() -> ChunithmCatalog.parseLxnsEnhancements(
                        LXNS_SONG_JSON.replace("\"disabled\":true",
                                "\"disabled\":\"yes\""),
                        LXNS_ALIAS_JSON),
                "non-boolean disabled flag rejected");
        expectThrows(() -> ChunithmCatalog.parseLxnsEnhancements(
                        LXNS_SONG_JSON.replace(
                                "\"difficulty\":5,\"version\":10500,\"origin_id\":33",
                                "\"difficulty\":5,\"version\":10500"),
                        LXNS_ALIAS_JSON),
                "World's End without origin_id rejected");
        expectThrows(() -> ChunithmCatalog.parseLxnsEnhancements(
                        LXNS_SONG_JSON.replaceFirst(
                                "\"difficulty\":0,\"version\":23000",
                                "\"difficulty\":0,\"version\":99999"),
                        LXNS_ALIAS_JSON),
                "unknown chart version rejected");
    }

    private static void testStrictPersistentRefresh() throws Exception {
        String onlineMusic = MUSIC_JSON.replace("\"id\":3", "\"id\":33");
        ChunithmCatalog.RemoteFetcher completeFetcher = endpoint -> {
            if (endpoint.equals(ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT)) {
                return onlineMusic.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.DIVING_FISH_VERSION_ENDPOINT)) {
                return VERSION_JSON.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.LXNS_SONG_ENDPOINT)) {
                return LXNS_SONG_JSON.getBytes(StandardCharsets.UTF_8);
            }
            if (endpoint.equals(ChunithmCatalog.LXNS_ALIAS_ENDPOINT)) {
                return LXNS_ALIAS_JSON.getBytes(StandardCharsets.UTF_8);
            }
            throw new AssertionError("unexpected endpoint: " + endpoint);
        };

        Path directory = Files.createTempDirectory("chunithm-refresh-test-");
        try {
            Files.writeString(
                    directory.resolve(ChunithmCatalog.DIVING_FISH_MUSIC_FILE),
                    MUSIC_JSON,
                    StandardCharsets.UTF_8);
            Files.writeString(
                    directory.resolve(ChunithmCatalog.DIVING_FISH_VERSION_FILE),
                    VERSION_JSON,
                    StandardCharsets.UTF_8);
            ChunithmCatalog catalog = ChunithmCatalog.fromJson(
                    MUSIC_JSON,
                    VERSION_JSON,
                    completeFetcher,
                    Duration.ofMinutes(1),
                    System::nanoTime,
                    directory,
                    ChunithmCatalog::writeSnapshotsAtomically);
            ChunithmCatalog.RefreshResult result = catalog.refreshNow();
            expect(true, result.success(),
                    "strict four-source refresh succeeds");
            expect(4, result.status().songCount(),
                    "strict refresh exposes the validated merged song count");
            expect(4, ((List<?>) ((Map<?, ?>) Json.parse(result.toJson()))
                            .get("sources")).size(),
                    "refresh result names all four public sources");
            for (String name : List.of(
                    ChunithmCatalog.DIVING_FISH_MUSIC_FILE,
                    ChunithmCatalog.DIVING_FISH_VERSION_FILE,
                    ChunithmCatalog.LXNS_SONG_FILE,
                    ChunithmCatalog.LXNS_ALIAS_FILE)) {
                expect(true, Files.isRegularFile(directory.resolve(name)),
                        "validated snapshot is published: " + name);
            }
            ChunithmCatalog reloaded = ChunithmCatalog.load(directory);
            expect("33", reloaded.search("bbkkbkk", 10, false)
                            .songs().getFirst().songId(),
                    "persisted LXNS aliases survive an offline reload");
            expect(true, reloaded.currentCatalog().songs().stream()
                            .filter(song -> song.songId().equals("20"))
                            .findFirst().orElseThrow().disabled(),
                    "persisted LXNS disabled metadata survives reload");
            Files.writeString(
                    directory.resolve(ChunithmCatalog.LXNS_ALIAS_FILE),
                    "{}",
                    StandardCharsets.UTF_8);
            ChunithmCatalog fallback = ChunithmCatalog.load(directory);
            expect("snapshot", fallback.catalog().source(),
                    "invalid persisted LXNS data falls back to Diving-Fish");
            expect(true, fallback.catalog().warning().contains(
                            "Persisted LXNS CHUNITHM snapshots are invalid"),
                    "offline LXNS fallback reports a safe warning");
        } finally {
            deleteTree(directory);
        }

        AtomicInteger writes = new AtomicInteger();
        ChunithmCatalog invalid = ChunithmCatalog.fromJson(
                MUSIC_JSON,
                VERSION_JSON,
                endpoint -> endpoint.equals(
                        ChunithmCatalog.LXNS_ALIAS_ENDPOINT)
                        ? "{}".getBytes(StandardCharsets.UTF_8)
                        : completeFetcher.fetch(endpoint),
                Duration.ofMinutes(1),
                System::nanoTime,
                Path.of("unused-test-directory"),
                (path, files) -> writes.incrementAndGet());
        expect(false, invalid.refreshNow().success(),
                "invalid LXNS data rejects strict publication");
        expect(0, writes.get(),
                "validation failure occurs before any publication");
        expect("3", invalid.catalog().songs().getFirst().songId(),
                "validation failure retains the bundled in-memory snapshot");

        ChunithmCatalog writeFailure = ChunithmCatalog.fromJson(
                MUSIC_JSON,
                VERSION_JSON,
                completeFetcher,
                Duration.ofMinutes(1),
                System::nanoTime,
                Path.of("unused-test-directory"),
                (path, files) -> {
                    throw new IOException("simulated atomic write failure");
                });
        expect(false, writeFailure.refreshNow().success(),
                "publication failure is terminal for the refresh");
        expect("3", writeFailure.currentCatalog().songs().getFirst().songId(),
                "publication failure retains last-known-good memory data");
    }

    private static void testHttpDownloadBoundary() throws IOException {
        HttpRequest request = ChunithmCatalog.buildRemoteRequest(
                ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT);
        expect(Duration.ofSeconds(12), request.timeout().orElse(null),
                "remote request has a whole-response timeout");
        expect("https", request.uri().getScheme(), "remote request uses HTTPS");

        tests++;
        try {
            ChunithmCatalog.buildRemoteRequest(
                    URI.create("http://localhost/music_data"));
            throw new AssertionError("plain HTTP endpoint must be rejected");
        } catch (IOException expected) {
            // Expected.
        }

        byte[] maximum = feedLimitedBody(
                ChunithmCatalog.MAX_REMOTE_BYTES - 1, 1);
        expect(ChunithmCatalog.MAX_REMOTE_BYTES, maximum.length,
                "exactly 2 MiB response is accepted");

        tests++;
        try {
            feedLimitedBody(ChunithmCatalog.MAX_REMOTE_BYTES, 1);
            throw new AssertionError("response above 2 MiB must fail");
        } catch (CompletionException | IllegalArgumentException expected) {
            // JDK limiting subscriber cancels an oversized response.
        }
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
                ChunithmCatalog.limitedRemoteBodyHandler().apply(responseInfo);
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
                // Test chunks are delivered synchronously below.
            }

            @Override
            public void cancel() {
                // Nothing external to cancel in this offline test.
            }
        });
        for (int size : chunkSizes) {
            subscriber.onNext(List.of(ByteBuffer.allocate(size)));
        }
        subscriber.onComplete();
        return subscriber.getBody().toCompletableFuture().join();
    }

    private static void testProductionCatalogue() throws IOException {
        ChunithmCatalog catalog = ChunithmCatalog.load(
                Path.of("web", "chunithm-catalog"));
        ChunithmCatalog.CatalogResult result = catalog.catalog();
        expect(true, result.songs().size() >= 1_000
                        && result.songs().size() <= 3_000,
                "persisted or bundled snapshot has a plausible full size");
        expect(true, !result.latestVersions().isEmpty()
                        && result.latestVersions().size() <= 20,
                "validated latest versions are retained");
        long newSongs = result.songs().stream()
                .filter(ChunithmCatalog.Song::isNew)
                .count();
        expect(true, newSongs > 0,
                "latest versions mark at least one current song");
        expect(true, result.songs().stream()
                        .filter(song -> song.charts().stream().noneMatch(
                                chart -> chart.difficultyIndex() == 5))
                        .allMatch(song -> song.coverUrl().matches(
                                "/api/chunithm/covers/[0-9]+\\.png")),
                "normal cover URLs stay on the local proxy");
        expect(true, result.songs().stream()
                        .filter(song -> song.charts().stream().anyMatch(
                                chart -> chart.difficultyIndex() == 5))
                        .allMatch(song -> song.coverUrl().matches(
                                "/api/chunithm/covers/[0-9]+\\.png")),
                "the production snapshot resolves every World's End cover");
        expect(true, result.songs().stream().allMatch(
                        song -> !song.charts().isEmpty()),
                "every song has a selectable chart");
        long worldEnds = result.songs().stream()
                .filter(song -> song.charts().stream().anyMatch(
                        chart -> chart.difficultyIndex() == 5))
                .count();
        expect(true, worldEnds > 0,
                "World's End rows remain available at difficulty index 5");
        expect(true, result.songs().stream()
                        .flatMap(song -> song.charts().stream())
                        .allMatch(chart -> chart.difficultyIndex() >= 0
                                && chart.difficultyIndex() <= 5),
                "all difficulty indexes are bounded");

        result = catalog.search("モ゜ルモﾟル", 10, false);
        expect("2193", result.songs().getFirst().songId(),
                "degree and Japanese semi-voiced mark variants match モ°ルモ°ル");
        result = catalog.search("Re: End of a Dream", 10, false);
        expect("2218", result.songs().getFirst().songId(),
                "colon width and spacing variants match Re：End of a Dream");
        result = catalog.search("Re: End of a Drean", 10, false);
        expect("2218", result.songs().getFirst().songId(),
                "one-character OCR error matches a sufficiently long title");

        result = catalog.search("Blessed", 10, false);
        expect("690", result.songs().getFirst().songId(),
                "strict title match ranks ahead of a compact-key collision");
        List<String> collisionIds = catalog.search(
                        "B.l.e.s.s.e.d", 10, false).songs().stream()
                .limit(2)
                .map(ChunithmCatalog.Song::songId)
                .toList();
        expect(List.of("8195", "690"), collisionIds,
                "ambiguous compact matches are both returned deterministically");
        expect(collisionIds, catalog.search(
                        "B.l.e.s.s.e.d", 10, false).songs().stream()
                .limit(2)
                .map(ChunithmCatalog.Song::songId)
                .toList(), "ambiguous compact ordering is stable");

        expect(0, catalog.search("°゜ﾟ", 10, false).songs().size(),
                "pure-symbol query does not expand through an empty compact key");
        expect(0, catalog.search("モル", 10, false).songs().size(),
                "short query does not use compact partial matching");
        expect(0, catalog.search("Flox", 10, false).songs().size(),
                "short query does not use edit-distance matching");
    }

    private static ChunithmCatalog fixture(
            ChunithmCatalog.RemoteFetcher fetcher, AtomicLong clock) {
        return ChunithmCatalog.fromJson(
                MUSIC_JSON,
                VERSION_JSON,
                fetcher,
                Duration.ofNanos(100),
                clock::get);
    }

    private static ChunithmCatalog.Song findSong(
            ChunithmCatalog.CatalogResult catalog, String songId) {
        return catalog.songs().stream()
                .filter(song -> song.songId().equals(songId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing fixture song " + songId));
    }

    private static String normalCoverSongJson(int songId, String title) {
        return """
                {
                  "id":%d,"title":"%s",
                  "ds":[1,2,3,4],"level":["1","2","3","4"],
                  "cids":[%d1,%d2,%d3,%d4],
                  "charts":[
                    {"combo":1,"charter":"A"},{"combo":2,"charter":"B"},
                    {"combo":3,"charter":"C"},{"combo":4,"charter":"D"}
                  ],
                  "basic_info":{
                    "title":"%s","artist":"Artist","genre":"原创",
                    "bpm":120,"from":"CHUNITHM VERSE"
                  }
                }
                """.formatted(
                        songId,
                        title,
                        songId,
                        songId,
                        songId,
                        songId,
                        title);
    }

    private static String legacyWorldsEndSongJson(
            int songId,
            String title,
            String first,
            String second,
            String third,
            String fourth,
            String fifth) {
        return """
                {
                  "id":%d,"title":"%s",
                  "ds":[0,0,0,0,0,0],
                  "level":["%s","%s","%s","%s","%s","狂"],
                  "cids":[%d1,%d2,%d3,%d4,%d5,%d6],
                  "charts":[
                    {"combo":0,"charter":"-"},{"combo":0,"charter":"-"},
                    {"combo":0,"charter":"-"},{"combo":0,"charter":"-"},
                    {"combo":0,"charter":"-"},{"combo":100,"charter":"WE"}
                  ],
                  "basic_info":{
                    "title":"%s","artist":"Artist","genre":"原创",
                    "bpm":120,"from":"CHUNITHM VERSE"
                  }
                }
                """.formatted(
                        songId,
                        title,
                        first,
                        second,
                        third,
                        fourth,
                        fifth,
                        songId,
                        songId,
                        songId,
                        songId,
                        songId,
                        songId,
                        title);
    }

    private static String oneEntryWorldsEndSongJson(int songId, String title) {
        return """
                {
                  "id":%d,"title":"%s",
                  "ds":[0],"level":["嘘"],"cids":[%d1],
                  "charts":[{"combo":100,"charter":"WE"}],
                  "basic_info":{
                    "title":"%s","artist":"Artist","genre":"原创",
                    "bpm":120,"from":"CHUNITHM VERSE"
                  }
                }
                """.formatted(songId, title, songId, title);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void expectThrows(Runnable action, String label) {
        tests++;
        try {
            action.run();
            throw new AssertionError(label + ": expected an exception");
        } catch (IllegalArgumentException expected) {
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
}
