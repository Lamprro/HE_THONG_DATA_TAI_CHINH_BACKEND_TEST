import unittest

from market_stage_batch import around_bad_prices, payload_fingerprint


class MarketStagingTests(unittest.TestCase):
    def test_transport_metadata_does_not_hide_price_or_source_changes(self):
        source = {"provider": "vndirect", "data": [{"date": "2021-01-04", "close": 10}],
                  "elapsed_ms": 5, "retrieved_at": "first"}
        self.assertEqual(payload_fingerprint(source), payload_fingerprint(source | {
            "elapsed_ms": 20, "retrieved_at": "second"
        }))
        self.assertNotEqual(payload_fingerprint(source), payload_fingerprint(source | {
            "data": [{"date": "2021-01-04", "close": 11}]
        }))
        self.assertNotEqual(payload_fingerprint(source), payload_fingerprint(source | {"provider": "other"}))

    def test_retries_skip_flagged_dates_without_altering_payload(self):
        raw = [{"date": "2022-09-13", "open_price": 18.25, "close_price": 19.5,
                "low_price": 19.15, "high_price": 19.5}]
        ranges, removed = around_bad_prices(raw, "2022-09-01", "2022-09-30")
        self.assertEqual(removed, ["2022-09-13"])
        self.assertEqual(ranges, [("2022-09-01", "2022-09-12"), ("2022-09-14", "2022-09-30")])
        self.assertEqual(raw[0]["open_price"], 18.25)
