"""Read-only resolver for the shared, synchronized maimai song catalogue."""

from __future__ import annotations

from dataclasses import dataclass
from difflib import SequenceMatcher
import json
import math
from pathlib import Path
import re
import unicodedata
from typing import Any, Iterable


MAX_CATALOG_BYTES = 64 * 1024 * 1024
MAX_SONGS = 10_000
MAX_ALIASES_PER_SONG = 100
CHUNITHM_DF_FILE = "diving-fish-music-data.json"
CHUNITHM_LXNS_SONG_FILE = "lxns-song-list.json"
CHUNITHM_LXNS_ALIAS_FILE = "lxns-alias-list.json"
DIFFICULTIES = ("BASIC", "ADVANCED", "EXPERT", "MASTER", "RE:MASTER")
CHUNITHM_DIFFICULTIES = ("BASIC", "ADVANCED", "EXPERT", "MASTER", "ULTIMA")


class CatalogError(RuntimeError):
    """The last-known-good shared catalogue cannot be used safely."""


@dataclass(frozen=True, slots=True)
class CatalogChart:
    chart_type: str
    difficulty: str
    level_value: float
    version: str


@dataclass(frozen=True, slots=True)
class CatalogSong:
    song_id: str
    title: str
    aliases: tuple[str, ...]
    charts: tuple[CatalogChart, ...]

    def chart(self, chart_type: str, difficulty: str) -> CatalogChart | None:
        wanted_type = normalize_chart_type(chart_type)
        wanted_difficulty = normalize_difficulty(difficulty)
        for chart in self.charts:
            if (
                chart.chart_type == wanted_type
                and chart.difficulty == wanted_difficulty
            ):
                return chart
        return None


@dataclass(frozen=True, slots=True)
class CatalogMatch:
    song: CatalogSong
    chart: CatalogChart


