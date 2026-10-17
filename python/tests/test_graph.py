"""Relationship graph layout and the draw.io and Mermaid emitters."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

from groovyscope import graph


def node(node_id: str, kind: str = "class", package: str = "app", test: bool = False) -> dict[str, Any]:
    return {"id": f"{package}.{node_id}", "name": node_id, "kind": kind, "package": package, "test": test}


def edge(source: str, target: str, kind: str, confidence: str = "STATIC", count: int = 1) -> dict[str, Any]:
    return {"from": source, "to": target, "kind": kind, "confidence": confidence, "count": count}


SAMPLE = {
    "nodes": [
        node("Base"),
        node("Service"),
        node("Repository", package="app.data"),
        node("Report", kind="interface"),
        node("ServiceSpec", test=True),
        node("Helper", kind="external", package="lib"),
    ],
    "edges": [
        edge("app.Service", "app.Base", "extends"),
        edge("app.Service", "app.Report", "implements"),
        edge("app.Service", "app.data.Repository", "has"),
        edge("app.Service", "app.data.Repository", "calls", count=3),
        edge("app.data.Repository", "app.Service", "calls", "DYNAMIC_UNKNOWN"),
        edge("app.ServiceSpec", "app.Service", "calls", "INFERRED", 5),
        edge("app.Service", "lib.Helper", "uses"),
    ],
    "truncated": False,
    "totalClasses": 5,
}


def test_layering_puts_bases_and_callers_above() -> None:
    ids = [n["id"] for n in SAMPLE["nodes"]]
    kept = graph.acyclic(ids, graph.layering_edges(SAMPLE["edges"]))
    layers = graph.assign_layers(ids, kept)
    assert layers["app.Base"] == 0 and layers["app.Report"] == 0
    assert layers["app.Service"] > layers["app.Base"]
    assert layers["app.data.Repository"] > layers["app.Service"]
    assert layers["app.ServiceSpec"] < layers["app.Service"]
    assert ("app.data.Repository", "app.Service") not in kept  # the cycle was broken for layering


def test_layout_fits_the_page_without_overlaps() -> None:
    drawn = graph.layout(SAMPLE)
    assert drawn["orientation"] in {"portrait", "landscape"}
    assert graph.check_layout(drawn) == []
    assert len(drawn["nodes"]) == 6 and drawn["note"] == ""
    assert drawn["packages"]["app"] != drawn["packages"]["app.data"]
    external = next(n for n in drawn["nodes"] if n["id"] == "lib.Helper")
    assert external["dashed"] and external["fill"] == graph.EXTERNAL_FILL
    spec = next(n for n in drawn["nodes"] if n["id"] == "app.ServiceSpec")
    assert spec["dashed"]


def test_large_graphs_scale_down_to_the_page_and_say_when_too_big() -> None:
    big = {
        "nodes": [node(f"Class{i}") for i in range(40)],
        "edges": [edge(f"app.Class{i}", f"app.Class{i + 1}", "calls") for i in range(39)],
    }
    drawn = graph.layout(big)
    assert graph.check_layout(drawn) == []
    assert drawn["scale"] < 1 and drawn["font"] >= graph.MIN_FONT
    huge = {
        "nodes": [node(f"C{i}") for i in range(400)],
        "edges": [edge(f"app.C{i}", f"app.C{i + 1}", "calls") for i in range(399)],
    }
    assert "too many nodes" in graph.layout(huge)["note"]
    wide = {"nodes": [node(f"W{i}") for i in range(8)], "edges": []}
    assert graph.layout(wide)["orientation"] == "landscape"
    assert graph.layout({"nodes": [], "edges": []})["nodes"] == []


def test_drawio_file_is_valid_xml_with_styled_nodes_and_edges(tmp_path: Path) -> None:
    text = graph.to_drawio(SAMPLE, title="demo")
    root = ET.fromstring(text)  # noqa: S314 - our own output
    assert root.tag == "mxfile" and root.find("diagram").get("name") == "demo"  # type: ignore[union-attr]
    model = root.find("diagram/mxGraphModel")
    assert model is not None and model.get("pageWidth") in {"827", "1169"}
    cells = model.findall("root/mxCell")
    vertices = [c for c in cells if c.get("vertex") == "1" and c.get("id", "").startswith("n-")]
    edges = [c for c in cells if c.get("edge") == "1"]
    assert len(vertices) == 6 and len(edges) == 7
    styles = [c.get("style", "") for c in edges]
    assert sum("endFill=0" in s for s in styles) == 2  # extends and implements
    assert any("endFill=0" in s and "dashed=1" in s for s in styles)  # implements
    assert sum("diamondThin" in s for s in styles) == 1  # has
    dynamic = next(c for c in edges if c.get("source") == "n-app_data_Repository")
    assert "dashPattern" in dynamic.get("style", "") and graph.EDGE_COLOURS["DYNAMIC_UNKNOWN"] in dynamic.get(
        "style", ""
    )
    counted = next(c for c in edges if c.get("value") == "3")
    assert counted.get("target") == "n-app_data_Repository"
    legend = [c for c in cells if c.get("id", "").startswith("legend-")]
    assert len(legend) >= 3
    assert all(c.get("value") for c in vertices)
    (tmp_path / "g.drawio").write_text(text, encoding="utf-8")


def test_mermaid_class_diagram() -> None:
    text = graph.to_mermaid(SAMPLE)
    assert text.startswith("classDiagram\n")
    assert "    Base <|-- Service" in text
    assert "    Report <|.. Service" in text
    assert "    Repository *-- Service" in text
    assert "    Service --> Repository : calls x3" in text
    assert "    Repository --> Service : calls (by name)" in text
    assert "    Service ..> Helper : uses" in text
    assert "    <<interface>> Report" in text and "    <<external>> Helper" in text


def test_compact_caps_nodes_and_edges() -> None:
    small = graph.compact(SAMPLE, cap=2)
    assert [n["id"] for n in small["nodes"]] == ["app.Base", "app.Service"]
    assert all(
        e["from"] in {"app.Base", "app.Service"} and e["to"] in {"app.Base", "app.Service"} for e in small["edges"]
    )
    assert small["truncated"] and small["nodeCount"] == 6 and small["totalClasses"] == 5
    full = graph.compact(SAMPLE, cap=50)
    assert not full["truncated"] and len(full["edges"]) == 7
    assert "test" in next(n for n in full["nodes"] if n["id"] == "app.ServiceSpec")
