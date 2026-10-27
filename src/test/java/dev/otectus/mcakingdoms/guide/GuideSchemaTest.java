package dev.otectus.mcakingdoms.guide;

import dev.otectus.mcakingdoms.interaction.SearchText;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The versioned guide format (A01): legacy resources keep loading, schema 2 blocks are validated, an invalid optional
 * block falls back without losing its article, an invalid entry is skipped without losing its file, and an unsupported
 * schema fails only its own resource.
 */
class GuideSchemaTest {
    private static KingdomGuide.ReadResult parse(String json) {
        return KingdomGuide.parse(new StringReader(json));
    }

    private static final String LEGACY = """
            {"entries":[{"id":"old_topic","category":"basics","title":"Old","summary":"Legacy entry","body":["First.","Second."],"commands":["/kingdoms help"],"task":"settlement.here"}]}""";

    @Test
    void legacyEntriesBecomeParagraphBlocks() {
        var result = parse(LEGACY);
        assertEquals(1, result.schema());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        var entry = result.entries().get(0);
        assertEquals(List.of("First.", "Second."), entry.body());
        assertEquals(List.of("p1", "p2"), entry.blocks().stream().map(KingdomGuide.Block::id).toList());
        assertEquals("settlement.here", entry.task());
        assertEquals(List.of("/kingdoms help"), entry.commands());
    }

    @Test
    void schemaTwoReadsEveryBlockType() {
        var result = parse("""
                {"schema":2,"entries":[{"id":"visual","category":"basics","title":"Visual","summary":"All blocks","keywords":["craft"],
                 "blocks":[
                  {"id":"a","type":"paragraph","text":"Press {key:tasks}."},
                  {"id":"b","type":"heading","text":"Heading"},
                  {"id":"c","type":"steps","items":["One",{"text":"Two","link":{"kind":"guide","target":"start_here"}}]},
                  {"id":"d","type":"recipe","recipe":"mcakingdoms:village_ledger","output":"mcakingdoms:village_ledger"},
                  {"id":"e","type":"item","item":"minecraft:compass","count":2,"text":"A compass"},
                  {"id":"f","type":"callout","tone":"consequence","text":"Changes things"},
                  {"id":"g","type":"image","texture":"mcakingdoms:textures/gui/heraldry/yew.png","width":32,"height":32,"alt":"Yew heraldry"},
                  {"id":"h","type":"comparison","alt":"Two columns","columns":[{"title":"A","points":["x"]},{"title":"B","item":"minecraft:book","points":["y"]}]},
                  {"id":"i","type":"diagram","alt":"Flow","nodes":[{"id":"n1","label":"One"},{"id":"n2","label":"Two"}],"edges":[{"from":"n1","to":"n2","label":"then"}]},
                  {"id":"j","type":"requirements","rows":[{"label":"Standing","state":"example","detail":"Default: 20"}]},
                  {"id":"k","type":"links","links":[{"kind":"task","target":"settlement.rename","operator":true},{"kind":"task","target":"*"}]},
                  {"id":"l","type":"kingdoms"}]}]}""");
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        var entry = result.entries().get(0);
        assertEquals(12, entry.blocks().size());
        assertEquals(2, result.schema());
        assertEquals(List.of(new net.minecraft.resources.ResourceLocation("mcakingdoms", "village_ledger")), entry.recipes());
        assertTrue(entry.links().stream().anyMatch(l -> l.operator() && l.target().equals("settlement.rename")));
        assertTrue(entry.body().contains("Press tasks."), "placeholders read as words in the text equivalent");
        assertTrue(entry.commands().isEmpty());
    }