class MaimaiCatalog:
    """Resolve an official title to one canonical base SongID.

    Exact title and alias matching is preferred.  A conservative fuzzy pass is
    used only when it produces one clearly better candidate that also owns the
    requested chart.  Titles are never returned as persistent identifiers.
    """

    def __init__(self, songs: Iterable[CatalogSong]) -> None:
        copied = tuple(songs)
        if not copied:
            raise CatalogError("synchronized maimai catalogue contains no songs")
        self._songs = copied
        self._title_exact: dict[str, list[CatalogSong]] = {}
        self._title_compact: dict[str, list[CatalogSong]] = {}
        self._alias_exact: dict[str, list[CatalogSong]] = {}
        for song in copied:
            title_key = normalize_title(song.title)
            compact_key = compact_title(song.title)
            if title_key:
                self._title_exact.setdefault(title_key, []).append(song)
            if compact_key:
                self._title_compact.setdefault(compact_key, []).append(song)
            for alias in song.aliases:
                alias_key = normalize_title(alias)
                if alias_key:
                    self._alias_exact.setdefault(alias_key, []).append(song)

    @classmethod
    def load(cls, path: Path) -> "MaimaiCatalog":
        try:
            if path.is_symlink() or not path.is_file():
                raise CatalogError("synchronized maimai catalogue is missing")
            size = path.stat().st_size
            if size <= 0 or size > MAX_CATALOG_BYTES:
                raise CatalogError("synchronized maimai catalogue has an invalid size")
            raw = path.read_bytes()
            if len(raw) > MAX_CATALOG_BYTES:
                raise CatalogError("synchronized maimai catalogue is too large")
            document = json.loads(raw.decode("utf-8"))
        except CatalogError:
            raise
        except (OSError, UnicodeDecodeError, ValueError) as error:
            raise CatalogError("synchronized maimai catalogue is unreadable") from error

        if not isinstance(document, dict) or document.get("schemaVersion") != 1:
            raise CatalogError("synchronized maimai catalogue has an unsupported schema")
        raw_songs = document.get("songs")
        if not isinstance(raw_songs, list) or len(raw_songs) > MAX_SONGS:
            raise CatalogError("synchronized maimai catalogue has invalid songs")

        songs: list[CatalogSong] = []
        seen_ids: set[str] = set()
        for index, value in enumerate(raw_songs):
            context = f"songs[{index}]"
            if not isinstance(value, dict):
                raise CatalogError(f"{context} must be an object")
            song_id = canonical_song_id(value.get("songId"), context)
            if song_id in seen_ids:
                raise CatalogError(f"duplicate SongID {song_id}")
            seen_ids.add(song_id)
            title = required_title(value.get("title"), f"{context}.title", 300)
            raw_aliases = value.get("aliases", [])
            if not isinstance(raw_aliases, list) or len(raw_aliases) > 100:
                raise CatalogError(f"{context}.aliases must be an array")
            aliases = tuple(
                required_text(alias, f"{context}.aliases", 300)
                for alias in raw_aliases
            )
            raw_charts = value.get("charts")
            if not isinstance(raw_charts, list) or len(raw_charts) > 10:
                raise CatalogError(f"{context}.charts must be an array")
            charts: list[CatalogChart] = []
            seen_charts: set[tuple[str, str]] = set()
            for chart_index, raw_chart in enumerate(raw_charts):
                chart_context = f"{context}.charts[{chart_index}]"
                if not isinstance(raw_chart, dict):
                    raise CatalogError(f"{chart_context} must be an object")
                try:
                    chart_type = normalize_chart_type(raw_chart.get("chartType"))
                    difficulty = normalize_difficulty(raw_chart.get("difficulty"))
                except (TypeError, ValueError) as error:
                    raise CatalogError(f"{chart_context} has an invalid chart key") from error
                key = (chart_type, difficulty)
                if key in seen_charts:
                    raise CatalogError(f"{chart_context} duplicates a chart")
                seen_charts.add(key)
                level_value = finite_number(
                    raw_chart.get("levelValue"), f"{chart_context}.levelValue"
                )
                if level_value < 1.0 or level_value > 15.9:
                    raise CatalogError(f"{chart_context}.levelValue is out of range")
                version = required_text(
                    raw_chart.get("version"), f"{chart_context}.version", 20
                ).lower()
                if version not in {"legacy", "current"}:
                    raise CatalogError(f"{chart_context}.version is invalid")
                charts.append(
                    CatalogChart(chart_type, difficulty, level_value, version)
                )
            if charts:
                songs.append(CatalogSong(song_id, title, aliases, tuple(charts)))
        return cls(songs)

    def resolve(
        self,
        title: str,
        chart_type: str,
        difficulty: str,
    ) -> CatalogMatch | None:
        wanted_type = normalize_chart_type(chart_type)
        wanted_difficulty = normalize_difficulty(difficulty)
        key = normalize_title(title)
        title_exact = self._eligible(
            self._title_exact.get(key, ()), wanted_type, wanted_difficulty
        )
        if len(title_exact) == 1:
            song = title_exact[0]
            return CatalogMatch(song, song.chart(wanted_type, wanted_difficulty))  # type: ignore[arg-type]
        if len(title_exact) > 1:
            return None

        compact_query = compact_title(title)
        compact_exact = self._eligible(
            self._title_compact.get(compact_query, ()), wanted_type, wanted_difficulty
        )
        if len(compact_exact) == 1:
            song = compact_exact[0]
            return CatalogMatch(song, song.chart(wanted_type, wanted_difficulty))  # type: ignore[arg-type]
        if len(compact_exact) > 1:
            return None

        # Third-party aliases are accepted only as exact, unique bindings.
        # They never participate in fuzzy persistence decisions.
        alias_exact = self._eligible(
            self._alias_exact.get(key, ()), wanted_type, wanted_difficulty
        )
        if len(alias_exact) == 1:
            song = alias_exact[0]
            return CatalogMatch(song, song.chart(wanted_type, wanted_difficulty))  # type: ignore[arg-type]
        if len(alias_exact) > 1:
            return None

        if len(compact_query) < 4:
            return None
        scored: list[tuple[float, CatalogSong]] = []
        for song in self._songs:
            if song.chart(wanted_type, wanted_difficulty) is None:
                continue
            score = SequenceMatcher(
                None, compact_query, compact_title(song.title)
            ).ratio()
            if score >= 0.82:
                scored.append((score, song))
        scored.sort(key=lambda item: (-item[0], int(item[1].song_id)))
        if not scored:
            return None
        if len(scored) > 1 and scored[0][0] - scored[1][0] < 0.06:
            return None
        song = scored[0][1]
        chart = song.chart(wanted_type, wanted_difficulty)
        return CatalogMatch(song, chart) if chart is not None else None

    @staticmethod
    def _eligible(
        songs: Iterable[CatalogSong], chart_type: str, difficulty: str
    ) -> list[CatalogSong]:
        unique: dict[str, CatalogSong] = {}
        for song in songs:
            if song.chart(chart_type, difficulty) is not None:
                unique[song.song_id] = song
        return list(unique.values())


