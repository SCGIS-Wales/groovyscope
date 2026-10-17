"""Relationship graphs as pictures: a layered layout (base classes and callers above, what they use below) sized
to an A4 page, a calm palette, no overlapping boxes or text, written as a draw.io file or a Mermaid diagram.

The graph comes from the engine (``groovyscope/graph``): nodes are classes (or one class's methods), edges are
``extends``, ``implements``, ``has``, ``uses`` and ``calls`` with a confidence. Everything here is pure: the same
graph always gives the same file.
"""

from __future__ import annotations

import html
from collections.abc import Mapping, Sequence
from typing import Any, TypedDict

# A4 at 96 dpi, as draw.io prints it; margins keep the drawing off the page edge.
A4_PORTRAIT = (827, 1169)
A4_LANDSCAPE = (1169, 827)
MARGIN = 40
LEGEND_HEIGHT = 70
NODE_HEIGHT = 46
MIN_NODE_WIDTH = 120
MAX_NODE_WIDTH = 260
CHAR_WIDTH = 6.6  # at the base font size
BASE_FONT = 11
MIN_FONT = 8
H_GAP = 36
V_GAP = 64
SWEEPS = 6

# A calm palette: light fills with one dark text colour, assigned per package in order of appearance.
FILLS = ("#E8EEF4", "#E6F0E8", "#F1EDE4", "#EDE8F0", "#E4EEF0", "#F0EAE6", "#EAEAEA", "#E9EFE0")
STROKE = "#8A9BA8"
TEXT = "#2E3A46"
EXTERNAL_FILL = "#F5F5F5"
EDGE_COLOURS = {"STATIC": "#5C6F80", "INFERRED": "#8FA3B3", "DYNAMIC_UNKNOWN": "#B5BFC7"}
EDGE_STYLES = {
    "extends": "endArrow=block;endFill=0;endSize=10;",
    "implements": "endArrow=block;endFill=0;endSize=10;dashed=1;",
    "has": "startArrow=diamondThin;startFill=1;startSize=10;endArrow=none;",
    "uses": "endArrow=open;endSize=8;dashed=1;",
    "calls": "endArrow=open;endSize=8;",
}
MERMAID_ARROWS = {"extends": "<|--", "implements": "<|..", "has": "*--", "uses": "..>", "calls": "-->"}


class Placed(TypedDict):
    """One node with its box on the page."""

    id: str
    label: str
    sublabel: str
    x: float
    y: float
    width: float
    height: float
    fill: str
    dashed: bool


class Layout(TypedDict):
    """The whole drawing."""

    page: tuple[int, int]
    orientation: str
    font: int
    scale: float
    nodes: list[Placed]
    packages: dict[str, str]  # package -> fill
    note: str


def layering_edges(edges: Sequence[Mapping[str, Any]]) -> list[tuple[str, str]]:
    """Edges pointing down the page: a base class above what extends or implements it, a caller or user above
    what it calls, uses or has."""
    down: list[tuple[str, str]] = []
    for edge in edges:
        source, target = str(edge["from"]), str(edge["to"])
        if edge.get("kind") in {"extends", "implements"}:
            down.append((target, source))
        else:
            down.append((source, target))
    return down


def acyclic(ids: Sequence[str], down: Sequence[tuple[str, str]]) -> list[tuple[str, str]]:
    """The edges that survive a depth-first cycle break (back edges are dropped for layering only)."""
    successors: dict[str, list[str]] = {node: [] for node in ids}
    for source, target in down:
        if source in successors and target in successors and source != target:
            successors[source].append(target)
    state: dict[str, int] = {}
    kept: list[tuple[str, str]] = []

    def visit(node: str) -> None:
        state[node] = 1
        for child in successors[node]:
            if state.get(child, 0) == 1:
                continue  # a back edge closes a cycle; leave it out of the layering
            kept.append((node, child))
            if state.get(child, 0) == 0:
                visit(child)
        state[node] = 2

    for node in ids:
        if state.get(node, 0) == 0:
            visit(node)
    return kept


def assign_layers(ids: Sequence[str], kept: Sequence[tuple[str, str]]) -> dict[str, int]:
    """Longest-path layering: a node sits one layer below the deepest node pointing at it."""
    predecessors: dict[str, list[str]] = {node: [] for node in ids}
    for source, target in kept:
        predecessors[target].append(source)
    layers: dict[str, int] = {}

    def layer_of(node: str) -> int:
        if node in layers:
            return layers[node]
        layers[node] = 0
        layers[node] = max((layer_of(p) + 1 for p in predecessors[node]), default=0)
        return layers[node]

    for node in ids:
        layer_of(node)
    return layers