    @Test
    void invalidOptionalBlockFallsBackWithoutLosingTheArticle() {
        var result = parse("""
                {"schema":2,"entries":[{"id":"partial","category":"basics","title":"Partial","summary":"One bad block",
                 "blocks":[{"id":"good","type":"paragraph","text":"Kept"},{"id":"bad","type":"hologram","text":"?"},
                           {"id":"far","type":"image","texture":"https://example.com/x.png","width":10,"height":10,"alt":"x"}]}]}""");
        assertEquals(1, result.entries().size());
        var blocks = result.entries().get(0).blocks();
        assertInstanceOf(KingdomGuide.Paragraph.class, blocks.get(0));
        assertInstanceOf(KingdomGuide.Unavailable.class, blocks.get(1));
        assertInstanceOf(KingdomGuide.Unavailable.class, blocks.get(2));
        assertEquals(2, result.problems().size(), result.problems().toString());
        assertThrows(IllegalArgumentException.class, () -> KingdomGuide.read(new StringReader("""
                {"schema":2,"entries":[{"id":"partial","category":"basics","title":"Partial","summary":"s","blocks":[{"id":"bad","type":"hologram"}]}]}""")),
                "shipped resources are read strictly");
    }

    @Test
    void ambiguousOrInvalidEntriesAreSkippedAndNamed() {
        var result = parse("""
                {"schema":2,"entries":[
                 {"id":"both","category":"basics","title":"Both","summary":"s","body":["x"],"blocks":[{"id":"a","type":"paragraph","text":"y"}]},
                 {"id":"dupe_blocks","category":"basics","title":"D","summary":"s","blocks":[{"id":"a","type":"paragraph","text":"y"},{"id":"a","type":"paragraph","text":"z"}]},
                 {"id":"bad_link","category":"basics","title":"L","summary":"s","blocks":[{"id":"a","type":"links","links":[{"kind":"url","target":"x"}]}]},
                 {"id":"placeholder","category":"basics","title":"P","summary":"s","blocks":[{"id":"a","type":"paragraph","text":"{key:jump}"}]},
                 {"id":"fine","category":"basics","title":"F","summary":"s","blocks":[{"id":"a","type":"paragraph","text":"ok"}]},
                 {"id":"fine","category":"basics","title":"F again","summary":"s","blocks":[{"id":"a","type":"paragraph","text":"ok"}]}]}""");
        assertEquals(List.of("dupe_blocks", "bad_link", "placeholder", "fine"), result.entries().stream().map(KingdomGuide.Entry::id).toList());
        assertTrue(result.problems().stream().anyMatch(p -> p.startsWith("entry both") && p.contains("both body and blocks")), result.problems().toString());
        assertTrue(result.problems().stream().anyMatch(p -> p.startsWith("entry fine") && p.contains("duplicate")), result.problems().toString());
        var dupe = result.entries().get(0);
        assertInstanceOf(KingdomGuide.Unavailable.class, dupe.blocks().get(1), "a duplicate block id is a block problem");
        assertEquals(2, dupe.blocks().stream().map(KingdomGuide.Block::id).distinct().count());
    }

    @Test
    void unsupportedSchemaFailsItsResourceAndLegacyFilesCannotCarryBlocks() {
        assertThrows(IllegalArgumentException.class, () -> parse("{\"schema\":3,\"entries\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"schema\":\"2\",\"entries\":[]}"));
        var legacyWithBlocks = parse("""
                {"entries":[{"id":"x","category":"basics","title":"X","summary":"s","blocks":[{"id":"a","type":"paragraph","text":"y"}],"commands":[]}]}""");
        assertTrue(legacyWithBlocks.entries().isEmpty());
        assertTrue(legacyWithBlocks.problems().get(0).contains("schema"), legacyWithBlocks.problems().toString());
    }

