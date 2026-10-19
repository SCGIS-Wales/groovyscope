from __future__ import annotations

from pathlib import Path

import pytest

from groovyscope import daemon, selfheal

MB = 1024 * 1024
GB = 1024 * MB


def test_heap_sizes_parse_and_format() -> None:
    assert selfheal.parse_heap("1g") == GB
    assert selfheal.parse_heap("1536m") == 1536 * MB
    assert selfheal.parse_heap("2048K") == 2048 * 1024
    assert selfheal.format_heap(GB) == "1g"
    assert selfheal.format_heap(1536 * MB) == "1536m"
    with pytest.raises(ValueError, match="not <number>k"):
        selfheal.parse_heap("lots")
    assert selfheal.max_heap_bytes("auto", 64 * GB) == 4 * GB
    assert selfheal.max_heap_bytes("auto", 8 * GB) == 2 * GB
    assert selfheal.max_heap_bytes("auto", None) == 4 * GB
    assert selfheal.max_heap_bytes("3g", 64 * GB) == 3 * GB


def test_state_round_trip_and_corrupt_file(tmp_path: Path) -> None:
    path = selfheal.state_path(tmp_path / "abc.log")
    assert path.name == "abc.state.json"
    assert selfheal.load_state(path) == selfheal.empty_state()
    state = selfheal.started(selfheal.empty_state(), "1g", selfheal.EXIT_OOM, 42, 1000.0)
    selfheal.save_state(path, state)
    assert selfheal.load_state(path) == {
        "heap": "1g",
        "configured": None,
        "restarts": [1000.0],
        "lastExit": "oom",
        "logSize": 42,
    }
    grown = selfheal.started(selfheal.empty_state(), "2g", "oom", 0, 1.0, configured="1g")
    assert (
        selfheal.grown_heap(grown) == "2g"
        and selfheal.grown_heap(selfheal.started(grown, "1g", "exit", 0, 2.0)) is None
    )
    path.write_text("[not an object]")
    assert selfheal.load_state(path) == selfheal.empty_state()
    path.write_text('{"heap": 7, "restarts": "x", "lastExit": "weird", "logSize": -3}')
    assert selfheal.load_state(path) == selfheal.empty_state()


def test_exit_cause_reads_only_what_the_last_jvm_wrote(tmp_path: Path) -> None:
    log = tmp_path / "d.log"
    state = selfheal.empty_state()
    assert selfheal.exit_cause(log, state) == selfheal.EXIT_NONE
    log.write_text("old OutOfMemoryError from long ago\n")
    state = selfheal.started(state, "1g", selfheal.EXIT_NONE, log.stat().st_size, 0.0)
    assert selfheal.exit_cause(log, state) == selfheal.EXIT_NONE  # nothing new since the start
    with log.open("a") as handle:
        handle.write("daemon listening\nidle, exiting\n")
    assert selfheal.exit_cause(log, state) == selfheal.EXIT_OTHER
    with log.open("a") as handle:
        handle.write("Terminating due to java.lang.OutOfMemoryError: Java heap space\n")
    assert selfheal.exit_cause(log, state) == selfheal.EXIT_OOM


