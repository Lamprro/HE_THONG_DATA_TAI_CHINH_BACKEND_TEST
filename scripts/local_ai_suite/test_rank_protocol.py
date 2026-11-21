"""Unit tests (no DB, synthetic frames) for rank-v2 composite baseline and gated origins."""
import unittest

import numpy as np
import pandas as pd

from train_rank import COMPOSITE_SIGNS, composite_score, scored_origins


def cohort(cutoff, n, seed=0):
    rng = np.random.default_rng(seed)
    frame = pd.DataFrame({name: rng.random(n) for name in COMPOSITE_SIGNS})
    frame["cutoff"] = pd.Timestamp(cutoff)
    frame["ticker"] = [f"T{i:03d}" for i in range(n)]
    return frame


class CompositeScore(unittest.TestCase):
    def test_signs_order_best_and_worst_names(self):
        frame = cohort("2024-03-31", 3)
        for name, sign in COMPOSITE_SIGNS.items():
            frame[name] = [1.0, .5, 0.0] if sign > 0 else [0.0, .5, 1.0]
        score = composite_score(frame)
        self.assertEqual(list(np.argsort(score)), [2, 1, 0])
        self.assertAlmostEqual(score.max(), 1.0)

    def test_missing_feature_is_neutral_not_dropped(self):
        frame = cohort("2024-03-31", 5, seed=1)
        frame.loc[0, "ret120_rk"] = np.nan
        score = composite_score(frame)
        self.assertTrue(np.isfinite(score).all())
        self.assertEqual(len(score), 5)

    def test_rejects_mixed_origins(self):
        frame = pd.concat([cohort("2024-03-31", 4), cohort("2024-06-30", 4)])
        with self.assertRaises(ValueError):
            composite_score(frame)

    def test_does_not_read_labels(self):
        frame = cohort("2024-03-31", 6, seed=2)
        a = composite_score(frame.assign(label_relative=0, actual_return=-1.0))
        b = composite_score(frame.assign(label_relative=1, actual_return=9.0))
        np.testing.assert_array_equal(a, b)


class GatedOrigins(unittest.TestCase):
    def test_small_cohorts_are_excluded_from_gate(self):
        panel = pd.concat([cohort("2021-12-31", 25), cohort("2022-03-31", 95), cohort("2022-06-30", 100)])
        origins = sorted(panel.cutoff.unique())
        self.assertEqual(scored_origins(panel, origins), [pd.Timestamp("2022-03-31"), pd.Timestamp("2022-06-30")])


if __name__ == "__main__":
    unittest.main()
