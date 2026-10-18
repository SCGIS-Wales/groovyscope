"""Relationship graphs as pictures: a layered layout (base classes and callers above, what they use below) on the
smallest paper that keeps the text readable (A4, then A3, then A2), a calm palette, no overlapping boxes or text,
written as a draw.io file or a Mermaid diagram.

The graph comes from the engine (``groovyscope/graph``): nodes are classes (or one class's methods), edges are
``extends``, ``implements``, ``has``, ``uses`` and ``calls`` with a confidence. Everything here is pure: the same
graph always gives the same file.
"""

from __future__ import annotations

import html
from collections.abc import Mapping, Sequence
from typing import Any, TypedDict

# Paper at 96 dpi, as draw.io prints it (short side, long side), smallest first: a drawing moves to the next size
# instead of shrinking its text below MIN_FONT. Margins keep the drawing off the page edge.
PAPERS = (("A4", (827, 1169)), ("A3", (1169, 1654)), ("A2", (1654, 2339)))
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
    row: int
    references: str  # the shared helpers this node calls, named instead of drawn (see groovyscope.diagram)


class Route(TypedDict):
    """One edge as a polyline from its source port to its target port, every segment horizontal or vertical."""

    edge: int  # index into the graph's edges
    points: list[tuple[float, float]]
    exit: tuple[float, float]  # the port on the source box, relative (0..1, 0 top or 1 bottom)
    entry: tuple[float, float]  # the port on the target box


class Layout(TypedDict):
    """The whole drawing."""

    page: tuple[int, int]
    paper: str  # A4, A3 or A2 (a drawing larger than A2 at a readable size extends the A2 page)
    orientation: str
    font: int
    scale: float
    nodes: list[Placed]
    routes: list[Route]
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
    # An odd number of sweeps, so the last one runs downwards: each row ends ordered by the row above it, which keeps
    # the callees or subclasses of one node together under it.
    for sweep in range(SWEEPS + 1 - SWEEPS % 2):
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


def adjacent_neighbours(
    rows: Sequence[Sequence[str]], layers: Mapping[str, int], kept: Sequence[tuple[str, str]]
) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
    """For every node, its neighbours one row up and one row down (longer edges do not pull on positions)."""
    up: dict[str, list[str]] = {node: [] for row in rows for node in row}
    down: dict[str, list[str]] = {node: [] for row in rows for node in row}
    for source, target in kept:
        if layers[target] == layers[source] + 1:
            down[source].append(target)
            up[target].append(source)
    return up, down


def place_row(row: Sequence[str], widths: Mapping[str, float], wanted: Mapping[str, float]) -> dict[str, float]:
    """Left edges for one row, in its order, as close to the wanted centres as the gaps allow: the mean of the
    tightest placement pushed right and the tightest pushed left, which keeps every gap and is centred on the
    wishes."""
    want = [wanted[node] - widths[node] / 2 for node in row]
    pushed_right: list[float] = []
    for index in range(len(row)):
        floor = pushed_right[-1] + widths[row[index - 1]] + H_GAP if index else want[index]
        pushed_right.append(max(want[index], floor))
    pushed_left = [0.0] * len(row)
    for index in range(len(row) - 1, -1, -1):
        ceiling = pushed_left[index + 1] - widths[row[index]] - H_GAP if index < len(row) - 1 else want[index]
        pushed_left[index] = min(want[index], ceiling)
    return {node: (pushed_right[i] + pushed_left[i]) / 2 for i, node in enumerate(row)}