@dataclass(frozen=True, slots=True)
class ChunithmCatalogChart:
    difficulty: str
    constant: float
    version: str


@dataclass(frozen=True, slots=True)
class ChunithmCatalogSong:
    song_id: str
    title: str
    charts: tuple[ChunithmCatalogChart, ...]
    aliases: tuple[str, ...] = ()

    def chart(self, difficulty: str) -> ChunithmCatalogChart | None:
        wanted = normalize_chunithm_difficulty(difficulty)
        return next((chart for chart in self.charts if chart.difficulty == wanted), None)


@dataclass(frozen=True, slots=True)
class ChunithmCatalogMatch:
    song: ChunithmCatalogSong
    chart: ChunithmCatalogChart


@dataclass(frozen=True, slots=True)
class _LxnsChunithmSong:
    chart_versions: dict[int, str]


@dataclass(frozen=True, slots=True)
class _LxnsChunithmEnhancements:
    songs: dict[str, _LxnsChunithmSong]
    aliases: dict[str, tuple[str, ...]]


class ChunithmCatalog:
    """Read the bundled CHUNITHM snapshot and resolve only canonical SongIDs."""

    def __init__(self, songs: Iterable[ChunithmCatalogSong]) -> None:
        copied = tuple(songs)
        if not copied:
            raise CatalogError("synchronized CHUNITHM catalogue contains no songs")
        self._songs = copied
        self._by_id = {song.song_id: song for song in copied}
        if len(self._by_id) != len(copied):
            raise CatalogError("synchronized CHUNITHM catalogue contains duplicate SongIDs")
        self._title_exact: dict[str, list[ChunithmCatalogSong]] = {}
        self._title_compact: dict[str, list[ChunithmCatalogSong]] = {}
        self._alias_exact: dict[str, list[ChunithmCatalogSong]] = {}
        self._alias_compact: dict[str, list[ChunithmCatalogSong]] = {}
        for song in copied:
            self._title_exact.setdefault(normalize_title(song.title), []).append(song)
            self._title_compact.setdefault(compact_title(song.title), []).append(song)
            for alias in song.aliases:
                alias_key = normalize_title(alias)
                compact_key = compact_title(alias)
                if alias_key:
                    self._alias_exact.setdefault(alias_key, []).append(song)
                if compact_key:
                    self._alias_compact.setdefault(compact_key, []).append(song)

    @classmethod
    def load(cls, path: Path) -> "ChunithmCatalog":
        try:
            if path.is_symlink():
                raise CatalogError("synchronized CHUNITHM catalogue is missing")
            if path.is_dir():
                catalog_directory = path
                music_path = path / CHUNITHM_DF_FILE
            else:
                catalog_directory = path.parent
                music_path = path
            if music_path.is_symlink() or not music_path.is_file():
                raise CatalogError("synchronized CHUNITHM catalogue is missing")
            size = music_path.stat().st_size
            if size <= 0 or size > MAX_CATALOG_BYTES:
                raise CatalogError("synchronized CHUNITHM catalogue has an invalid size")
            document = json.loads(music_path.read_bytes().decode("utf-8"))
        except CatalogError:
            raise
        except (OSError, UnicodeDecodeError, ValueError) as error:
            raise CatalogError("synchronized CHUNITHM catalogue is unreadable") from error
        if not isinstance(document, list) or not document or len(document) > MAX_SONGS:
            raise CatalogError("synchronized CHUNITHM catalogue has invalid songs")
        enhancements = _load_lxns_chunithm_enhancements(catalog_directory)

        songs: list[ChunithmCatalogSong] = []
        seen_ids: set[str] = set()
        for index, value in enumerate(document):
            context = f"songs[{index}]"
            if not isinstance(value, dict):
                raise CatalogError(f"{context} must be an object")
            song_id = canonical_chunithm_song_id(value.get("id"), context)
            if song_id in seen_ids:
                raise CatalogError(f"duplicate CHUNITHM SongID {song_id}")
            seen_ids.add(song_id)
            title = required_title(value.get("title"), f"{context}.title", 300)
            basic_info = value.get("basic_info")
            if not isinstance(basic_info, dict):
                raise CatalogError(f"{context}.basic_info must be an object")
            version = required_text(
                basic_info.get("from"), f"{context}.basic_info.from", 100
            )
            lxns_song = enhancements.songs.get(song_id)
            constants = value.get("ds")
            levels = value.get("level")
            if (
                not isinstance(constants, list)
                or not isinstance(levels, list)
                or len(constants) != len(levels)
                or len(constants) < 1
                or len(constants) > 6
            ):
                raise CatalogError(f"{context} has invalid chart arrays")
            charts: list[ChunithmCatalogChart] = []
            # One-entry and six-entry rows are WORLD'S END data. Normal rating
            # pages expose only indexes 0..4, so placeholder WE rows are ignored.
            normal_count = 0 if len(constants) == 1 else min(len(constants), 5)
            for difficulty_index in range(normal_count):
                level = levels[difficulty_index]
                if level == "-":
                    continue
                constant = finite_number(
                    constants[difficulty_index],
                    f"{context}.ds[{difficulty_index}]",
                )
                if constant < 0.0 or constant > 20.0:
                    raise CatalogError(f"{context} has an invalid chart constant")
                charts.append(
                    ChunithmCatalogChart(
                        CHUNITHM_DIFFICULTIES[difficulty_index],
                        constant,
                        (
                            lxns_song.chart_versions.get(difficulty_index, version)
                            if lxns_song is not None
                            else version
                        ),
                    )
                )
            if charts:
                songs.append(ChunithmCatalogSong(
                    song_id,
                    title,
                    tuple(charts),
                    enhancements.aliases.get(song_id, ()),
                ))
        return cls(songs)

    def resolve_by_id(
        self,
        song_id: str,
        title: str,
        difficulty: str,
    ) -> ChunithmCatalogMatch | None:
        try:
            canonical_id = canonical_chunithm_song_id(song_id, "rating idx")
            wanted = normalize_chunithm_difficulty(difficulty)
        except (CatalogError, TypeError, ValueError):
            return None
        song = self._by_id.get(canonical_id)
        if song is None:
            return None
        # The rating idx is accepted as a SongID only when independent title
        # and difficulty fields agree with the DF title or a same-ID LXNS alias.
        if not self._title_matches_song(song, title):
            return None
        chart = song.chart(wanted)
        return ChunithmCatalogMatch(song, chart) if chart is not None else None

    def resolve(self, title: str, difficulty: str) -> ChunithmCatalogMatch | None:
        wanted = normalize_chunithm_difficulty(difficulty)
        exact = self._eligible(self._title_exact.get(normalize_title(title), ()), wanted)
        if len(exact) == 1:
            chart = exact[0].chart(wanted)
            return ChunithmCatalogMatch(exact[0], chart)  # type: ignore[arg-type]
        if len(exact) > 1:
            return None
        compact = compact_title(title)
        compact_matches = self._eligible(self._title_compact.get(compact, ()), wanted)
        if len(compact_matches) == 1:
            chart = compact_matches[0].chart(wanted)
            return ChunithmCatalogMatch(compact_matches[0], chart)  # type: ignore[arg-type]
        if len(compact_matches) > 1:
            return None
        alias_exact = self._eligible(
            self._alias_exact.get(normalize_title(title), ()), wanted
        )
        if len(alias_exact) == 1:
            chart = alias_exact[0].chart(wanted)
            return ChunithmCatalogMatch(alias_exact[0], chart)  # type: ignore[arg-type]
        if len(alias_exact) > 1:
            return None
        alias_compact = self._eligible(
            self._alias_compact.get(compact, ()), wanted
        )
        if len(alias_compact) == 1:
            chart = alias_compact[0].chart(wanted)
            return ChunithmCatalogMatch(alias_compact[0], chart)  # type: ignore[arg-type]
        if len(alias_compact) > 1:
            return None
        if len(compact) < 4:
            return None
        scored: list[tuple[float, ChunithmCatalogSong]] = []
        for song in self._songs:
            if song.chart(wanted) is None:
                continue
            targets = [compact_title(song.title), *map(compact_title, song.aliases)]
            score = max(
                (
                    SequenceMatcher(None, compact, target).ratio()
                    for target in targets
                    if target
                ),
                default=0.0,
            )
            if score >= 0.86:
                scored.append((score, song))
        scored.sort(key=lambda item: (-item[0], int(item[1].song_id)))
        if not scored or len(scored) > 1 and scored[0][0] - scored[1][0] < 0.06:
            return None
        song = scored[0][1]
        chart = song.chart(wanted)
        return ChunithmCatalogMatch(song, chart) if chart is not None else None

    @staticmethod
    def _title_matches_song(song: ChunithmCatalogSong, title: str) -> bool:
        exact = normalize_title(title)
        compact = compact_title(title)
        if exact == normalize_title(song.title) or (
            compact and compact == compact_title(song.title)
        ):
            return True
        return any(
            exact == normalize_title(alias)
            or (compact and compact == compact_title(alias))
            for alias in song.aliases
        )

    @staticmethod
    def _eligible(
        songs: Iterable[ChunithmCatalogSong], difficulty: str
    ) -> list[ChunithmCatalogSong]:
        return list({song.song_id: song for song in songs if song.chart(difficulty)}.values())


