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


def test_larger_graphs_move_to_a3_then_a2_and_never_shrink_text_below_readable() -> None:
    def chain(count: int, prefix: str = "C") -> dict[str, Any]:
        return {
            "nodes": [node(f"{prefix}{i}") for i in range(count)],
            "edges": [edge(f"app.{prefix}{i}", f"app.{prefix}{i + 1}", "calls") for i in range(count - 1)],
        }

    def wide(count: int) -> dict[str, Any]:
        return {"nodes": [node(f"W{i}") for i in range(count)], "edges": []}

    small = graph.layout(chain(5))
    assert (small["paper"], small["scale"]) == ("A4", 1.0)
    # A wide layer wraps onto several lines; the paper grows only when the lines no longer fit at a readable size.
    papers = {count: graph.layout(wide(count))["paper"] for count in (12, 100, 180)}
    assert papers == {12: "A3", 100: "A3", 180: "A2"}
    for count in (12, 100, 180):
        drawn = graph.layout(wide(count))
        assert graph.check_layout(drawn) == []
        assert drawn["scale"] >= graph.READABLE_SCALE and drawn["font"] >= graph.MIN_FONT
        assert drawn["page"] in {(1169, 827), (827, 1169), (1654, 1169), (1169, 1654), (2339, 1654), (1654, 2339)}

    # Past A2 the page grows instead of the text shrinking out of its boxes (what made the method graph unreadable).
    huge = graph.layout(chain(60))
    assert huge["paper"] == "A2" and "larger than A2" in huge["note"]
    assert huge["scale"] == graph.READABLE_SCALE and huge["page"][1] > 2339
    assert graph.check_layout(huge) == []
    lowest = max(box["y"] + box["height"] for box in huge["nodes"])
    assert lowest <= huge["page"][1] - graph.MARGIN - graph.LEGEND_HEIGHT
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


REPORT_WRITERS = {
    "nodes": [
        node("ReportWriter", kind="interface", package="org.codenarc.report"),
        *(
            node(name, package="org.codenarc.report")
            for name in (
                "AbstractReportWriter",
                "AbstractHtmlReportWriter",
                "BaselineXmlReportWriter",
                "AbstractJsonReportWriter",
                "TextReportWriter",
                "XmlReportWriter",
                "HtmlReportWriter",
                "SortableHtmlReportWriter",
                "GitlabCodeQualityReportWriter",
                "JsonReportWriter",
                "SarifReportWriter",
                "CompactTextReportWriter",
                "IdeTextReportWriter",
                "InlineXmlReportWriter",
            )
        ),
    ],
    "edges": [
        edge("org.codenarc.report.AbstractReportWriter", "org.codenarc.report.ReportWriter", "implements"),
        *(
            edge(f"org.codenarc.report.{child}", f"org.codenarc.report.{parent}", "extends")
            for child, parent in (
                ("AbstractHtmlReportWriter", "AbstractReportWriter"),
                ("BaselineXmlReportWriter", "AbstractReportWriter"),
                ("AbstractJsonReportWriter", "AbstractReportWriter"),
                ("TextReportWriter", "AbstractReportWriter"),
                ("XmlReportWriter", "AbstractReportWriter"),
                ("HtmlReportWriter", "AbstractHtmlReportWriter"),
                ("SortableHtmlReportWriter", "AbstractHtmlReportWriter"),
                ("GitlabCodeQualityReportWriter", "AbstractJsonReportWriter"),
                ("JsonReportWriter", "AbstractJsonReportWriter"),
                ("SarifReportWriter", "AbstractJsonReportWriter"),
                ("CompactTextReportWriter", "TextReportWriter"),
                ("IdeTextReportWriter", "TextReportWriter"),
                ("InlineXmlReportWriter", "XmlReportWriter"),
            )
        ),
    ],
}


def test_no_line_crosses_a_box_and_parents_sit_over_their_children() -> None:
    drawn = graph.layout(REPORT_WRITERS)
    assert graph.check_layout(drawn) == []
    boxes = {box["id"]: box for box in drawn["nodes"]}
    centre = {key: box["x"] + box["width"] / 2 for key, box in boxes.items()}
    children: dict[str, list[str]] = {}
    for e in REPORT_WRITERS["edges"]:
        children.setdefault(e["to"], []).append(e["from"])
    for parent, kids in children.items():
        low = min(boxes[k]["x"] for k in kids)
        high = max(boxes[k]["x"] + boxes[k]["width"] for k in kids)
        # Over its children, not off to one side (within a gap, as a wrapped line of children may shift a little).
        assert low - graph.H_GAP <= centre[parent] <= high + graph.H_GAP, parent
    # Every child of one base class joins it through one shared port: one arrowhead per base class.
    ends = {}
    for route in drawn["routes"]:
        target = REPORT_WRITERS["edges"][route["edge"]]["to"]
        ends.setdefault(target, set()).add(route["points"][-1])
    assert all(len(points) == 1 for points in ends.values())
    for route in drawn["routes"]:
        assert all(a[0] == b[0] or a[1] == b[1] for a, b in zip(route["points"], route["points"][1:], strict=False))


def test_long_edges_pass_between_boxes_and_same_row_edges_run_below() -> None:
    sample = {
        "nodes": [node("Top"), node("Mid1"), node("Mid2"), node("Mid3"), node("Bottom"), node("Peer")],
        "edges": [
            edge("app.Top", "app.Mid1", "calls"),
            edge("app.Top", "app.Mid2", "calls"),
            edge("app.Top", "app.Mid3", "calls"),
            edge("app.Mid2", "app.Bottom", "calls"),
            edge("app.Top", "app.Bottom", "uses"),  # skips the middle row
            edge("app.Bottom", "app.Top", "calls", "DYNAMIC_UNKNOWN"),  # back edge, upwards
        ],
    }
    drawn = graph.layout(sample)
    assert graph.check_layout(drawn) == []
    assert len(drawn["routes"]) == len(sample["edges"])
    back = next(r for r in drawn["routes"] if sample["edges"][r["edge"]]["from"] == "app.Bottom")
    assert back["exit"][1] == 0.0 and back["entry"][1] == 1.0  # leaves Bottom upwards, enters Top from below

    def box(name: str, x: float) -> graph.Placed:
        return {
            "id": name,
            "label": name,
            "sublabel": "",
            "x": x,
            "y": 40.0,
            "width": 100.0,
            "height": 46.0,
            "fill": "#fff",
            "dashed": False,
            "row": 0,
        }

    beside = [box("A", 40.0), box("B", 180.0), box("C", 320.0)]
    (route,) = graph.route_edges([edge("A", "C", "calls")], beside, 64.0)
    assert route["exit"][1] == 1.0 and route["entry"][1] == 1.0  # within one row: out and in through the bottom
    assert all(
        not graph.crosses(a, b, other)
        for a, b in zip(route["points"], route["points"][1:], strict=False)
        for other in beside
    )


def test_check_layout_reports_a_line_through_a_box() -> None:
    drawn = graph.layout(SAMPLE)
    box = drawn["nodes"][0]
    through = (box["x"] + box["width"] / 2, box["y"] - 10), (box["x"] + box["width"] / 2, box["y"] + box["height"] + 10)
    drawn["routes"].append({"edge": 99, "points": list(through), "exit": (0.5, 1.0), "entry": (0.5, 0.0)})
    assert f"edge 99 crosses {box['id']}" in graph.check_layout(drawn)
