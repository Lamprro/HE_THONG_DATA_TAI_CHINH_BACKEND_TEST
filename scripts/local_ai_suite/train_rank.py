"""Research-only H1 comparison on an immutable Java snapshot."""
import argparse
import hashlib
import json
import platform
from importlib.metadata import version
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from scipy.stats import spearmanr
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import balanced_accuracy_score, recall_score, roc_auc_score
from sklearn.model_selection import GridSearchCV
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from threadpoolctl import threadpool_limits

RAW = ["return_1q", "return_2q", "return_4q", "ret120", "volatility_1q",
       "downside_volatility_1q", "log_avg_volume_1q", "price_to_ma50",
       "price_to_ma200", "max_drawdown_1q"]
RANK = [name + "_rk" for name in RAW]
BASELINES = ["MAJORITY", "MOM2_FIXED", "MOM120_RANK", "FACTOR_COMPOSITE"]
# Unfitted textbook prior (low-volatility/low-drawdown + medium-term momentum). Signs are fixed in
# code, never estimated: rank-v2 adds it as a strong, explainable baseline the ML challenger must beat.
COMPOSITE_SIGNS = {"max_drawdown_1q_rk": 1, "volatility_1q_rk": -1, "downside_volatility_1q_rk": -1,
                   "ret120_rk": 1, "return_2q_rk": 1, "price_to_ma200_rk": 1}
# Gate/tuning origins: only cohorts close to the deployed universe size. Early origins held a much
# smaller cohort (25 of 100 names), i.e. a different population with tiny training sets.
MIN_COHORT_FRACTION = .9
PROTOCOL_VERSION = "rank-v3"
CASE_B_FEATURES = ["npat_yoy", "revenue_yoy", "debt_equity", "roe_ttm", "roa_ttm"]
VARIANTS = {"V0": ("quarter", "label_absolute", RAW),
            "V1": ("quarter", "label_relative", RANK),
            "V2": ("month", "label_relative", RANK),
            "V3": ("month", "label_relative", RANK + [name + "_rk" for name in CASE_B_FEATURES])}
DEV_END = pd.Timestamp("2024-06-30")
RETRO_START = pd.Timestamp("2025-01-01")
RETRO_END = pd.Timestamp("2026-09-30")
GATE = {"auc_gain": .02, "min_ic_t": 2, "min_win_fraction": .55, "min_recall": .45}


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def dump(path, obj):
    path.write_text(json.dumps(obj, indent=2, allow_nan=False), encoding="utf-8")