def assign_x(
    rows: Sequence[Sequence[str]],
    widths: Mapping[str, float],
    up: Mapping[str, list[str]],
    down: Mapping[str, list[str]],
) -> dict[str, float]:
    """Left edges for every node: children under their parents, then parents centred over their children (the last
    sweep), each row keeping its order and its gaps."""
    left: dict[str, float] = {}
    for row in rows:
        x = 0.0
        for node in row:
            left[node] = x
            x += widths[node] + H_GAP

    def centre(node: str) -> float:
        return left[node] + widths[node] / 2

    def sweep(indexes: Sequence[int], neighbours: Mapping[str, list[str]]) -> None:
        for index in indexes:
            wanted = {
                node: sum(centre(n) for n in neighbours[node]) / len(neighbours[node])
                if neighbours[node]
                else centre(node)
                for node in rows[index]
            }
            left.update(place_row(rows[index], widths, wanted))

    for _ in range(SWEEPS):
        sweep(range(1, len(rows)), up)
        sweep(range(len(rows) - 2, -1, -1), down)
    shift = min(left.values(), default=0.0)
    return {node: x - shift for node, x in left.items()}


READABLE_SCALE = MIN_FONT / BASE_FONT
OVERSIZED = "larger than A2"


def fit_scale(page: tuple[int, int], width: float, height: float) -> float:
    """How far a drawing of ``width`` by ``height`` must shrink to fit ``page`` (1.0 when it fits as it is)."""
    usable_width = page[0] - 2 * MARGIN
    usable_height = page[1] - 2 * MARGIN - LEGEND_HEIGHT
    return min(1.0, usable_width / width if width else 1.0, usable_height / height if height else 1.0)


def wrap_rows(rows: Sequence[Sequence[str]], widths: Mapping[str, float], limit: float) -> list[list[str]]:
    """Each row split, in its order, into lines no wider than ``limit``: a layer of a hundred methods becomes several
    stacked lines instead of one line ten sheets wide."""
    wrapped: list[list[str]] = []
    for row in rows:
        line: list[str] = []
        used = 0.0
        for node in row:
            needed = widths[node] + (H_GAP if line else 0.0)
            if line and used + needed > limit:
                wrapped.append(line)
                line, used, needed = [], 0.0, widths[node]
            line.append(node)
            used += needed
        if line:
            wrapped.append(line)
    return wrapped


def packed_size(rows: Sequence[Sequence[str]], widths: Mapping[str, float]) -> tuple[float, float]:
    """Width and height of the rows packed left to right, one gap more than the rows need below the last (edges
    between boxes of the last row run there)."""
    width = max((sum(widths[n] for n in row) + H_GAP * (len(row) - 1) for row in rows), default=0.0)
    return width, len(rows) * (NODE_HEIGHT + V_GAP)


WRAP_SCALES = (1.0, 0.9, 0.8, MIN_FONT / BASE_FONT)
REWRAP_TRIES = 3
REWRAP_FACTOR = 0.85


def choose_paper(
    rows: Sequence[Sequence[str]], widths: Mapping[str, float]
) -> tuple[str, tuple[int, int], float, list[list[str]]]:
    """The smallest paper (A4, then A3, then A2; portrait or landscape) on which the drawing keeps a readable font:
    the paper name, the page size, the scale and the rows to draw. Rows stay as they are when any of the three holds
    them; only a drawing too wide even for A2 has its wide rows wrapped onto several lines (again A4 first). Past
    that the rows wrap to the A2 width at the readable scale and the page grows downwards."""
    unwrapped = [list(row) for row in rows]
    for name, (short, long) in PAPERS:
        page = max(((long, short), (short, long)), key=lambda c: fit_scale(c, *packed_size(unwrapped, widths)))
        scale = fit_scale(page, *packed_size(unwrapped, widths))
        if scale >= READABLE_SCALE:
            return name, page, scale, unwrapped
    for name, (short, long) in PAPERS:
        best: tuple[float, tuple[int, int], list[list[str]]] | None = None
        for page in ((long, short), (short, long)):
            usable = page[0] - 2 * MARGIN
            for target in WRAP_SCALES:
                candidate = wrap_rows(rows, widths, usable / target)
                scale = fit_scale(page, *packed_size(candidate, widths))
                if scale >= READABLE_SCALE and (best is None or scale > best[0]):
                    best = (scale, page, candidate)
        if best is not None:
            return name, best[1], best[0], best[2]
    short, long = PAPERS[-1][1]
    page = (long, short)
    wrapped = wrap_rows(rows, widths, (page[0] - 2 * MARGIN) / READABLE_SCALE)
    return OVERSIZED, grown_page(page, *packed_size(wrapped, widths)), READABLE_SCALE, wrapped


