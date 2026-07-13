package io.github.whatss7.tinkerscsvexporter;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import slimeknights.tconstruct.library.client.materials.MaterialTooltipCache;
import slimeknights.tconstruct.library.materials.IMaterialRegistry;
import slimeknights.tconstruct.library.materials.MaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.stats.IMaterialStats;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;

import static net.minecraft.commands.Commands.literal;

/**
 * Implements the {@code /tcexporter export} client command, which dumps all
 * visible Tinkers' Construct materials and their stats to a timestamped CSV file.
 * <p>
 * This class only orchestrates the export: material collection, CSV writing and
 * player feedback. Stat serialization lives in {@link MaterialStatsSerializer}
 * and header translation in {@link HeaderTranslator}.
 */
public class TinkersCsvExporterExportCommand {

    /**
     * Registers the command tree {@code /tcexporter export} with the given
     * dispatcher.
     *
     * @param dispatcher the client command dispatcher provided by Forge
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("tcexporter")
                .then(literal("export")
                        .executes((context) -> TinkersCsvExporterExportCommand.execute(false, false))
                        .then(literal("detailed")
                                .executes((context) -> TinkersCsvExporterExportCommand.execute(false, true)))
                        .then(literal("all")
                                .executes((context) -> TinkersCsvExporterExportCommand.execute(true, false)))
                        .then(literal("all-detailed")
                                .executes((context) -> TinkersCsvExporterExportCommand.execute(true, true)))
                ));
    }

    /**
     * Executes the export: iterates over every visible material, collects its
     * stats, writes them to a CSV file under {@code <gameDir>/tcexporter},
     * and reports the result back to the player.
     *
     * @param includeHidden whether to include hidden materials in the export
     * @param detailed      whether to split header row and translation row
     * @return 1 on success, 0 if there is no local player
     */
    private static int execute(Boolean includeHidden, Boolean detailed) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return 0;

        // Build a unique, timestamped output path inside the game directory.
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path exportPath = gameDir.resolve("tcexporter/materials_" + time + ".csv");

        // Helpers: one serializes stats, the other translates column headers.
        MaterialStatsSerializer serializer = new MaterialStatsSerializer();
        HeaderTranslator translator = new HeaderTranslator(TinkersCsvExporter.MOD_ID);

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

        int exported_count = 0;
        for (IMaterial material : materials) {
            if (exportMaterial(builder, serializer, material, registry, includeHidden)) {
                exported_count++;
            }
        }

        try {
            Path written = builder.buildAndWrite();
            Path traitsMd = gameDir.resolve("tcexporter/traits_" + time + ".md");
            writeTraitsMarkdown(traitsMd, serializer.getCollectedTraits(), detailed);
            player.sendSystemMessage(
                    Component.literal("Exported " + exported_count + " materials to " + written
                            + " (traits: " + traitsMd + ")")
            );
        } catch (IOException e) {
            player.sendSystemMessage(
                    Component.literal("Failed to export materials: " + e.getMessage())
            );
        }
        return 1;
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
     * @return true if the material was exported, false if it had no stats
     */
    private static Boolean exportMaterial(CsvBuilder builder, MaterialStatsSerializer serializer,
                                          IMaterial material, IMaterialRegistry registry, Boolean forced) {
        if (registry.getAllStats(material.getIdentifier()).isEmpty() && !forced) {
            return false;
        }

        String id = material.getIdentifier().toString();

        // Basic identity columns shared by every material.
        builder.addItem(id)
                .put(id, "name", MaterialTooltipCache.getDisplayName(material.getIdentifier()).getString())
                .put(id, "tier", String.valueOf(material.getTier()));

        // Expand each stat type into prefixed columns.
        for (IMaterialStats stat : registry.getAllStats(material.getIdentifier())) {
            String prefix = stat.getIdentifier() + "/";
            for (Map.Entry<String, String> entry : serializer.collectFields(stat, material, registry).entrySet()) {
                builder.put(id, prefix + entry.getKey(), entry.getValue());
            }
        }
        return true;
    }

    /**
     * Writes a Markdown document listing every collected trait and its
     * description. Traits are emitted in first-seen order with a level-2 heading
     * per trait followed by its description paragraph. When {@code detailed} is
     * true, the trait's modifier id is printed beneath the heading as well.
     *
     * @param path     the destination Markdown file path
     * @param traits   the trait name to info map to render
     * @param detailed when true, also emit each trait's id
     * @throws IOException if the file cannot be written
     */
    private static void writeTraitsMarkdown(Path path, Map<String, MaterialStatsSerializer.TraitInfo> traits, Boolean detailed) throws IOException {
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
                        if (detailed) {
                            sb.append("id: ").append(trait.getKey()).append("\n\n");
                        }
                        sb.append(trait.getValue().description()).append("\n\n");
                    });
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }
}