def order_layers(ids: Sequence[str], layers: Mapping[str, int], kept: Sequence[tuple[str, str]]) -> list[list[str]]:
    """Rows of nodes, ordered by the barycentre of their neighbours in the adjacent row, swept down and up."""
    depth = max(layers.values(), default=-1) + 1
    rows: list[list[str]] = [[] for _ in range(depth)]
    for node in ids:
        rows[layers[node]].append(node)
    neighbours: dict[str, list[str]] = {node: [] for node in ids}
    for source, target in kept:
        neighbours[source].append(target)
        neighbours[target].append(source)
    for sweep in range(SWEEPS):
        sequence = range(1, depth) if sweep % 2 == 0 else range(depth - 2, -1, -1)
        for index in sequence:
            reference = rows[index - 1] if sweep % 2 == 0 else rows[index + 1]
            position = {node: i for i, node in enumerate(reference)}

            def centre(node: str, position: Mapping[str, int] = position, row_index: int = index) -> float:
                places = [position[n] for n in neighbours[node] if n in position]
                return sum(places) / len(places) if places else rows[row_index].index(node)

            rows[index] = sorted(rows[index], key=lambda n: (centre(n), n))
    return rows


def label_of(node: Mapping[str, Any]) -> tuple[str, str]:
    name = str(node.get("name") or node.get("id"))
    kind = str(node.get("kind") or "class")
    package = node.get("package") or ""
    sub = kind if kind in {"class", "method"} and not package else (f"{kind} · {package}" if package else kind)
    return (name, sub)


def box_width(label: str, sublabel: str) -> float:
    longest = max(len(label), int(len(sublabel) * 0.9))
    return max(MIN_NODE_WIDTH, min(MAX_NODE_WIDTH, longest * CHAR_WIDTH + 24))


def layout(graph: Mapping[str, Any]) -> Layout:
    """Place every node on an A4 page (portrait or landscape, whichever fits the shape better)."""
    nodes = list(graph.get("nodes", []))
    ids = [str(n["id"]) for n in nodes]
    by_id = {str(n["id"]): n for n in nodes}
    kept = acyclic(ids, layering_edges(graph.get("edges", [])))
    rows = order_layers(ids, assign_layers(ids, kept), kept)
    packages: dict[str, str] = {}
    for node in nodes:
        package = str(node.get("package") or "(default)")
        if package not in packages:
            packages[package] = FILLS[len(packages) % len(FILLS)]
    row_widths = []
    placed_rows: list[list[Placed]] = []
    for row in rows:
        boxes: list[Placed] = []
        x = 0.0
        for node_id in row:
            node = by_id[node_id]
            label, sublabel = label_of(node)
            width = box_width(label, sublabel)
            external = node.get("kind") == "external"
            boxes.append(
                {
                    "id": node_id,
                    "label": label,
                    "sublabel": sublabel,
                    "x": x,
                    "y": 0.0,
                    "width": width,
                    "height": float(NODE_HEIGHT),
                    "fill": EXTERNAL_FILL if external else packages[str(node.get("package") or "(default)")],
                    "dashed": bool(node.get("test")) or external,
                }
            )
            x += width + H_GAP
        row_widths.append(max(0.0, x - H_GAP))
        placed_rows.append(boxes)
    total_width = max(row_widths, default=0.0)
    total_height = max(0.0, len(rows) * NODE_HEIGHT + (len(rows) - 1) * V_GAP)
    page = A4_LANDSCAPE if total_width > total_height * 1.15 else A4_PORTRAIT
    usable_width = page[0] - 2 * MARGIN
    usable_height = page[1] - 2 * MARGIN - LEGEND_HEIGHT
    scale = min(
        1.0, usable_width / total_width if total_width else 1.0, usable_height / total_height if total_height else 1.0
    )
    font = max(MIN_FONT, round(BASE_FONT * scale))
    note = ""
    if scale < MIN_FONT / BASE_FONT:
        note = "too many nodes for one readable page: narrow the scope (fewer seeds, lower depth) or print on A3"
    placed: list[Placed] = []
    for index, boxes in enumerate(placed_rows):
        offset = (total_width - row_widths[index]) / 2
        for box in boxes:
            box["x"] = MARGIN + (box["x"] + offset) * scale
            box["y"] = MARGIN + index * (NODE_HEIGHT + V_GAP) * scale
            box["width"] = box["width"] * scale
            box["height"] = NODE_HEIGHT * scale
            placed.append(box)
    return {
        "page": page,
        "orientation": "landscape" if page == A4_LANDSCAPE else "portrait",
        "font": font,
        "scale": scale,
        "nodes": placed,
        "packages": packages,
        "note": note,
    }