def case_b(panel, path):
    """Lag assumption for historical research. Ambiguous statement identities are excluded."""
    panel = panel.copy()
    facts = pd.read_csv(path, parse_dates=["end_date"])
    facts = facts[facts.currency.eq("VND") & facts.unit_scale.gt(0)].copy()
    facts["amount"] = facts.value * facts.unit_scale
    facts["quarter"] = facts.end_date.dt.to_period("Q")
    facts = facts[facts.period_type.str.fullmatch("Q[1-4]|FY", na=False)]
    facts["annual"] = facts.period_type.eq("FY")
    if "audit_status" in facts:
        facts = facts[~facts.annual | facts.audit_status.eq("AUDITED")].copy()
    else:
        facts = facts[~facts.annual].copy()
    facts["available"] = facts.end_date + pd.to_timedelta(np.where(facts.annual, 90, 45), unit="D")
    # Actual publication, when present, can delay but never accelerate the proxy.
    pub = pd.to_datetime(facts.source_published_at, utc=True, errors="coerce", format="mixed")
    pub = pub.dt.tz_convert("Asia/Ho_Chi_Minh").dt.tz_localize(None).dt.normalize() + pd.Timedelta(days=1)
    facts["available"] = pd.concat([facts.available, pub], axis=1).max(axis=1)
    keys = ["ticker", "quarter", "item_code", "annual"]
    facts = facts[~facts.duplicated(keys, keep=False)]
    histories = {ticker: g.set_index(["quarter", "item_code", "annual"]) for ticker, g in facts.groupby("ticker")}
    features, provenance = [], []
    for row in panel.itertuples():
        h = histories.get(row.ticker)
        values, used = {}, {}
        cutoff = row.cutoff
        q = (cutoff - pd.Timedelta(days=45)).to_period("Q")
        if cutoff < q.end_time.normalize() + pd.Timedelta(days=45):
            q -= 1
        if h is not None:
            def lookup(period, code, annual=False):
                if (period, code, annual) in h.index:
                    item = h.loc[(period, code, annual), :]
                    if item.available <= cutoff:
                        return item
                return None
            for name, code in [("npat_yoy", "NET_PROFIT_AFTER_TAX"), ("revenue_yoy", "NET_SALES")]:
                items = [lookup(q - i, code) for i in range(5)]
                if all(item is not None for item in items):
                    recent, prior = items[0], items[-1]
                    annual_recent, annual_prior = lookup(q, code, True), lookup(q - 4, code, True)
                    if annual_recent is not None and annual_prior is not None:
                        recent, prior = annual_recent, annual_prior
                    if recent.report_scope == prior.report_scope and recent.period_basis == prior.period_basis and prior.amount != 0:
                        values[name] = float(np.clip((recent.amount - prior.amount) / abs(prior.amount), -3, 3))
                        used[name] = [recent, prior, *items[1:-1]]
            debt, equity = lookup(q, "LIABILITIES"), lookup(q, "OWNERS_EQUITY")
            if debt is not None and equity is not None and debt.statement_id == equity.statement_id and equity.amount > 0:
                values["debt_equity"] = debt.amount / equity.amount
                used["debt_equity"] = [debt, equity]
            income = [lookup(q - i, "NET_PROFIT_AFTER_TAX") for i in range(4)]
            if all(item is not None and item.period_basis in ("STANDALONE_QUARTER", "UNKNOWN", "POINT_IN_TIME")
                   and item.report_scope in ("CONSOLIDATED", "UNKNOWN") for item in income):
                ttm = sum(item.amount for item in income)
                for name, denominator in [("roe_ttm", equity), ("roa_ttm", lookup(q, "TOTAL_ASSETS"))]:
                    if denominator is not None and denominator.report_scope in ("CONSOLIDATED", "UNKNOWN") and denominator.amount > 0:
                        values[name] = ttm / denominator.amount
                        used[name] = [*income, denominator]
        features.append(values)
        cited = [item for series in used.values() for item in series]
        ids = sorted({str(item.item_id) for item in cited})
        provenance.append(dict(
            financial_max_assumed_availability=max((item.available for item in cited), default=pd.NaT),
            financial_item_ids="|".join(ids),
            financial_point_in_time_status="CURRENT_CANONICAL_LAG_PROXY" if ids else "NO_FEATURES",
        ))
    f = pd.DataFrame(features, index=panel.index, columns=CASE_B_FEATURES)
    for name in f:
        panel[name + "_rk"] = f.groupby(panel.cutoff)[name].rank(pct=True)
    trace = pd.DataFrame(provenance, index=panel.index)
    if trace.financial_max_assumed_availability.gt(panel.cutoff).any():
        raise ValueError("FINANCIAL_SOURCE_NOT_YET_AVAILABLE")
    panel = panel.join(trace)
    panel["feature_version"] += "-case-b-v1"
    return panel


def known(panel, origin, frequency):
    boundary = origin - pd.DateOffset(months=1) if frequency == "month" else origin
    return panel.label_available_at.lt(boundary) & panel.target_end.lt(boundary)


def estimator(features, model):
    transforms = ColumnTransformer([("numbers", Pipeline([
        ("impute", SimpleImputer(strategy="median", add_indicator=True, keep_empty_features=True)),
        ("scale", StandardScaler()),
    ]), features)])
    return Pipeline([("features", transforms), ("model", model)])