def grown_page(page: tuple[int, int], width: float, height: float) -> tuple[int, int]:
    """``page``, enlarged where a drawing of ``width`` by ``height`` at the readable scale does not fit on it."""
    needed_width = width * READABLE_SCALE + 2 * MARGIN
    needed_height = height * READABLE_SCALE + 2 * MARGIN + LEGEND_HEIGHT
    return (max(page[0], round(needed_width + 0.5)), max(page[1], round(needed_height + 0.5)))


def layout(graph: Mapping[str, Any]) -> Layout:
    """Place every node on the smallest paper that keeps the text readable (A4, A3 or A2, portrait or landscape) and
    route every edge through the gaps between rows and boxes, so no line crosses a box."""
    nodes = list(graph.get("nodes", []))
    ids = [str(n["id"]) for n in nodes]
    by_id = {str(n["id"]): n for n in nodes}
    kept = acyclic(ids, layering_edges(graph.get("edges", [])))
    layers = assign_layers(ids, kept)
    rows = order_layers(ids, layers, kept)
    packages: dict[str, str] = {}
    for node in nodes:
        package = str(node.get("package") or "(default)")
        if package not in packages:
            packages[package] = FILLS[len(packages) % len(FILLS)]
    labels = {node_id: label_of(by_id[node_id]) for node_id in ids}
    widths = {node_id: box_width(*labels[node_id]) for node_id in ids}
    layered = rows
    paper, page, scale, rows = choose_paper(layered, widths)
    # Centring parents over children can widen a line past its packed width: wrap tighter (a few tries) before the
    # text would have to shrink, and only then grow the page.
    limit = (page[0] - 2 * MARGIN) / scale
    for _ in range(REWRAP_TRIES + 1):
        placed_rows = {node: index for index, row in enumerate(rows) for node in row}
        up, down = adjacent_neighbours(rows, placed_rows, kept)
        left = assign_x(rows, widths, up, down)
        total_width = max((left[n] + widths[n] for n in ids), default=0.0)
        total_height = max(0.0, len(rows) * (NODE_HEIGHT + V_GAP))
        if fit_scale(page, total_width, total_height) >= READABLE_SCALE:
            scale = min(scale, fit_scale(page, total_width, total_height))
            break
        limit *= REWRAP_FACTOR
        rows = wrap_rows(layered, widths, limit)
    else:
        paper, page, scale = OVERSIZED, grown_page(page, total_width, total_height), READABLE_SCALE
    usable_width = page[0] - 2 * MARGIN
    font = max(MIN_FONT, round(BASE_FONT * scale))
    note = ""
    if paper == OVERSIZED:
        note = (
            "larger than A2 at a readable size: the page was extended past A2; print it tiled, or narrow the scope "
            "(fewer seeds, lower depth, a lower max_nodes)"
        )
    offset = (usable_width - total_width * scale) / 2
    placed: list[Placed] = []
    for index, row in enumerate(rows):
        for node_id in row:
            node = by_id[node_id]
            label, sublabel = labels[node_id]
            external = node.get("kind") == "external"
            placed.append(
                {
                    "id": node_id,
                    "label": label,
                    "sublabel": sublabel,
                    "x": MARGIN + max(0.0, offset) + left[node_id] * scale,
                    "y": MARGIN + index * (NODE_HEIGHT + V_GAP) * scale,
                    "width": widths[node_id] * scale,
                    "height": NODE_HEIGHT * scale,
                    "fill": EXTERNAL_FILL if external else packages[str(node.get("package") or "(default)")],
                    "dashed": bool(node.get("test")) or external,
                    "row": index,
                    "references": "",
                }
            )
    return {
        "page": page,
        "paper": "A2" if paper == OVERSIZED else paper,
        "orientation": "landscape" if page[0] > page[1] else "portrait",
        "font": font,
        "scale": scale,
        "nodes": placed,
        "routes": route_edges(graph.get("edges", []), placed, V_GAP * scale),
        "packages": packages,
        "note": note,
    }