def overlaps(a: Placed, b: Placed) -> bool:
    return not (
        a["x"] + a["width"] <= b["x"]
        or b["x"] + b["width"] <= a["x"]
        or a["y"] + a["height"] <= b["y"]
        or b["y"] + b["height"] <= a["y"]
    )


def check_layout(drawn: Layout) -> list[str]:
    """Problems a reader would see: boxes that overlap, or boxes outside the page. Empty when the page is clean."""
    problems: list[str] = []
    width, height = drawn["page"]
    nodes = drawn["nodes"]
    for index, box in enumerate(nodes):
        if box["x"] < 0 or box["y"] < 0 or box["x"] + box["width"] > width or box["y"] + box["height"] > height:
            problems.append(f"{box['id']} leaves the page")
        for other in nodes[index + 1 :]:
            if overlaps(box, other):
                problems.append(f"{box['id']} overlaps {other['id']}")
    return problems


def cell_id(node_id: str) -> str:
    return "n-" + "".join(c if c.isalnum() else "_" for c in node_id)


def edge_label(edge: Mapping[str, Any]) -> str:
    count = int(edge.get("count") or 1)
    kind = str(edge.get("kind"))
    if kind == "calls" and count > 1:
        return str(count)
    return ""


def to_drawio(graph: Mapping[str, Any], drawn: Layout | None = None, *, title: str = "groovyscope") -> str:
    """A draw.io (diagrams.net) file: one page, the nodes as rounded boxes, the edges styled by kind and faded
    by confidence, a legend band at the bottom."""
    drawn = drawn or layout(graph)
    width, height = drawn["page"]
    font = drawn["font"]
    cells: list[str] = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>']
    for box in drawn["nodes"]:
        small = max(MIN_FONT, font - 2)
        value = (
            f"<b>{html.escape(box['label'])}</b><br/>"
            f'<font style="font-size: {small}px">{html.escape(box["sublabel"])}</font>'
        )
        style = (
            f"rounded=1;whiteSpace=wrap;html=1;arcSize=12;fillColor={box['fill']};strokeColor={STROKE};"
            f"fontColor={TEXT};fontSize={font};fontFamily=Helvetica;align=center;verticalAlign=middle;"
            + ("dashed=1;" if box["dashed"] else "")
        )
        cells.append(
            f'<mxCell id="{cell_id(box["id"])}" value="{html.escape(value, quote=True)}" style="{style}" '
            f'vertex="1" parent="1"><mxGeometry x="{box["x"]:.1f}" y="{box["y"]:.1f}" width="{box["width"]:.1f}" '
            f'height="{box["height"]:.1f}" as="geometry"/></mxCell>'
        )
    placed_ids = {box["id"] for box in drawn["nodes"]}
    for index, edge in enumerate(graph.get("edges", [])):
        source, target = str(edge["from"]), str(edge["to"])
        if source not in placed_ids or target not in placed_ids:
            continue
        confidence = str(edge.get("confidence") or "STATIC")
        style = (
            f"edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;jettySize=auto;orthogonalLoop=1;"
            f"strokeColor={EDGE_COLOURS.get(confidence, EDGE_COLOURS['STATIC'])};strokeWidth=1.2;"
            f"fontSize={max(MIN_FONT, font - 2)};"
            f"fontColor={TEXT};labelBackgroundColor=#FFFFFF;"
            + EDGE_STYLES.get(str(edge.get("kind")), EDGE_STYLES["calls"])
            + ("dashPattern=1 3;dashed=1;" if confidence == "DYNAMIC_UNKNOWN" else "")
        )
        cells.append(
            f'<mxCell id="e-{index}" value="{html.escape(edge_label(edge), quote=True)}" style="{style}" '
            'edge="1" parent="1" '
            f'source="{cell_id(source)}" target="{cell_id(target)}"><mxGeometry relative="1" as="geometry"/></mxCell>'
        )
    cells += legend_cells(drawn, font)
    model = (
        f'<mxGraphModel dx="0" dy="0" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" '
        f'page="1" pageScale="1" pageWidth="{width}" pageHeight="{height}" background="#FFFFFF"><root>'
        + "".join(cells)
        + "</root></mxGraphModel>"
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<mxfile host="groovyscope" type="device"><diagram name="{html.escape(title, quote=True)}" id="groovyscope-1">'
        + model
        + "</diagram></mxfile>\n"
    )


