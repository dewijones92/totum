#!/usr/bin/env python3
import argparse
import http.server
import os
import re
import sys
import time


class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stdout.write("%s ranges %s %s\n" % (time.strftime("%H:%M:%S"), self.address_string(), fmt % args))
        sys.stdout.flush()

    def send_head(self):
        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            self.send_error(404)
            return None
        size = os.path.getsize(path)
        spec = self.headers.get("Range")
        match = re.match(r"bytes=(\d*)-(\d*)$", spec or "")
        handle = open(path, "rb")
        if not match:
            self.send_response(200)
            self.send_header("Content-Length", str(size))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Type", self.guess_type(path))
            self.end_headers()
            return handle
        first, last = match.groups()
        if first == "":
            start, end = max(0, size - int(last)), size - 1
        else:
            start, end = int(first), min(int(last) if last else size - 1, size - 1)
        handle.seek(start)
        self.range_left = end - start + 1
        self.send_response(206)
        self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Content-Length", str(self.range_left))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", self.guess_type(path))
        self.end_headers()
        return handle

    def copyfile(self, source, outputfile):
        left = getattr(self, "range_left", None)
        self.range_left = None
        while True:
            chunk = source.read(65536 if left is None else min(65536, left))
            if not chunk:
                break
            outputfile.write(chunk)
            if left is not None:
                left -= len(chunk)
                if left <= 0:
                    break


def main():
    parser = argparse.ArgumentParser(description="A directory served with byte ranges, for the cast stand-ins.")
    parser.add_argument("directory")
    parser.add_argument("--port", type=int, default=8780)
    parser.add_argument("--bind", default="0.0.0.0")
    arguments = parser.parse_args()
    os.chdir(arguments.directory)
    http.server.ThreadingHTTPServer((arguments.bind, arguments.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