HIERARCHY = frozenset({"extends", "implements"})
EPSILON = 1e-6
PortKey = tuple[str, str, str]  # node, side (top or bottom), what shares the port


class Plan(TypedDict):
    """One edge before routing: its upper and lower box, its port keys and the corridors through the rows between."""

    index: int
    upper: Placed
    lower: Placed
    same: bool
    upper_key: PortKey
    lower_key: PortKey
    corridors: list[float]
    reversed: bool


def middle(box: Placed) -> float:
    return box["x"] + box["width"] / 2


def route_edges(edges: Sequence[Mapping[str, Any]], placed: Sequence[Placed], gap: float) -> list[Route]:
    """Orthogonal routes: down from the upper box's bottom, along a lane in the gap below its row, through the space
    between boxes of any row in between, into the lower box's top. Children of one base class share its port and
    lane (one bus per base class); every other edge gets its own port and lane."""
    rows: dict[int, list[Placed]] = {}
    for box in placed:
        rows.setdefault(box["row"], []).append(box)
    for row in rows.values():
        row.sort(key=lambda b: b["x"])
    plans = plan_edges(edges, {box["id"]: box for box in placed}, rows)
    ports = port_positions(plans, {box["id"]: box for box in placed})
    lanes = lane_positions(plans, ports, rows, gap)
    return [polyline(plan, ports, lanes) for plan in plans]


def plan_edges(
    edges: Sequence[Mapping[str, Any]], boxes: Mapping[str, Placed], rows: Mapping[int, Sequence[Placed]]
) -> list[Plan]:
    plans: list[Plan] = []
    for index, edge in enumerate(edges):
        source, target = str(edge["from"]), str(edge["to"])
        if source not in boxes or target not in boxes or source == target:
            continue
        a, b = boxes[source], boxes[target]
        same = a["row"] == b["row"]
        upper, lower = (a, b) if same or a["row"] < b["row"] else (b, a)
        # One port and one lane per base class for its subclasses, and per caller for its calls (a fan-out bus);
        # an edge pointing upwards (a call back to a caller) keeps its own.
        hierarchy = str(edge.get("kind")) in HIERARCHY
        shared = upper["id"] == (target if hierarchy else source)
        span = lower["row"] - upper["row"]
        plans.append(
            {
                "index": index,
                "upper": upper,
                "lower": lower,
                "same": same,
                "upper_key": (upper["id"], "bottom", f"k:{edge.get('kind')}" if shared else f"e{index}"),
                "lower_key": (lower["id"], "bottom" if same else "top", f"e{index}"),
                "corridors": [
                    corridor(rows[k], middle(upper) + (middle(lower) - middle(upper)) * (k - upper["row"]) / span)
                    for k in range(upper["row"] + 1, lower["row"])
                ],
                "reversed": upper["id"] != source,
            }
        )
    return plans


def port_positions(plans: Sequence[Plan], boxes: Mapping[str, Placed]) -> dict[PortKey, float]:
    """Ports spread evenly along each box side, ordered by where their other ends are, so lines leave without
    crossing at the box."""
    sides: dict[tuple[str, str], dict[str, float]] = {}
    for plan in plans:
        for key, other in ((plan["upper_key"], plan["lower"]), (plan["lower_key"], plan["upper"])):
            peers = sides.setdefault((key[0], key[1]), {})
            peers[key[2]] = peers.get(key[2], 0.0) + middle(other)
    ports: dict[PortKey, float] = {}
    for (node_id, side), peers in sides.items():
        box = boxes[node_id]
        ordered = sorted(peers, key=lambda k: (peers[k], k))
        for position, share in enumerate(ordered):
            ports[(node_id, side, share)] = box["x"] + box["width"] * (position + 1) / (len(ordered) + 1)
    return ports


