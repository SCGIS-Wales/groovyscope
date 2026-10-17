"""`groovyscope fetch`, `groovyscope jdk install` and jdk.autoProvision through the CLI."""

from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
from click.testing import CliRunner

from groovyscope import cli, fetch, provision
from tests.conftest import make_fake_jdk


def test_fetch_defaults_to_the_matching_release(
    runner: CliRunner, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: list[dict[str, Any]] = []

    def fake_fetch(source: str, **kwargs: Any) -> fetch.FetchResult:
        calls.append({"source": source, **kwargs})
        return {"path": "/cache/engine/x.jar", "version": kwargs["version"], "sha256": "ab" * 32, "source": source}

    monkeypatch.setattr(cli.fetch_engine, "fetch_jar", fake_fetch)
    result = runner.invoke(cli.main, ["fetch"])
    assert result.exit_code == 0, result.output
    assert calls[0]["source"] == fetch.default_source(cli.__version__)
    assert calls[0]["destination"] == Path(clean_env["GROOVYSCOPE_CACHE_DIR"]) / "engine"
    assert calls[0]["sha256"] is None
    assert "fetched /cache/engine/x.jar" in result.output

    result = runner.invoke(cli.main, ["fetch", "--from", "./x.jar", "--sha256", "cd" * 32, "--engine-version", "9.9.9"])
    assert result.exit_code == 0, result.output
    assert calls[1]["source"] == "./x.jar" and calls[1]["sha256"] == "cd" * 32 and calls[1]["version"] == "9.9.9"


def test_fetch_reports_failures(runner: CliRunner, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch) -> None:
    def failing(source: str, **_kwargs: Any) -> fetch.FetchResult:
        msg = f"SHA-256 mismatch for {source}"
        raise ValueError(msg)

    monkeypatch.setattr(cli.fetch_engine, "fetch_jar", failing)
    result = runner.invoke(cli.main, ["fetch", "--from", "https://example.test/x.jar"])
    assert result.exit_code == 1
    assert "fetch failed: SHA-256 mismatch" in result.output


def test_jdk_install(runner: CliRunner, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch) -> None:
    def fake_provision(install_dir: Path, *, feature: int, **_kwargs: Any) -> provision.ProvisionedJdk:
        assert install_dir == Path(clean_env["GROOVYSCOPE_CACHE_DIR"]) / "jdk"
        return {"home": f"/cache/jdk/jdk-{feature}", "version": feature, "release": f"jdk-{feature}"}

    monkeypatch.setattr(cli.provision, "provision_jdk", fake_provision)
    result = runner.invoke(cli.main, ["jdk", "install", "--feature", "25"])
    assert result.exit_code == 0, result.output
    assert "installed jdk-25 at /cache/jdk/jdk-25" in result.output

    def failing(install_dir: Path, **_kwargs: Any) -> provision.ProvisionedJdk:
        raise OSError("network down")

    monkeypatch.setattr(cli.provision, "provision_jdk", failing)
    result = runner.invoke(cli.main, ["jdk", "install"])
    assert result.exit_code == 1 and "network down" in result.output


def test_resolve_jdk_provisions_only_when_asked(
    tmp_path: Path, clean_env: dict[str, str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Discovery is stubbed to know only provisioned JDKs, so the JDKs on the test machine stay out of it."""

    def discover(_configured: str | None, _environ: Any, *, minimum: int, provisioned: list[Path]) -> Any:
        assert minimum == 17
        jdk = {"home": str(provisioned[0]), "version": 21, "source": "provisioned"} if provisioned else None
        return {"jdk": jdk, "notes": []}

    monkeypatch.setattr(cli, "discover", discover)
    effective: dict[str, Any] = {"jdk": {"home": None, "minimum": 17, "autoProvision": False}}
    assert cli.resolve_jdk(effective, dict(clean_env)) is None

    provisioned = make_fake_jdk(tmp_path / "unpacked", "21.0.9")
    downloads: list[int] = []

    def fake_provision(install_dir: Path, *, feature: int, **_kwargs: Any) -> provision.ProvisionedJdk:
        downloads.append(feature)
        return {"home": str(provisioned), "version": feature, "release": "jdk-21.0.9+10"}

    monkeypatch.setattr(cli.provision, "provision_jdk", fake_provision)
    effective["jdk"]["autoProvision"] = True
    found = cli.resolve_jdk(effective, dict(clean_env))
    assert found == {"home": str(provisioned), "version": 21, "source": "provisioned"}
    assert downloads == [21]

    # A JDK already unpacked under the cache is handed to discovery, so nothing is downloaded again.
    cache_jdk = Path(clean_env["GROOVYSCOPE_CACHE_DIR"]) / "jdk"
    make_fake_jdk(cache_jdk, "21.0.9").rename(cache_jdk / "jdk-21.0.9+10")
    cached = cli.resolve_jdk(effective, dict(clean_env))
    assert cached is not None and cached["home"].endswith("jdk-21.0.9+10") and downloads == [21]

    def failing(install_dir: Path, **_kwargs: Any) -> provision.ProvisionedJdk:
        raise ValueError("no Temurin build for win32/x86_64")

    monkeypatch.setattr(cli.provision, "provision_jdk", failing)
    monkeypatch.setattr(cli.provision, "provisioned_homes", lambda _d: [])
    assert cli.resolve_jdk(effective, dict(clean_env)) is None
