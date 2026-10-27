package dev.otectus.mcakingdoms.interaction;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Shared, server-owned task definitions. GUI tasks call services, never command dispatch. */
public final class ActionRegistry {
    /**
     * A named record the player can select. {@code label} and {@code detail} are the English reading used by commands,
     * logs and English search; {@code display} and {@code detailDisplay}, when set, are what the client renders, so a
     * label built from translation keys reaches each player in their own language. Player-authored names stay literal.
     */
    public record Choice(String value,String label,String detail,Map<String,String> metadata,Component display,Component detailDisplay) {
        public Choice { metadata=Map.copyOf(metadata); }
        public Choice(String value,String label,String detail,Map<String,String> metadata){this(value,label,detail,metadata,null,null);}
        public Choice(String value,String label){this(value,label,"",Map.of());}
        public Choice(String value,String label,String detail){this(value,label,detail,Map.of());}
        /** A choice whose label and detail are components; the English readings come from the server's language. */
        public static Choice of(String value,Component display,Component detail,Map<String,String> metadata){
            return new Choice(value,display.getString(),detail==null?"":detail.getString(),metadata,display,detail);
        }
        public static Choice of(String value,Component display,Component detail){return of(value,display,detail,Map.of());}
        public static Choice of(String value,Component display){return of(value,display,null,Map.of());}
        /** The label as rendered for players. */
        public Component shown(){return display!=null?display:Component.literal(label);}
        public Component shownDetail(){return detailDisplay!=null?detailDisplay:Component.literal(detail);}
    }
    public enum Kind { CHOICE, MULTI, TEXT, NUMBER }
    /**
     * Bounds and units of a numeric field. The form shows them before submission, checks them before advancing, and
     * the task handler checks the same constraint again when it runs, so the message cannot drift from the rule.
     */
    public record Constraint(long min,long max,String unit,String example) {
        public Constraint { if(min>max)throw new IllegalArgumentException("Empty constraint"); unit=unit==null?"":unit; example=example==null?"":example; }
        /** {@code unit} names a bundled unit word: blocks, chunks, ticks, minutes, seconds, page, points or empty. */
        public static Constraint range(long min,long max,String unit,long example){return new Constraint(min,max,unit,Long.toString(example));}
        public Component describe(){
            Component unitWord=unit.isEmpty()?Component.empty():tr("interaction.mcakingdoms.unit."+unit,unit);
            return example.isEmpty()?tr("interaction.mcakingdoms.constraint.range","Allowed: %s to %s %s.",min,max,unitWord)
                    :tr("interaction.mcakingdoms.constraint.range_example","Allowed: %s to %s %s. Example: %s.",min,max,unitWord,example);
        }
        /** The value, or a refusal naming the allowed range. */
        public long check(long value){
            if(value<min||value>max)throw new InteractionNetwork.FormException(tr("interaction.mcakingdoms.error.range","Enter a number from %s to %s.",min,max));
            return value;
        }
    }
    /**
     * One form field. {@code targetKind} names the shared selector (for empty-state hints); it is empty for inline option
     * lists. {@code dependsOn} lists earlier fields whose values decide this field's choices: editing one of them clears
     * this field's selection instead of keeping it.
     */
    public record Field(String key,String label,String help,Kind kind,String initial,boolean optional,Function<Context,List<Choice>> options,String targetKind,Function<Context,Constraint> bounds,Set<String> dependsOn) {
        public Field { dependsOn=Set.copyOf(dependsOn); }
        public Field(String key,String label,String help,Kind kind,String initial,boolean optional,Function<Context,List<Choice>> options,String targetKind){this(key,label,help,kind,initial,optional,options,targetKind,null,Set.of());}
        public Field(String key,String label,String help,Kind kind,String initial,boolean optional,Function<Context,List<Choice>> options){this(key,label,help,kind,initial,optional,options,"");}
        /** This field's choices (or bounds) are computed from the named earlier fields. */
        public Field after(String... keys){return new Field(key,label,help,kind,initial,optional,options,targetKind,bounds,Set.of(keys));}
        public Field within(Constraint value){return within(c->value);}
        /** Bounds that depend on earlier answers, such as a duration in the units chosen before it. */
        public Field within(Function<Context,Constraint> value){return new Field(key,label,help,kind,initial,optional,options,targetKind,value,dependsOn);}
        /** The bounds for the answers given so far, or null when the field has none. */
        public Constraint constraint(Context context){return bounds==null?null:bounds.apply(context);}
    }
    /** A result that finishes later; {@code message} (text or a component) is shown until {@code completion} reports the outcome. */
    public record Pending(java.util.concurrent.CompletionStage<?> completion,Object message) {}
    /**
     * Where a consequential operation stands, as its owning service's records say (F07): {@code APPLIED} (in effect),
     * {@code PENDING} (recorded and waiting for another party, a native save or a retry), {@code REFUSED} (nothing was
     * changed), {@code UNKNOWN} (the outcome cannot be confirmed) or {@code READ} (nothing was meant to change). It is
     * decided from typed service state, never by reading message text. {@code operation} names the record acted on, so
     * Check can read it again later without repeating anything; {@code next} is an action valid for this state.
     */
    public record Outcome(State state,Object message,String operation,Optional<NextStep> next) {
        public enum State { APPLIED, PENDING, REFUSED, UNKNOWN, READ }
        public Outcome { Objects.requireNonNull(state);message=message==null?"":message;operation=operation==null?"":operation;next=next==null?Optional.empty():next; }
        public static Outcome of(State state,Object message,String operation){return new Outcome(state,message,operation,Optional.empty());}
        public static Outcome applied(Object message,String operation){return of(State.APPLIED,message,operation);}
        public static Outcome pending(Object message,String operation){return of(State.PENDING,message,operation);}
        public static Outcome refused(Object message,String operation){return of(State.REFUSED,message,operation);}
        public Outcome then(String task,Map<String,String> prefill){return new Outcome(state,message,operation,Optional.of(new NextStep(task,prefill)));}
        /** The protocol word the client shows: done, waiting, refused, unknown or read. */
        public String status(){return switch(state){case APPLIED->"done";case PENDING->"waiting";case REFUSED->"refused";case UNKNOWN->"unknown";case READ->"read";};}
    }
    /**
     * Reads the current state of an operation a task started, from its owner, without changing anything (F07). Used
     * for Check; a task's handler usually returns the same reading right after acting. Empty when the record is gone or
     * no longer visible to this player.
     */
    @FunctionalInterface public interface Probe { Optional<Outcome> status(Context context,String operation); }
    /** A server-validated follow-up offered after a result: open {@code task}, preselecting {@code prefill} where still valid. */
    public record NextStep(String task,Map<String,String> prefill) {
        public NextStep { prefill=Map.copyOf(prefill); }
        public NextStep(String task){this(task,Map.of());}
    }
    /**
     * One labelled check shown before a workflow and on its review: met, needed or not known yet, with the current and
     * required values in {@code detail}; or a {@code NOTE}, such as which route applies or another way to qualify, which
     * is information rather than a condition. A projection of the owning service's rules for display; the service decides.
     */
    public record Requirement(Component label,Check check,Component detail) {
        public enum Check { MET, UNMET, UNKNOWN, NOTE }
        public Requirement { detail=detail==null?Component.empty():detail; }
        public static Requirement of(boolean met,Component label,Component detail){return new Requirement(label,met?Check.MET:Check.UNMET,detail);}
        /** The row as players read it: a mark and a word ("✓ Ready"), then the label and detail. Never colour alone. */
        public Component line(){
            if(check==Check.NOTE)return detail.getString().isEmpty()?tr("interaction.mcakingdoms.requirement.note","• %s",label):tr("interaction.mcakingdoms.requirement.note_detail","• %s — %s",label,detail);
            Component word=switch(check){case MET->tr("interaction.mcakingdoms.requirement.met","✓ Ready");case UNMET->tr("interaction.mcakingdoms.requirement.unmet","✗ Needed");case UNKNOWN,NOTE->tr("interaction.mcakingdoms.requirement.unknown","? Could not check");};
            return detail.getString().isEmpty()?tr("interaction.mcakingdoms.requirement.line","%s: %s",word,label):tr("interaction.mcakingdoms.requirement.line_detail","%s: %s — %s",word,label,detail);
        }
    }
    @FunctionalInterface public interface Handler { Object run(Context context) throws Exception; }
    public record Task(String id,String category,String title,String help,boolean operator,boolean consequential,List<Field> fields,Function<Context,String> version,Handler handler) {
        public Task { fields=List.copyOf(fields); }
        /** Translation key for this task's title; packs translate it, the English title is the fallback. */
        public String titleKey(){return "task.mcakingdoms."+id+".title";}
        public String helpKey(){return "task.mcakingdoms."+id+".help";}
        public String categoryKey(){return "task.mcakingdoms.category."+slug(category);}
    }
    public static final class Context {
        public final ServerPlayer player;
        private final Map<String,String> values;
        private final Map<String,Choice> selected;
        public Context(ServerPlayer player,Map<String,String> values,Map<String,Choice> selected){this.player=player;this.values=Map.copyOf(values);this.selected=Map.copyOf(selected);}
        public net.minecraft.server.MinecraftServer server(){return player.getServer();}
        public String text(String key){return values.getOrDefault(key,"");}
        // Typed reads explain a bad value in words; the JDK's own messages ("For input string: x") are not for players.
        public UUID uuid(String key){try{return UUID.fromString(text(key));}catch(IllegalArgumentException invalid){throw new InteractionNetwork.FormException(tr("interaction.mcakingdoms.error.choice","That choice is no longer valid. Pick it again."));}}
        public net.minecraft.resources.ResourceLocation id(String key){var id=net.minecraft.resources.ResourceLocation.tryParse(text(key));if(id==null)throw new InteractionNetwork.FormException(tr("interaction.mcakingdoms.error.choice","That choice is no longer valid. Pick it again."));return id;}
        public long number(String key){try{return Long.parseLong(text(key).strip());}catch(NumberFormatException invalid){throw new InteractionNetwork.FormException(tr("interaction.mcakingdoms.error.number","Enter a whole number, using digits only."));}}
        /** A number checked against the same constraint the form showed. */
        public long number(String key,Constraint constraint){return constraint.check(number(key));}
        public int integer(String key){long value=number(key);if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new InteractionNetwork.FormException(tr("interaction.mcakingdoms.error.number_range","That number is too large."));return (int)value;}
        public int integer(String key,Constraint constraint){return (int)number(key,constraint);}
        public boolean bool(String key){return Boolean.parseBoolean(text(key));}
        public Choice choice(String key){return selected.get(key);}
        public String meta(String key,String name){var choice=selected.get(key);if(choice==null||!choice.metadata().containsKey(name))throw new IllegalArgumentException("Select the record again.");return choice.metadata().get(name);}
        public long revision(String key){return Long.parseLong(meta(key,"revision"));}
        public List<Choice> choices(String kind){return ActionRegistry.choices(kind,this);}
        public Map<String,String> values(){return values;}
    }
    private static final Map<String,Task> TASKS=new LinkedHashMap<>();
    private static final Map<String,Function<Context,List<Choice>>> TARGETS=new LinkedHashMap<>();
    private static final Map<String,Function<Context,Object>> TARGET_HINTS=new LinkedHashMap<>();
    private static final Map<String,Function<Context,List<Object>>> EFFECTS=new LinkedHashMap<>();
    private static final Map<String,BiFunction<Context,Throwable,Optional<NextStep>>> NEXT=new LinkedHashMap<>();
    private static final Map<String,Function<Context,List<Requirement>>> REQUIREMENTS=new LinkedHashMap<>();
    private static final Map<String,Function<Context,Component>> APPLY_LABELS=new LinkedHashMap<>();
    private static final Map<String,Probe> PROBES=new LinkedHashMap<>();
    /** Registers how Check reads {@code task}'s operation again; see {@link Probe}. */
    public static void probe(String task,Probe probe){if(PROBES.putIfAbsent(task,probe)!=null)throw new IllegalArgumentException("Duplicate probe "+task);}
    /** The operation's current state, or empty when the task has no probe or the record is unavailable. */
    public static Optional<Outcome> probe(Task task,Context context,String operation){var probe=PROBES.get(task.id());if(probe==null||operation==null||operation.isBlank())return Optional.empty();return probe.status(context,operation);}
    public static boolean probed(Task task){return PROBES.containsKey(task.id());}
    /**
     * What {@code task} needs, shown on its first page and on its review from current records. Checks that depend on a
     * later choice should say so ("Choose a guild to check this") rather than report failure.
     */
    public static void requirements(String task,Function<Context,List<Requirement>> requirements){if(REQUIREMENTS.putIfAbsent(task,requirements)!=null)throw new IllegalArgumentException("Duplicate requirements "+task);}
    public static List<Requirement> requirements(Task task,Context context){var fn=REQUIREMENTS.get(task.id());if(fn==null)return List.of();try{return List.copyOf(fn.apply(context));}catch(RuntimeException unknown){return List.of();}}
    /** The final button of {@code task}'s review, naming the action and its stage ("Join Lamplighters", "Sign the agreement"). */
    public static void applyLabel(String task,Function<Context,Component> label){if(APPLY_LABELS.putIfAbsent(task,label)!=null)throw new IllegalArgumentException("Duplicate apply label "+task);}
    /** The task's own label, or its title, which already names the action ("Propose an agreement"). */
    public static Component applyLabel(Task task,Context context){var fn=APPLY_LABELS.get(task.id());if(fn!=null)try{return fn.apply(context);}catch(RuntimeException unknown){}return tr(task.titleKey(),task.title());}
    public static void add(Task task){if(TASKS.putIfAbsent(task.id(),task)!=null)throw new IllegalArgumentException("Duplicate task "+task.id());}
    public static void targets(String kind,Function<Context,List<Choice>> provider){if(TARGETS.putIfAbsent(kind,provider)!=null)throw new IllegalArgumentException("Duplicate target kind "+kind);}
    /** Explains an empty selector of this kind in plain words (what to do so that choices appear): text, a bundled key or a component. */
    public static void targetHint(String kind,Object hint){TARGET_HINTS.put(kind,c->hint);}
    public static void targetHint(String kind,Function<Context,Object> hint){TARGET_HINTS.put(kind,hint);}
    public static Optional<Object> targetHint(String kind,Context context){var hint=TARGET_HINTS.get(kind);return hint==null?Optional.empty():Optional.ofNullable(hint.apply(context));}
    /** What applying {@code task} will retain, move or block, shown on its review page from the current records. */
    public static void effects(String task,Function<Context,List<Object>> effects){if(EFFECTS.putIfAbsent(task,effects)!=null)throw new IllegalArgumentException("Duplicate effects "+task);}
    public static List<Object> effects(Task task,Context context){var effects=EFFECTS.get(task.id());return effects==null?List.of():effects.apply(context);}
    /** A follow-up task offered after {@code task} finishes; {@code failure} is null after success. */
    public static void nextStep(String task,BiFunction<Context,Throwable,Optional<NextStep>> next){if(NEXT.putIfAbsent(task,next)!=null)throw new IllegalArgumentException("Duplicate next step "+task);}
    public static Optional<NextStep> nextStep(Task task,Context context,Throwable failure){var next=NEXT.get(task.id());return next==null?Optional.empty():next.apply(context,failure);}
    public static List<Choice> choices(String kind,Context context){var provider=TARGETS.get(kind);return provider==null?List.of():List.copyOf(provider.apply(context));}
    public static Set<String> targetKinds(){return Set.copyOf(TARGETS.keySet());}
    public static List<Task> tasks(ServerPlayer player){return TASKS.values().stream().filter(t->!t.operator()||player.hasPermissions(2)).toList();}
    /** Every registered task, regardless of who may open it; for coverage checks, never for a player listing. */
    public static List<Task> all(){return List.copyOf(TASKS.values());}
    public static Task task(String id,ServerPlayer player){var task=TASKS.get(id);if(task==null||task.operator()&&!player.hasPermissions(2))throw new IllegalArgumentException("This task is unavailable to you.");return task;}
    public static Optional<Task> find(String id){return Optional.ofNullable(TASKS.get(id));}
    public static Field pick(String key,String label,String kind){return new Field(key,label,"interaction.mcakingdoms.help.pick",Kind.CHOICE,"",false,c->c.choices(kind),kind);}
    public static Field pick(String key,String label,Function<Context,List<Choice>> options){return new Field(key,label,"interaction.mcakingdoms.help.pick",Kind.CHOICE,"",false,options,"");}
    public static Field multi(String key,String label,Function<Context,List<Choice>> options){return new Field(key,label,"interaction.mcakingdoms.help.multi",Kind.MULTI,"",false,options,"");}
    public static Field optionalMulti(String key,String label,Function<Context,List<Choice>> options){return new Field(key,label,"interaction.mcakingdoms.help.optional_multi",Kind.MULTI,"",true,options,"");}
    public static Field text(String key,String label){return new Field(key,label,"interaction.mcakingdoms.help.text",Kind.TEXT,"",false,c->List.of(),"");}
    public static Field text(String key,String label,String initial,boolean optional){return new Field(key,label,optional?"interaction.mcakingdoms.help.optional_text":"interaction.mcakingdoms.help.text",Kind.TEXT,initial,optional,c->List.of(),"");}
    public static Field number(String key,String label,long initial){return new Field(key,label,"interaction.mcakingdoms.help.number",Kind.NUMBER,Long.toString(initial),false,c->List.of(),"");}
    public static Field number(String key,String label,long initial,Constraint constraint){return number(key,label,initial).within(constraint);}
    /** Yes and No, in the player's language through Minecraft's own keys. */
    public static Field toggle(String key,String label){return pick(key,label,c->List.of(Choice.of("true",Component.translatable("gui.yes")),Choice.of("false",Component.translatable("gui.no"))));}
    /** Enum choices labelled {@code interaction.mcakingdoms.enum.<type>.<value>}, with the English words as fallback. */
    public static List<Choice> enums(Enum<?>[] values){return Arrays.stream(values).map(v->Choice.of(v.name().toLowerCase(Locale.ROOT),enumLabel(v))).toList();}
    public static Component enumLabel(Enum<?> value){return tr(enumKey(value),words(value.name()));}
    /** {@code interaction.mcakingdoms.enum.<owner_type>.<value>}; nested enums include their owner so Kind enums stay apart. */
    public static String enumKey(Enum<?> value){Class<?> type=value.getDeclaringClass();String name=type.getEnclosingClass()==null?type.getSimpleName():type.getEnclosingClass().getSimpleName()+"_"+type.getSimpleName();
        return "interaction.mcakingdoms.enum."+slug(name.replaceAll("([a-z])([A-Z])","$1_$2"))+"."+value.name().toLowerCase(Locale.ROOT);}
    public static String words(String value){String s=value.replace('_',' ').toLowerCase(Locale.ROOT);return s.isEmpty()?s:Character.toUpperCase(s.charAt(0))+s.substring(1);}
    /** Lower-case key fragment for a human category or label. */
    public static String slug(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","_").replaceAll("^_|_$","");}
    /** Translatable text with the English source as fallback, so untranslated packs still read correctly. */
    public static Component tr(String key,String fallback,Object... args){return Component.translatableWithFallback(key,fallback,args);}
    private ActionRegistry(){}
}
