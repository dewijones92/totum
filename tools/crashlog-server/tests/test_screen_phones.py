import json
import os
import sys
import tempfile
import time
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

DATA = tempfile.mkdtemp()
os.environ.setdefault("CRASHLOG_DATA", DATA)
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "app"))

import main  # noqa: E402

NOW = datetime(2026, 11, 14, 9, 0, tzinfo=timezone.utc)
PIXEL_7 = "11111111-aaaa-bbbb-cccc-111111111111"
PIXEL_4A = "22222222-aaaa-bbbb-cccc-222222222222"


def url(last_octet: int = 42) -> str:
    return f"http://192.168.0.{last_octet}:8765/screen#key=abcdef12"


def entry(minutes_ago: float, playing: bool = False, model: str | None = "Pixel", title: str | None = None,
          address: str | None = None) -> dict:
    return {
        "url": address or url(),
        "at": (NOW - timedelta(minutes=minutes_ago)).isoformat(),
        "model": model,
        "playing": playing,
        "title": title,
    }


class PhonesTest(unittest.TestCase):
    def setUp(self):
        for file in (main.SCREEN_FILE, main.SCREENS_FILE):
            if file.exists():
                file.unlink()

    def put(self, **phones: dict) -> None:
        main.SCREENS_FILE.write_text(json.dumps({"phones": phones}))

    def phones(self):
        return main.screen_phones(NOW)

    def decide(self, choose: bool = False):
        return main.screen_decision(self.phones(), choose)

    def test_no_phone_has_announced_so_there_is_nothing_to_go_to(self):
        self.assertEqual(("none", None), self.decide())
        self.assertIsNone(main.screen_target())
        self.assertIn("No phone yet", main.screen_page(None))

    def test_one_fresh_phone_is_gone_to_straight_away(self):
        self.put(a=entry(5, address=url(42)))

        self.assertEqual(("redirect", url(42)), self.decide())

    def test_two_fresh_phones_go_straight_to_the_one_that_is_playing(self):
        self.put(a=entry(5, playing=False, address=url(42)), b=entry(9, playing=True, address=url(43)))

        self.assertEqual(("redirect", url(43)), self.decide())

    def test_two_fresh_phones_both_playing_are_a_choice(self):
        self.put(a=entry(5, playing=True), b=entry(9, playing=True))

        self.assertEqual("choose", self.decide()[0])

    def test_two_fresh_idle_phones_are_a_choice(self):
        self.put(a=entry(5), b=entry(9))

        kind, listed = self.decide()
        self.assertEqual("choose", kind)
        self.assertEqual(2, len(listed))

    def test_a_stale_phone_does_not_stand_in_the_way_of_the_fresh_one(self):
        self.put(old=entry(60 * 24, address=url(50)), fresh=entry(10, address=url(42)))

        self.assertEqual(("redirect", url(42)), self.decide())

    def test_only_a_stale_phone_is_shown_rather_than_gone_to(self):
        self.put(old=entry(60 * 24))

        kind, listed = self.decide()
        self.assertEqual("choose", kind)
        self.assertFalse(listed[0]["fresh"])
        self.assertIn("heard 24 h ago", main.screen_page(listed))

    def test_the_switch_phone_link_always_shows_the_chooser(self):
        self.put(a=entry(5))

        self.assertEqual("choose", self.decide(choose=True)[0])

    def test_the_bookmark_target_is_only_ever_a_redirect(self):
        self.put(a=entry(5, address=url(42)))
        self.assertEqual(url(42), main.screen_target())

        self.put(a=entry(5), b=entry(6))
        self.assertIsNone(main.screen_target())

    def test_freshness_ends_after_seventy_five_minutes(self):
        self.put(edge=entry(75), over=entry(76))
        by_id = {p["id"]: p for p in self.phones()}

        self.assertTrue(by_id["edge"]["fresh"])
        self.assertFalse(by_id["over"]["fresh"])

    def test_the_list_is_fresh_first_then_playing_then_the_most_recent(self):
        self.put(
            stale_playing=entry(600, playing=True),
            fresh_idle_new=entry(2),
            fresh_playing=entry(20, playing=True),
            fresh_idle_old=entry(50),
        )

        self.assertEqual(
            ["fresh_playing", "fresh_idle_new", "fresh_idle_old", "stale_playing"],
            [p["id"] for p in self.phones()],
        )

    def test_the_chooser_page_escapes_a_model_and_a_title(self):
        self.put(a=entry(1, model="<b>Evil</b>", title="<script>alert(1)</script>"), b=entry(2))

        page = main.screen_page(self.phones())

        self.assertNotIn("<b>Evil", page)
        self.assertNotIn("<script>alert", page)
        self.assertIn("&lt;b&gt;Evil&lt;/b&gt;", page)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", page)

    def test_the_chooser_links_each_phone_and_says_what_it_is_doing(self):
        self.put(a=entry(1, playing=True, model="Pixel 7", title="Peep Show", address=url(42)),
                 b=entry(2, model="Pixel 4a", address=url(43)))

        page = main.screen_page(self.phones())

        self.assertIn(f'href="{url(42)}"', page)
        self.assertIn(f'href="{url(43)}"', page)
        self.assertIn("Playing · Peep Show", page)
        self.assertIn("Not playing", page)
        self.assertIn("Pixel 7", page)

    def test_a_phone_with_no_model_is_called_phone(self):
        self.put(a=entry(1, model=None), b=entry(2))

        self.assertIn("📱 Phone</a>", main.screen_page(self.phones()))

    def test_an_address_planted_in_the_file_is_never_listed(self):
        self.put(evil=entry(1, address="http://203.0.113.9:8765/screen#key=abcdef12"), ok=entry(2))

        self.assertEqual(["ok"], [p["id"] for p in self.phones()])

    def test_an_entry_whose_time_cannot_be_read_is_skipped(self):
        bad = entry(1)
        bad["at"] = "yesterday-ish"
        self.put(bad=bad, ok=entry(2))

        self.assertEqual(["ok"], [p["id"] for p in self.phones()])

    def test_a_corrupt_file_reads_as_empty_and_the_next_announce_repairs_it(self):
        main.SCREENS_FILE.write_text("{ nope")

        self.assertEqual([], self.phones())
        self.assertTrue(main.remember_screen({"kind": "screen", "url": url(), "installId": PIXEL_7}))
        self.assertEqual([PIXEL_7], [p["id"] for p in main.screen_phones()])

    def test_the_single_phone_file_of_an_older_build_is_read_as_one_legacy_phone(self):
        main.SCREEN_FILE.write_text(json.dumps({"url": url(42), "at": NOW.isoformat()}))

        self.assertEqual(["legacy"], [p["id"] for p in self.phones()])
        self.assertEqual(("redirect", url(42)), self.decide())

    def test_the_first_announce_from_a_real_phone_retires_the_legacy_entry(self):
        main.SCREEN_FILE.write_text(json.dumps({"url": url(99), "at": datetime.now(timezone.utc).isoformat()}))

        main.remember_screen({"kind": "screen", "url": url(42), "installId": PIXEL_7})

        self.assertEqual([PIXEL_7], [p["id"] for p in main.screen_phones()])