    @Test
    void budgetsAndBoundsAreEnforced() {
        String paragraphs = String.join(",", java.util.stream.IntStream.range(0, 65).mapToObj(i -> "{\"id\":\"b" + i + "\",\"type\":\"paragraph\",\"text\":\"x\"}").toList());
        assertFalse(parse("{\"schema\":2,\"entries\":[{\"id\":\"long\",\"category\":\"basics\",\"title\":\"L\",\"summary\":\"s\",\"blocks\":[" + paragraphs + "]}]}").problems().isEmpty());
        var oversize = parse("""
                {"schema":2,"entries":[{"id":"img","category":"basics","title":"I","summary":"s","blocks":[
                 {"id":"a","type":"image","texture":"mcakingdoms:textures/x.png","width":900,"height":10,"alt":"big"},
                 {"id":"b","type":"diagram","alt":"one node","nodes":[{"id":"n","label":"Only"}]},
                 {"id":"c","type":"diagram","alt":"bad edge","nodes":[{"id":"n1","label":"A"},{"id":"n2","label":"B"}],"edges":[{"from":"n1","to":"n9"}]},
                 {"id":"d","type":"requirements","source":"live","rows":[{"label":"x","state":"met"}]}]}]}""");
        assertEquals(4, oversize.problems().size(), oversize.problems().toString());
        assertThrows(IllegalArgumentException.class, () -> new KingdomGuide.Link(KingdomGuide.Link.Kind.GUIDE, "../escape", ""));
        assertThrows(IllegalArgumentException.class, () -> new KingdomGuide.Link(KingdomGuide.Link.Kind.GUIDE, "start_here", "", true),
                "only task links can be operator-only");
    }

    @Test
    void searchRanksTitleThenKeywordsThenSummaryThenText() {
        var entries = parse("""
                {"schema":2,"entries":[
                 {"id":"in_text","category":"basics","title":"Alpha","summary":"s","blocks":[{"id":"a","type":"paragraph","text":"About the compass recipe."}]},
                 {"id":"in_summary","category":"basics","title":"Beta","summary":"The compass recipe in short","blocks":[{"id":"a","type":"paragraph","text":"x"}]},
                 {"id":"in_keywords","category":"basics","title":"Gamma","summary":"s","keywords":["compass recipe"],"blocks":[{"id":"a","type":"paragraph","text":"x"}]},
                 {"id":"in_title","category":"basics","title":"Compass recipe","summary":"s","blocks":[{"id":"a","type":"paragraph","text":"x"}]}]}""").entries();
        var tokens = SearchText.tokens("recipe compass");
        assertEquals(List.of(KingdomGuide.Rank.BODY, KingdomGuide.Rank.SUMMARY, KingdomGuide.Rank.KEYWORDS, KingdomGuide.Rank.TITLE),
                entries.stream().map(e -> e.rank(tokens)).toList());
    }

    @Test
    void searchUnderstandsPlayerSynonymsAndGoalPhrases() {
        var entry = parse("""
                {"schema":2,"entries":[{"id":"x","category":"civic","title":"Browse commissions","summary":"Paid work at a chapter","blocks":[{"id":"a","type":"paragraph","text":"Sign a treaty."}]}]}""")
                .entries().get(0);
        assertTrue(entry.matches("job"), "job finds commissions");
        assertTrue(entry.matches("branch"), "branch finds chapters");
        assertTrue(entry.matches("agreement"), "agreement finds treaty");
        assertTrue(entry.matches("where is a job at the branch"), "function words are ignored when meaningful words remain");
        assertTrue(SearchText.matches(SearchText.tokens("job"), SearchText.normalize("Needs MCA Quests content")), "a synonym matches at a word start");
        assertFalse(SearchText.matches(SearchText.tokens("job"), SearchText.normalize("Request neutral resource access")),
                "a synonym never matches inside another word");
        assertTrue(SearchText.matches(SearchText.tokens("vil"), SearchText.normalize("Read this village")), "typed words still match as prefixes");
        assertEquals(List.of("join"), SearchText.tokens("why can't I join"));
        assertEquals(List.of("where", "am", "i"), SearchText.tokens("Where am I?"), "a query of function words keeps them as a phrase");
        assertTrue(SearchText.matches(SearchText.tokens("where am I"), SearchText.normalize("where am i, current place")));
        assertFalse(SearchText.matches(SearchText.tokens("where am I"), SearchText.normalize("i am somewhere")), "phrase queries are not scattered letters");
        assertEquals(SearchText.Rank.ALIAS, SearchText.rank(SearchText.tokens("paid work"), List.of("Browse commissions"),
                List.of("earn money, paid work"), List.of("Guilds"), List.of()));
        assertEquals(SearchText.Rank.ALIAS, SearchText.rank(SearchText.tokens("make peace"), List.of("Propose a control agreement"),
                List.of("make peace, end a war"), List.of("Conflict"), List.of()));
    }
}
