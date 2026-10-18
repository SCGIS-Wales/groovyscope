"""Diagrams laid out by the engine's layered layout: shared helpers named in boxes, paper choice, line quality."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from groovyscope import diagram
from groovyscope import graph as graphs


def node(name: str, kind: str = "class") -> dict[str, Any]:
    return {"id": f"app.{name}", "name": name, "kind": kind, "package": "app"}


def edge(source: str, target: str, kind: str = "calls") -> dict[str, Any]:
    return {"from": f"app.{source}", "to": f"app.{target}", "kind": kind, "confidence": "STATIC", "count": 1}


def row_engine(calls: list[Mapping[str, Any]], *, gap: float = 40.0, rows: int = 1) -> diagram.EngineLayout:
    """A stand-in for groovyscope/layout: boxes in ``rows`` rows (more when a layer bound is asked for), each route
    straight down from the source's bottom to the target's top."""

    def engine(body: Mapping[str, Any]) -> Mapping[str, Any]:
        calls.append(body)
        boxes = list(body["boxes"])
        per_row = max(1, -(-len(boxes) // rows))
        bound = int(body["options"].get("layerBound") or 0)
        if bound:
            per_row = min(per_row, bound)
        placed: dict[str, dict[str, float]] = {}
        x = y = 0.0
        width = 0.0
        for index, box in enumerate(boxes):
            if index and index % per_row == 0:
                x, y = 0.0, y + 46 + 64
            placed[box["id"]] = {"x": x, "y": y}
            x += float(box["width"]) + gap
            width = max(width, x - gap)
        routes: dict[str, list[list[float]]] = {}
        sizes = {b["id"]: b for b in boxes}
        for link in body["links"]:
            s, t = placed[link["source"]], placed[link["target"]]
            sx = s["x"] + float(sizes[link["source"]]["width"]) / 2
            tx = t["x"] + float(sizes[link["target"]]["width"]) / 2
            routes[link["id"]] = [[sx, s["y"] + 46], [sx, s["y"] + 55], [tx, s["y"] + 55], [tx, t["y"]]]
        return {"boxes": placed, "routes": routes, "width": width, "height": y + 46}

    return engine


def test_shared_helpers_are_named_in_their_users_boxes_instead_of_drawn() -> None:
    callers = [f"Caller{i}" for i in range(6)]
    graph = {
        "nodes": [node("Helper"), node("Base"), node("Child"), *(node(c) for c in callers)],
        "edges": [
            *(edge(c, "Helper") for c in callers[:5]),
            edge(callers[5], "Helper", "has"),
            edge("Child", "Base", "extends"),
        ],
    }
    hubs, referenced, counts = diagram.shared_helpers(graph["edges"], 5)
    assert hubs == {"app.Helper"} and counts == {"app.Helper": 6} and referenced["app.Caller0"] == ["app.Helper"]
    assert diagram.shared_helpers(graph["edges"], 0) == (set(), {}, {})

    calls: list[Mapping[str, Any]] = []
    drawn = diagram.layout(graph, row_engine(calls), shared_callers=5)
    assert [link["id"] for link in calls[0]["links"]] == ["6"]  # only the inheritance edge is drawn
    boxes = {box["id"]: box for box in drawn["nodes"]}
    assert boxes["app.Helper"]["sublabel"].endswith("used by 6") and boxes["app.Helper"]["dashed"]
    assert boxes["app.Caller0"]["references"] == "→ Helper"
    assert boxes["app.Caller0"]["height"] > boxes["app.Base"]["height"]
    assert [route["edge"] for route in drawn["routes"]] == [6]
    text = graphs.to_drawio(graph, drawn)
    assert "→ Helper" in text and text.count('edge="1"') == 1


def test_references_are_cut_with_a_count_of_what_was_left_out() -> None:
    assert diagram.references_text([], 40) == ""
    assert diagram.references_text(["alpha", "beta"], 40) == "→ alpha, beta"
    cut = diagram.references_text([f"name{i}" for i in range(20)], 30)
    assert cut.startswith("→ name0, name1") and cut.endswith("+" + str(20 - cut.count(",") - 1)) and len(cut) <= 34


def test_the_smallest_readable_paper_wins_and_a_layer_bound_is_tried_before_growing_the_page() -> None:
    small = {"nodes": [node("A"), node("B")], "edges": [edge("A", "B")]}
    calls: list[Mapping[str, Any]] = []
    drawn = diagram.layout(small, row_engine(calls, rows=2))
    assert drawn["paper"] == "A4" and drawn["note"] == "" and len(drawn["routes"]) == 1
    assert calls[0]["options"]["layerBound"] == 0

    wide = {"nodes": [node(f"N{i}") for i in range(40)], "edges": []}
    calls = []
    fitted = diagram.layout(wide, row_engine(calls))
    bounds = [c["options"]["layerBound"] for c in calls]
    assert bounds[0] == 0 and any(bounds[1:])  # one row of 40 fits nothing; a bound wraps it
    assert fitted["paper"] in {"A4", "A3", "A2"} and fitted["scale"] >= graphs.READABLE_SCALE

    huge = {"nodes": [node(f"H{i}") for i in range(4000)], "edges": []}
    grown = diagram.layout(huge, row_engine([]))
    assert grown["paper"] == "A2" and "larger than A2" in grown["note"] and grown["scale"] == graphs.READABLE_SCALE
    assert graphs.check_layout(grown) == []


def test_route_quality_counts_crossings_and_overlaps_but_not_the_hierarchy_bus() -> None:
    graph = {
        "nodes": [node(n) for n in ("A", "B", "C", "D", "Base", "S1", "S2")],
        "edges": [edge("A", "B"), edge("C", "D"), edge("S1", "Base", "extends"), edge("S2", "Base", "extends")],
    }
    drawn: graphs.Layout = {
        "page": (1169, 827),
        "paper": "A4",
        "orientation": "landscape",
        "font": 11,
        "scale": 1.0,
        "nodes": [],
        "routes": [
            {"edge": 0, "points": [(0.0, 50.0), (100.0, 50.0)], "exit": (1.0, 0.5), "entry": (0.0, 0.5)},
            {"edge": 1, "points": [(50.0, 0.0), (50.0, 100.0)], "exit": (0.5, 1.0), "entry": (0.5, 0.0)},
            {"edge": 2, "points": [(200.0, 10.0), (300.0, 10.0)], "exit": (0.5, 0.0), "entry": (0.5, 1.0)},
            {"edge": 3, "points": [(250.0, 10.0), (320.0, 10.0)], "exit": (0.5, 0.0), "entry": (0.5, 1.0)},
        ],
        "packages": {},
        "note": "",
    }
    assert diagram.route_quality(drawn, graph) == {"crossings": 1, "overlaps": 0}
    drawn["routes"][3]["edge"] = 0  # a call lying on the subclass's track is a real overlap
    assert diagram.route_quality(drawn, graph)["overlaps"] == 1


def test_ports_are_relative_to_the_box_and_clamped() -> None:
    box: graphs.Placed = {
        "id": "a",
        "label": "a",
        "sublabel": "",
        "x": 10.0,
        "y": 20.0,
        "width": 100.0,
        "height": 50.0,
        "fill": "",
        "dashed": False,
        "row": 0,
        "references": "",
    }
    assert diagram.port(box, (60.0, 70.0)) == (0.5, 1.0)
    assert diagram.port(box, (0.0, 0.0)) == (0.0, 0.0)
