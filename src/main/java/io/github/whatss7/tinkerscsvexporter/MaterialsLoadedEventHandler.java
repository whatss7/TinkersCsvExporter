package io.github.whatss7.tinkerscsvexporter;

import net.minecraft.client.Minecraft;
import slimeknights.tconstruct.library.events.MaterialsLoadedEvent;
import slimeknights.tconstruct.library.materials.IMaterialRegistry;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.stats.IMaterialStats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;

/**
 * Dumps all Tinkers' Construct materials (both visible and hidden) and their
 * stats to a timestamped CSV file, plus a companion Markdown traits document.
 * <p>
 * This class only orchestrates the export: material collection, CSV writing and
 * player feedback. Stat serialization lives in {@link MaterialStatsSerializer}
 * and header translation in {@link HeaderTranslator}.
 */
public class MaterialsLoadedEventHandler {
    static private Boolean hasExported = false;

    public static void onMaterialsLoaded(MaterialsLoadedEvent event) {
        // MaterialsLoadedEvent may be fired more than once, so we only need to export once.
        if (hasExported) return;
        hasExported = true;

        // Skip the whole export when a previous run already produced a CSV, so we
        // don't overwrite or duplicate the generated files on every login.c
        Path exportDir = Minecraft.getInstance().gameDirectory.toPath().resolve("tcexporter");
        if (csvAlreadyExists(exportDir)) return;

        // Export both detailed and non-detailed CSV files.
        exportMaterials(false, false);
        exportMaterials(true, true);
    }

    /**
     * Returns true if {@code exportDir} exists and already holds at least one
     * {@code .csv} file, indicating a prior export has run.
     */
    private static boolean csvAlreadyExists(Path exportDir) {
        if (!Files.isDirectory(exportDir)) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(exportDir, "*.csv")) {
            return stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Executes the export: iterates over every visible material (or all materials
     * when {@code includeHidden} is true), collects its stats, and writes them to
     * a CSV file under {@code <gameDir>/tcexporter}. A companion Markdown document
     * describing every trait encountered is written alongside it.
     *
     * @param includeHidden whether to include hidden materials in the export
     * @param detailed      whether to emit the header and its translation as
     *                      separate rows (the default merges them into one)
     */
    private static void exportMaterials(Boolean includeHidden, Boolean detailed) {
        // Build a unique, timestamped output path inside the game directory.
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String detailedStr = detailed ? "_detailed" : "";
        Path exportPath = gameDir.resolve("tcexporter/materials_" + time + detailedStr + ".csv");

        // Helpers: one serializes stats, the other translates column headers.
        MaterialStatsSerializer serializer = new MaterialStatsSerializer();
        HeaderTranslator translator = new HeaderTranslator();

        CsvBuilder builder = new CsvBuilder(exportPath)
                .nameHeader("id")
                .priority("name", 10)
                .priority("tier", 10)
                .translator(translator::translate)
                .mergeTranslation(!detailed);

        // Query the Tinkers' Construct registry for all player-visible materials,
        // or all materials if includeHidden is true.
        IMaterialRegistry registry = MaterialRegistry.getInstance();
        Collection<IMaterial> materials = includeHidden ? registry.getAllMaterials() : registry.getVisibleMaterials();

        for (IMaterial material : materials) {
            exportMaterial(builder, serializer, material, registry, includeHidden);
        }

        try {
            builder.buildAndWrite();
            if (detailed) {
                Path traitsMd = gameDir.resolve("tcexporter/traits_" + time + "_detailed.md");
                writeTraitsMarkdown(traitsMd, serializer.getCollectedTraits());
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Adds a single material and all of its stat types to the CSV builder. Each
     * stat's fields are namespaced under a {@code <statId>/} prefix so that
     * fields from different stat types don't collide.
     *
     * @param builder    the CSV builder to populate
     * @param serializer serializes a stat into a field name -> value map
     * @param material   the material to export
     * @param registry   the material registry used to look up stats
     * @param forced     whether to export the material even if it has no stats
     */
    private static void exportMaterial(CsvBuilder builder, MaterialStatsSerializer serializer,
                                       IMaterial material, IMaterialRegistry registry, Boolean forced) {
        if (registry.getAllStats(material.getIdentifier()).isEmpty() && !forced) {
            return;
        }

        String id = material.getIdentifier().toString();

        // Basic identity columns shared by every material.
        builder.addItem(id)
                .put(id, "name", material.getDisplayName().getString())
                .put(id, "tier", String.valueOf(material.getTier()));

        // Expand each stat type into prefixed columns.
        for (IMaterialStats stat : registry.getAllStats(material.getIdentifier())) {
            String prefix = stat.getIdentifier() + "/";
            for (Map.Entry<String, String> entry : serializer.collectFields(stat, material, registry).entrySet()) {
                builder.put(id, prefix + entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * Writes a Markdown document listing every collected trait and its
     * description. Traits are emitted in lexicographic (dictionary) order by id
     * with a level-2 heading per trait, its modifier id printed beneath the
     * heading, followed by its description paragraph.
     *
     * @param path   the destination Markdown file path
     * @param traits the trait id to info map to render
     * @throws IOException if the file cannot be written
     */
    private static void writeTraitsMarkdown(Path path, Map<String, MaterialStatsSerializer.TraitInfo> traits) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Material Traits\n\n");
        if (traits.isEmpty()) {
            sb.append("_No traits found._\n");
        } else {
            // Emit traits in lexicographic (dictionary) order by id.
            traits.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(trait -> {
                        sb.append("## ").append(trait.getValue().name()).append("\n\n");
                        sb.append("id: ").append(trait.getKey()).append("\n\n");
                        sb.append(trait.getValue().description()).append("\n\n");
                    });
        }
        Files.createDirectories(path.getParent());
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
