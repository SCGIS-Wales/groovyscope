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
package org.groovyscope.engine.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.groovyscope.engine.graph.DiagramLayout;

/** The JSON shape of {@code groovyscope/layout}: boxes and links in, positions and routes out. */
final class LayoutJson {

    private LayoutJson() {
    }

    static Map<String, Object> layout(Map<String, Object> params) {
        List<DiagramLayout.Box> boxes = new ArrayList<>();
        for (Map<?, ?> box : maps(params.get("boxes"))) {
            boxes.add(new DiagramLayout.Box(String.valueOf(box.get("id")), number(box.get("width"), 120),
                    number(box.get("height"), 46)));
        }
        List<DiagramLayout.Link> links = new ArrayList<>();
        for (Map<?, ?> link : maps(params.get("links"))) {
            links.add(new DiagramLayout.Link(String.valueOf(link.get("id")), String.valueOf(link.get("source")),
                    String.valueOf(link.get("target")), Boolean.TRUE.equals(link.get("hierarchy"))));
        }
        Map<?, ?> given = params.get("options") instanceof Map<?, ?> map ? map : Map.of();
        DiagramLayout.Options defaults = DiagramLayout.Options.DEFAULTS;
        DiagramLayout.Options options = new DiagramLayout.Options(number(given.get("nodeSpacing"),
                defaults.nodeSpacing()), number(given.get("layerSpacing"), defaults.layerSpacing()),
                number(given.get("edgeSpacing"), defaults.edgeSpacing()), number(given.get("aspectRatio"), 0),
                (int) number(given.get("layerBound"), 0));
        DiagramLayout.Result result = DiagramLayout.layout(boxes, links, options);
        Map<String, Object> placed = new LinkedHashMap<>();
        result.boxes().forEach((id, position) -> placed.put(id, Map.of("x", position.x(), "y", position.y())));
        Map<String, Object> routes = new LinkedHashMap<>();
        result.routes().forEach((id, points) -> {
            List<List<Double>> list = new ArrayList<>();
            for (double[] point : points) {
                list.add(List.of(point[0], point[1]));
            }
            routes.put(id, list);
        });
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("boxes", placed);
        response.put("routes", routes);
        response.put("width", result.width());
        response.put("height", result.height());
        return response;
    }

    private static List<Map<?, ?>> maps(Object value) {
        List<Map<?, ?>> maps = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    maps.add(map);
                }
            }
        }
        return maps;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }
}
