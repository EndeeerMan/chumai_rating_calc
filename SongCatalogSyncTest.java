import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Offline combined-result and root-script contract tests. */
public final class SongCatalogSyncTest {
    private static int tests;

    private SongCatalogSyncTest() {
    }

    public static void main(String[] args) throws Exception {
        SongCatalog.SyncStatus maimaiStatus = new SongCatalog.SyncStatus(
                "ready", Instant.EPOCH, Instant.EPOCH, null, 2_000);
        ChunithmCatalog.SyncStatus chunithmStatus =
                new ChunithmCatalog.SyncStatus(
                        "ready", Instant.EPOCH, Instant.EPOCH, null, 1_500);
        String json = SongCatalogSync.combinedJson(
                new SongCatalog.RefreshResult(
                        true, "Maimai catalogue synchronized", maimaiStatus),
                new ChunithmCatalog.RefreshResult(
                        true, "CHUNITHM catalogue synchronized", chunithmStatus));
        Map<?, ?> root = (Map<?, ?>) Json.parse(json);
        expect(true, root.get("success"), "combined success");
        expect(3, sources(root, "maimai").size(),
                "maimai names three public API resources");
        expect(4, sources(root, "chunithm").size(),
                "CHUNITHM names four public API resources");
        expect(false, root.containsKey("officialPlayerData"),
                "catalogue command never reports official player data");

        String failedJson = SongCatalogSync.combinedJson(
                new SongCatalog.RefreshResult(
                        false, "failed", new SongCatalog.SyncStatus(
                                "failed", Instant.EPOCH, null, "offline", 10)),
                new ChunithmCatalog.RefreshResult(
                        true, "ok", chunithmStatus));
        Map<?, ?> failed = (Map<?, ?>) Json.parse(failedJson);
        expect(false, failed.get("success"),
                "one game failure fails the unified command");
        expect(true, sources(failed, "maimai").stream().allMatch(source ->
                        "not-published".equals(source.get("publication"))),
                "failed strict group reports no publication");

        String script = Files.readString(Path.of("sync-song-catalogs.ps1"));
        expect(true, script.contains("SongCatalogSync"),
                "root script invokes the unified CLI");
        expect(true, script.contains("--release 26"),
                "root script pins JDK 26 compilation");
        expect(true, script.contains("publicApiSources"),
                "root script renders every public API source");
        expect(true, script.contains("[switch]$Json"),
                "root script retains an explicit machine-readable mode");
        expect(true, script.contains("SongCoverSync"),
                "root script invokes the all-cover synchronization CLI");
        expect(true, script.contains("remainingFiles"),
                "root script reports remaining cover files explicitly");
        expect(false, script.contains("华立官网玩家数据"),
                "root script has no official player-data section");
        expect(false, Files.exists(Path.of("sync-maimai-catalog.ps1")),
                "misleading legacy maimai-only command is removed");

        System.out.println(
                "SongCatalogSyncTest: all " + tests + " tests passed.");
    }

    private static List<? extends Map<?, ?>> sources(
            Map<?, ?> root, String game) {
        Map<?, ?> gameValue = (Map<?, ?>) root.get(game);
        return ((List<?>) gameValue.get("publicApiSources")).stream()
                .map(value -> (Map<?, ?>) value)
                .toList();
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }
}
