"""Score a complete origin cohort before filtering any requested symbols."""
import argparse
import hashlib
import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd


def load_bundle(folder, role="selected"):
    from train_rank import sha

    if role not in ["selected", "challenger", "baseline"]:
        raise ValueError("UNKNOWN_MODEL_ROLE")
    artifact = folder / (role + ".joblib")
    inputs = folder / "latest_features.csv"
    manifest = json.loads((folder / "artifact.json").read_text(encoding="utf-8"))
    expected = manifest["model_sha256"] if role == "selected" else manifest["roles"][role]
    if sha(artifact) != expected or sha(inputs) != manifest["input_sha256"]:
        raise ValueError("ARTIFACT_OR_INPUT_HASH_MISMATCH")
    return joblib.load(artifact), pd.read_csv(inputs), manifest


def format_batch(bundle, rows, symbols=None):
    from train_rank import BASELINES, composite_score

    cutoff = bundle["fit_cutoff"]
    if rows.empty or not rows.cutoff.astype(str).str[:10].eq(cutoff).all():
        raise ValueError("ORIGIN_MISMATCH")
    if rows.ticker.duplicated().any() or not rows.universe_size.eq(len(rows)).all():
        raise ValueError("COMPLETE_ORIGIN_REQUIRED")
    cohort = "|".join(sorted(rows.ticker))
    cohort_hash = hashlib.sha256(cohort.encode()).hexdigest()
    if not rows.universe_hash.eq(cohort_hash).all() or not rows.feature_version.eq(bundle["feature_version"]).all():
        raise ValueError("ORIGIN_CONTRACT_MISMATCH")
    requested = set(rows.ticker) if symbols is None else set(symbols)
    if not requested.issubset(rows.ticker):
        raise ValueError("UNKNOWN_SYMBOL")
    method = bundle["selected"]
    if method == "MAJORITY":
        score = np.full(len(rows), bundle["majority"])
    elif method == "MOM2_FIXED":
        score = rows.return_2q.fillna(0).to_numpy()
    elif method == "MOM120_RANK":
        score = rows.ret120_rk.to_numpy()
    elif method == "FACTOR_COMPOSITE":
        score = composite_score(rows)
    elif method not in BASELINES:
        score = bundle["model"].predict_proba(rows[bundle["features"]])[:, 1]
    if not np.isfinite(score).all():
        raise ValueError("NONFINITE_SCORE")
    rank = pd.Series(score).rank(method="average", pct=True)
    results = []
    for ticker, value, pct in zip(rows.ticker, score, rank):
        if ticker in requested:
            bucket = "TOP" if pct > .8 else "BOTTOM" if pct <= .2 else "MIDDLE"
            results.append(dict(ticker=ticker, score=float(value), rank_percentile=float(pct), bucket=bucket,
                                probability_status="uncalibrated"))
    return dict(schema="local.stock.rank.output.v3", cutoff=cutoff, model=method,
                universe_hash=cohort_hash, universe_size=len(rows),
                feature_version=bundle["feature_version"], results=results,
                production_eligible=False, replace_llm=False)


def main():
    from train_rank import dump

    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, required=True, help="Trusted V1 research artifact folder")
    parser.add_argument("--symbols", nargs="+")
    parser.add_argument("--role", choices=["selected", "challenger", "baseline"], default="selected")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    bundle, rows, _ = load_bundle(args.run, args.role)
    result = format_batch(bundle, rows, args.symbols)
    dump(args.out, result)


if __name__ == "__main__":
    main()