def lane_positions(
    plans: Sequence[Plan], ports: Mapping[PortKey, float], rows: Mapping[int, Sequence[Placed]], gap: float
) -> dict[tuple[int, str], float]:
    """The y of each horizontal run in the gap below a row: one lane per owner (a shared port, or one edge's own
    run), spread over the gap and ordered left to right."""
    owners: dict[int, dict[str, float]] = {}
    for plan in plans:
        row = plan["upper"]["row"]
        owners.setdefault(row, {}).setdefault(first_owner(plan), ports[plan["upper_key"]])
        for offset, x in enumerate(plan["corridors"], start=1):
            owners.setdefault(row + offset, {}).setdefault(run_owner(plan), x)
    lanes: dict[tuple[int, str], float] = {}
    for row_index, row_owners in owners.items():
        bottom = rows[row_index][0]["y"] + rows[row_index][0]["height"]
        ordered = sorted(row_owners, key=lambda k: (row_owners[k], k))
        for position, owner in enumerate(ordered):
            lanes[(row_index, owner)] = bottom + gap * (position + 1) / (len(ordered) + 1)
    return lanes


def run_owner(plan: Plan) -> str:
    """The lane owner of an edge's runs below the first gap: shared by the edges of one shared port, else its own."""
    shared = plan["upper_key"][2].startswith("k:")
    return "run|" + "|".join(plan["upper_key"]) if shared else f"run{plan['index']}"


def first_owner(plan: Plan) -> str:
    return f"same{plan['index']}" if plan["same"] else "|".join(plan["upper_key"])


def polyline(plan: Plan, ports: Mapping[PortKey, float], lanes: Mapping[tuple[int, str], float]) -> Route:
    upper, lower = plan["upper"], plan["lower"]
    start_x, end_x = ports[plan["upper_key"]], ports[plan["lower_key"]]
    points = [(start_x, upper["y"] + upper["height"])]
    x = start_x
    for step, row_index in enumerate(range(upper["row"], max(lower["row"], upper["row"] + 1))):
        lane = lanes[(row_index, first_owner(plan) if step == 0 else run_owner(plan))]
        following = plan["corridors"][step] if step < len(plan["corridors"]) else end_x
        points += [(x, lane), (following, lane)]
        x = following
    points.append((end_x, lower["y"] + (lower["height"] if plan["same"] else 0.0)))
    points = simplify(points)
    upper_port = ((start_x - upper["x"]) / upper["width"], 1.0)
    lower_port = ((end_x - lower["x"]) / lower["width"], 1.0 if plan["same"] else 0.0)
    if plan["reversed"]:
        points.reverse()
        upper_port, lower_port = lower_port, upper_port
    return {"edge": plan["index"], "points": points, "exit": upper_port, "entry": lower_port}


def corridor(row: Sequence[Placed], wanted: float) -> float:
    """The x of a vertical run through a row: in the free space between its boxes (or beside them) nearest to
    ``wanted``."""
    edges = [-1e9]
    for box in row:
        edges += [box["x"], box["x"] + box["width"]]
    edges.append(1e9)
    best = wanted
    best_distance = float("inf")
    for low, high in zip(edges[0::2], edges[1::2], strict=True):
        pad = min(8.0, (high - low) / 2)
        x = min(max(wanted, low + pad), high - pad)
        if abs(x - wanted) < best_distance:
            best, best_distance = x, abs(x - wanted)
    return best


