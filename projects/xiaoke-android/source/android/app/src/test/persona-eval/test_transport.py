import io
import json
import time
import unittest

from transport import read_sse


def event(value):
    return ("data: " + json.dumps(value) + "\n\n").encode()


def open_stream(body):
    """Delivers the given events, then behaves like a relay that never closes the stream."""
    yield from io.BytesIO(body)
    raise AssertionError("read past the terminal event")


class TransportTest(unittest.TestCase):
    def test_stops_at_terminal_event_with_usage(self):
        body = event({"candidates": [{"content": {"parts": [{"text": "好"}]}}]}) + \
            event({"candidates": [{"finishReason": "STOP"}], "usageMetadata": {"totalTokenCount": 3}})
        events = read_sse(open_stream(body), time.monotonic() + 5)
        self.assertEqual(2, len(events))

    def test_done_and_missing_separator(self):
        self.assertEqual([], read_sse(io.BytesIO(b"data: [DONE]\n\n"), time.monotonic() + 5))
        self.assertEqual([{"a": 1}], read_sse(io.BytesIO(b'data: {"a": 1}'), time.monotonic() + 5))


if __name__ == "__main__":
    unittest.main()
