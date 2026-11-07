import importlib.util
import pathlib
import unittest

SPEC = importlib.util.spec_from_file_location("audio_ctl", pathlib.Path(__file__).with_name("audio-ctl.py"))
audio_ctl = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audio_ctl)

HA = "🏠 Home Assistant"
SOUNDBAR = "54158976cda4"
SOUNDMIC = "f44efd21926d"


def entry(item_id, title="x", added_by=HA, handle=None):
    return {"item": {"itemId": item_id, "title": title, "handle": handle, "playbackType": "PODCAST"},
            "addedBy": added_by}


def target(target_id, entries=(), at=-1, playing=False, linked=False, problem=None, repeat="OFF"):
    return {"target": {"id": target_id, "label": target_id, "kind": "SPEAKER"}, "entries": list(entries),
            "at": at, "playing": playing, "linked": linked, "problem": problem, "repeat": repeat}


def targets(*states):
    return {"targets": list(states)}


BROWN = entry("sound:brown-noise", "Brown noise")
JAZZ = entry("Dx5qFachd3A", "Jazz station")
SHOW = entry("abc123abc12", "Episode 4", handle="https://www.youtube.com/watch?v=abc123abc12")
PHONE = entry("zzz", "Bluey", added_by="Dewi's Pixel 7")


class FakeTotum:
    def __init__(self, *states):
        self.state = targets(*states)
        self.calls = []
        self.queue_answer = None
        self.unreachable = False

    def targets(self):
        if self.unreachable:
            raise audio_ctl.TotumError("Totum is unreachable: refused")
        return self.state

    def queue(self, target_id, body):
        self.calls.append(("queue", target_id, body))
        if self.queue_answer is not None:
            return self.queue_answer
        return target(target_id, [{"item": body["items"][0], "addedBy": HA}], at=0, playing=True, linked=True)

    def control(self, target_id, body):
        self.calls.append(("control", target_id, body))
        return {}


class StatusTest(unittest.TestCase):
    def test_brown_noise_playing_keeps_the_shape_home_assistant_reads(self):
        status = audio_ctl.status_from(targets(target(SOUNDBAR, [BROWN], 0, True, True)), "soundbar")

        self.assertEqual("playing", status["state"])
        self.assertEqual("brown-noise", status["want"])
        self.assertIsNone(status["media"])
        self.assertEqual("soundbar", status["speaker"])
        self.assertEqual(["soundbar", "soundmic"], status["speakers"])
        self.assertEqual(["brown-noise"], status["sounds"])
        self.assertEqual(["jazz"], status["stations"])
        self.assertTrue(status["link"])
        self.assertTrue(status["bonded"])
        self.assertFalse(status["starting"])
        self.assertIsNone(status["last_error"])
        self.assertEqual(
            {"state", "starting", "want", "media", "speaker", "speakers", "sounds", "stations", "last_error",
             "bonded", "link", "keepalive", "holding_silence"}, set(status))

    def test_the_jazz_station_is_media_with_its_station_name(self):
        status = audio_ctl.status_from(targets(target(SOUNDMIC, [JAZZ], 0, True)), "soundmic")

        self.assertEqual("playing", status["state"])
        self.assertIsNone(status["want"])
        self.assertEqual("jazz", status["media"]["station"])
        self.assertEqual("https://www.youtube.com/watch?v=Dx5qFachd3A", status["media"]["url"])

    def test_a_show_is_media_without_a_station(self):
        status = audio_ctl.status_from(targets(target(SOUNDBAR, [SHOW], 0, True)), "soundbar")

        self.assertEqual("Episode 4", status["media"]["title"])
        self.assertNotIn("station", status["media"])
        self.assertEqual("https://www.youtube.com/watch?v=abc123abc12", status["media"]["url"])

    def test_it_reports_the_speaker_that_holds_a_sound_of_ours_even_if_another_is_selected(self):
        status = audio_ctl.status_from(
            targets(target(SOUNDBAR), target(SOUNDMIC, [JAZZ], 0, True)), "soundbar")

        self.assertEqual("soundmic", status["speaker"])
        self.assertEqual("jazz", status["media"]["station"])

    def test_what_a_phone_queued_is_not_ours(self):
        status = audio_ctl.status_from(targets(target(SOUNDBAR, [PHONE], 0, True)), "soundbar")

        self.assertEqual("stopped", status["state"])
        self.assertIsNone(status["want"])
        self.assertIsNone(status["media"])

    def test_a_paused_or_stopped_sound_is_stopped_and_a_request_in_flight_is_starting(self):
        idle = targets(target(SOUNDBAR, [BROWN], 0, False))

        self.assertEqual("stopped", audio_ctl.status_from(idle, "soundbar")["state"])
        self.assertEqual("starting", audio_ctl.status_from(idle, "soundbar", starting=True)["state"])

    def test_a_speaker_totum_does_not_list_is_not_bonded(self):
        status = audio_ctl.status_from(targets(target(SOUNDBAR)), "soundmic")

        self.assertFalse(status["bonded"])
        self.assertEqual("soundmic", status["speaker"])

    def test_totums_problem_is_the_last_error_while_we_hold_an_entry(self):
        failing = targets(target(SOUNDMIC, [JAZZ], -1, False, problem="❌ Couldn't play Jazz station: gone"))

        self.assertEqual("❌ Couldn't play Jazz station: gone",
                         audio_ctl.status_from(failing, "soundmic")["last_error"])
        phones = targets(target(SOUNDMIC, [PHONE], -1, False, problem="❌ Couldn't play Bluey: gone"))
        self.assertIsNone(audio_ctl.status_from(phones, "soundmic")["last_error"])

    def test_the_status_is_cached_and_an_action_forgets_it(self):
        totum = FakeTotum(target(SOUNDBAR, [BROWN], 0, True))
        now = [100.0]
        adapter = audio_ctl.Adapter(totum, clock=lambda: now[0])
        reads = []
        original = totum.targets
        totum.targets = lambda: reads.append(1) or original()

        adapter.status()
        adapter.status()
        self.assertEqual(1, len(reads))
        now[0] += audio_ctl.STATUS_TTL + 1
        adapter.status()
        self.assertEqual(2, len(reads))
        adapter.handle("/stop", {})
        adapter.status()
        self.assertEqual(3, len(reads))

    def test_an_unreachable_totum_is_said_not_swallowed(self):
        totum = FakeTotum()
        totum.unreachable = True

        status = audio_ctl.Adapter(totum).status()

        self.assertEqual("stopped", status["state"])
        self.assertIn("unreachable", status["last_error"])


