"""Evaluate a frozen issued V1 research forecast using a later verified snapshot."""
import argparse
import json
from pathlib import Path

import pandas as pd

from train_rank import dump, sha, summarize


def evaluate(run, observed, out):
    issued = json.loads((run / "issued.json").read_text(encoding="utf-8"))
    if issued["schema"] != "local.stock.rank.output.v3":
        raise ValueError("FORECAST_SCHEMA")
    for key, file in [("model_sha256", "selected.joblib"), ("input_sha256", "latest_features.csv")]:
        if issued[key] != sha(run / file):
            raise ValueError("FORECAST_INTEGRITY")
    now = pd.Timestamp.now(tz="Asia/Ho_Chi_Minh")
    issue = pd.Timestamp(issued["issued_at"])
    end = pd.Timestamp(issued["target_end"])
    from predict_rank import format_batch
    import joblib
    expected = format_batch(joblib.load(run / "selected.joblib"), pd.read_csv(run / "latest_features.csv"))
    if issued["results"] != expected["results"] or issued["universe_hash"] != expected["universe_hash"]:
        raise ValueError("ISSUED_RESULTS_MISMATCH")
    result = dict(issued_sha256=sha(run / "issued.json"), production_eligible=False, timing=issued["timing"])
    if issue > now or issue.date() > end.date():
        result["status"] = "EXCLUDED_ISSUANCE_TIME"
    elif now.date() <= end.date():
        result["status"] = "PENDING_TARGET_MATURITY"
    else:
        protocol = json.loads((observed.parent / "protocol.json").read_text(encoding="utf-8"))
        actual_file = observed / "dataset.csv"
        if sha(actual_file) != json.loads((observed / "eda.json").read_text())["dataset_sha256"]:
            raise ValueError("OBSERVED_DATASET_INTEGRITY")
        actual = pd.read_csv(actual_file)
        actual = actual[actual.cutoff.eq(issued["cutoff"]) & actual.target_end.eq(issued["target_end"])
                        & actual.universe_hash.eq(issued["universe_hash"]) & actual.label_relative.notna()]
        frozen = pd.read_csv(run / "latest_features.csv").set_index("ticker")
        if len(actual) != issued["universe_size"] or not actual.set_index("ticker").provider.sort_index().equals(frozen.provider.sort_index()):
            result["status"] = "PENDING_MATCHING_REALIZED_COHORT"
        elif actual.label_relative.nunique() < 2:
            result["status"] = "REALIZED_ONE_CLASS"
        else:
            audit = actual.merge(pd.DataFrame(issued["results"]), on="ticker", validate="one_to_one")
            threshold = 0 if issued["model"] == "MOM2_FIXED" else .5
            audit["decision"] = audit.score.gt(threshold).astype(int)
            audit["y"] = audit.label_relative
            result.update(status="REALIZED_CURRENT_VINTAGE_RESEARCH", metrics=summarize(audit, False),
                          observed_snapshot_sha256=protocol["snapshot_sha256"],
                          observed_dataset_sha256=sha(actual_file))
    dump(out, result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for key in ("run", "observed", "out"):
        parser.add_argument("--" + key, required=True, type=Path)
    args = parser.parse_args()
    evaluate(args.run, args.observed, args.out)