def test_plan_grows_the_heap_once_after_an_oom_exit_and_stops_at_the_ceiling() -> None:
    settings = {"heapGrowthFactor": 1.5, "maxHeap": "2g", "maxRestartsPerTenMinutes": 3}
    first = selfheal.plan_restart(selfheal.empty_state(), selfheal.EXIT_OOM, settings, "1g", 1000.0)
    assert first["allowed"] and first["heap"] == "1536m" and first["delay"] == 0
    assert "OutOfMemoryError on a 1g heap; starting with 1536m" in first["reason"]
    state = selfheal.started(selfheal.empty_state(), "1536m", selfheal.EXIT_OOM, 0, 1000.0)
    second = selfheal.plan_restart(state, selfheal.EXIT_OOM, settings, "1g", 1010.0, jitter=lambda: 0.5)
    assert second["heap"] == "2g" and second["delay"] == 1.0  # the ceiling, after a one-second backoff
    state = selfheal.started(state, "2g", selfheal.EXIT_OOM, 0, 1010.0)
    third = selfheal.plan_restart(state, selfheal.EXIT_OOM, settings, "1g", 1020.0, jitter=lambda: 0.5)
    assert third["allowed"] and third["heap"] == "2g" and "already at daemon.maxHeap (2g)" in third["reason"]
    assert third["delay"] == 2.0
    # A grown heap is kept across a clean exit, and a plain exit grows nothing.
    kept = selfheal.plan_restart(state, selfheal.EXIT_OTHER, settings, "1g", 2000.0)
    assert kept["heap"] == "2g" and kept["reason"] == ""
    fresh = selfheal.plan_restart(selfheal.empty_state(), selfheal.EXIT_OTHER, settings, "1g", 2000.0)
    assert fresh["heap"] is None and fresh["delay"] == 0


def test_plan_refuses_a_crash_loop() -> None:
    settings = {"maxRestartsPerTenMinutes": 2}
    state = selfheal.empty_state()
    for moment in (100.0, 200.0):
        state = selfheal.started(state, "1g", selfheal.EXIT_OTHER, 0, moment)
    refused = selfheal.plan_restart(state, selfheal.EXIT_OTHER, settings, "1g", 300.0)
    assert not refused["allowed"] and "started 2 times in the last ten minutes" in refused["reason"]
    assert "daemon.heap" in refused["reason"]
    later = selfheal.plan_restart(state, selfheal.EXIT_OTHER, settings, "1g", 300.0 + selfheal.WINDOW_SECONDS)
    assert later["allowed"] and later["delay"] == 0
    assert selfheal.with_heap(["java", "-Xmx1g", "-jar", "x"], "2g") == ["java", "-Xmx2g", "-jar", "x"]
    assert selfheal.configured_heap(["java", "-Xmx1g"]) == "1g" and selfheal.configured_heap(["java"]) == "1g"


def test_rotate_log_keeps_the_newest_files(tmp_path: Path) -> None:
    log = tmp_path / "d.log"
    log.write_text("x" * 100)
    assert not selfheal.rotate_log(log, 1000, 2)
    assert selfheal.rotate_log(log, 50, 2)
    assert not log.exists() and (tmp_path / "d.log.1").read_text() == "x" * 100
    log.write_text("y" * 100)
    assert selfheal.rotate_log(log, 50, 2)
    assert (tmp_path / "d.log.1").read_text() == "y" * 100 and (tmp_path / "d.log.2").read_text() == "x" * 100
    log.write_text("z" * 100)
    assert selfheal.rotate_log(log, 50, 2)
    assert (tmp_path / "d.log.2").read_text() == "y" * 100 and not (tmp_path / "d.log.3").exists()
    assert not selfheal.rotate_log(tmp_path / "missing.log", 1, 1)


