#!/usr/bin/env python3
import importlib.util
import os
import pathlib
import sys
import unittest

BRIDGE = pathlib.Path(__file__).resolve().parents[2] / "main/python/totum_ytdlp.py"

try:
    import yt_dlp  # noqa: F401
    from yt_dlp.extractor.youtube._video import YoutubeIE
except ImportError:
    if os.environ.get("CI"):
        raise
    print("yt-dlp is not installed for this python; skipping the caption tests", file=sys.stderr)
    sys.exit(0)


def _bridge():
    spec = importlib.util.spec_from_file_location("totum_ytdlp", BRIDGE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BRIDGE_MODULE = _bridge()


def _response(*translations, tracks=("a-ko",)):
    return {"captions": {"playerCaptionsTracklistRenderer": {
        "captionTracks": [{"languageCode": code.removeprefix("a-"), "vssId": f".{code}"} for code in tracks],
        "translationLanguages": [{"languageCode": code, "languageName": {"simpleText": code}} for code in translations],
    }}}


def _kept(response):
    return [t["languageCode"] for t in response["captions"]["playerCaptionsTracklistRenderer"]["translationLanguages"]]


class CaptionTranslationsTest(unittest.TestCase):

    def test_only_the_languages_the_app_offers_and_the_spoken_ones_are_translated(self):
        response = _response("af", "en", "fr", "ko", "pt-PT", "zh-Hans")

        dropped = BRIDGE_MODULE._prune_translation_languages([response], {"en", "fr", "pt"})

        self.assertEqual(["en", "fr", "ko", "pt-PT"], _kept(response))
        self.assertEqual(2, dropped)

    def test_a_response_without_captions_is_left_alone(self):
        self.assertEqual(0, BRIDGE_MODULE._prune_translation_languages([{}, None, {"captions": None}], {"en"}))

    def test_yt_dlp_builds_captions_only_for_the_limited_languages(self):
        self.assertTrue(BRIDGE_MODULE.configure_caption_languages(["en", "fr"]).startswith("caption translations limited"))
        self.assertEqual("limited_translations", YoutubeIE._extract_player_responses.__name__)
        response = _response("af", "en", "fr", "ko")

        BRIDGE_MODULE._limited(lambda extractor: ([response], "player"))(None)

        self.assertEqual(["en", "fr", "ko"], _kept(response))

    def test_a_failure_to_prune_never_breaks_the_extraction(self):
        BRIDGE_MODULE.configure_caption_languages(["en"])

        self.assertEqual("as is", BRIDGE_MODULE._limited(lambda extractor: "as is")(None))

    def test_limiting_twice_wraps_once(self):
        BRIDGE_MODULE.configure_caption_languages(["en"])
        first = YoutubeIE._extract_player_responses
        BRIDGE_MODULE.configure_caption_languages(["en"])
        self.assertIs(first, YoutubeIE._extract_player_responses)


if __name__ == "__main__":
    unittest.main(verbosity=2)