def _load_lxns_chunithm_enhancements(
    directory: Path,
) -> _LxnsChunithmEnhancements:
    empty = _LxnsChunithmEnhancements({}, {})
    song_path = directory / CHUNITHM_LXNS_SONG_FILE
    alias_path = directory / CHUNITHM_LXNS_ALIAS_FILE
    # The synchronizer publishes these as one logical pair. A partial or
    # invalid pair must never make the trusted Diving-Fish snapshot unusable.
    if (
        song_path.is_symlink()
        or alias_path.is_symlink()
        or not song_path.is_file()
        or not alias_path.is_file()
    ):
        return empty
    try:
        song_root = _read_lxns_json(song_path, "LXNS CHUNITHM song data")
        alias_root = _read_lxns_json(alias_path, "LXNS CHUNITHM alias data")
        return _parse_lxns_chunithm_enhancements(song_root, alias_root)
    except CatalogError:
        return empty


def _read_lxns_json(path: Path, context: str) -> Any:
    try:
        size = path.stat().st_size
        if size <= 0 or size > 2 * 1024 * 1024:
            raise CatalogError(f"{context} has an invalid size")
        raw = path.read_bytes()
        if len(raw) != size or len(raw) > 2 * 1024 * 1024:
            raise CatalogError(f"{context} changed while being read")
        return json.loads(raw.decode("utf-8"))
    except CatalogError:
        raise
    except (OSError, UnicodeDecodeError, ValueError) as error:
        raise CatalogError(f"{context} is unreadable") from error


