"""Relationship graphs laid out by the engine's layered layout (the Eclipse Layout Kernel, ``groovyscope/layout``):
crossings minimised layer by layer, boxes placed so edges run straight, every edge routed orthogonally on its own
track. Only the subclasses of one base class share a line (one port under the base).

What makes a call graph unreadable is not the boxes but the shared helpers: a method called from fifteen places
pulls fifteen lines across the page. A node that ``diagram.sharedHelperCallers`` others or more call, use or hold
is drawn once, labelled with how many, and each of them names it inside its own box after an arrow instead of
drawing a line, so every relation is still on the page and the long lines are gone.

The layout is fitted to paper as :mod:`groovyscope.graph` does it: A4, then A3, then A2, never shrinking the text
below a readable size; a drawing too wide for A2 is laid out again with a bound on boxes per layer, and only past
that is the page extended.
"""

from __future__ import annotations

import bisect
from collections.abc import Callable, Iterable, Mapping, Sequence
from typing import Any

from groovyscope import graph as graphs

EngineLayout = Callable[[Mapping[str, Any]], Mapping[str, Any]]
HIERARCHY = graphs.HIERARCHY
# Relations named instead of drawn for a shared helper; inheritance is always drawn.
SHARED_KINDS = frozenset({"calls", "uses", "has"})
REFERENCE_HEIGHT = 16  # the extra line a box gets for the shared helpers it calls
REFERENCE_PREFIX = "\u2192 "  # an arrow: the box calls, uses or holds each name after it
EDGE_SPACING = 10


def shared_helpers(
    edges: Sequence[Mapping[str, Any]], min_callers: int
) -> tuple[set[str], dict[str, list[str]], dict[str, int]]:
    """The nodes called (or used) by at least ``min_callers`` distinct others, each caller's list of them, and how
    many call each. ``min_callers`` 0 or below turns the treatment off."""
    if min_callers <= 0:
        return set(), {}, {}
    callers: dict[str, set[str]] = {}
    for edge in edges:
        source, target = str(edge["from"]), str(edge["to"])
        if str(edge.get("kind")) in SHARED_KINDS and source != target:
            callers.setdefault(target, set()).add(source)
    hubs = {node for node, who in callers.items() if len(who) >= min_callers}
    referenced: dict[str, list[str]] = {}
    for hub in sorted(hubs):
        for caller in sorted(callers[hub]):
            referenced.setdefault(caller, []).append(hub)
    return hubs, referenced, {hub: len(callers[hub]) for hub in hubs}


def is_shared_edge(edge: Mapping[str, Any], hubs: set[str]) -> bool:
    return str(edge.get("kind")) in SHARED_KINDS and str(edge["to"]) in hubs and str(edge["from"]) != str(edge["to"])


def references_text(names: Iterable[str], limit: int) -> str:
    """``→ a, b, c +2``, cut to ``limit`` characters with the number of names left out."""
    names = list(names)
    shown: list[str] = []
    for index, name in enumerate(names):
        candidate = REFERENCE_PREFIX + ", ".join([*shown, name])
        rest = len(names) - index - 1
        if len(candidate) + (len(f" +{rest}") if rest else 0) > limit and shown:
            return REFERENCE_PREFIX + ", ".join(shown) + f" +{len(names) - len(shown)}"
        shown.append(name)
    return REFERENCE_PREFIX + ", ".join(shown) if shown else ""


def short_name(node: Mapping[str, Any] | None, node_id: str) -> str:
    return str((node or {}).get("name") or node_id.rsplit(".", 1)[-1])


def boxes_for(
    graph: Mapping[str, Any], hubs: set[str], referenced: Mapping[str, list[str]], callers: Mapping[str, int]
) -> list[dict[str, Any]]:
    """Every node's text and size: name, kind and package, ``called from N`` on a shared helper, and the shared
    helpers a caller uses on a line of its own."""
    nodes = list(graph.get("nodes", []))
    by_id = {str(n["id"]): n for n in nodes}
    limit = int((graphs.MAX_NODE_WIDTH - 24) / (graphs.CHAR_WIDTH * 0.85))
    boxes: list[dict[str, Any]] = []
    for node in nodes:
        node_id = str(node["id"])
        label, sublabel = graphs.label_of(node)
        if node_id in hubs:
            sublabel = f"{sublabel} · used by {callers[node_id]}"
        references = references_text((short_name(by_id.get(h), h) for h in referenced.get(node_id, [])), limit)
        width = graphs.box_width(label, sublabel)
        if references:
            width = max(width, min(graphs.MAX_NODE_WIDTH, len(references) * graphs.CHAR_WIDTH * 0.85 + 24))
        height = graphs.NODE_HEIGHT + (REFERENCE_HEIGHT if references else 0)
        boxes.append(
            {
                "id": node_id,
                "label": label,
                "sublabel": sublabel,
                "references": references,
                "width": width,
                "height": height,
                "node": node,
            }
        )
    return boxes