def simplify(points: Sequence[tuple[float, float]]) -> list[tuple[float, float]]:
    """Drops repeated points and the middle of three points on one line."""
    kept: list[tuple[float, float]] = []
    for point in points:
        if kept and abs(kept[-1][0] - point[0]) < EPSILON and abs(kept[-1][1] - point[1]) < EPSILON:
            continue
        if len(kept) > 1:
            (x0, y0), (x1, y1) = kept[-2], kept[-1]
            if (abs(x0 - x1) < EPSILON and abs(x1 - point[0]) < EPSILON) or (
                abs(y0 - y1) < EPSILON and abs(y1 - point[1]) < EPSILON
            ):
                kept[-1] = point
                continue
        kept.append(point)
    return kept


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
    # Boxes by row band, so each segment is tested only against the rows it spans (a thousand edges over two
    # hundred boxes stays well under a second).
    bands: dict[tuple[float, float], list[Placed]] = {}
    for box in nodes:
        bands.setdefault((box["y"], box["y"] + box["height"]), []).append(box)
    for route in drawn.get("routes", []):
        crossed: list[str] = []
        for start, end in zip(route["points"], route["points"][1:], strict=False):
            low, high = sorted((start[1], end[1]))
            for (top, bottom), boxes in bands.items():
                if low < bottom and high > top:
                    crossed += [box["id"] for box in boxes if box["id"] not in crossed and crosses(start, end, box)]
        problems += [f"edge {route['edge']} crosses {box_id}" for box_id in crossed]
    return problems


def crosses(start: tuple[float, float], end: tuple[float, float], box: Placed) -> bool:
    """True when a horizontal or vertical segment runs through the inside of a box (touching its border is fine)."""
    inset = 0.5
    left, right = box["x"] + inset, box["x"] + box["width"] - inset
    top, bottom = box["y"] + inset, box["y"] + box["height"] - inset
    low_x, high_x = sorted((start[0], end[0]))
    low_y, high_y = sorted((start[1], end[1]))
    return low_x < right and high_x > left and low_y < bottom and high_y > top


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
        if box.get("references"):
            value += f'<br/><font style="font-size: {small}px"><i>{html.escape(box["references"])}</i></font>'

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
    edges = list(graph.get("edges", []))
    for route in drawn["routes"]:
        index = route["edge"]
        edge = edges[index]
        source, target = str(edge["from"]), str(edge["to"])
        confidence = str(edge.get("confidence") or "STATIC")
        # Fixed ports and waypoints with no automatic routing: draw.io draws exactly the route laid out here.
        style = (
            f"edgeStyle=none;rounded=1;html=1;"
            f"exitX={route['exit'][0]:.4f};exitY={route['exit'][1]:.0f};exitDx=0;exitDy=0;exitPerimeter=0;"
            f"entryX={route['entry'][0]:.4f};entryY={route['entry'][1]:.0f};entryDx=0;entryDy=0;entryPerimeter=0;"
            f"strokeColor={EDGE_COLOURS.get(confidence, EDGE_COLOURS['STATIC'])};strokeWidth=1.2;"
            f"fontSize={max(MIN_FONT, font - 2)};"
            f"fontColor={TEXT};labelBackgroundColor=#FFFFFF;"
            + EDGE_STYLES.get(str(edge.get("kind")), EDGE_STYLES["calls"])
            + ("dashPattern=1 3;dashed=1;" if confidence == "DYNAMIC_UNKNOWN" else "")
        )
        waypoints = "".join(f'<mxPoint x="{x:.1f}" y="{y:.1f}"/>' for x, y in route["points"][1:-1])
        cells.append(
            f'<mxCell id="e-{index}" value="{html.escape(edge_label(edge), quote=True)}" style="{style}" '
            'edge="1" parent="1" '
            f'source="{cell_id(source)}" target="{cell_id(target)}"><mxGeometry relative="1" as="geometry">'
            f'<Array as="points">{waypoints}</Array></mxGeometry></mxCell>'
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
    lines.append(
        "grey dotted edges: matched by name only (DYNAMIC_UNKNOWN) · dashed boxes: tests, external classes and shared "
        "helpers (used from many places; each user names them after \u2192 instead of a line)"
    )
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