class AnnounceTest(unittest.TestCase):
    def setUp(self):
        for file in (main.SCREEN_FILE, main.SCREENS_FILE):
            if file.exists():
                file.unlink()

    def stored(self) -> dict:
        return json.loads(main.SCREENS_FILE.read_text())["phones"]

    def test_each_install_id_is_its_own_phone_and_a_repeat_replaces_its_own_entry(self):
        main.remember_screen({"kind": "screen", "url": url(42), "installId": PIXEL_7, "model": "Pixel 7"})
        main.remember_screen({"kind": "screen", "url": url(43), "installId": PIXEL_4A, "model": "Pixel 4a"})
        main.remember_screen({"kind": "screen", "url": url(44), "installId": PIXEL_7, "model": "Pixel 7"})

        stored = self.stored()
        self.assertEqual({PIXEL_7, PIXEL_4A}, set(stored))
        self.assertEqual(url(44), stored[PIXEL_7]["url"])
        self.assertEqual(url(43), stored[PIXEL_4A]["url"])

    def test_a_missing_or_malformed_install_id_is_one_shared_unknown_phone(self):
        main.remember_screen({"kind": "screen", "url": url(42)})
        main.remember_screen({"kind": "screen", "url": url(43), "installId": "bad id!"})
        main.remember_screen({"kind": "screen", "url": url(44), "installId": 12345})

        self.assertEqual({"unknown"}, set(self.stored()))
        self.assertEqual(url(44), self.stored()["unknown"]["url"])

    def test_a_ninth_phone_makes_the_eighth_oldest_forgotten(self):
        ids = [f"{n:08d}-0000" for n in range(1, 10)]
        for install_id in ids:
            main.remember_screen({"kind": "screen", "url": url(), "installId": install_id})
            time.sleep(0.002)

        self.assertEqual(set(ids[1:]), set(self.stored()))
        self.assertEqual(8, len(self.stored()))

    def test_text_is_cleaned_and_cut_to_length(self):
        main.remember_screen({
            "kind": "screen", "url": url(), "installId": PIXEL_7,
            "model": "M" * 100, "title": "T\x00itle\n" + "x" * 200,
        })

        phone = self.stored()[PIXEL_7]
        self.assertEqual(60, len(phone["model"]))
        self.assertTrue(len(phone["title"]) <= 120)
        self.assertNotIn("\x00", phone["title"])
        self.assertNotIn("\n", phone["title"])

    def test_a_model_or_title_that_is_not_text_is_dropped_not_stored(self):
        main.remember_screen({"kind": "screen", "url": url(), "installId": PIXEL_7, "model": 7, "title": ["a"]})

        phone = self.stored()[PIXEL_7]
        self.assertIsNone(phone["model"])
        self.assertIsNone(phone["title"])

    def test_playing_is_true_only_for_the_real_boolean(self):
        for claimed, expected in ((True, True), ("true", False), (1, False), (None, False), (False, False)):
            main.remember_screen({"kind": "screen", "url": url(), "installId": PIXEL_7, "playing": claimed})
            self.assertEqual(expected, self.stored()[PIXEL_7]["playing"], repr(claimed))

    def test_the_page_key_is_stored_only_as_part_of_the_address_the_phone_gave(self):
        main.remember_screen({"kind": "screen", "url": url(), "installId": PIXEL_7, "key": "SECRET-EXTRA"})

        self.assertNotIn("SECRET-EXTRA", main.SCREENS_FILE.read_text())

    def test_a_refused_announce_changes_nothing_on_disk(self):
        main.remember_screen({"kind": "screen", "url": url(42), "installId": PIXEL_7})
        before = main.SCREENS_FILE.read_text()

        self.assertFalse(main.remember_screen({"kind": "screen", "url": "http://8.8.8.8:8765/screen#key=abcdef12"}))
        self.assertFalse(main.remember_screen("not a dict"))

        self.assertEqual(before, main.SCREENS_FILE.read_text())


class AgoTest(unittest.TestCase):
    def test_how_long_ago_is_said_in_the_largest_whole_unit_that_reads_well(self):
        self.assertEqual("just now", main._ago(0.5))
        self.assertEqual("5 min ago", main._ago(5))
        self.assertEqual("89 min ago", main._ago(89))
        self.assertEqual("2 h ago", main._ago(120))
        self.assertEqual("3 days ago", main._ago(3 * 24 * 60 + 5))


if __name__ == "__main__":
    unittest.main()