def algorithms():
    return {
        "Logistic": (LogisticRegression(class_weight="balanced", max_iter=2000), {"model__C": [.01, .1, 1]}),
        "HistGB": (HistGradientBoostingClassifier(learning_rate=.03, max_iter=100,
                   l2_regularization=1, early_stopping=False, random_state=47),
                   {"model__max_depth": [2, 3], "model__min_samples_leaf": [40, 80]}),
        "RandomForest": (RandomForestClassifier(n_estimators=120, class_weight="balanced", random_state=47, n_jobs=1),
                         {"model__max_depth": [3, 4], "model__min_samples_leaf": [20, 40]}),
    }


def composite_score(cohort):
    """Cross-sectional percentile of the fixed-sign composite; uses features of one origin only."""
    if cohort.cutoff.nunique() != 1:
        raise ValueError("SINGLE_ORIGIN_REQUIRED")
    raw = sum(sign * cohort[name].fillna(.5) for name, sign in COMPOSITE_SIGNS.items()) / len(COMPOSITE_SIGNS)
    return raw.rank(method="average", pct=True).to_numpy()


def scored_origins(panel, origins):
    """Origins whose cohort is at least MIN_COHORT_FRACTION of the largest cohort in the panel."""
    sizes = panel.groupby("cutoff").ticker.nunique()
    floor = MIN_COHORT_FRACTION * sizes.max()
    return [origin for origin in origins if sizes.get(origin, 0) >= floor]


def scores(method, train, test, y, features, options):
    if method == "MAJORITY":
        return np.full(len(test), train[y].mean()), .5
    if method == "MOM2_FIXED":
        return test.return_2q.fillna(0).to_numpy(), 0
    if method == "MOM120_RANK":
        return test.ret120_rk.to_numpy(), .5
    if method == "FACTOR_COMPOSITE":
        return composite_score(test), .5
    pipe = estimator(features, algorithms()[method][0]).set_params(**options)
    pipe.fit(train[features], train[y])
    return pipe.predict_proba(test[features])[:, 1], .5


def auc(frame):
    return float(roc_auc_score(frame.y, frame.score))


def summarize(frame, monthly):
    ics, spreads = [], []
    for _, group in frame.groupby("cutoff"):
        if group.score.nunique() > 1 and group.actual_return.nunique() > 1:
            ics.append(float(spearmanr(group.score, group.actual_return).statistic))
        def tail_mean(ascending):
            groups = group.groupby("score").actual_return.agg(["size", "mean"]).sort_index(ascending=ascending)
            target = .2 * len(group)
            weights = (target - groups["size"].cumsum().shift(fill_value=0)).clip(lower=0)
            weights = np.minimum(weights, groups["size"])
            return float((weights * groups["mean"]).sum() / target)
        spreads.append(tail_mean(False) - tail_mean(True))
    ic = float(np.mean(ics)) if ics else None
    # Newey-West variance for overlapping 63-session monthly outcomes.
    t = None
    if len(ics) > 2:
        x = np.asarray(ics) - ic
        var = np.dot(x, x) / len(x)
        if monthly:
            for lag in (1, 2):
                var += 2 * (1 - lag / 3) * np.dot(x[lag:], x[:-lag]) / len(x)
        if var > 0:
            t = float(ic / np.sqrt(var / len(x)))
    recall = recall_score(frame.y, frame.decision, labels=[0, 1], average=None, zero_division=0)
    per_origin = [auc(g) for _, g in frame.groupby("cutoff") if g.y.nunique() == 2]
    return dict(rows=len(frame), origins=frame.cutoff.nunique(), auc=auc(frame),
                origin_auc=float(np.mean(per_origin)) if per_origin else None,
                ba=float(balanced_accuracy_score(frame.y, frame.decision)),
                recall_zero=float(recall[0]), recall_one=float(recall[1]),
                rank_ic=ic, rank_ic_t=t, positive_ic_fraction=float(np.mean(np.asarray(ics) > 0)) if ics else None,
                top_minus_bottom=float(np.mean(spreads)))


