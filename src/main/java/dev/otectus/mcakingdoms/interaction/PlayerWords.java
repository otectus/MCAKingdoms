package dev.otectus.mcakingdoms.interaction;

import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Common-side English fallback for labels returned by servers, without loading client language classes.
 * Result text is never rewritten by pattern matching: services are expected to name records, not identifiers.
 */
public final class PlayerWords {
    private static final Map<String,String> WORDS=new HashMap<>();
    private static final int LIMIT=16000;
    static {try(var stream=PlayerWords.class.getResourceAsStream("/assets/mcakingdoms/lang/en_us.json")){if(stream!=null)JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject().entrySet().forEach(e->WORDS.put(e.getKey(),e.getValue().getAsString()));}catch(Exception ignored){}}
    public static String text(String value){if(encoded(value))return Keyed.english(value);return WORDS.getOrDefault(value,value);}
    /** True for a service result encoded by {@link Keyed#text}: a component's JSON. */
    static boolean encoded(String value){return value!=null&&value.startsWith("{\"")&&value.endsWith("}");}
    /** True when the value is a bundled translation key rather than free text. */
    public static boolean isKey(String value){return value!=null&&WORDS.containsKey(value);}
    /** Plain text for a handler result: joined paragraphs, keys resolved, selected record values shown by their labels, bounded. */
    public static String safe(Object value,ActionRegistry.Context context){
        String result=value instanceof Collection<?> list?String.join("\n\n",list.stream().map(PlayerWords::plain).toList()):plain(value);
        result=text(result);
        result=labels(result,context);
        return result.length()>LIMIT?result.substring(0,LIMIT)+"\nMore details are available by selecting a narrower task.":result;
    }
    /**
     * Component form of a handler result. A {@link Component} paragraph is kept as built (translation key, arguments and
     * player-authored literals intact); a string paragraph that is a bundled key becomes translatable; any other string
     * stays literal. Nothing is converted to English and back.
     */
    public static Component component(Object value,ActionRegistry.Context context){
        List<?> paragraphs=value instanceof Collection<?> list?List.copyOf(list):List.of(value==null?"":value);
        MutableComponent out=Component.empty();int length=0;boolean first=true;
        for(Object item:paragraphs){
            if(!first)out.append("\n\n");first=false;
            if(item instanceof Component built){
                int size=built.getString().length();
                if(length+size>LIMIT){out.append(ActionRegistry.tr("interaction.mcakingdoms.truncated","More details are available by selecting a narrower task."));break;}
                length+=size;out.append(built);continue;
            }
            String paragraph=String.valueOf(item);
            if(encoded(paragraph)){Component built=Keyed.component(paragraph);int size=built.getString().length();
                if(length+size>LIMIT){out.append(ActionRegistry.tr("interaction.mcakingdoms.truncated","More details are available by selecting a narrower task."));break;}
                length+=size;out.append(built);continue;}
            if(length+paragraph.length()>LIMIT){out.append(ActionRegistry.tr("interaction.mcakingdoms.truncated","More details are available by selecting a narrower task."));break;}
            length+=paragraph.length();
            out.append(isKey(paragraph)?ActionRegistry.tr(paragraph,WORDS.get(paragraph)):Component.literal(labels(paragraph,context)));
        }
        return out;
    }
    /**
     * One service message for a player: a bundled translation key becomes translatable (English as fallback), anything
     * else stays literal. For messages outside a task form, such as command feedback and the Kingdom screen (F14).
     */
    public static Component component(String value){return Keyed.component(value);}
    /** A bundled key as translatable text, anything else literal; {@link Keyed#component(String)} reads encoded results first. */
    static Component literalOrKey(String value){
        if(value==null)return Component.empty();
        return isKey(value)?ActionRegistry.tr(value,WORDS.get(value)):Component.literal(value);
    }
    /** English reading of one paragraph, for logs, tests and string-only consumers. */
    private static String plain(Object value){return value instanceof Component built?built.getString():String.valueOf(value);}
    private static String labels(String result,ActionRegistry.Context context){
        for(var entry:context.values().entrySet()){var choice=context.choice(entry.getKey());if(choice!=null&&!choice.value().isBlank())result=result.replace(choice.value(),choice.label());}
        return result;
    }
    private PlayerWords(){}
}
