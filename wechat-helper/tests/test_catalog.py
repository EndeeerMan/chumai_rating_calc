from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from wechat_helper.catalog import ChunithmCatalog


DF_SONGS = [
    {
        "id": 42,
        "title": "Canonical Title",
        "ds": [3.0, 7.0, 11.0, 14.5],
        "level": ["3", "7", "11", "14+"],
        "basic_info": {"from": "CHUNITHM OLD"},
    },
    {
        "id": 43,
        "title": "Another Song",
        "ds": [2.0, 6.0, 10.0, 13.0],
        "level": ["2", "6", "10", "13"],
        "basic_info": {"from": "CHUNITHM OLD"},
    },
]

LXNS_SONGS = {
    "versions": [
        {"version": 1, "title": "CHUNITHM OLD"},
        {"version": 2, "title": "CHUNITHM VERSE"},
    ],
    "songs": [
        {
            "id": 42,
            "difficulties": [
                {"difficulty": value, "version": 2}
                for value in range(4)
            ],
        },
        {
            "id": 43,
            "difficulties": [
                {"difficulty": value, "version": 1}
                for value in range(4)
            ],
        },
        {
            "id": 999,
            "difficulties": [
                {"difficulty": value, "version": 2}
                for value in range(4)
            ],
        },
    ],
}

LXNS_ALIASES = {
    "aliases": [
        {
            "song_id": 42,
            "aliases": [
                "モ°ルモ°ル",
                " モ°ルモ°ル ",
                "Re：End of a Dream",
                "Help me, ERINNNNNN!!",
                "shared alias",
            ],
        },
        {"song_id": 43, "aliases": ["shared alias"]},
        {"song_id": 999, "aliases": ["LXNS only song"]},
    ]
}


class ChunithmCatalogLxnsTests(unittest.TestCase):
    def test_lxns_aliases_and_chart_versions_enhance_only_df_song_ids(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.write(directory, include_alias=True)
            catalog = ChunithmCatalog.load(directory)

        exact = catalog.resolve("モ°ルモ°ル", "MASTER")
        self.assertIsNotNone(exact)
        self.assertEqual("42", exact.song.song_id)
        self.assertEqual("Canonical Title", exact.song.title)
        self.assertEqual("CHUNITHM VERSE", exact.chart.version)
        self.assertEqual(4, len(exact.song.aliases))

        punctuation = catalog.resolve("Re:End of a Dream", "MASTER")
        self.assertIsNotNone(punctuation)
        self.assertEqual("42", punctuation.song.song_id)

        fuzzy = catalog.resolve("Help me, ERINNNNN!", "MASTER")
        self.assertIsNotNone(fuzzy)
        self.assertEqual("42", fuzzy.song.song_id)

        by_id = catalog.resolve_by_id("42", "モ°ルモ°ル", "MASTER")
        self.assertIsNotNone(by_id)
        self.assertEqual("42", by_id.song.song_id)
        self.assertIsNone(
            catalog.resolve_by_id("43", "モ°ルモ°ル", "MASTER")
        )
        self.assertIsNone(catalog.resolve("shared alias", "MASTER"))
        self.assertIsNone(catalog.resolve("LXNS only song", "MASTER"))

    def test_incomplete_lxns_pair_safely_falls_back_to_diving_fish(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            self.write(directory, include_alias=False)
            catalog = ChunithmCatalog.load(
                directory / "diving-fish-music-data.json"
            )

        canonical = catalog.resolve("Canonical Title", "MASTER")
        self.assertIsNotNone(canonical)
        self.assertEqual("CHUNITHM OLD", canonical.chart.version)
        self.assertIsNone(catalog.resolve("モ°ルモ°ル", "MASTER"))

    @staticmethod
    def write(directory: Path, *, include_alias: bool) -> None:
        (directory / "diving-fish-music-data.json").write_text(
            json.dumps(DF_SONGS, ensure_ascii=False), encoding="utf-8"
        )
        (directory / "lxns-song-list.json").write_text(
            json.dumps(LXNS_SONGS, ensure_ascii=False), encoding="utf-8"
        )
        if include_alias:
            (directory / "lxns-alias-list.json").write_text(
                json.dumps(LXNS_ALIASES, ensure_ascii=False), encoding="utf-8"
            )


if __name__ == "__main__":
    unittest.main()
