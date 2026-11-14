import importlib.util
import os
import unittest

spec = importlib.util.spec_from_file_location("discover_dlna", os.path.join(os.path.dirname(__file__), "discover-dlna.py"))
discover = importlib.util.module_from_spec(spec)
spec.loader.exec_module(discover)

REPLY = (
    "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.0.211:1285/\r\n"
    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:x::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
)

DESCRIPTION = (
    '<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0"><device>'
    "<friendlyName>Kodi (192.168.0.211)</friendlyName></device></root>"
)


class DiscoverTest(unittest.TestCase):
    def test_a_reply_gives_where_its_description_is_whatever_the_case_of_the_header(self):
        self.assertEqual("http://192.168.0.211:1285/", discover.locations(REPLY)["location"])
        self.assertEqual("http://x/", discover.locations("HTTP/1.1 200 OK\r\nLocation: http://x/\r\n")["location"])

    def test_the_friendly_name_comes_out_of_a_description(self):
        self.assertEqual("Kodi (192.168.0.211)", discover.friendly_name(DESCRIPTION))

    def test_a_line_to_paste_has_no_separators_in_the_name(self):
        self.assertEqual("Kodi 1=http://h:1/", discover.entry("Kodi;1", "http://h:1/"))
        self.assertEqual("Living room TV=http://h:1/d.xml", discover.entry("Living room=TV".replace("=", " "), "http://h:1/d.xml"))


if __name__ == "__main__":
    unittest.main()
