import importlib.util
import json
import os
import pathlib
import unittest

HERE = pathlib.Path(__file__).parent
FIXTURES = HERE / "fixtures"
SPEC = importlib.util.spec_from_file_location("audio_ctl", HERE / "audio-ctl.py")
audio_ctl = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audio_ctl)

REGENERATE = os.environ.get("TOTUM_REGEN_FIXTURES") == "1"
SOUNDBAR = "54158976cda4"
SOUNDMIC = "f44efd21926d"
SHOW = {"id": "abc123abc12", "title": "Episode 4", "channel": "msrachel",
        "url": "https://www.youtube.com/watch?v=abc123abc12"}


def fixture(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def empty_answer(target_id, **fields):
    return {"target": {"id": target_id, "label": target_id, "kind": "SPEAKER"}, "entries": [], "at": -1,
            "playing": False, "linked": False, "problem": None, "starting": False, "trouble": None, **fields}


class Recorder:
    def __init__(self, targets_answer):
        self.targets_answer = targets_answer
        self.calls = []

    def targets(self):
        return self.targets_answer

    def queue(self, target_id, body):
        self.calls.append({"path": f"/targets/queue?id={target_id}", "body": body})
        return empty_answer(target_id)

    def control(self, target_id, body):
        self.calls.append({"path": f"/targets/control?id={target_id}", "body": body})
        return {}


def requests_made():
    made = {}
    for name, path, query in (("brown-noise", "/play", {}),
                              ("jazz", "/play/jazz", {"speaker": ["soundmic"]}),
                              ("show", "/play-show/msrachel", {"mode": ["latest"]})):
        recorder = Recorder({"targets": [empty_answer(SOUNDBAR), empty_answer(SOUNDMIC)]})
        adapter = audio_ctl.Adapter(recorder, resolve=lambda channel, mode, vid_id=None: SHOW, sleep=lambda s: None)
        adapter.handle(path, query)
        adapter.run_pending()
        [call] = recorder.calls
        made[name] = call
    return made


class WireShapeTest(unittest.TestCase):
    def test_the_requests_the_adapter_sends_are_the_ones_the_kotlin_side_was_checked_against(self):
        made = requests_made()
        if REGENERATE:
            (FIXTURES / "adapter-requests.json").write_text(
                json.dumps(made, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

        self.assertEqual(fixture("adapter-requests.json"), made,
                         "the adapter's requests changed: run with TOTUM_REGEN_FIXTURES=1, then run "
                         "HermesAdapterContractTest in :cli so the real routes accept them")

    def test_every_request_marks_itself_as_home_assistant_and_asks_to_hear_about_a_slow_start(self):
        for name, call in requests_made().items():
            with self.subTest(name):
                self.assertEqual({"id": "home-assistant", "label": "🏠 Home Assistant"}, call["body"]["from"])
                self.assertTrue(call["body"]["understandsStarting"])
                self.assertEqual("PLAY", call["body"]["mode"])


class RealTargetsAnswerTest(unittest.TestCase):
    def test_brown_noise_playing_as_the_real_route_reports_it_is_home_assistants_playing_state(self):
        status = audio_ctl.status_from(fixture("targets-brown-noise-playing.json"), "soundbar")

        self.assertEqual("playing", status["state"])
        self.assertEqual("brown-noise", status["want"])
        self.assertIsNone(status["media"])
        self.assertEqual("soundbar", status["speaker"])
        self.assertTrue(status["link"])
        self.assertTrue(status["bonded"])
        self.assertIsNone(status["last_error"])

    def test_a_speaker_the_pi_could_not_reach_reports_stopped_with_totums_own_reason(self):
        status = audio_ctl.status_from(fixture("targets-jazz-speaker-unreachable.json"), "soundmic")

        self.assertEqual("stopped", status["state"])
        self.assertIsNone(status["want"])
        self.assertEqual("jazz", status["media"]["station"])
        self.assertIn("isn't connected", status["last_error"])
        self.assertFalse(status["link"])

    def test_the_other_speaker_playing_our_sound_is_reported_even_when_a_different_one_is_selected(self):
        status = audio_ctl.status_from(fixture("targets-brown-noise-playing.json"), "soundmic")

        self.assertEqual("soundbar", status["speaker"])
        self.assertEqual("playing", status["state"])

    def test_a_stop_against_the_real_answer_stops_and_removes_only_what_was_ours(self):
        recorder = Recorder(fixture("targets-brown-noise-playing.json"))
        adapter = audio_ctl.Adapter(recorder, sleep=lambda s: None)

        adapter.handle("/stop", {})
        adapter.run_pending()

        controls = [c["body"] for c in recorder.calls]
        self.assertEqual({"action": "STOP"}, controls[0])
        removed = [c for c in controls if c["action"] == "REMOVE"]
        self.assertEqual(["sound:brown-noise"], [c["itemId"] for c in removed])
        self.assertTrue(all(isinstance(c["index"], int) for c in removed))

    def test_the_fields_the_adapter_reads_are_all_present_on_the_real_answer(self):
        read = {"target", "entries", "at", "playing", "linked", "problem", "starting", "trouble"}
        entry_read = {"item", "addedBy", "loop"}
        item_read = {"itemId", "title", "handle", "mediaUrl"}
        for name in ("targets-brown-noise-playing.json", "targets-jazz-speaker-unreachable.json"):
            for state in fixture(name)["targets"]:
                with self.subTest(name + " " + state["target"]["id"]):
                    self.assertLessEqual(read, set(state))
                    for entry in state["entries"]:
                        self.assertLessEqual(entry_read, set(entry))
                        self.assertLessEqual(item_read, set(entry["item"]))


if __name__ == "__main__":
    unittest.main()