def _parse_lxns_chunithm_enhancements(
    song_root: Any,
    alias_root: Any,
) -> _LxnsChunithmEnhancements:
    if not isinstance(song_root, dict):
        raise CatalogError("LXNS CHUNITHM song data must be an object")
    raw_versions = song_root.get("versions")
    if (
        not isinstance(raw_versions, list)
        or not raw_versions
        or len(raw_versions) > 50
    ):
        raise CatalogError("LXNS CHUNITHM versions are invalid")
    versions: dict[int, str] = {}
    for index, value in enumerate(raw_versions):
        context = f"LXNS versions[{index}]"
        if not isinstance(value, dict):
            raise CatalogError(f"{context} must be an object")
        number = _required_nonnegative_integer(value.get("version"), context)
        title = required_text(value.get("title"), f"{context}.title", 200)
        if number in versions:
            raise CatalogError(f"{context} duplicates a version")
        versions[number] = title

    raw_songs = song_root.get("songs")
    if not isinstance(raw_songs, list) or len(raw_songs) > MAX_SONGS:
        raise CatalogError("LXNS CHUNITHM songs are invalid")
    songs: dict[str, _LxnsChunithmSong] = {}
    for index, value in enumerate(raw_songs):
        context = f"LXNS songs[{index}]"
        if not isinstance(value, dict):
            raise CatalogError(f"{context} must be an object")
        song_id = canonical_chunithm_song_id(value.get("id"), context)
        disabled = value.get("disabled", False)
        if not isinstance(disabled, bool):
            raise CatalogError(f"{context}.disabled must be boolean")
        raw_difficulties = value.get("difficulties")
        if (
            not isinstance(raw_difficulties, list)
            or len(raw_difficulties) > 6
        ):
            raise CatalogError(f"{context}.difficulties are invalid")
        chart_versions: dict[int, str] = {}
        for difficulty_index, raw_difficulty in enumerate(raw_difficulties):
            difficulty_context = (
                f"{context}.difficulties[{difficulty_index}]"
            )
            if not isinstance(raw_difficulty, dict):
                raise CatalogError(f"{difficulty_context} must be an object")
            level_index = _required_nonnegative_integer(
                raw_difficulty.get("difficulty"),
                f"{difficulty_context}.difficulty",
            )
            if level_index > 5 or level_index in chart_versions:
                raise CatalogError(
                    f"{difficulty_context} has an invalid difficulty"
                )
            version_number = _required_nonnegative_integer(
                raw_difficulty.get("version"),
                f"{difficulty_context}.version",
            )
            chart_version = versions.get(version_number)
            if chart_version is None:
                raise CatalogError(
                    f"{difficulty_context} references an unknown version"
                )
            if level_index == 5:
                canonical_chunithm_song_id(
                    raw_difficulty.get("origin_id"),
                    f"{difficulty_context}.origin_id",
                )
            chart_versions[level_index] = chart_version
        if song_id in songs:
            raise CatalogError(f"{context} duplicates a SongID")
        songs[song_id] = _LxnsChunithmSong(chart_versions)

    if not isinstance(alias_root, dict):
        raise CatalogError("LXNS CHUNITHM alias data must be an object")
    raw_aliases = alias_root.get("aliases")
    if not isinstance(raw_aliases, list) or len(raw_aliases) > MAX_SONGS:
        raise CatalogError("LXNS CHUNITHM aliases are invalid")
    aliases: dict[str, tuple[str, ...]] = {}
    for index, value in enumerate(raw_aliases):
        context = f"LXNS aliases[{index}]"
        if not isinstance(value, dict):
            raise CatalogError(f"{context} must be an object")
        song_id = canonical_chunithm_song_id(value.get("song_id"), context)
        raw_values = value.get("aliases")
        if (
            not isinstance(raw_values, list)
            or len(raw_values) > MAX_ALIASES_PER_SONG
        ):
            raise CatalogError(f"{context}.aliases are invalid")
        parsed: list[str] = []
        seen: set[str] = set()
        for alias_index, raw_alias in enumerate(raw_values):
            alias = required_text(
                raw_alias,
                f"{context}.aliases[{alias_index}]",
                200,
            )
            key = normalize_title(alias)
            if key and key not in seen:
                seen.add(key)
                parsed.append(alias)
        if song_id in aliases:
            raise CatalogError(f"{context} duplicates a SongID")
        aliases[song_id] = tuple(parsed)
    return _LxnsChunithmEnhancements(songs, aliases)


