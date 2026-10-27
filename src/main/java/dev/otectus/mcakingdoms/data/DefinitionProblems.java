package dev.otectus.mcakingdoms.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.Reader;

/** Shared wording for definition diagnostics: the resource, the field, what was found and what is expected. */
public final class DefinitionProblems {
    private static final int FOUND_LENGTH = 48;

    private DefinitionProblems() {
    }

    /** {@code (found "abc")}, {@code (missing)} or the JSON type, bounded for chat. */
    public static String found(JsonElement value) {
        if (value == null) return "(missing)";
        if (value.isJsonNull()) return "(found null)";
        if (value.isJsonObject()) return "(found an object)";
        if (value.isJsonArray()) return "(found an array)";
        String text = value.toString();
        return "(found " + (text.length() > FOUND_LENGTH ? text.substring(0, FOUND_LENGTH - 1) + "…" : text) + ")";
    }

    /** Parses one resource as JSON, naming the resource and the parser's position when it is malformed. */
    public static JsonElement parse(ResourceLocation file, Resource resource) {
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader);
        } catch (JsonParseException malformed) {
            throw new IllegalArgumentException(file + ": malformed JSON: " + reason(malformed));
        } catch (IOException unreadable) {
            throw new IllegalArgumentException(file + ": cannot be read: " + reason(unreadable));
        }
    }

    /** The failure's own message (loaders put the resource first), or the most specific one in its chain. */
    public static String top(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? reason(failure) : message.replaceFirst("^[a-zA-Z_.]+(Exception|Error): ", "");
    }

    /** The most specific message in a failure chain, without the exception class names Gson prefixes. */
    public static String reason(Throwable failure) {
        Throwable cause = failure;
        String message = null;
        while (cause != null) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) message = cause.getMessage();
            if (cause.getCause() == cause) break;
            cause = cause.getCause();
        }
        if (message == null) return failure.getClass().getSimpleName();
        return message.replaceFirst("^[a-zA-Z_.]+(Exception|Error): ", "");
    }
}
