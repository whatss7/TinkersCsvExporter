package io.github.whatss7.tinkerscsvexporter;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Tier;
import net.minecraftforge.common.TierSortingRegistry;
import slimeknights.tconstruct.library.materials.IMaterialRegistry;
import slimeknights.tconstruct.library.materials.definition.IMaterial;
import slimeknights.tconstruct.library.materials.stats.IMaterialStats;
import slimeknights.tconstruct.library.materials.stats.MaterialStatType;
import slimeknights.tconstruct.library.modifiers.ModifierEntry;
import slimeknights.tconstruct.library.utils.HarvestTiers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Serializes a Tinkers' Construct material stat into a flat map of field name to
 * string value. Record-based stats are read via their record accessors, other
 * types fall back to reflection over declared fields, and the material's bound
 * traits for the stat are appended under the {@code traits} key.
 */
public class MaterialStatsSerializer {
    /**
     * Accumulates every trait encountered during serialization, keyed by the
     * trait's stable modifier id, so a single Markdown document can list all
     * traits with their display names and descriptions. Using the id as the key
     * avoids collisions and language-dependent display names. A
     * {@link LinkedHashMap} preserves first-seen order and deduplicates.
     */
    private final Map<String, TraitInfo> collectedTraits = new LinkedHashMap<>();

    /**
     * Returns the traits collected so far (id -> name/description), in first-seen
     * order. Call this after serializing all materials to build the Markdown doc.
     *
     * @return the accumulated trait id to {@link TraitInfo} map
     */
    public Map<String, TraitInfo> getCollectedTraits() {
        return collectedTraits;
    }

    /**
     * Holds the display name and description of a single collected trait. The
     * trait's id is used as the map key elsewhere.
     *
     * @param name        the trait's human-readable display name
     * @param description the trait's human-readable description
     */
    public record TraitInfo(String name, String description) {
    }

