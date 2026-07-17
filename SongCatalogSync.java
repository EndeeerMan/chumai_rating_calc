import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Unified manual full-catalog synchronization for maimai DX and CHUNITHM. */
public final class SongCatalogSync {
    private static final List<PublicSource> MAIMAI_SOURCES = List.of(
            new PublicSource(
                    "Diving-Fish",
                    SongCatalog.DIVING_FISH_ENDPOINT.toString()),
            new PublicSource(
                    "LXNS",
                    SongCatalog.LXNS_SONG_ENDPOINT.toString()),
            new PublicSource(
                    "LXNS",
                    SongCatalog.LXNS_ALIAS_ENDPOINT.toString()));
    private static final List<PublicSource> CHUNITHM_SOURCES = List.of(
            new PublicSource(
                    "Diving-Fish",
                    ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT.toString()),
            new PublicSource(
                    "Diving-Fish",
                    ChunithmCatalog.DIVING_FISH_VERSION_ENDPOINT.toString()),
            new PublicSource(
                    "LXNS",
                    ChunithmCatalog.LXNS_SONG_ENDPOINT.toString()),
            new PublicSource(
                    "LXNS",
                    ChunithmCatalog.LXNS_ALIAS_ENDPOINT.toString()));

    private SongCatalogSync() {
    }

    public static void main(String[] args) {
        if (args.length > 3) {
            System.err.println(
                    "Usage: SongCatalogSync [maimai-directory] "
                            + "[maimai-cache] [chunithm-directory]");
            System.exit(2);
        }
        Path maimaiDirectory = args.length >= 1
                ? Path.of(args[0])
                : Path.of("web", "song-catalog");
        Path maimaiCache = args.length >= 2
                ? Path.of(args[1])
                : SongCatalog.DEFAULT_CACHE_PATH;
        Path chunithmDirectory = args.length >= 3
                ? Path.of(args[2])
                : Path.of("web", "chunithm-catalog");

        SongCatalog.RefreshResult maimai = synchronizeMaimai(
                maimaiDirectory, maimaiCache);
        ChunithmCatalog.RefreshResult chunithm = synchronizeChunithm(
                chunithmDirectory);
        System.out.println(combinedJson(maimai, chunithm));
        if (!maimai.success() || !chunithm.success()) {
            System.exit(1);
        }
    }

    private static SongCatalog.RefreshResult synchronizeMaimai(
            Path directory, Path cache) {
        try {
            return SongCatalog.load(directory, cache).refreshNow();
        } catch (Exception error) {
            String message = safeMessage(error);
            return new SongCatalog.RefreshResult(
                    false,
                    "Maimai catalogue synchronization could not start: "
                            + message,
                    new SongCatalog.SyncStatus(
                            "failed", Instant.now(), null, message, 0));
        }
    }

    private static ChunithmCatalog.RefreshResult synchronizeChunithm(
            Path directory) {
        try {
            return ChunithmCatalog.load(directory).refreshNow();
        } catch (Exception error) {
            String message = safeMessage(error);
            return new ChunithmCatalog.RefreshResult(
                    false,
                    "CHUNITHM catalogue synchronization could not start: "
                            + message,
                    new ChunithmCatalog.SyncStatus(
                            "failed", Instant.now(), null, message, 0));
        }
    }

    static String combinedJson(
            SongCatalog.RefreshResult maimai,
            ChunithmCatalog.RefreshResult chunithm) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("success", maimai.success() && chunithm.success());
        value.put("completedAt", Instant.now().toString());
        value.put("maimai", gameResult(
                Json.parse(maimai.toJson()),
                MAIMAI_SOURCES,
                maimai.success()));
        value.put("chunithm", gameResult(
                Json.parse(chunithm.toJson()),
                CHUNITHM_SOURCES,
                chunithm.success()));
        return Json.stringify(value);
    }

    private static Map<String, Object> gameResult(
            Object result,
            List<PublicSource> sources,
            boolean success) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("result", result);
        List<Map<String, Object>> sourceValues = new ArrayList<>();
        for (PublicSource source : sources) {
            Map<String, Object> sourceValue = new LinkedHashMap<>();
            sourceValue.put("provider", source.provider());
            sourceValue.put("url", source.url());
            sourceValue.put(
                    "publication",
                    success ? "validated-and-published" : "not-published");
            sourceValues.add(sourceValue);
        }
        value.put("publicApiSources", sourceValues);
        return value;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    private record PublicSource(String provider, String url) {
    }
}
