"""The ``groovyscope`` command line: one click group, ``main``, with its commands in this package's modules.

``common`` holds what every command shares, including the seams tests replace (``common._RPC`` and the rest);
``workspace``, ``kiro``, ``daemon``, ``quality``, ``graph`` and ``checks`` register their commands on ``main`` when
imported. The helpers re-exported here keep ``groovyscope.cli.<name>`` working for callers and tests that read
them; to replace one in a test, patch the module that owns it.
"""

from __future__ import annotations

from groovyscope.cli import checks, daemon, graph, kiro, quality, workspace
from groovyscope.cli.checks import hook_seconds
from groovyscope.cli.common import (
    CONFIG_ENV,
    ENV_CONFIG_LIMIT_BYTES,
    RELOAD_METHOD,
    apply_config_logging,
    bundled_config,
    cds_archive_for,
    config_hash,
    config_source,
    configure_logging,
    daemon_paths,
    engine_config_file,
    engine_view,
    exec_process,
    hand_over_config,
    main,
    resolve_jdk,
    target_params,
)
from groovyscope.cli.graph import testing_lines
from groovyscope.cli.kiro import profile_guidance_for
from groovyscope.cli.quality import hook_targets, run_build, session_baseline_file, triage_lines

__all__ = [
    "CONFIG_ENV",
    "ENV_CONFIG_LIMIT_BYTES",
    "RELOAD_METHOD",
    "apply_config_logging",
    "bundled_config",
    "cds_archive_for",
    "checks",
    "config_hash",
    "config_source",
    "configure_logging",
    "daemon",
    "daemon_paths",
    "engine_config_file",
    "engine_view",
    "exec_process",
    "graph",
    "hand_over_config",
    "hook_seconds",
    "hook_targets",
    "kiro",
    "main",
    "profile_guidance_for",
    "quality",
    "resolve_jdk",
    "run_build",
    "session_baseline_file",
    "target_params",
    "testing_lines",
    "triage_lines",
    "workspace",
]