def request(boxes: Sequence[Mapping[str, Any]], links: Sequence[Mapping[str, Any]], layer_bound: int) -> dict[str, Any]:
    return {
        "boxes": [{"id": b["id"], "width": b["width"], "height": b["height"]} for b in boxes],
        "links": list(links),
        "options": {
            "nodeSpacing": graphs.H_GAP,
            "layerSpacing": graphs.V_GAP,
            "edgeSpacing": EDGE_SPACING,
            "layerBound": layer_bound,
        },
    }


def best_page(width: float, height: float) -> tuple[str, tuple[int, int], float] | None:
    """The smallest paper, in its better orientation, on which a drawing this size keeps a readable font."""
    for name, (short, long) in graphs.PAPERS:
        page = max(((long, short), (short, long)), key=lambda candidate: graphs.fit_scale(candidate, width, height))
        scale = graphs.fit_scale(page, width, height)
        if scale >= graphs.READABLE_SCALE:
            return name, page, scale
    return None


def layer_bounds(boxes: Sequence[Mapping[str, Any]]) -> list[int]:
    """Boxes per layer that would fit the width of A4, A3 and A2 (each orientation) at the readable scale."""
    if not boxes:
        return []
    average = sum(float(b["width"]) for b in boxes) / len(boxes) + graphs.H_GAP
    bounds: list[int] = []
    for _name, (short, long) in graphs.PAPERS:
        for page_width in (long, short):
            bound = max(2, int((page_width - 2 * graphs.MARGIN) / graphs.READABLE_SCALE / average))
            if bound not in bounds:
                bounds.append(bound)
    return sorted(bounds, reverse=True)


def layout(graph: Mapping[str, Any], engine: EngineLayout, *, shared_callers: int = 0) -> graphs.Layout:
    """The drawing for ``graph``, laid out by ``engine`` (a ``groovyscope/layout`` call) and fitted to paper."""
    edges = list(graph.get("edges", []))
    hubs, referenced, callers = shared_helpers(edges, shared_callers)
    boxes = boxes_for(graph, hubs, referenced, callers)
    links = [
        {
            "id": str(index),
            "source": str(edge["from"]),
            "target": str(edge["to"]),
            "hierarchy": str(edge.get("kind")) in HIERARCHY,
        }
        for index, edge in enumerate(edges)
        if not is_shared_edge(edge, hubs)
    ]
    # Candidates: the free layering, then a bound on boxes per layer for each paper width. The smallest paper that
    # keeps the text readable wins; on the same paper, fewer crossings, then the larger text.
    candidates: list[tuple[int, int, float, tuple[str, tuple[int, int], float], Mapping[str, Any]]] = []
    fallback: Mapping[str, Any] | None = None
    for bound in [0, *layer_bounds(boxes)]:
        result = engine(request(boxes, links, bound))
        if fallback is None or area(result) < area(fallback):
            fallback = result
        fit = best_page(float(result["width"]), float(result["height"]))
        if fit is not None:
            rank = [name for name, _size in graphs.PAPERS].index(fit[0])
            crossings = route_quality(
                placed_layout(graph, boxes, result, page=fit[1], paper=fit[0], scale=fit[2], hubs=hubs, note=""),
                graph,
            )["crossings"]
            candidates.append((rank, crossings, -fit[2], fit, result))
            if rank == 0 and crossings == 0:
                break
    note = ""
    if candidates:
        *_order, chosen, result = min(candidates, key=lambda c: (c[0], c[1], c[2]))
    else:
        result = fallback if fallback is not None else engine(request(boxes, links, 0))
        width, height = float(result["width"]), float(result["height"])
        short, long = graphs.PAPERS[-1][1]
        base = (long, short) if width >= height else (short, long)
        chosen = (graphs.OVERSIZED, graphs.grown_page(base, width, height), graphs.READABLE_SCALE)
        note = (
            "larger than A2 at a readable size: the page was extended past A2; print it tiled, or narrow the scope "
            "(fewer seeds, lower depth, a lower max_nodes)"
        )
    paper, page, scale = chosen
    return placed_layout(graph, boxes, result, page=page, paper=paper, scale=scale, hubs=hubs, note=note)


def area(result: Mapping[str, Any]) -> float:
    return float(result["width"]) * float(result["height"])


