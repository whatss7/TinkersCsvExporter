package io.github.whatss7.tinkerscsvexporter;

import net.minecraft.client.resources.language.I18n;

import java.util.Map;
import java.util.Objects;

/**
 * Translates Tinkers' Exporter CSV column headers into human-readable labels.
 * <p>
 * A header is decomposed into three blocks:
 * <ul>
 *   <li>{@code modid} — the mod owning the stat, extracted from the stat
 *       identifier (e.g. {@code tconstruct} from {@code tconstruct:head});</li>
 *   <li>{@code stat} — the stat name (e.g. {@code head});</li>
 *   <li>{@code tool_stat} — the tool-stat field (e.g. {@code traits}).</li>
 * </ul>
 * A header with no {@code /} is one of this mod's reserved columns and maps to
 * the key {@code <modid>.column.<name>}. The stat and tool_stat blocks are
 * translated separately and joined with {@code /}.
 * <p>
 * Every translatable block resolves through the same chain of candidate keys:
 * the owning mod's primary key first, then this mod's fallback of that full key,
 * then Tinkers' Construct, and finally this mod's general fallback of the bare
 * name ({@code <modid>.fallback.<...>}) so unrecognized keys can be supplied via
 * this mod's resource pack. Reserved columns only have this mod's candidates
 * (primary then fallback).
 * <p>
 * Translation is resolved through {@link I18n}; when a key is untranslated
 * Minecraft returns it unchanged, in which case {@link #lookup(String)} reports
 * failure with {@code null} and the caller falls through to the next candidate.
 */
public class HeaderTranslator {
    /**
     * Maps a fully-qualified translation key to its canonical counterpart used
     * for I18n lookups. The key is assembled in {@link #translateStat} as
     * {@code <prefix><modId>.<field>}; a few Tinkers' Construct tool-stat fields
     * are exposed under a different or ambiguous name than the key upstream
     * provides. When an original key has no translation, {@link #lookup} retries
     * the aliased counterpart from this map before reporting failure, avoiding
     * per-name branching.
     */
    private static final Map<String, String> ALIASES = Map.of(
            "tool_stat.tconstruct.attack", "tool_stat.tconstruct.attack_damage",
            "tool_stat.tconstruct.melee_damage", "tool_stat.tconstruct.attack_damage",
            "tool_stat.tconstruct.melee_speed", "tool_stat.tconstruct.attack_speed",
            "tool_stat.tconstruct.tier", "tool_stat.tconstruct.harvest_tier",
            "tool_stat.tconstruct.toughness", "tool_stat.tconstruct.armor_toughness"
    );

    /**
     * Translates a full CSV column header into a label. Most headers are split
     * into three segments: {@code modid}, {@code stat}, and {@code tool_stat}.
     * Headers that can't be split are reserved columns under this mod's
     * namespace. The stat and tool-stat blocks are translated independently via
     * {@link #translateStat}. The two translations are joined with {@code /}.
     * Returns {@code null} when any block cannot be translated, so the caller
     * can leave the cell blank.
     *
     * @param header the column header, e.g. {@code tconstruct:head/traits}
     * @return the combined translation, or {@code null} if translation failed
     */
    public String translate(String header) {
        String[] segments = header.split("[:/]", 3);
        // 1 segment: a reserved column, namespaced under this mod.
        if (segments.length == 1) return translateColumn(segments[0]);
        // 2 segments: failed.
        if (segments.length == 2) return null;

        assert (segments.length == 3);
        String modId = segments[0];
        String stat = segments[1];
        String toolStat = segments[2];

        // Translate the stat name and tool-stat field.
        String statTrans = translateStat(modId, stat, "stat.", false);
        if (statTrans == null) return null;
        String toolTrans = translateStat(modId, toolStat, "tool_stat.", true);
        if (toolTrans == null) return null;

        return statTrans + "/" + toolTrans;
    }

    /**
     * Translates a reserved column name (e.g. {@code id}, {@code name},
     * {@code tier}) via this mod's namespace, trying the primary key then the
     * fallback key.
     *
     * @param name the reserved column name
     * @return the translated label, or {@code null} when untranslated
     */
    private String translateColumn(String name) {
        String snake = name.toLowerCase();
        String result = lookup(TinkersCsvExporter.MOD_ID + ".column." + snake);
        if (result != null) return result;
        return lookup(TinkersCsvExporter.MOD_ID + ".fallback." + snake);
    }

    /**
     * Translates a block, trying the owning mod's primary key first, then this
     * mod's fallback of that full key, then Tinkers' Construct, then this
     * mod's general fallback of the bare name.
     *
     * @param modId     the mod owning the stat the block belongs to
     * @param name      the raw block name (stat name, or camelCase tool-stat field)
     * @param prefix    the translation key prefix, e.g. {@code "stat."} or
     *                  {@code "tool_stat."}
     * @param camelCase whether {@code name} is a camelCase tool-stat field that
     *                  should be normalised to snake_case (alias redirect is
     *                  handled inside {@link #lookup})
     * @return the translated label, or {@code null} when all candidates fail
     */
    private String translateStat(String modId, String name, String prefix, boolean camelCase) {
        String keyName = camelCase ? camelToSnake(name) : name;
        String key = prefix + modId + "." + keyName;

        // Try the owning mod's primary key first.
        String result = lookup(key);
        if (result != null) return result;

        // Try this mod's fallback namespace to allow resource pack overrides.
        result = lookup(TinkersCsvExporter.MOD_ID + ".fallback." + key);
        if (result != null) return result;

        // Fallback to Tinkers' Construct namespace.
        result = lookup(prefix + "tconstruct." + keyName);
        if (result != null) return result;

        // Final fallback to this mod's general fallback namespace.
        return lookup(TinkersCsvExporter.MOD_ID + ".fallback." + keyName);
    }

    /**
     * Looks up a single Minecraft translation key via the client-side I18n
     * helper. Minecraft returns the key unchanged when no translation is
     * registered, so this method first checks the original key; if it has no
     * translation, it retries the aliased counterpart from {@link #ALIASES}
     * (used when an upstream tool-stat field is exposed under a different name)
     * before reporting failure with {@code null}, so the caller can fall through
     * to the next candidate in its chain.
     *
     * @param key the translation key (matched against {@link #ALIASES} on a miss)
     * @return the translated text, or {@code null} if neither key is translated
     */
    private String lookup(String key) {
        if (I18n.exists(key)) {
            return I18n.get(key);
        }
        String aliased = ALIASES.getOrDefault(key, key);
        if (!Objects.equals(aliased, key) && I18n.exists(aliased)) {
            return I18n.get(aliased);
        }
        return null;
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
