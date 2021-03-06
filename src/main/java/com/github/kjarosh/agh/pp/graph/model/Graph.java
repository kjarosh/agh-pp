package com.github.kjarosh.agh.pp.graph.model;

import com.github.kjarosh.agh.pp.config.Config;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.github.kjarosh.agh.pp.config.Config.ZONE_ID;

/**
 * The local graph representation. It contains only the local portion of the
 * distributed graph. Thread-safe.
 *
 * @author Kamil Jarosz
 */
public class Graph {
    private final Map<VertexId, Vertex> vertices = new ConcurrentHashMap<>();
    private final Map<EdgeId, Edge> edges = new ConcurrentHashMap<>();
    private final Map<VertexId, Set<Edge>> edgesBySrc = new ConcurrentHashMap<>();
    private final Map<VertexId, Set<Edge>> edgesByDst = new ConcurrentHashMap<>();

    public Graph() {

    }

    @SneakyThrows
    public static Graph deserialize(InputStream serialized) {
        Json value = Config.MAPPER.readValue(serialized, Json.class);
        Graph graph = new Graph();
        value.vertices.forEach(graph::addVertex);
        value.edges.forEach(graph::addEdge);
        return graph;
    }

    public void addVertex(Vertex v) {
        if (ZONE_ID == null || v.id().owner().equals(ZONE_ID)) {
            vertices.putIfAbsent(v.id(), v);
        } else {
            throw new IllegalStateException("Wrong vertex zone");
        }
    }

    public boolean hasVertex(VertexId id) {
        return vertices.containsKey(id);
    }

    public boolean hasEdge(EdgeId edgeId) {
        return edges.containsKey(edgeId);
    }

    public Vertex getVertex(VertexId id) {
        Vertex vertex = vertices.get(id);
        if (vertex == null && !hasVertex(id)) {
            throw new RuntimeException(
                    "Vertex " + id + " not found in zone " + Config.ZONE_ID);
        }
        return vertex;
    }

    public void addEdge(Edge e) {
        if (!hasVertex(e.src()) && !hasVertex(e.dst())) {
            throw new IllegalStateException("No src or dst vertex: " + e);
        }

        edges.put(e.id(), e);
        edgesBySrc.computeIfAbsent(e.src(), i -> new ConcurrentSkipListSet<>()).add(e);
        edgesByDst.computeIfAbsent(e.dst(), i -> new ConcurrentSkipListSet<>()).add(e);
    }

    public void removeEdge(Edge e) {
        if (e != null) {
            edges.remove(e.id());
            edgesBySrc.get(e.src()).remove(e);
            edgesByDst.get(e.dst()).remove(e);
        }
    }

    public Edge getEdge(EdgeId edgeId) {
        return edges.get(edgeId);
    }

    public void setPermissions(EdgeId edgeId, Permissions permissions) {
        Edge edge = getEdge(edgeId);
        if (edge == null) {
            throw new IllegalStateException("Edge " + edgeId + " doesn't exist");
        }

        removeEdge(edge);
        addEdge(new Edge(edgeId.getFrom(), edgeId.getTo(), permissions));
    }

    public Set<Edge> getEdgesBySource(VertexId source) {
        return edgesBySrc.getOrDefault(source, Collections.emptySet());
    }

    public Set<Edge> getEdgesByDestination(VertexId destination) {
        return edgesByDst.getOrDefault(destination, Collections.emptySet());
    }

    public Collection<Vertex> allVertices() {
        return vertices.values();
    }

    public Collection<Edge> allEdges() {
        return edges.values();
    }

    public Collection<ZoneId> allZones() {
        Stream<ZoneId> fromVertices = allVertices()
                .stream()
                .map(Vertex::id)
                .map(VertexId::owner);
        Stream<ZoneId> fromEdges = allEdges()
                .stream()
                .flatMap(e -> Stream.of(e.src(), e.dst()))
                .map(VertexId::owner);
        return Stream.concat(fromVertices, fromEdges)
                .collect(Collectors.toUnmodifiableSet());
    }

    @SneakyThrows
    public void serialize(OutputStream os) {
        Json json = new Json();
        json.setEdges(new ArrayList<>(this.edges.values()));
        json.setVertices(new ArrayList<>(this.vertices.values()));
        Config.MAPPER.writeValue(os, json);
    }

    @Override
    public String toString() {
        return "Graph(" + edges.size() + " edges, " + vertices.size() + " vertices)";
    }

    @Getter
    @Setter
    private static class Json {
        private List<Vertex> vertices;
        private List<Edge> edges;
    }
}