def placed_layout(
    graph: Mapping[str, Any],
    boxes: Sequence[Mapping[str, Any]],
    result: Mapping[str, Any],
    *,
    page: tuple[int, int],
    paper: str,
    scale: float,
    hubs: set[str],
    note: str,
) -> graphs.Layout:
    """The engine's coordinates scaled, centred on the page, with each route's ports relative to its boxes."""
    usable_width = page[0] - 2 * graphs.MARGIN
    offset_x = graphs.MARGIN + max(0.0, (usable_width - float(result["width"]) * scale) / 2)
    offset_y = float(graphs.MARGIN)
    positions = result.get("boxes", {})
    packages: dict[str, str] = {}
    placed: list[graphs.Placed] = []
    for box in boxes:
        node = box["node"]
        package = str(node.get("package") or "(default)")
        if package not in packages:
            packages[package] = graphs.FILLS[len(packages) % len(graphs.FILLS)]
        external = node.get("kind") == "external"
        where = positions.get(box["id"], {"x": 0.0, "y": 0.0})
        placed.append(
            {
                "id": box["id"],
                "label": box["label"],
                "sublabel": box["sublabel"],
                "references": box["references"],
                "x": offset_x + float(where["x"]) * scale,
                "y": offset_y + float(where["y"]) * scale,
                "width": float(box["width"]) * scale,
                "height": float(box["height"]) * scale,
                "fill": graphs.EXTERNAL_FILL if external else packages[package],
                "dashed": bool(node.get("test")) or external or box["id"] in hubs,
                "row": 0,
            }
        )
    by_id = {box["id"]: box for box in placed}
    edges = list(graph.get("edges", []))
    routes: list[graphs.Route] = []
    for key, points in (result.get("routes") or {}).items():
        index = int(key)
        source, target = by_id.get(str(edges[index]["from"])), by_id.get(str(edges[index]["to"]))
        if source is None or target is None or len(points) < 2:  # noqa: PLR2004 - a route has two ends at least
            continue
        scaled = [(offset_x + float(x) * scale, offset_y + float(y) * scale) for x, y in points]
        routes.append(
            {"edge": index, "points": scaled, "exit": port(source, scaled[0]), "entry": port(target, scaled[-1])}
        )
    return {
        "page": page,
        "paper": "A2" if paper == graphs.OVERSIZED else paper,
        "orientation": "landscape" if page[0] > page[1] else "portrait",
        "font": max(graphs.MIN_FONT, round(graphs.BASE_FONT * scale)),
        "scale": scale,
        "nodes": placed,
        "routes": routes,
        "packages": packages,
        "note": note,
    }


def port(box: graphs.Placed, point: tuple[float, float]) -> tuple[float, float]:
    """Where ``point`` sits on ``box``, relative (0 to 1 across, 0 to 1 down)."""
    across = (point[0] - box["x"]) / box["width"] if box["width"] else 0.5
    down = (point[1] - box["y"]) / box["height"] if box["height"] else 0.5
    return (min(1.0, max(0.0, across)), min(1.0, max(0.0, down)))


Segment = tuple[int, float, float, float]  # edge index, fixed coordinate, low, high


def route_quality(drawn: graphs.Layout, graph: Mapping[str, Any]) -> dict[str, int]:
    """How clean the lines are: ``crossings`` (a horizontal run crossing a vertical one of another edge) and
    ``overlaps`` (two edges on the same stretch of line). Subclasses sharing their base class's line are the one
    overlap by design and are not counted."""
    edges = list(graph.get("edges", []))
    horizontal: list[Segment] = []
    vertical: list[Segment] = []
    for route in drawn["routes"]:
        for (x1, y1), (x2, y2) in zip(route["points"], route["points"][1:], strict=False):
            if abs(y1 - y2) < graphs.EPSILON:
                horizontal.append((route["edge"], y1, min(x1, x2), max(x1, x2)))
            elif abs(x1 - x2) < graphs.EPSILON:
                vertical.append((route["edge"], x1, min(y1, y2), max(y1, y2)))

    def bus(first: int, second: int) -> bool:
        a, b = edges[first], edges[second]
        return str(a.get("kind")) in HIERARCHY and str(b.get("kind")) in HIERARCHY and a["to"] == b["to"]

    vertical.sort(key=lambda s: s[1])
    xs = [s[1] for s in vertical]
    crossings = 0
    for edge, y, low, high in horizontal:
        for other, _x, top, bottom in vertical[bisect.bisect_right(xs, low) : bisect.bisect_left(xs, high)]:
            if other != edge and top < y < bottom:
                crossings += 1
    return {"crossings": crossings, "overlaps": overlapping(horizontal, bus) + overlapping(vertical, bus)}


def overlapping(segments: Sequence[Segment], bus: Callable[[int, int], bool]) -> int:
    """Pairs of segments of different edges lying on the same line and sharing a stretch longer than a pixel."""
    lines: dict[int, list[Segment]] = {}
    for segment in segments:
        lines.setdefault(round(segment[1]), []).append(segment)
    count = 0
    for line in lines.values():
        line.sort(key=lambda s: s[2])
        for index, (edge, _fixed, _low, high) in enumerate(line):
            for other, _fixed2, low2, high2 in line[index + 1 :]:
                if low2 >= high - 1:
                    break
                if other != edge and min(high, high2) - low2 > 1 and not bus(edge, other):
                    count += 1
    return count