class UrlsTest(unittest.TestCase):
    def adapter(self, *states, resolve=None):
        totum = FakeTotum(*states)
        return audio_ctl.Adapter(totum, resolve=resolve or (lambda c, m, i=None: {})), totum

    def play(self, adapter, path, query=None):
        code, body = adapter.handle(path, query or {})
        adapter.run_pending()
        return code, body

    def test_play_queues_brown_noise_as_a_file_on_the_pi_that_repeats_this_one(self):
        adapter, totum = self.adapter(target(SOUNDBAR), target(SOUNDMIC))

        code, body = self.play(adapter, "/play")

        self.assertEqual((200, {"state": "starting", "sound": "brown-noise"}), (code, body))
        [(kind, target_id, request)] = totum.calls
        self.assertEqual(("queue", SOUNDBAR), (kind, target_id))
        self.assertEqual("PLAY", request["mode"])
        self.assertEqual("ONE", request["repeat"])
        self.assertEqual({"id": "home-assistant", "label": HA}, request["from"])
        [item] = request["items"]
        self.assertEqual("sound:brown-noise", item["itemId"])
        self.assertEqual("PODCAST", item["playbackType"])
        self.assertEqual("path=file:///sounds/brown-noise.opus", item["handle"])

    def test_play_jazz_on_the_soundmic_queues_the_station_without_repeat(self):
        adapter, totum = self.adapter(target(SOUNDBAR), target(SOUNDMIC))

        code, body = self.play(adapter, "/play/jazz", {"speaker": ["soundmic"]})

        self.assertEqual((200, {"state": "starting", "station": "jazz"}), (code, body))
        [(_, target_id, request)] = totum.calls
        self.assertEqual(SOUNDMIC, target_id)
        self.assertEqual("OFF", request["repeat"])
        item = request["items"][0]
        self.assertEqual("Dx5qFachd3A", item["itemId"])
        self.assertEqual("LIVE", item["contentKind"])
        self.assertEqual("VIDEO", item["playbackType"])
        self.assertEqual("https://www.youtube.com/watch?v=Dx5qFachd3A", item["handle"])

    def test_play_show_resolves_through_firetv_ctl_and_queues_what_it_returns(self):
        asked = []

        def resolve(channel, mode, vid_id=None):
            asked.append((channel, mode, vid_id))
            return {"id": "abc123abc12", "title": "Episode 4", "channel": "msrachel",
                    "url": "https://www.youtube.com/watch?v=abc123abc12"}

        adapter, totum = self.adapter(target(SOUNDBAR), target(SOUNDMIC), resolve=resolve)

        code, body = self.play(adapter, "/play-show/msrachel", {"mode": ["latest"], "id": ["abc123abc12"]})

        self.assertEqual((200, {"state": "starting", "channel": "msrachel", "mode": "latest"}), (code, body))
        self.assertEqual([("msrachel", "latest", "abc123abc12")], asked)
        [(_, target_id, request)] = totum.calls
        self.assertEqual(SOUNDBAR, target_id)
        self.assertEqual("OFF", request["repeat"])
        self.assertEqual("Episode 4", request["items"][0]["title"])
        self.assertEqual("https://www.youtube.com/watch?v=abc123abc12", request["items"][0]["handle"])

    def test_a_second_show_tap_while_starting_is_ignored(self):
        adapter, totum = self.adapter(target(SOUNDBAR))
        adapter.handle("/play-show/msrachel", {})

        code, body = adapter.handle("/play-show/msrachel", {})

        self.assertEqual("already-starting", body["state"])

    def test_stop_stops_a_sound_of_ours_and_takes_it_out_of_the_queue_but_leaves_a_phones(self):
        adapter, totum = self.adapter(target(SOUNDBAR, [PHONE, BROWN], 1, True, True))

        code, body = self.play(adapter, "/stop")

        self.assertEqual((200, {"state": "stopped"}), (code, body))
        self.assertEqual([("control", SOUNDBAR, {"action": "STOP"}),
                          ("control", SOUNDBAR, {"action": "REMOVE", "index": 1})], totum.calls)

    def test_stop_leaves_alone_a_speaker_a_phone_is_playing(self):
        adapter, totum = self.adapter(target(SOUNDBAR, [PHONE], 0, True, True))

        self.play(adapter, "/stop")

        self.assertEqual([], totum.calls)

    def test_choosing_the_other_speaker_for_a_new_play_stops_the_old_one_first(self):
        adapter, totum = self.adapter(target(SOUNDBAR, [BROWN], 0, True, True), target(SOUNDMIC))

        self.play(adapter, "/play/jazz", {"speaker": ["soundmic"]})

        self.assertEqual(
            [("control", SOUNDBAR, {"action": "STOP"}),
             ("control", SOUNDBAR, {"action": "REMOVE", "index": 0})],
            totum.calls[:2])
        self.assertEqual(("queue", SOUNDMIC), totum.calls[2][:2])

    def test_a_speaker_switch_moves_what_is_playing_and_keeps_its_repeat(self):
        adapter, totum = self.adapter(target(SOUNDBAR, [BROWN], 0, True, True, repeat="ONE"), target(SOUNDMIC))

        code, body = self.play(adapter, "/speaker/soundmic")

        self.assertEqual((200, {"speaker": "soundmic"}), (code, body))
        queues = [c for c in totum.calls if c[0] == "queue"]
        self.assertEqual(SOUNDMIC, queues[0][1])
        self.assertEqual("ONE", queues[0][2]["repeat"])
        self.assertEqual("sound:brown-noise", queues[0][2]["items"][0]["itemId"])
        self.assertIn(("control", SOUNDBAR, {"action": "STOP"}), totum.calls)

    def test_playing_what_already_plays_on_that_speaker_changes_nothing(self):
        adapter, totum = self.adapter(target(SOUNDBAR, [BROWN], 0, True, True))

        self.play(adapter, "/play")

        self.assertEqual([], totum.calls)

    def test_an_older_sound_of_ours_is_removed_after_the_new_one_starts(self):
        adapter, totum = self.adapter(target(SOUNDBAR, [BROWN], 0, True, True))
        totum.queue_answer = target(SOUNDBAR, [BROWN, JAZZ], 1, True, True)

        self.play(adapter, "/play/jazz")

        self.assertEqual(("control", SOUNDBAR, {"action": "REMOVE", "index": 0}), totum.calls[-1])
        self.assertEqual(2, len(totum.calls))

    def test_an_unknown_speaker_is_a_400_and_an_unknown_sound_a_404(self):
        adapter, totum = self.adapter(target(SOUNDBAR))

        code, body = adapter.handle("/play", {"speaker": ["kitchen"]})
        self.assertEqual(400, code)
        self.assertEqual(["soundbar", "soundmic"], body["known"])
        code, body = adapter.handle("/play/whale-song", {})
        self.assertEqual(404, code)
        self.assertEqual(["brown-noise", "jazz"], body["known"])
        self.assertEqual(404, adapter.handle("/nope", {})[0])
        self.assertEqual([], totum.calls)

    def test_totum_refusing_the_play_is_the_last_error(self):
        adapter, totum = self.adapter(target(SOUNDBAR))
        totum.queue_answer = target(SOUNDBAR, [JAZZ], -1, False, problem="❌ Couldn't play Jazz station: not available")

        self.play(adapter, "/play/jazz")

        self.assertEqual("❌ Couldn't play Jazz station: not available", adapter.last_error)
        self.assertEqual(1, len(totum.calls))
        self.assertEqual("❌ Couldn't play Jazz station: not available", adapter.status()["last_error"])

    def test_totum_being_down_is_the_last_error_and_clears_on_the_next_good_play(self):
        adapter, totum = self.adapter(target(SOUNDBAR))
        totum.unreachable = True
        self.play(adapter, "/play")
        self.assertIn("unreachable", adapter.last_error)

        totum.unreachable = False
        self.play(adapter, "/play")
        self.assertIsNone(adapter.last_error)

    def test_keepalive_is_accepted_and_does_nothing(self):
        adapter, totum = self.adapter(target(SOUNDBAR))

        code, body = adapter.handle("/keepalive/on", {})

        self.assertEqual((200, False), (code, body["keepalive"]))
        self.assertEqual([], totum.calls)

    def test_the_item_for_a_show_without_an_id_takes_it_from_the_address(self):
        item = audio_ctl.show_item({"url": "https://www.youtube.com/watch?v=qqq111qqq11", "title": "T"})

        self.assertEqual("qqq111qqq11", item["itemId"])


if __name__ == "__main__":
    unittest.main()
