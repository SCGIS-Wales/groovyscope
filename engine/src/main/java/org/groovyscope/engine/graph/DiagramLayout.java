/*
 * Copyright 2026 SCGIS-Wales and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.groovyscope.engine.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.elk.alg.layered.options.GreedySwitchType;
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.alg.layered.options.LayeringStrategy;
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy;
import org.eclipse.elk.alg.layered.options.WrappingStrategy;
import org.eclipse.elk.core.RecursiveGraphLayoutEngine;
import org.eclipse.elk.core.data.LayoutMetaDataService;
import org.eclipse.elk.core.math.ElkPadding;
import org.eclipse.elk.core.options.CoreOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.core.options.PortConstraints;
import org.eclipse.elk.core.options.PortSide;
import org.eclipse.elk.core.util.BasicProgressMonitor;
import org.eclipse.elk.graph.ElkBendPoint;
import org.eclipse.elk.graph.ElkConnectableShape;
import org.eclipse.elk.graph.ElkEdge;
import org.eclipse.elk.graph.ElkEdgeSection;
import org.eclipse.elk.graph.ElkNode;
import org.eclipse.elk.graph.ElkPort;
import org.eclipse.elk.graph.util.ElkGraphUtil;

/**
 * Box positions and edge routes for a diagram, from the Eclipse Layout Kernel's layered algorithm (Sugiyama): long
 * edges get their own slot in every layer they cross, layer sweeps with greedy switching minimise crossings, network
 * simplex places the boxes so edges run straight, and the orthogonal router gives every edge its own track. Only
 * the subclasses of one base class share a line, through one port under it (the UML hierarchy bus).
 *
 * <p>Coordinates are at scale 1, top left at the origin; the caller fits them to paper.
 */
public final class DiagramLayout {

    static {
        LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(new LayeredMetaDataProvider());
    }

    /** A box to place. */
    public record Box(String id, double width, double height) {
    }

    /**
     * An edge from {@code source} to {@code target}. With {@code hierarchy} the source is a subclass and the target its
     * base class: the base is drawn above and its subclasses meet in one port under it.
     */
    public record Link(String id, String source, String target, boolean hierarchy) {
    }

    /**
     * Spacing between boxes in a layer, between layers and between parallel edges; {@code aspectRatio} above zero
     * wraps a long chain of layers into several stripes to approach that width-to-height ratio.
     */
    public record Options(double nodeSpacing, double layerSpacing, double edgeSpacing, double aspectRatio,
            int layerBound) {
        public static final Options DEFAULTS = new Options(36, 56, 10, 0, 0);
    }

    /** Where a box went (top left corner). */
    public record Position(double x, double y) {
    }

    /** The placed boxes, each edge as a polyline from its source's border to its target's, and the drawing's size. */
    public record Result(Map<String, Position> boxes, Map<String, List<double[]>> routes, double width,
            double height) {
    }

    private DiagramLayout() {
    }