def test_prepare_start_rotates_grows_waits_and_records(tmp_path: Path) -> None:
    log = tmp_path / "d.log"
    log.write_text("Terminating due to java.lang.OutOfMemoryError: Java heap space\n")
    state_file = selfheal.state_path(log)
    selfheal.save_state(state_file, selfheal.started(selfheal.empty_state(), "1g", "none", 0, 990.0))
    waited: list[float] = []
    settings = {"heap": "1g", "maxHeap": "4g", "heapGrowthFactor": 2, "logMaxBytes": 10, "logsKept": 1}
    command = daemon.prepare_start(
        ["java", "-Xmx1g", "-jar", "e.jar"], log, settings, sleep=waited.append, now=lambda: 1000.0
    )
    assert command == ["java", "-Xmx2g", "-jar", "e.jar"]
    assert len(waited) == 1 and 0.5 <= waited[0] <= 1.5
    assert (tmp_path / "d.log.1").exists() and not log.exists()  # rotated: over 10 bytes
    saved = selfheal.load_state(state_file)
    assert saved["heap"] == "2g" and saved["lastExit"] == "oom" and saved["restarts"] == [990.0, 1000.0]
    assert saved["logSize"] == 0 and saved["configured"] == "1g" and selfheal.grown_heap(saved) == "2g"
    for moment in (1001.0, 1002.0):
        selfheal.save_state(state_file, selfheal.started(selfheal.load_state(state_file), "2g", "exit", 0, moment))
    with pytest.raises(RuntimeError, match="started 4 times in the last ten minutes"):
        daemon.prepare_start(["java", "-Xmx1g"], log, {**settings, "maxRestartsPerTenMinutes": 3}, now=lambda: 1003.0)
    daemon.forget_restarts(log)  # a stop or start someone asked for is not a crash
    forgiven = selfheal.load_state(state_file)
    assert forgiven["restarts"] == [] and forgiven["heap"] == "2g"
    daemon.prepare_start(["java", "-Xmx1g"], log, {**settings, "maxRestartsPerTenMinutes": 3}, now=lambda: 1004.0)
    daemon.forget_restarts(tmp_path / "never.log")
    assert not selfheal.state_path(tmp_path / "never.log").exists()


def test_only_a_heap_exhaustion_grows_the_heap(tmp_path: Path) -> None:
    settings = {"heapGrowthFactor": 1.5, "maxHeap": "4g", "maxRestartsPerTenMinutes": 3}
    log = tmp_path / "d.log"
    log.write_text("Terminating due to java.lang.OutOfMemoryError: Metaspace\n")
    state = selfheal.empty_state()
    assert selfheal.exit_cause(log, state) == selfheal.EXIT_OOM_OTHER
    plan = selfheal.plan_restart(state, selfheal.EXIT_OOM_OTHER, settings, "1g", 1000.0)
    assert plan["heap"] is None and "a bigger heap does not cure" in plan["reason"]
    advice = daemon.out_of_memory_advice(str(log))
    assert "Metaspace" in advice and "daemon.heap" not in advice
    log.write_text("java.lang.OutOfMemoryError: unable to create native thread: possibly out of memory\n")
    assert "ulimit -u" in daemon.out_of_memory_advice(str(log))
    log.write_text("java.lang.OutOfMemoryError: Requested array size exceeds VM limit\n")
    assert "Requested array size exceeds VM limit" in daemon.out_of_memory_advice(str(log))
    log.write_text("Terminating due to java.lang.OutOfMemoryError: GC overhead limit exceeded\n")
    assert selfheal.exit_cause_of(log.read_text()) == selfheal.EXIT_OOM
    assert "daemon.heap" in daemon.out_of_memory_advice(str(log))
    assert selfheal.load_state(tmp_path / "missing.json")["lastExit"] == selfheal.EXIT_NONE


def test_a_new_configured_heap_replaces_a_heap_grown_from_the_old_one() -> None:
    settings = {"heapGrowthFactor": 1.5, "maxHeap": "16g", "maxRestartsPerTenMinutes": 3}
    state = selfheal.started(selfheal.empty_state(), "1536m", selfheal.EXIT_OOM, 0, 1000.0, configured="1g")
    raised = selfheal.plan_restart(state, selfheal.EXIT_OTHER, settings, "8g", 5000.0)
    assert raised["heap"] is None  # the command's own -Xmx8g stands
    grown = selfheal.plan_restart(state, selfheal.EXIT_OOM, settings, "8g", 5000.0)
    assert grown["heap"] == "12g" and "on a 8g heap" in grown["reason"]
    kept = selfheal.plan_restart(state, selfheal.EXIT_OTHER, settings, "1g", 5000.0)
    assert kept["heap"] == "1536m"
    # A recorded heap smaller than the configured one is never used.
    small = selfheal.started(selfheal.empty_state(), "512m", selfheal.EXIT_OOM, 0, 1000.0)
    assert selfheal.plan_restart(small, selfheal.EXIT_OTHER, settings, "1g", 5000.0)["heap"] is None
