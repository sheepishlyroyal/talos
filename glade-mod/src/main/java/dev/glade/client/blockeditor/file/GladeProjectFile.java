package dev.glade.client.blockeditor.file;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.glade.client.blockeditor.model.BlockNode;
import dev.glade.client.blockeditor.model.Workspace;
import dev.glade.client.blockeditor.registry.BlockRegistry;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/** Versioned JSON persistence for .glade projects. The graph, never generated Python, is authoritative. */
public final class GladeProjectFile {
    public static final int FORMAT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Path save(String projectName, Workspace workspace) throws IOException {
        Path file = resolve(projectName);
        Files.createDirectories(file.getParent());
        List<NodeJson> nodes = workspace.nodes().stream().map(NodeJson::from).toList();
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(new ProjectJson(FORMAT_VERSION, List.copyOf(workspace.topLevel()), nodes), writer);
        }
        return file;
    }

    public Workspace load(String projectName) throws IOException {
        Path file = resolve(projectName);
        ProjectJson project;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            project = GSON.fromJson(reader, ProjectJson.class);
        }
        if (project == null || project.version != FORMAT_VERSION)
            throw new IOException("Unsupported or missing .glade format version");
        Workspace workspace = new Workspace();
        for (NodeJson raw : project.nodes == null ? List.<NodeJson>of() : project.nodes) {
            try {
                BlockNode node = new BlockNode(raw.id, BlockRegistry.get(raw.type), raw.x, raw.y);
                node.fields().clear(); if (raw.fields != null) node.fields().putAll(raw.fields);
                if (raw.values != null) node.valueInputs().putAll(raw.values);
                if (raw.statements != null) raw.statements.forEach((key, value) ->
                        node.statementInputs().put(key, new ArrayList<>(value)));
                node.next(raw.next); workspace.add(node, false);
            } catch (IllegalArgumentException error) {
                throw new IOException("Unknown block in project: " + raw.type, error);
            }
        }
        if (project.topLevel != null) project.topLevel.stream().filter(id -> workspace.get(id) != null)
                .forEach(workspace.topLevel()::add);
        return workspace;
    }

    public Path resolve(String projectName) {
        String safe = projectName == null ? "untitled" : projectName.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (safe.endsWith(".glade")) safe = safe.substring(0, safe.length() - 6);
        if (safe.isBlank()) safe = "untitled";
        return FabricLoader.getInstance().getGameDir().resolve("glade").resolve("projects").resolve(safe + ".glade");
    }

    private record ProjectJson(int version, List<String> topLevel, List<NodeJson> nodes) {}
    private record NodeJson(String id, String type, double x, double y, Map<String, String> fields,
            Map<String, String> values, Map<String, List<String>> statements, String next) {
        static NodeJson from(BlockNode node) {
            Map<String, List<String>> statements = new LinkedHashMap<>();
            node.statementInputs().forEach((key, value) -> statements.put(key, List.copyOf(value)));
            return new NodeJson(node.id(), node.definition().id(), node.x(), node.y(),
                    new LinkedHashMap<>(node.fields()), new LinkedHashMap<>(node.valueInputs()), statements, node.next());
        }
    }
}