def _required_nonnegative_integer(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise CatalogError(f"{context} must be a non-negative integer")
    return value


def normalize_chart_type(value: Any) -> str:
    if not isinstance(value, str):
        raise TypeError("chart type must be text")
    normalized = value.strip().upper()
    if normalized in {"SD", "STD", "STANDARD"}:
        return "standard"
    if normalized == "DX":
        return "dx"
    raise ValueError("unsupported chart type")


def normalize_difficulty(value: Any) -> str:
    if isinstance(value, int) and not isinstance(value, bool):
        if 0 <= value < len(DIFFICULTIES):
            return DIFFICULTIES[value]
        raise ValueError("unsupported difficulty index")
    if not isinstance(value, str):
        raise TypeError("difficulty must be text")
    normalized = re.sub(r"\s+", "", value).upper()
    if normalized == "REMASTER":
        normalized = "RE:MASTER"
    if normalized not in DIFFICULTIES:
        raise ValueError("unsupported difficulty")
    return normalized


def normalize_chunithm_difficulty(value: Any) -> str:
    if not isinstance(value, str):
        raise TypeError("CHUNITHM difficulty must be text")
    normalized = re.sub(r"\s+", "", value).upper().replace("'", "")
    aliases = {"WORLDEND": "WORLD'S END", "WORLDSEND": "WORLD'S END"}
    normalized = aliases.get(normalized, normalized)
    if normalized not in (*CHUNITHM_DIFFICULTIES, "WORLD'S END"):
        raise ValueError("unsupported CHUNITHM difficulty")
    return normalized


def normalize_title(value: str) -> str:
    if not isinstance(value, str):
        return ""
    # Official SongID 1422 intentionally uses one ideographic space as title.
    if value == "\u3000":
        return value
    normalized = unicodedata.normalize("NFKC", value).casefold().strip()
    # Official pages occasionally alternate Japanese/ASCII punctuation.
    normalized = normalized.translate(
        str.maketrans({"：": ":", "！": "!", "？": "?", "，": ","})
    )
    return " ".join(normalized.split())


def compact_title(value: str) -> str:
    return "".join(
        character
        for character in normalize_title(value)
        if unicodedata.category(character)[0] in {"L", "N"}
    )


def canonical_song_id(value: Any, context: str) -> str:
    if isinstance(value, bool):
        raise CatalogError(f"{context}.songId is invalid")
    if isinstance(value, int):
        number = value
    elif isinstance(value, str) and value.strip().isdigit():
        digits = value.strip()
        if len(digits) > 12:
            raise CatalogError(f"{context}.songId is invalid")
        number = int(digits, 10)
    else:
        raise CatalogError(f"{context}.songId is invalid")
    if number < 0:
        raise CatalogError(f"{context}.songId is invalid")
    # Diving-Fish represents the DX side with a 10000 offset.  Song identity
    # is the base ID; chart type remains an independent part of the chart key.
    number %= 10_000
    return str(number)


def canonical_chunithm_song_id(value: Any, context: str) -> str:
    if isinstance(value, bool):
        raise CatalogError(f"{context}.songId is invalid")
    if isinstance(value, int):
        number = value
    elif isinstance(value, str) and value.strip().isdigit():
        digits = value.strip()
        if len(digits) > 12:
            raise CatalogError(f"{context}.songId is invalid")
        number = int(digits, 10)
    else:
        raise CatalogError(f"{context}.songId is invalid")
    if number < 0:
        raise CatalogError(f"{context}.songId is invalid")
    return str(number)


def required_text(value: Any, context: str, maximum: int) -> str:
    if not isinstance(value, str):
        raise CatalogError(f"{context} must be text")
    normalized = value.strip()
    if not normalized or len(normalized) > maximum:
        raise CatalogError(f"{context} is invalid")
    return normalized


def required_title(value: Any, context: str, maximum: int) -> str:
    if not isinstance(value, str) or len(value) > maximum:
        raise CatalogError(f"{context} is invalid")
    if value == "\u3000":
        return value
    normalized = value.strip()
    if not normalized:
        raise CatalogError(f"{context} is invalid")
    return normalized


def finite_number(value: Any, context: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CatalogError(f"{context} must be a number")
    number = float(value)
    if not math.isfinite(number):
        raise CatalogError(f"{context} must be finite")
    return number
