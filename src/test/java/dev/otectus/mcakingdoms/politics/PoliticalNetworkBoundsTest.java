package dev.otectus.mcakingdoms.politics;

import dev.otectus.mcakingdoms.api.politics.Politics.*;
import dev.otectus.mcakingdoms.network.PayloadBudget;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H08: the political page reply never exceeds its byte ceiling, whatever the titles, details, fields and header hold.
 * Rows that do not fit are left for the next page and the page says so; text is cut only between code points.
 */
class PoliticalNetworkBoundsTest {
    private static final String WIDE = "王國議會記錄🏰";  // three-byte CJK and a four-byte emoji

    private static String repeat(String unit, int chars) {
        var out = new StringBuilder();
        while (out.length() < chars) out.append(unit);
        return out.toString();
    }

    private static PoliticalNetwork.Reply reply(int rows, int titleChars, int headerChars) {
        var pageRows = new ArrayList<Row>();
        var titles = new ArrayList<String>(); var details = new ArrayList<String>(); var fields = new ArrayList<List<String>>();
        for (int i = 0; i < rows; i++) {
            pageRows.add(new Row(UUID.randomUUID().toString(), repeat(WIDE, 100), repeat(WIDE, 400), ""));
            titles.add(Component.Serializer.toJson(Component.literal(repeat(WIDE, titleChars))));
            details.add(Component.Serializer.toJson(Component.literal(repeat(WIDE, 2000))));
            var pairs = new ArrayList<String>();
            for (int f = 0; f < 6; f++) { pairs.add(Component.Serializer.toJson(Component.literal("label " + f))); pairs.add(Component.Serializer.toJson(Component.literal(repeat(WIDE, 300)))); }
            fields.add(pairs);
        }
        var definitions = new ArrayList<Choice>();
        for (int i = 0; i < 128; i++) definitions.add(new Choice("mcakingdoms:definition_" + i, "office", repeat("t", 120)));
        var page = new Page(UUID.randomUUID(), 7, "mcakingdoms:serenum", "council", 40, false, pageRows, definitions, Map.of(Action.APPOINT, ""), "");
        var header = List.of(Component.Serializer.toJson(Component.literal(repeat(WIDE, headerChars))), Component.Serializer.toJson(Component.literal("Leader: someone")));
        return new PoliticalNetwork.Reply(page, null, titles, details, Component.Serializer.toJson(Component.literal("Serenum")), fields, header, "mcakingdoms:serenum", 0xE6C65C, true);
    }

    @Test
    void worstCaseRepliesFitTheByteCeilingAndContinue() {
        for (int[] shape : new int[][]{{20, 900, 100}, {20, 30_000, 100}, {20, 50, 20_000}, {1, 59_000, 59_000}, {0, 10, 10}}) {
            var original = reply(shape[0], shape[1], shape[2]);
            String json = PoliticalPacking.bounded(original);
            assertTrue(PayloadBudget.fits(json), "shape " + Arrays.toString(shape) + " is " + PayloadBudget.utf8(json) + " bytes");
            var decoded = PoliticalSavedData.JSON.fromJson(json, PoliticalNetwork.Reply.class);
            assertEquals(original.page().offset(), decoded.page().offset());
            assertTrue(decoded.page().rows().size() <= original.page().rows().size());
            if (decoded.page().rows().size() < original.page().rows().size())
                assertTrue(decoded.page().hasMore(), "a page that left rows out must say more follows");
            for (int i = 0; i < decoded.page().rows().size(); i++)
                assertEquals(original.page().rows().get(i).id(), decoded.page().rows().get(i).id(), "rows keep their order; only trailing rows wait");
            assertFalse(json.chars().anyMatch(c -> Character.isSurrogate((char) c) && !validPair(json)), "no lone surrogate");
        }
    }

    @Test
    void smallRepliesAreUnchanged() {
        var small = reply(3, 20, 20);
        var stripped = new PoliticalNetwork.Reply(small.page(), null, small.titles(), List.of(), small.kingdomName(), List.of(), small.header(), small.heraldry(), small.color(), small.governed());
        String json = PoliticalPacking.bounded(stripped);
        assertEquals(PoliticalSavedData.JSON.toJson(stripped), json);
    }

    @Test
    void clippingKeepsCodePointsWhole() {
        String text = repeat("🏰", 40);
        for (int limit = 1; limit < 60; limit++) {
            String clipped = PayloadBudget.clip(text, limit);
            assertTrue(clipped.length() <= limit, "limit " + limit);
            for (int i = 0; i < clipped.length(); i++) {
                char c = clipped.charAt(i);
                if (Character.isHighSurrogate(c)) assertTrue(i + 1 < clipped.length() && Character.isLowSurrogate(clipped.charAt(i + 1)), "split pair at " + limit);
                if (Character.isLowSurrogate(c)) assertTrue(i > 0 && Character.isHighSurrogate(clipped.charAt(i - 1)), "split pair at " + limit);
            }
        }
    }

    private static boolean validPair(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(++i)))) return false;
            if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