def compare(candidate, baseline, monthly):
    pairs = []
    for origin, a in candidate.groupby("cutoff", sort=True):
        b = baseline[baseline.cutoff.eq(origin)]
        if a.y.nunique() == 2:
            pairs.append((a, b, auc(a) - auc(b)))
    if not pairs:
        raise ValueError("NO_COMPARABLE_ORIGINS")
    diff = np.array([d for _, _, d in pairs])
    rng = np.random.default_rng(47)
    n = len(diff)
    block = 3 if monthly else 1
    starts = rng.integers(n, size=(3000, (n + block - 1) // block))
    sample = ((starts[:, :, None] + np.arange(block)) % n).reshape(3000, -1)[:, :n]
    ci = np.quantile(diff[sample].mean(axis=1), [.025, .975])
    return dict(win_fraction=float((diff > 0).mean()), mean_origin_auc_gain=float(diff.mean()),
                paired_origin_auc_ci=[float(v) for v in ci], bootstrap_block_origins=block)


def predictions(panel, origins, methods, y, features, frequency, params, audit, stage):
    out = {method: [] for method in methods}
    for origin in origins:
        test = panel[panel.cutoff.eq(origin)]
        train = panel[known(panel, origin, frequency)]
        if len(train) < 80 or train[y].nunique() != 2 or test[y].nunique() != 2:
            continue
        audit.append(dict(stage=stage, cutoff=str(origin.date()), rows=len(train),
                          max_label=str(train.label_available_at.max().date())))
        for method in methods:
            score, boundary = scores(method, train, test, y, features, params.get(method, {}))
            p = test[["sample_id", "cutoff", "ticker", "actual_return"]].copy()
            p["y"], p["score"], p["decision"] = test[y], score, (score > boundary).astype(int)
            out[method].append(p)
    return {method: pd.concat(parts, ignore_index=True) for method, parts in out.items()}


def run_variant(name, panel, folder, workers):
    frequency, y, features = VARIANTS[name]
    monthly = frequency == "month"
    selected_panel = panel[panel[y].notna()].reset_index(drop=True)
    dev = selected_panel[selected_panel.target_end.le(DEV_END)].reset_index(drop=True)
    if name == "V3" and not dev[features[len(RANK):]].notna().any().any():
        return {"status": "BLOCKED_NO_CASE_B_FEATURES"}
    folds, origins, audit = [], [], []
    all_dev_origins = sorted(dev.cutoff.unique())
    gated = set(scored_origins(dev, all_dev_origins))
    for origin in all_dev_origins:
        if origin not in gated:
            continue
        tr = np.flatnonzero(known(dev, origin, frequency))
        te = np.flatnonzero(dev.cutoff.eq(origin))
        if len(tr) >= 80 and dev.loc[tr, y].nunique() == dev.loc[te, y].nunique() == 2:
            folds.append((tr, te))
            origins.append(origin)
    if len(folds) < 6:
        return {"status": "BLOCKED_INSUFFICIENT_DEVELOPMENT_ORIGINS", "folds": len(folds)}
    params = {}
    for method, (model, grid) in algorithms().items():
        cv = GridSearchCV(estimator(features, model), grid, scoring="roc_auc", cv=folds,
                          refit=False, n_jobs=workers, error_score="raise")
        with joblib.parallel_config(backend="threading"):
            cv.fit(dev[features], dev[y])
        params[method] = cv.best_params_
        pd.DataFrame(cv.cv_results_).to_csv(folder / (method + "_grid.csv"), index=False)
    methods = BASELINES + list(params)
    # Predictions on every development origin are kept for transparency (learning curve),
    # but only full-cohort origins are used for tuning, choosing and gating.
    development = predictions(dev, all_dev_origins, methods, y, features, frequency, params, audit, "development")
    gated_dev = {m: frame[frame.cutoff.isin(origins)] for m, frame in development.items()}
    metrics = {m: summarize(frame, monthly) for m, frame in gated_dev.items()}
    metrics_all = {m: summarize(frame, monthly) for m, frame in development.items()}
    baseline = max(BASELINES, key=lambda m: metrics[m]["origin_auc"] or 0)
    challenger = max(params, key=lambda m: metrics[m]["origin_auc"] or 0)
    a, b = metrics[challenger], metrics[baseline]
    pairing = compare(gated_dev[challenger], gated_dev[baseline], monthly)
    checks = dict(auc_gain=pairing["mean_origin_auc_gain"] >= GATE["auc_gain"],
                  ic=(a["rank_ic"] or 0) > 0 and (a["rank_ic_t"] or 0) >= GATE["min_ic_t"],
                  wins=pairing["win_fraction"] >= GATE["min_win_fraction"],
                  recalls=min(a["recall_one"], a["recall_zero"]) >= GATE["min_recall"])
    selected = challenger if all(checks.values()) else baseline
    lock = dict(protocol_version=PROTOCOL_VERSION, baseline=baseline, challenger=challenger, selected=selected,
                params=params, gate=checks, gated_development_origins=[str(o.date()) for o in origins],
                development=metrics, development_all_origins=metrics_all, paired_development=pairing)
    dump(folder / "selection_before_retrospective.json", lock)
    retro_origins = selected_panel.loc[selected_panel.target_end.between(RETRO_START, RETRO_END)
                                       & selected_panel.cutoff.gt(DEV_END), "cutoff"].unique()
    # Earlier observed retrospective outcomes may enter later fits once mature, never tuning.
    retrospective = predictions(selected_panel, sorted(retro_origins), methods, y, features,
                                frequency, params, audit, "retrospective")
    for stage, frames in [("development", development), ("retrospective", retrospective)]:
        pd.concat([p.assign(method=m) for m, p in frames.items()]).to_csv(folder / (stage + ".csv"), index=False)
    pd.DataFrame(audit).to_csv(folder / "fit_audit.csv", index=False)
    latest = panel[panel.cutoff.eq(panel.cutoff.max())]
    origin = latest.cutoff.iloc[0]
    train = selected_panel[known(selected_panel, origin, frequency)]
    for role, method in [("selected", selected), ("challenger", challenger), ("baseline", baseline)]:
        model = None
        if method not in BASELINES:
            model = estimator(features, algorithms()[method][0]).set_params(**params[method]).fit(train[features], train[y])
        bundle = dict(model=model, selected=method, features=features, fit_cutoff=str(origin.date()),
                      frequency=frequency, feature_version=latest.feature_version.iloc[0], research_only=True)
        if method == "MAJORITY":
            bundle["majority"] = float(train[y].mean())
        joblib.dump(bundle, folder / (role + ".joblib"))
    latest.to_csv(folder / "latest_features.csv", index=False)
    identity = dict(model_sha256=sha(folder / "selected.joblib"), input_sha256=sha(folder / "latest_features.csv"))
    dump(folder / "artifact.json", identity | {
        "roles": {role: sha(folder / (role + ".joblib")) for role in ("selected", "challenger", "baseline")},
        "fit_rows": len(train),
        "latest_training_label": str(train.label_available_at.max().date()),
        "fit_cutoff": str(origin.date()),
    })
    from predict_rank import format_batch
    now = pd.Timestamp.now(tz="Asia/Ho_Chi_Minh")
    result = format_batch(joblib.load(folder / "selected.joblib"), latest)
    if name == "V1":
        target = origin + pd.offsets.QuarterEnd()
        result.update(issued_at=now.isoformat(), target_end=str(target.date()),
                      timing="PARTIAL_HORIZON_RESEARCH" if origin.date() < now.date() <= target.date() else "HISTORICAL_RESEARCH",
                      **identity)
        dump(folder / "issued.json", result)
    return dict(status="COMPLETE_RESEARCH", **lock,
                retrospective={m: summarize(frame, monthly) for m, frame in retrospective.items()},
                paired_retrospective=compare(retrospective[challenger], retrospective[baseline], monthly))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--snapshot-run", required=True, type=Path)
    p.add_argument("--out", required=True, type=Path)
    p.add_argument("--business-date", required=True)
    p.add_argument("--workers", type=int, choices=range(1, 5), default=1)
    p.add_argument("--readiness-json", type=Path)
    args = p.parse_args()
    from pipeline import java
    snapshot = args.snapshot_run / "data/snapshot"
    manifest = args.snapshot_run / "snapshot_manifest.json"
    snapshot_spec = json.loads(manifest.read_text())
    if not snapshot_spec.get("read_only"):
        raise ValueError("READ_ONLY_SNAPSHOT_REQUIRED")
    snapshot_at = pd.Timestamp(snapshot_spec["snapshot_time"])
    if snapshot_at.tzinfo is None or pd.Timestamp(args.business_date).date() > snapshot_at.tz_convert("Asia/Ho_Chi_Minh").date():
        raise ValueError("BUSINESS_DATE_AFTER_SNAPSHOT")
    for filename, digest in snapshot_spec["files"].items():
        if sha(snapshot / filename) != digest:
            raise ValueError("SNAPSHOT_HASH_MISMATCH")
    quality = pd.read_csv(snapshot / "16_key_quality.csv")
    if quality.empty or quality.duplicate_groups.ne(0).any():
        raise ValueError("CANONICAL_DUPLICATE_KEYS")
    args.out.mkdir(parents=True, exist_ok=False)
    roster_args = []
    roster_sha = None
    if args.readiness_json:
        readiness = json.loads(args.readiness_json.read_text(encoding="utf-8"))
        if readiness.get("ready_to_train") is not True:
            raise ValueError("EXPANDED_MARKET_NOT_READY")
        members = sorted(ticker for ticker, item in readiness["stock_details"].items() if item["eligible"])
        if len(members) != readiness["eligible_stocks"] or len(members) < readiness["stock_target"]:
            raise ValueError("LOCKED_UNIVERSE_COUNT")
        roster = args.out / "universe.txt"
        roster.write_text("\n".join(members) + "\n", encoding="utf-8")
        roster_args = [roster.resolve()]
        roster_sha = sha(roster)
    source = Path(__file__).resolve().parent
    code_files = [source / name for name in ("train_rank.py", "predict_rank.py", "evaluate_rank_forward.py")]
    code_files += [args.snapshot_run / "source/java" / name for name in
                   ("RankDataset.java", "RankMarketFeatures.java", "QuarterlyMarketFeatures.java", "ReturnDataset.java")]
    dump(args.out / "protocol.json", dict(gate=GATE, development_end=str(DEV_END.date()),
         retrospective_target_start=str(RETRO_START.date()), retrospective_target_end=str(RETRO_END.date()),
         variants=VARIANTS, baseline_registry=BASELINES, seed=47,
         protocol_version=PROTOCOL_VERSION, composite_signs=COMPOSITE_SIGNS, min_cohort_fraction=MIN_COHORT_FRACTION,
         selection=("Development only. Tuning, choice and gate use origins whose cohort is >= "
                    "min_cohort_fraction of the largest cohort; choice by mean per-origin AUC; "
                    "auc_gain = mean paired per-origin AUC gain. All origins still reported."),
         design_note=("rank-v3 locks the protocol on 2026-11-21: (1) Replaced legacy currency filtering gap "
                      "by strictly fixing missing source metadata at builder/metadata level (VND, scale=1) "
                      "audited and confirmed across 100% universe; (2) Relaxed period_basis to include STANDALONE_QUARTER, "
                      "UNKNOWN, and POINT_IN_TIME based on empirical audit confirming quarterly sum matches annual totals in 95.6% "
                      "of samples; (3) Selection is STRICTLY based on Development period (2017-2024); the 2025-2026 "
                      "retrospective window has been repeatedly viewed and exhibits regime-shift dynamics (IC reversal), "
                      "so retrospective metrics are reported for historical analysis only and MUST NOT be used to claim ML superiority."),
         embargo_months=1, snapshot_sha256=sha(manifest),
         source_sha256={path.name: sha(path) for path in code_files},
         business_date=args.business_date, grid_workers=args.workers,
         canonical_key_quality=quality.to_dict(orient="records"),
         market_readiness_sha256=sha(args.readiness_json) if args.readiness_json else None,
         locked_universe_sha256=roster_sha,
         final_artifact_roles=["selected", "challenger", "baseline"],
         runtime={"python": platform.python_version(), "packages": {
             name: version(name) for name in ("numpy", "pandas", "scipy", "scikit-learn", "joblib", "threadpoolctl")
         }},
         case_b="45-day quarter and 90-day annual proxy; later known publication delays assumed availability; current revisions may leak; research only",
         production_eligible=False, replace_llm=False))
    panels = {}
    for freq in ["quarter", "month"]:
        dest = args.out / freq
        java(args.snapshot_run, ["RankDataset", snapshot, dest, args.business_date, freq, *roster_args], "rank-" + freq)
        panels[freq] = pd.read_csv(dest / "samples.csv", parse_dates=["cutoff", "target_end", "label_available_at"])
        verify_panel(panels[freq])
    summary = {}
    for name, (freq, y, features) in VARIANTS.items():
        folder = args.out / name
        folder.mkdir()
        panel = panels[freq].copy()
        if name == "V3":
            path = snapshot / "17_rank_financial.csv"
            if not path.exists():
                summary[name] = {"status": "BLOCKED_NO_FINANCIAL_SNAPSHOT"}
                continue
            panel = case_b(panel, path)
        panel.to_csv(folder / "dataset.csv", index=False)
        same_origin = panel.pivot(index="cutoff", columns="ticker", values="label_absolute").corr(min_periods=8)
        off_diagonal = same_origin.to_numpy()[np.triu_indices(len(same_origin), k=1)]
        finite = off_diagonal[np.isfinite(off_diagonal)]
        dump(folder / "eda.json", dict(rows=len(panel), origins=panel.cutoff.nunique(), tickers=panel.ticker.nunique(),
             dataset_sha256=sha(folder / "dataset.csv"),
             mean_pairwise_absolute_label_correlation=float(finite.mean()) if len(finite) else None,
             label_counts={str(k): int(v) for k, v in panel[y].value_counts(dropna=False).items()},
             missing={key: float(value) for key, value in panel[features].isna().mean().items()}))
        summary[name] = run_variant(name, panel, folder, args.workers)
        print(name, summary[name]["status"], summary[name].get("selected"), flush=True)
    dump(args.out / "summary.json", summary)


def verify_panel(panel):
    if panel.sample_id.duplicated().any() or panel.origin_price_date.gt(panel.cutoff.dt.strftime("%Y-%m-%d")).any():
        raise ValueError("INVALID_ORIGIN_KEYS_OR_SOURCE_DATES")
    for cutoff, cohort in panel.groupby("cutoff"):
        if not cohort.universe_size.eq(len(cohort)).all():
            raise ValueError("UNIVERSE_SIZE_MISMATCH")
        members = "|".join(sorted(cohort.ticker))
        if not cohort.universe_hash.eq(hashlib.sha256(members.encode()).hexdigest()).all():
            raise ValueError("UNIVERSE_HASH_MISMATCH")
        ranks = cohort[RAW].rank(method="average", pct=True)
        np.testing.assert_allclose(cohort[RANK], ranks, atol=1e-12, rtol=0, equal_nan=True)
        if cohort.label_relative.notna().any():
            if cohort.actual_return.isna().any():
                raise ValueError("INCOMPLETE_TARGET_COHORT")
            excess = cohort.actual_return - cohort.actual_return.median()
            np.testing.assert_allclose(cohort.excess_return_vs_universe, excess, atol=1e-12, rtol=0)
            np.testing.assert_array_equal(cohort.label_relative, excess.gt(0).astype(int))


if __name__ == "__main__":
    with threadpool_limits(limits=1):
        main()