    public static Result layout(List<Box> boxes, List<Link> links, Options options) {
        ElkNode root = ElkGraphUtil.createGraph();
        configure(root, options);
        Map<String, ElkNode> nodes = new LinkedHashMap<>();
        for (Box box : boxes) {
            ElkNode node = ElkGraphUtil.createNode(root);
            node.setIdentifier(box.id());
            node.setDimensions(box.width(), box.height());
            nodes.put(box.id(), node);
        }
        Map<String, ElkPort> basePorts = new HashMap<>();
        Map<String, ElkEdge> edges = new LinkedHashMap<>();
        Map<String, Boolean> reversed = new HashMap<>();
        for (Link link : links) {
            ElkNode source = nodes.get(link.source());
            ElkNode target = nodes.get(link.target());
            if (source == null || target == null || source == target) {
                continue;
            }
            ElkConnectableShape from;
            ElkConnectableShape to;
            if (link.hierarchy()) {
                // Laid out from the base class down to the subclass, so bases sit above; the drawn route is reversed.
                from = basePorts.computeIfAbsent(link.target(), id -> southPort(target));
                to = source;
                reversed.put(link.id(), true);
            } else {
                from = source;
                to = target;
            }
            edges.put(link.id(), ElkGraphUtil.createSimpleEdge(from, to));
        }
        new RecursiveGraphLayoutEngine().layout(root, new BasicProgressMonitor());

        Map<String, Position> placed = new LinkedHashMap<>();
        for (Map.Entry<String, ElkNode> entry : nodes.entrySet()) {
            placed.put(entry.getKey(), new Position(entry.getValue().getX(), entry.getValue().getY()));
        }
        Map<String, List<double[]>> routes = new LinkedHashMap<>();
        for (Map.Entry<String, ElkEdge> entry : edges.entrySet()) {
            List<double[]> points = points(entry.getValue());
            if (points.isEmpty()) {
                continue;
            }
            if (reversed.getOrDefault(entry.getKey(), false)) {
                java.util.Collections.reverse(points);
            }
            routes.put(entry.getKey(), points);
        }
        return new Result(placed, routes, root.getWidth(), root.getHeight());
    }

    private static void configure(ElkNode root, Options options) {
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
        root.setProperty(CoreOptions.DIRECTION, Direction.DOWN);
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL);
        root.setProperty(CoreOptions.PADDING, new ElkPadding(0));
        root.setProperty(LayeredOptions.SPACING_NODE_NODE, options.nodeSpacing());
        root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, options.layerSpacing());
        root.setProperty(LayeredOptions.SPACING_EDGE_EDGE, options.edgeSpacing());
        root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, options.edgeSpacing());
        root.setProperty(LayeredOptions.SPACING_EDGE_NODE, options.edgeSpacing() * 1.5);
        root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, options.edgeSpacing() * 1.5);
        root.setProperty(LayeredOptions.THOROUGHNESS, 20);
        root.setProperty(LayeredOptions.CROSSING_MINIMIZATION_GREEDY_SWITCH_TYPE, GreedySwitchType.TWO_SIDED);
        root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX);
        root.setProperty(LayeredOptions.FEEDBACK_EDGES, true);
        root.setProperty(LayeredOptions.SEPARATE_CONNECTED_COMPONENTS, true);
        if (options.layerBound() > 0) {
            // At most this many boxes per layer: a hundred callees of one method spread over several layers instead
            // of one row many sheets wide (Coffman-Graham layering keeps every edge pointing down).
            root.setProperty(LayeredOptions.LAYERING_STRATEGY, LayeringStrategy.COFFMAN_GRAHAM);
            root.setProperty(LayeredOptions.LAYERING_COFFMAN_GRAHAM_LAYER_BOUND, options.layerBound());
        }
        if (options.aspectRatio() > 0) {
            root.setProperty(LayeredOptions.WRAPPING_STRATEGY, WrappingStrategy.MULTI_EDGE);
            root.setProperty(CoreOptions.ASPECT_RATIO, options.aspectRatio());
        }
    }

    private static ElkPort southPort(ElkNode base) {
        base.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE);
        ElkPort port = ElkGraphUtil.createPort(base);
        port.setDimensions(1, 1);
        port.setProperty(CoreOptions.PORT_SIDE, PortSide.SOUTH);
        return port;
    }

    private static List<double[]> points(ElkEdge edge) {
        List<double[]> points = new ArrayList<>();
        if (edge.getSections().isEmpty()) {
            return points;
        }
        ElkEdgeSection section = edge.getSections().get(0);
        points.add(new double[] {section.getStartX(), section.getStartY()});
        for (ElkBendPoint bend : section.getBendPoints()) {
            points.add(new double[] {bend.getX(), bend.getY()});
        }
        points.add(new double[] {section.getEndX(), section.getEndY()});
        return points;
    }
}
