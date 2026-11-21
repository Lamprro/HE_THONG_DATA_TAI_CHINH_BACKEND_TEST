"""Seal V1 research ranking for the existing Spring registry and Python server."""
import argparse
import json
import shutil
import uuid
from pathlib import Path

from predict_rank import format_batch, load_bundle
from train_rank import dump, sha


def risk_companion(source, batch, target):
    from release_server import Release

    engine = Release(source)
    result = {}
    for ticker in (row["ticker"] for row in batch["results"]):
        fields = {}
        for model in ("volatility_h1", "drawdown_h1"):
            spec = engine.specs.get(model)
            stored = engine.inputs.get((model, ticker))
            if not spec or not stored or spec["target_period"] != target or stored["data_as_of"] != batch["cutoff"]:
                break
            payload = json.loads(stored["input_payload"])
            if payload["cutoff"] != batch["cutoff"]:
                break
            forecast = engine.models[(model, "selected")].run(payload)
            fields[model] = dict(value=forecast["prediction"], units=forecast["units"],
                                 model_sha256=forecast["model_sha256"], input_sha256=stored["input_sha256"])
        if len(fields) == 2:
            result[ticker] = fields
    return dict(source_manifest_sha256=engine.digest, cutoff=batch["cutoff"], target_period=target, rows=result)


def build(run, out, risk_release=None):
    bundle, rows, identity = load_bundle(run)
    issued = json.loads((run / "issued.json").read_text(encoding="utf-8"))
    batch = format_batch(bundle, rows)
    if batch["results"] != issued["results"] or issued["model_sha256"] != identity["model_sha256"]:
        raise ValueError("ISSUED_ARTIFACT_MISMATCH")
    out.mkdir(parents=True)
    package = out / "rank"
    package.mkdir()
    here = Path(__file__).resolve().parent
    for name in ["train_rank.py", "predict_rank.py", "release_server.py"]:
        shutil.copy2(here / name, out / name)
    for name in ["selected.joblib", "latest_features.csv", "artifact.json", "issued.json"]:
        shutil.copy2(run / name, package / name)
    model = "stock_rank_h1"
    inputs = []
    import hashlib
    for rec in batch["results"]:
        payload = json.dumps(dict(cutoff=batch["cutoff"], ticker=rec["ticker"],
                                 universe_hash=batch["universe_hash"], feature_version=bundle["feature_version"]),
                             sort_keys=True, separators=(",", ":"))
        inputs.append(dict(model_id=model, ticker=rec["ticker"], data_as_of=batch["cutoff"],
                           input_payload=payload, input_sha256=hashlib.sha256(payload.encode()).hexdigest()))
    end = issued["target_end"]
    target = end[:4] + "Q" + str((int(end[5:7]) + 2) // 3)
    spec = dict(id=model, kind="rank", target_period=target, cutoff=batch["cutoff"],
                feature_version=bundle["feature_version"], universe_hash=batch["universe_hash"],
                universe_size=batch["universe_size"], artifacts={"selected": {"sha256": identity["model_sha256"]}})
    protocol_path = run.parent / "protocol.json"
    protocol = json.loads(protocol_path.read_text(encoding="utf-8"))
    shutil.copy2(protocol_path, package / "protocol.json")
    if "runtime" in protocol:
        requirements = "".join(f"{name}=={value}\n" for name, value in protocol["runtime"]["packages"].items())
        (out / "requirements.txt").write_text(requirements, encoding="utf-8")
    if risk_release is not None:
        risk = risk_companion(risk_release, batch, target)
        dump(package / "risk.json", risk)
        spec["risk_manifest_sha256"] = risk["source_manifest_sha256"]
    dump(out / "release.json", dict(schema="local_ai.registered.release.v1", package_id=str(uuid.uuid4()),
         models=[spec], inputs=inputs, production_eligible=False, replace_llm=False,
         training_protocol_sha256=sha(protocol_path), source_snapshot_sha256=protocol["snapshot_sha256"],
         files={p.relative_to(out).as_posix(): sha(p) for p in out.rglob("*") if p.is_file()}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--risk-release", type=Path)
    args = parser.parse_args()
    build(args.run, args.out, args.risk_release)
