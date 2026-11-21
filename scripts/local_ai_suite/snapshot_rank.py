"""Export the same read-only input tables as the existing local AI suite."""
import argparse
import shutil
from pathlib import Path

from pipeline import HERE, snapshot


def main():
    parser = argparse.ArgumentParser()
    for key in ("backend", "config", "jdbc", "out"):
        parser.add_argument("--" + key, required=True, type=Path)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True)
    shutil.copytree(HERE, out / "source", ignore=shutil.ignore_patterns("__pycache__"))
    snapshot(out, args.backend.resolve(), args.config.resolve(), args.jdbc.resolve())


if __name__ == "__main__":
    main()
