package io.github.whatss7.tinkerscsvexporter;

import net.minecraft.client.resources.language.I18n;

import java.util.Map;

/**
 * Translates Tinkers' Exporter CSV column headers into human-readable labels.
 * <p>
 * The section of the header being translated is decided structurally rather than
 * inferred from the raw value's shape:
 * <ul>
 *   <li>A header with no {@code /} is one of this mod's reserved columns and maps
 *       to the key {@code <modid>.column.<name>}.</li>
 *   <li>A header split by {@code /} has its first half translated as a material
 *       stat identifier ({@code stat.<modid>.<name>}) and its second half as a
 *       tool-stat field ({@code tool_stat.tconstruct.<snake_case>}).</li>
 * </ul>
 * A stat field that shares a reserved column name (e.g. {@code traits}) is
 * translated as a tool-stat and resolves through the upstream key, falling back
 * to this mod's {@code <modid>.fallback.<name>} namespace so it can be supplied
 * via the resource pack.
 * <p>
 * Translation is resolved through {@link I18n}; when the primary (upstream) key
 * is missing, a second lookup under this mod's namespace is attempted so that
 * unrecognized keys can be appended via this mod's resource pack.
 */
public class HeaderTranslator {

    /** Mod identifier, used to namespace fallback translation keys. */
    private final String modId;

    /**
     * Maps a raw tool-stat field name to the canonical key used for translation
     * lookups. A few Tinkers' Construct tool-stat fields are exposed under a
     * different or ambiguous name than the key upstream provides, so they are
     * redirected here instead of branching on each name individually.
     */
    private static final Map<String, String> TOOL_STAT_ALIASES = Map.of(
            "attack", "attack_damage",
            "melee_damage", "attack_damage",
            "melee_speed", "attack_speed",
            "tier", "harvest_tier",
            "toughness", "armor_toughness"
    );

    /**
     * Identifies which portion of a header is being translated. This lets the
     * correct translation key be derived without inspecting the raw value for
     * clues such as a colon or camelCase shape.
     */
    private enum Section {
        /** The material stat identifier, e.g. {@code tconstruct:head}. */
        STAT,
        /** A tool-stat field name, e.g. {@code miningSpeed}. */
        TOOL_STAT,
        /** A reserved column name, e.g. {@code id}, {@code name}, {@code tier}. */
        COLUMN
    }

    /**
     * @param modId the mod identifier (used for fallback resource-pack keys)
     */
    public HeaderTranslator(String modId) {
        this.modId = modId;
    }

    /**
     * Translates a full CSV column header into a label. The translated halves are
     * joined with {@code /}, preserving any punctuation (e.g. the trailing colon
     * of "护甲值："). Returns {@code null} when any half cannot be translated, so
     * the caller can leave the cell blank.
     *
     * @param header the column header, e.g. {@code tconstruct:plating_leggings/armor}
     * @return the combined translation, or {@code null} if construction failed
     */
    public String translate(String header) {
        String[] segments = header.split("/", 2);
        if (segments.length == 1) {
            // No slash: a reserved column.
            return translateSegment(Section.COLUMN, segments[0]);
        }
        // Split by a slash: the first half is the stat, the second half is the
        // tool-stat field (e.g. "traits" resolves via tool_stat.tconstruct.traits
        // and, when untranslated, the tinkerscsvexporter.fallback.traits fallback).
        String stat = translateSegment(Section.STAT, segments[0]);
        if (stat == null) {
            return null;
        }
        String rest = translateSegment(Section.TOOL_STAT, segments[1]);
        if (rest == null) {
            return null;
        }
        return stat + "/" + rest;
    }

    /**
     * Translates a single header segment, deriving the translation key from the
     * explicitly supplied {@link Section} rather than from the value's shape.
     *
     * @param section the portion of the header being translated
     * @param value   the raw segment value
     * @return the translated label, or {@code null} when no translation exists
     */
    private String translateSegment(Section section, String value) {
        return switch (section) {
            case STAT -> {
                String stat = value.replace(':', '.');
                yield lookup("stat." + stat, stat);
            }
            case TOOL_STAT -> {
                String snake = camelToSnake(value);
                snake = TOOL_STAT_ALIASES.getOrDefault(snake, snake);
                yield lookup("tool_stat.tconstruct." + snake, snake);
            }
            case COLUMN -> lookup(modId + ".column." + value.toLowerCase(), value.toLowerCase());
        };
    }

    /**
     * Looks up a Minecraft translation key via the client-side I18n helper.
     * Minecraft returns the key unchanged when no translation is registered, in
     * which case this method reports failure with {@code null} so the caller can
     * leave the cell blank.
     * <p>
     * When the primary (upstream) key is not found, a second lookup is attempted
     * under this mod's own namespace ({@code <modid>.fallback.<fallback>}). This
     * lets translations for keys that upstream mods fail to provide be appended
     * through this mod's resource pack. If both lookups fail, {@code null} is
     * returned so the corresponding cell is left blank.
     *
     * @param key     the translation key
     * @param fallback the suffix used to build the mod-namespaced fallback key
     * @return the translated text, or {@code null} if untranslated
     */
    private String lookup(String key, String fallback) {
        String result = I18n.get(key);
        if (!result.equals(key)) {
            return result;
        }
        // Fallback: a key namespaced under this mod, so missing translations can
        // be supplied via this mod's resource pack (e.g. tinkerscsvexporter.fallback.melee_damage).
        String fallback_key = modId + ".fallback." + fallback;
        String fb = I18n.get(fallback_key);
        return fb.equals(fallback_key) ? null : fb;
    }

    /**
     * Converts a camelCase identifier to snake_case, used to derive Tinkers'
     * Construct tool-stat translation keys from raw field names.
     *
     * @param text the camelCase identifier (e.g. {@code knockbackResistance})
     * @return the snake_case form (e.g. {@code knockback_resistance})
     */
    private static String camelToSnake(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
