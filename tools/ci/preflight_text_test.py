#!/usr/bin/env python3
"""The truncation guard's own tests — every hole an adversarial review found in it, pinned.

The guard (`check_text_wraps_instead_of_truncating`) is the only thing enforcing "the app wraps,
nothing ends in three dots", and it works by pattern-matching Kotlin, which is exactly the kind of
check that silently stops matching. Three real holes were found in its first version by review and
each is a case below: a string literal blinding the comment-stripper, a second spelling of
`Ellipsis`, and a count-based allowance that let an undeclared cap replace a declared one.

Run by `preflight.py` itself (tools/ci/*_test.py), so the guard is guarded on every push.
"""
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "tools/ci/preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class CodeOnlyTest(unittest.TestCase):
    """Comments must go and code must stay — including code that neighbours a string literal."""

    def test_a_kdoc_about_a_removed_cap_is_not_read_as_a_cap(self):
        source = '/** It used to be `maxLines = 1`, capped at TextOverflow.Ellipsis. */\nfun a() = 1\n'
        self.assertNotIn("maxLines", preflight.code_only(source))
        self.assertIn("fun a()", preflight.code_only(source))

    def test_a_mime_literal_does_not_swallow_the_code_after_it(self):
        """`"*/*"` opens a fake block comment; ImportExportScreen has two of them.

        Measured before the fix: the checker saw NEITHER the cap nor the ellipsis in this snippet
        and reported the file clean.
        """
        source = 'val mime = "*/*"\nText(text = "x", maxLines = 1, overflow = TextOverflow.Ellipsis)\n'
        code = preflight.code_only(source)
        self.assertIn("maxLines", code)
        self.assertIn("TextOverflow.Ellipsis", code)

    def test_a_url_literal_does_not_swallow_the_rest_of_its_line(self):
        source = 'Text(text = "see https://example.com", maxLines = 1)\n'
        self.assertIn("maxLines", preflight.code_only(source))

    def test_a_raw_string_holding_a_comment_marker_is_removed_whole(self):
        source = 'val q = """a // b */ c"""\nText(maxLines = 2)\n'
        self.assertIn("maxLines", preflight.code_only(source))


class EllipsisSpellingsTest(unittest.TestCase):
    """A ban on one literal is a ban on one spelling."""

    def test_it_catches_every_ellipsis_overflow_this_compose_version_ships(self):
        for spelling in (
            "overflow = TextOverflow.Ellipsis",
            "overflow = TextOverflow.StartEllipsis",
            "overflow = TextOverflow.MiddleEllipsis",
            "overflow = Ellipsis",  # via `import …TextOverflow.Companion.Ellipsis`
        ):
            self.assertTrue(preflight.ELLIPSIS.search(spelling), spelling)

    def test_it_does_not_fire_on_clip_which_is_what_the_description_uses(self):
        self.assertIsNone(preflight.ELLIPSIS.search("overflow = TextOverflow.Clip"))


class MaxLinesMatchingTest(unittest.TestCase):
    """The cap's VALUE is what is declared, so a swap cannot hide behind an unchanged count."""

    def test_it_reads_the_whole_cap_expression_not_just_the_keyword(self):
        found = preflight.MAXLINES.findall("maxLines = if (expanded) Int.MAX_VALUE else LINES,")
        self.assertEqual(["maxLines = if (expanded) Int.MAX_VALUE else LINES"], [f.rstrip(",") for f in found])

    def test_a_different_value_in_an_allowed_file_is_not_the_allowed_cap(self):
        allowed, _ = preflight.ALLOWED_MAXLINES[
            "app/src/main/java/com/dewijones92/totum/ui/settings/SettingsScreen.kt"
        ]
        self.assertIn("maxLines = 6", allowed)
        self.assertNotIn("maxLines = 1", allowed)

    def test_every_declared_allowance_still_exists_in_the_file_it_names(self):
        """A stale allowance is how the next undeclared cap gets in.

        This is the same assertion the guard makes at runtime, made here as well so the failure
        names the table rather than a file.
        """
        for relative, (caps, _) in preflight.ALLOWED_MAXLINES.items():
            body = preflight.code_only((ROOT / relative).read_text())
            present = [" ".join(c.split()).rstrip(",").strip() for c in preflight.MAXLINES.findall(body)]
            for cap in caps:
                self.assertIn(cap, present, f"{relative} no longer has {cap!r}")


class TheGuardPassesOnTheRealTreeTest(unittest.TestCase):
    def test_no_truncation_anywhere_in_the_app(self):
        self.assertEqual(0, preflight.check_text_wraps_instead_of_truncating())


if __name__ == "__main__":
    unittest.main()