    /**
     * Collects every field of the given stat (plus its traits) into a flat map.
     *
     * @param stats    the stat instance to serialize
     * @param material the owning material, used to look up traits
     * @param registry the material registry
     * @return a map of field name to its string representation
     */
    public Map<String, String> collectFields(IMaterialStats stats, IMaterial material, IMaterialRegistry registry) {
        JsonObject json = toJson(stats, material, registry);
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonElement element = entry.getValue();
            fields.put(entry.getKey(), element.isJsonNull() ? "N/A" : element.getAsString());
        }
        return fields;
    }

    /**
     * Converts a stat instance into a flat {@link JsonObject} of field name to
     * value. Record-based stats are read via their record accessors; other types
     * fall back to reflection over declared fields. The material's traits for
     * this stat are appended under a {@code traits} key.
     */
    private JsonObject toJson(IMaterialStats stats, IMaterial material, IMaterialRegistry registry) {
        JsonObject json = new JsonObject();
        Class<?> clazz = stats.getClass();

        // Prefer record accessors when the stat is a record type.
        RecordComponent[] components = clazz.getRecordComponents();
        if (components != null && components.length > 0) {
            for (RecordComponent component : components) {
                try {
                    String name = component.getName();
                    Object value = component.getAccessor().invoke(stats);
                    addValue(json, name, value);
                } catch (Exception ignored) {
                }
            }
        } else {
            // Non-record stats: read fields reflectively.
            fallbackToReflection(stats, json);
        }

        // Collect the display names of all bound traits for this material/stat pair.
        List<ModifierEntry> traits = registry.getTraits(material.getIdentifier(), stats.getIdentifier());

        StringBuilder modifierBuilder = new StringBuilder();
        for (ModifierEntry entry : traits) {
            if (!entry.isBound()) continue;

            // Display the display name of the trait (contains level).
            Component name = entry.getModifier().getDisplayName(entry.getLevel());
            if (!modifierBuilder.isEmpty()) modifierBuilder.append(", ");
            modifierBuilder.append(name.getString());

            // Remember each unique trait (keyed by its stable modifier id) with its
            // display name and description so a Markdown summary can be produced
            // once all materials are exported.
            Component rawName = entry.getModifier().getDisplayName();
            String id = entry.getModifier().getId().toString();
            Component description = entry.getModifier().getDescription();
            collectedTraits.putIfAbsent(id, new TraitInfo(rawName.getString(), description.getString()));
        }
        if (modifierBuilder.isEmpty()) {
            modifierBuilder.append(I18n.get(TinkersCsvExporter.MOD_ID + ".none"));
        }
        json.addProperty("traits", modifierBuilder.toString());

        return json;
    }

    /**
     * Adds a value to the JSON object using the most appropriate JSON type.
     * Internal metadata types ({@link MaterialStatType} and {@link Class}) are
     * skipped. Unknown types first try to derive a human-readable name via a
     * reflective {@code getDisplayName()} call (see {@link #tryGetDisplayName(Object)}),
     * and only fall back to their {@code toString()} form when that yields nothing.
     */
    private void addValue(JsonObject json, String name, Object value) {
        if (value instanceof MaterialStatType) return;
        if (value instanceof Class) return;

        if (value == null) {
            json.add(name, JsonNull.INSTANCE);
        } else if (value instanceof Number) {
            json.addProperty(name, (Number) value);
        } else if (value instanceof String) {
            json.addProperty(name, (String) value);
        } else if (value instanceof Boolean) {
            json.addProperty(name, (Boolean) value);
        } else if (value instanceof ResourceLocation location) {
            if (Objects.equals(name, "harvestTier")) {
                Tier tier = TierSortingRegistry.byName(location);
                if (tier == null) {
                    json.addProperty(name, location.toString());
                } else {
                    String tierName = HarvestTiers.getName(tier).getString();
                    if (TierSortingRegistry.isTierSorted(tier)) {
                        List<Tier> tierSort = TierSortingRegistry.getSortedTiers();
                        int tierLevel = tierSort.indexOf(tier);
                        json.addProperty(name, tierLevel + " (" + tierName + ")");
                    } else {
                        json.addProperty(name, tierName);
                    }
                }
            }
        } else if (value.getClass().isEnum()) {
            json.addProperty(name, value.toString());
        } else {
            // Before the final toString() fallback, try to obtain a friendlier
            // name via a reflective getDisplayName() call.
            String displayName = tryGetDisplayName(value);
            json.addProperty(name, displayName != null ? displayName : value.toString());
        }
    }

    /**
     * Attempts to derive a human-readable name from {@code value} by reflectively
     * invoking a no-arg {@code getDisplayName()} method, if one exists. The result
     * is used directly when it is a {@link String}, or resolved via a no-arg
     * {@code getString()} call when it is a {@link Component}-like object (any type
     * exposing such a method, e.g. {@link Component}). Returns {@code null} when no
     * usable name can be obtained, so the caller can fall back to
     * {@link Object#toString()}.
     *
     * @param value the object to derive a display name from
     * @return the display name string, or {@code null} if none is available
     */
    private static String tryGetDisplayName(Object value) {
        try {
            Method getDisplayName = value.getClass().getMethod("getDisplayName");
            Object display = getDisplayName.invoke(value);
            if (display == null) return null;
            if (display instanceof String s) return s;

            // Component-like: any object exposing a no-arg getString() method.
            try {
                Method getString = display.getClass().getMethod("getString");
                Object str = getString.invoke(display);
                if (str instanceof String s) return s;
            } catch (NoSuchMethodException ignored) {
                // Not Component-like; fall through to return null.
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Reads all relevant instance fields of {@code obj} via reflection and adds
     * them to {@code json}. Static, synthetic, and outer-class reference fields
     * are skipped.
     */
    private void fallbackToReflection(Object obj, JsonObject json) {
        for (Field f : obj.getClass().getDeclaredFields()) {
            // Skip static, transient, compiler-generated, and outer-instance ("this$") fields.
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (Modifier.isTransient(f.getModifiers())) continue;
            if (f.isSynthetic()) continue;
            if (f.getName().startsWith("this$")) continue;

            f.setAccessible(true);
            try {
                addValue(json, f.getName(), f.get(obj));
            } catch (IllegalAccessException ignored) {
            }
        }
    }
}
