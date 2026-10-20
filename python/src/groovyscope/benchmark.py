"""The benchmark corpus the package ships: CodeNarc's ``src/main`` tree, unpacked once into the user's home so a
machine that cannot reach GitHub still has the fixed workspace ``docs/benchmark.md`` and the reference prompts use.

Nothing here runs a build or reaches the network. The corpus goes to ``~/.groovyscope/benchmark/codenarc/``
(``GROOVYSCOPE_BENCHMARK_DIR`` overrides the parent) with a ready ``.groovyscope.yaml`` and a stamp recording the
bundled version, so a later package with a newer corpus refreshes it and an unchanged one is left alone.
"""

from __future__ import annotations

import json
import zipfile
from collections.abc import Mapping
from importlib import resources
from pathlib import Path
from typing import Any, TypedDict

import yaml

from groovyscope import config
from groovyscope.config import CONFIG_FILENAME

RESOURCE_DIR = "_benchmark"
MANIFEST = "manifest.json"
STAMP = ".groovyscope-benchmark.json"
USER_DIR = Path(".groovyscope") / "benchmark"
CORPUS_DIR = "codenarc"
BENCHMARK_DIR_ENV = config.BENCHMARK_DIR_ENV
README = """# groovyscope benchmark corpus

CodeNarc {version} `src/main`, Apache License 2.0 (see LICENSE.txt and NOTICE.txt), unpacked by groovyscope from
{source}. It is the fixed workspace of docs/benchmark.md and of the reference prompts in docs/prompts.md.

Edit freely; `groovyscope benchmark path --refresh` restores it. No build is included, so library imports show up
as GS100 missing-dependency warnings: that is expected and is itself one of the things the benchmark measures.
"""


class Manifest(TypedDict):
    """What the bundled zip holds."""

    name: str
    version: str
    zip: str
    sha256: str
    files: int
    source: str
    license: str
    roots: list[str]


class UnpackResult(TypedDict):
    """What ``ensure`` did."""

    path: str
    action: str  # unpacked | refreshed | up-to-date
    version: str
    files: int


def manifest() -> Manifest | None:
    """The bundled corpus manifest, or None when the package carries no corpus."""
    try:
        text = resources.files("groovyscope").joinpath(RESOURCE_DIR, MANIFEST).read_text(encoding="utf-8")
    except (OSError, ValueError):
        return None
    loaded: Any = json.loads(text)
    if not isinstance(loaded, dict) or not {"name", "version", "zip", "sha256", "files", "source", "roots"} <= set(
        loaded
    ):
        return None
    return {
        "name": str(loaded["name"]),
        "version": str(loaded["version"]),
        "zip": str(loaded["zip"]),
        "sha256": str(loaded["sha256"]),
        "files": int(loaded["files"]),
        "source": str(loaded["source"]),
        "license": str(loaded.get("license", "")),
        "roots": [str(root) for root in loaded["roots"]],
    }


def corpus_root(home: Path, environ: Mapping[str, str]) -> Path:
    """Where the corpus lives: ``$GROOVYSCOPE_BENCHMARK_DIR/codenarc`` or ``~/.groovyscope/benchmark/codenarc``."""
    override = environ.get(BENCHMARK_DIR_ENV, "")
    parent = Path(override).expanduser() if override else home / USER_DIR
    return parent / CORPUS_DIR


def status(target: Path) -> dict[str, Any]:
    """Whether the corpus is present at ``target`` and which version the stamp records."""
    stamp = target / STAMP
    if not stamp.is_file():
        return {"present": False, "version": None}
    try:
        recorded: Any = json.loads(stamp.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"present": True, "version": None}
    return {"present": True, "version": recorded.get("version") if isinstance(recorded, dict) else None}


def workspace_config(found: Manifest) -> str:
    """A ``.groovyscope.yaml`` for the corpus: its source roots and the Groovy level CodeNarc builds with."""
    settings = {
        "workspace": {"sourceRoots": list(found["roots"])},
        "groovy": {"version": "5.0", "compat": []},
    }
    header = "# groovyscope settings for the bundled benchmark corpus; defaults: `groovyscope config validate --show`\n"
    return header + yaml.safe_dump(settings, sort_keys=False, default_flow_style=False)


def safe_members(archive: zipfile.ZipFile, target: Path) -> list[zipfile.ZipInfo]:
    """Entries that stay inside ``target`` (zip-slip guard); directories are skipped."""
    members = []
    base = target.resolve()
    for info in archive.infolist():
        if info.is_dir():
            continue
        destination = (target / info.filename).resolve()
        if not destination.is_relative_to(base):
            msg = f"corpus entry {info.filename!r} escapes {target}"
            raise ValueError(msg)
        members.append(info)
    return members


def unpack(target: Path, found: Manifest) -> int:
    """Extract the bundled zip into ``target`` and write the configuration, README and stamp. Returns the count."""
    archive_path = resources.files("groovyscope").joinpath(RESOURCE_DIR, found["zip"])
    target.mkdir(parents=True, exist_ok=True)
    with resources.as_file(archive_path) as path, zipfile.ZipFile(path) as archive:
        members = safe_members(archive, target)
        for info in members:
            destination = target / info.filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(archive.read(info))
    (target / CONFIG_FILENAME).write_text(workspace_config(found), encoding="utf-8")
    (target / "README.md").write_text(README.format(version=found["version"], source=found["source"]), encoding="utf-8")
    stamp = {"name": found["name"], "version": found["version"], "sha256": found["sha256"], "files": len(members)}
    (target / STAMP).write_text(json.dumps(stamp, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return len(members)


def ensure(target: Path, *, refresh: bool = False) -> UnpackResult | None:
    """Unpack the corpus at ``target`` when it is missing or older than the bundled one (or always with
    ``refresh``). None when the package carries no corpus."""
    found = manifest()
    if found is None:
        return None
    current = status(target)
    if current["present"] and current["version"] == found["version"] and not refresh:
        return {"path": str(target), "action": "up-to-date", "version": found["version"], "files": found["files"]}
    count = unpack(target, found)
    action = "refreshed" if current["present"] else "unpacked"
    return {"path": str(target), "action": action, "version": found["version"], "files": count}