def legend_cells(drawn: Layout, font: int) -> list[str]:
    """A band at the bottom of the page naming the edge kinds and the package colours."""
    width, height = drawn["page"]
    top = height - MARGIN - LEGEND_HEIGHT + 8
    small = max(MIN_FONT, font - 2)
    lines = ["extends: hollow arrow · implements: dashed hollow arrow · has: diamond · uses: dashed · calls: solid"]
    lines.append("grey dotted edges: matched by name only (DYNAMIC_UNKNOWN) · dashed boxes: tests and external classes")
    text = "<br/>".join(html.escape(line) for line in lines)
    cells = [
        f'<mxCell id="legend-text" value="{html.escape(text, quote=True)}" '
        f'style="text;html=1;align=left;verticalAlign=top;fontSize={small};fontColor={TEXT};fontFamily=Helvetica;" '
        'vertex="1" parent="1">'
        f'<mxGeometry x="{MARGIN}" y="{top}" width="{width - 2 * MARGIN}" height="30" as="geometry"/></mxCell>'
    ]
    x = float(MARGIN)
    for package, fill in drawn["packages"].items():
        swatch_width = min(220.0, len(package) * (small * 0.6) + 24)
        if x + swatch_width > width - MARGIN:
            break
        cells.append(
            f'<mxCell id="legend-{cell_id(package)}" value="{html.escape(package, quote=True)}" '
            f'style="rounded=1;html=1;fillColor={fill};strokeColor={STROKE};fontColor={TEXT};fontSize={small};'
            'fontFamily=Helvetica;" vertex="1" parent="1">'
            f'<mxGeometry x="{x:.1f}" y="{top + 36}" width="{swatch_width:.1f}" height="22" as="geometry"/></mxCell>'
        )
        x += swatch_width + 10
    return cells


def mermaid_name(node_id: str) -> str:
    return "".join(c if c.isalnum() else "_" for c in node_id.rsplit(".", 1)[-1])


def to_mermaid(graph: Mapping[str, Any]) -> str:
    """A Mermaid class diagram of the same graph, for chat answers and Markdown."""
    lines = ["classDiagram"]
    nodes = list(graph.get("nodes", []))
    names: dict[str, str] = {}
    for node in nodes:
        node_id = str(node["id"])
        name = mermaid_name(node_id)
        while name in names.values():
            name += "_"
        names[node_id] = name
        kind = str(node.get("kind") or "class")
        lines.append(f"    class {name}")
        if kind not in {"class", "method"}:
            lines.append(f"    <<{kind}>> {name}")
    for edge in graph.get("edges", []):
        source, target = names.get(str(edge["from"])), names.get(str(edge["to"]))
        if source is None or target is None:
            continue
        kind = str(edge.get("kind"))
        arrow = MERMAID_ARROWS.get(kind, "-->")
        label = ""
        if kind == "calls":
            count = int(edge.get("count") or 1)
            label = f" : calls{' x' + str(count) if count > 1 else ''}"
            if edge.get("confidence") == "DYNAMIC_UNKNOWN":
                label += " (by name)"
        elif kind == "uses":
            label = " : uses"
        if kind in {"extends", "implements", "has"}:
            lines.append(f"    {target} {arrow} {source}{label}")
        else:
            lines.append(f"    {source} {arrow} {target}{label}")
    return "\n".join(lines) + "\n"


def compact(graph: Mapping[str, Any], cap: int) -> dict[str, Any]:
    """The graph the MCP tool returns: ids, names, kinds and packages for nodes; kind, confidence and count for
    edges; capped, never file bodies."""
    nodes = [
        {
            k: v
            for k, v in {
                "id": n.get("id"),
                "name": n.get("name"),
                "kind": n.get("kind"),
                "package": n.get("package"),
                "test": n.get("test") or None,
            }.items()
            if v is not None
        }
        for n in list(graph.get("nodes", []))[:cap]
    ]
    kept = {n["id"] for n in nodes}
    edges = [
        {
            "from": e.get("from"),
            "to": e.get("to"),
            "kind": e.get("kind"),
            "confidence": e.get("confidence"),
            "count": e.get("count"),
        }
        for e in graph.get("edges", [])
        if e.get("from") in kept and e.get("to") in kept
    ]
    return {
        "nodes": nodes,
        "edges": edges[: cap * 4],
        "nodeCount": len(graph.get("nodes", [])),
        "edgeCount": len(graph.get("edges", [])),
        "truncated": bool(graph.get("truncated")) or len(graph.get("nodes", [])) > cap,
        "totalClasses": graph.get("totalClasses"),
    }
