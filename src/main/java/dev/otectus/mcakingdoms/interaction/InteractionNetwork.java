package dev.otectus.mcakingdoms.interaction;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;
import java.util.function.Consumer;
import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * Authenticated, bounded form protocol. Internal identifiers never become editable GUI fields.
 * Titles, details and option text travel as serialized {@link Component}s so the client renders them in its own language;
 * {@link Reply#titleText()} and friends give the plain English reading for tests and logs.
 *
 * <p>Protocol 3 adds the catalogue index (searched on the client in its own language), review editing ({@code EDIT}),
 * step counts, stable option identities and server-validated next steps. Protocol 4 adds the start view ({@code START}),
 * each index entry's category and availability, the review's task-specific confirm label ({@link Reply#action}), the
 * result's status and a Book topic to learn more. Client and server must run the same build.
 */
@Mod.EventBusSubscriber(modid="mcakingdoms",bus=Mod.EventBusSubscriber.Bus.MOD)
public final class InteractionNetwork {
    public static final UUID NONE=new UUID(0,0);
    public static final String PROTOCOL="4";
    /** Encoded reply bound, shared with the decoder. */
    public static final int REPLY_LIMIT=60000;
    /** {@code prefill} is an optional JSON object of field key to value supplied by the opening screen; matching choice fields are selected automatically. */
    public record Request(UUID request,UUID session,UUID state,String operation,String value,String search,int page,String prefill) {
        public Request(UUID request,UUID session,UUID state,String operation,String value,String search,int page){this(request,session,state,operation,value,search,page,"");}
    }
    /** {@code id} is a stable, session-scoped identity for the record behind this row, so focus can follow it across pages. */
    public record Option(String key,String label,String detail,boolean selected,String id) {
        public Option(String key,String label,String detail,boolean selected){this(key,label,detail,selected,key);}
        public Component labelComponent(){return component(label);}
        public Component detailComponent(){return component(detail);}
        public String labelText(){return labelComponent().getString();}
        public String detailText(){return detailComponent().getString();}
    }
    /**
     * {@code value} carries the search text on listings, the draft on text pages, the task id on review and result pages
     * and the catalogue on index replies. {@code step}/{@code steps} count form pages (0 when not a form); {@code next}
     * is an optional JSON next step ({@code task}, {@code prefill}, {@code label}).
     */
    public record Reply(UUID request,UUID session,UUID state,String mode,String title,String detail,List<Option> options,String value,int page,boolean more,boolean back,boolean multi,int step,int steps,String next,String action,String status,String learn) {
        public Reply(UUID request,UUID session,UUID state,String mode,String title,String detail,List<Option> options,String value,int page,boolean more,boolean back,boolean multi){
            this(request,session,state,mode,title,detail,options,value,page,more,back,multi,0,0,"");
        }
        public Reply(UUID request,UUID session,UUID state,String mode,String title,String detail,List<Option> options,String value,int page,boolean more,boolean back,boolean multi,int step,int steps,String next){
            this(request,session,state,mode,title,detail,options,value,page,more,back,multi,step,steps,next,"","","");
        }
        /** The review's confirm button label (serialized component), naming the action and its stage. */
        public Component actionComponent(){return action==null||action.isEmpty()?Component.empty():component(action);}
        /** A result's outcome: {@code done}, {@code waiting}, {@code refused} or {@code unknown}; empty elsewhere. */
        public String statusOrEmpty(){return status==null?"":status;}
        /** A Book topic that explains this page (an unavailable feature, for example); empty when none applies. */
        public String learnOrEmpty(){return learn==null?"":learn;}
        public Component titleComponent(){return component(title);}
        public Component detailComponent(){return component(detail);}
        public String titleText(){return titleComponent().getString();}
        public String detailText(){return detailComponent().getString();}
        public String nextOrEmpty(){return next==null?"":next;}
    }
    /** A protocol refusal with translatable text. */
    public static final class FormException extends IllegalArgumentException {
        public final transient Component component;
        FormException(Component component){super(component.getString());this.component=component;}
    }
    private static final Gson JSON=new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
    private static final Map<UUID,Session> SESSIONS=new HashMap<>();
    private static final Map<UUID,LinkedHashMap<UUID,Session>> RETAINED=new HashMap<>();
    private static final Map<UUID,ArrayDeque<Integer>> LAST_REQUEST=new HashMap<>();
    private static SimpleChannel channel;
    private static Consumer<Reply> receiver=r->{};
    private static final class Session {
        UUID id=UUID.randomUUID(),state=UUID.randomUUID(),startedRequest;Reply lastReply;Task task;int field;long expires;boolean executed;String version="";Component result=Component.empty();String search="";int page;
        /** The outcome reported with the result: done, waiting, refused (by the owning service) or unknown (a fault). */
        String status="";
        /** The record the executed operation acted on, read again by Check through the task's probe (F07). */
        String operation="";
        final Map<String,String> values=new LinkedHashMap<>();final Map<String,Choice> selected=new LinkedHashMap<>();
        /** Choice values to select again automatically when their field is reached, if still offered (opening screen, edits). */
        final Map<String,String> autoSelect=new LinkedHashMap<>();
        /** Text drafts shown when a text field is reached (opening screen, Back, edits, a refused entry). */
        final Map<String,String> drafts=new LinkedHashMap<>();
        /** The previous selection of a choice field being revisited, marked in its list. */
        final Map<String,String> current=new LinkedHashMap<>();
        List<Choice> candidates=List.of();final Set<String> multi=new LinkedHashSet<>();
        Component problem;Component notice;Optional<NextStep> next=Optional.empty();
        Context context(ServerPlayer p){return new Context(p,values,selected);}
    }
    @SubscribeEvent public static void setup(FMLCommonSetupEvent event){event.enqueueWork(InteractionNetwork::init);}
    public static synchronized void init(){if(channel!=null)return;InteractionTasks.init();
        channel=NetworkRegistry.ChannelBuilder.named(new ResourceLocation("mcakingdoms","interactions")).networkProtocolVersion(()->PROTOCOL).clientAcceptedVersions(PROTOCOL::equals).serverAcceptedVersions(PROTOCOL::equals).simpleChannel();
        channel.messageBuilder(Request.class,0,NetworkDirection.PLAY_TO_SERVER).encoder((q,b)->{b.writeUUID(q.request());b.writeUUID(q.session());b.writeUUID(q.state());b.writeUtf(q.operation(),24);b.writeUtf(q.value(),2048);b.writeUtf(q.search(),100);b.writeVarInt(q.page());b.writeUtf(q.prefill(),2048);})
            .decoder(b->new Request(b.readUUID(),b.readUUID(),b.readUUID(),b.readUtf(24),b.readUtf(2048),b.readUtf(100),b.readVarInt(),b.readUtf(2048)))
            .consumerMainThread((q,ctx)->{var player=ctx.get().getSender();if(player!=null)channel.send(PacketDistributor.PLAYER.with(()->player),handleEnvelope(player,q));}).add();
        channel.messageBuilder(Reply.class,1,NetworkDirection.PLAY_TO_CLIENT).encoder((r,b)->b.writeUtf(encodeReply(r),REPLY_LIMIT)).decoder(b->{var r=JSON.fromJson(b.readUtf(REPLY_LIMIT),Reply.class);if(r.options()==null||r.options().size()>20)throw new IllegalArgumentException("Invalid form reply");return r;})
            .consumerMainThread((r,ctx)->receiver.accept(r)).add();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(Cleanup.class);
    }
    public static void receive(Consumer<Reply> value){receiver=Objects.requireNonNull(value);}
    public static void send(Request request){channel.sendToServer(request);}
    /** Decodes serialized component text; plain strings (older peers, tests) are shown literally. */
    public static Component component(String encoded){
        if(encoded==null||encoded.isEmpty())return Component.empty();
        if(encoded.startsWith("{")||encoded.startsWith("[")||encoded.startsWith("\"")){try{var c=Component.Serializer.fromJson(encoded);if(c!=null)return c;}catch(RuntimeException ignored){}}
        return Component.literal(encoded);
    }
    public static String encode(Component component){return Component.Serializer.toJson(component);}
    static Component msg(String key,String fallback,Object... args){return tr("interaction.mcakingdoms."+key,fallback,args);}
    static FormException fail(String key,String fallback,Object... args){return new FormException(msg(key,fallback,args));}
    private static MutableComponent lines(List<Component> parts){var out=Component.empty();for(int i=0;i<parts.size();i++){if(i>0)out.append("\n");out.append(parts.get(i));}return out;}
    private static Reply handleEnvelope(ServerPlayer p,Request q){
        int now=p.getServer().getTickCount();var recent=LAST_REQUEST.computeIfAbsent(p.getUUID(),ignored->new ArrayDeque<>());
        while(!recent.isEmpty()&&now-recent.peekFirst()>=20)recent.removeFirst();
        if(recent.size()>=20){var s=SESSIONS.get(p.getUUID());return new Reply(q.request(),s==null?NONE:s.id,s==null?NONE:s.state,"error",encode(msg("wait.title","Please wait")),encode(msg("wait","Please wait a moment, then check the same action or continue.")),List.of(),"",0,false,s!=null,false);}
        recent.addLast(now);return handle(p,q);
    }
    public static Reply handle(ServerPlayer p,Request q){
        try {
            if(q.page()<0||q.page()>100000||!p.getServer().isSameThread())throw fail("page","Invalid page.");
            if(q.operation().equals("LIST"))return catalogue(p,q);
            if(q.operation().equals("INDEX"))return index(p,q);
            if(q.operation().equals("START"))return Suggestions.start(p,q);
            Session s=q.operation().equals("TASK")?SESSIONS.get(p.getUUID()):RETAINED.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(q.session());
            if(q.operation().equals("TASK")){
                if(s!=null&&q.request().equals(s.startedRequest)&&s.lastReply!=null){task(s.task.id(),p);return copy(q,s.lastReply);}
                var requested=task(q.value(),p);
                // A feature this server has turned off or lacks a provider for is explained before any form is filled in.
                var availability=TaskMeta.availability(requested,p.getServer());
                if(!availability.available())return unavailable(q,requested,availability);
                s=new Session();s.startedRequest=q.request();s.task=requested;seed(s,prefill(q.prefill()));SESSIONS.put(p.getUUID(),s);
                var retained=RETAINED.computeIfAbsent(p.getUUID(),ignored->new LinkedHashMap<>());retained.put(s.id,s);while(retained.size()>16)retained.remove(retained.keySet().iterator().next());
            }else if(q.operation().equals("CHECK")){
                s=RETAINED.getOrDefault(p.getUUID(),new LinkedHashMap<>()).get(q.session());
                if(s==null){var latest=SESSIONS.get(p.getUUID());if(latest!=null&&latest.startedRequest.toString().equals(q.value()))s=latest;}
                if(s==null)throw fail("check.none","No retained action was received. Inspect current status before starting again.");
                task(s.task.id(),p);
                if(s.executed){recheck(p,s);return result(q,s);}
                if(s.lastReply!=null)return copy(q,s.lastReply);
                throw fail("check.early","No reviewed action is available yet.");
            }
            else if(s==null||!s.id.equals(q.session())||s.expires<p.serverLevel().getGameTime())throw fail("expired","This form expired. Open the task again.");
            s.expires=p.serverLevel().getGameTime()+6000;
            task(s.task.id(),p);
            if(s.executed)return result(q,s);
            if(!q.operation().equals("TASK")&&!s.state.equals(q.state()))throw fail("changed","The form changed. Review the current page before continuing.");
            task(s.task.id(),p); // Operator authority is checked again on every message.
            s.problem=null;s.notice=null;
            if(q.operation().equals("BACK")){if(s.field>0){s.field--;revisit(s,s.task.fields().get(s.field));}s.page=0;s.search="";}
            else if(q.operation().equals("FILTER")){s.page=q.page();s.search=q.search();}
            else if(q.operation().equals("PICK")){
                if(s.field>=s.task.fields().size())throw fail("review","Review the current task.");
                var f=s.task.fields().get(s.field);if(f.kind()!=Kind.CHOICE&&f.kind()!=Kind.MULTI)throw fail("pick.kind","This field is not a selection.");
                int index;try{index=Integer.parseInt(q.value());}catch(NumberFormatException invalid){throw fail("pick.changed","That choice changed. Refresh the form.");}
                if(index<0||index>=s.candidates.size())throw fail("pick.changed","That choice changed. Refresh the form.");
                var choice=s.candidates.get(index);
                if(choice.metadata().containsKey(ActionRegistry.UNAVAILABLE))s.notice=msg("pick.unavailable","%s cannot be chosen now: %s",choice.shown(),choice.shownDetail()).copy().withStyle(ChatFormatting.RED);
                else if(f.kind()==Kind.MULTI){if(!s.multi.add(choice.value()))s.multi.remove(choice.value());}
                else {s.values.put(f.key(),choice.value());s.selected.put(f.key(),choice);s.current.remove(f.key());s.field++;s.page=0;s.search="";}
            }else if(q.operation().equals("NEXT")){
                if(s.field>=s.task.fields().size())throw fail("apply.review","Review the task before applying.");var f=s.task.fields().get(s.field);
                if(f.kind()==Kind.MULTI){if(s.multi.isEmpty()&&!f.optional())throw fail("multi.empty","Select at least one choice.");s.values.put(f.key(),String.join(",",s.multi));s.multi.clear();s.drafts.remove(f.key());s.field++;s.page=0;s.search="";}
                else if(f.kind()==Kind.CHOICE)throw fail("choice.named","Choose a named entry.");
                else{
                    // A refused entry keeps the typed draft on the same field; nothing earlier or later is erased.
                    s.problem=entryProblem(s,f,q.value(),s.context(p));
                    if(s.problem!=null)s.drafts.put(f.key(),q.value());
                    else{s.values.put(f.key(),q.value().strip());s.drafts.remove(f.key());s.field++;s.page=0;s.search="";}
                }
            }else if(q.operation().equals("EDIT")){
                if(s.field!=s.task.fields().size())throw fail("edit.review","Edit is available from the review page.");
                int index;try{index=Integer.parseInt(q.value());}catch(NumberFormatException invalid){throw fail("edit.field","Choose a field to edit.");}
                if(index<0||index>=s.task.fields().size())throw fail("edit.field","Choose a field to edit.");
                edit(s,index);
            }else if(q.operation().equals("APPLY")){
                if(s.field!=s.task.fields().size())throw fail("apply.incomplete","Complete the form first.");
                validate(p,s);var c=s.context(p);if(!s.version.equals(s.task.version().apply(c)))throw fail("apply.stale","The record changed since your review. Open the task again and review its new state.");
                return execute(p,q,s,c);
            }else if(!Set.of("TASK","BACK","FILTER","PICK","NEXT").contains(q.operation()))throw fail("operation","Unknown form operation.");
            autoSelect(p,s);
            if(s.field==s.task.fields().size()&&!s.task.consequential()){validate(p,s);var c=s.context(p);s.version=s.task.version().apply(c);return execute(p,q,s,c);}
            s.state=UUID.randomUUID();s.lastReply=form(p,q,s);return s.lastReply;
        }catch(Exception failure){var s=SESSIONS.get(p.getUUID());return new Reply(q.request(),s==null?NONE:s.id,s==null?NONE:s.state,"error",encode(msg("error.title","Could not continue")),encode(reason(failure,new Context(p,Map.of(),Map.of()))),List.of(),"",0,false,s!=null,false);}
    }
    /** Why a typed entry cannot be used yet, or null. Bounds come from the field's constraint, the same one the task checks. */
    private static Component entryProblem(Session s,Field f,String raw,Context c){
        String value=raw.strip();
        if(!f.optional()&&value.isBlank())return msg("text.empty","Enter %s.",label(s.task,f));
        if(value.length()>512||value.chars().anyMatch(Character::isISOControl))return msg("text.long","Please use at most 512 plain-text characters.");
        if(f.kind()==Kind.NUMBER&&!(f.optional()&&value.isBlank())){
            long number;try{number=Long.parseLong(value);}catch(NumberFormatException invalid){return msg("error.number","Enter a whole number, using digits only.");}
            var bounds=f.constraint(c);
            if(bounds!=null&&(number<bounds.min()||number>bounds.max()))return msg("error.range","Enter a number from %s to %s.",bounds.min(),bounds.max());
        }
        return null;
    }
    /** Back to an earlier field: its value becomes the draft (text) or the marked current choice, never erased. */
    private static void revisit(Session s,Field f){
        String old=s.values.remove(f.key());s.selected.remove(f.key());s.multi.clear();s.autoSelect.remove(f.key());
        if(old==null)return;
        if(f.kind()==Kind.CHOICE)s.current.put(f.key(),old);else s.drafts.put(f.key(),old);
    }
    /**
     * Review editing: return to field {@code index} with its value as the draft or marked choice. Later fields keep their
     * values (text as drafts, choices re-selected only while still offered) unless they depend on an edited field, in which
     * case they are cleared and chosen again from fresh candidates. The earlier review and its version are void.
     */
    private static void edit(Session s,int index){
        var fields=s.task.fields();Set<String> changed=new HashSet<>();changed.add(fields.get(index).key());
        revisit(s,fields.get(index));
        for(int j=index+1;j<fields.size();j++){
            var f=fields.get(j);String old=s.values.remove(f.key());s.selected.remove(f.key());s.autoSelect.remove(f.key());s.current.remove(f.key());
            if(!Collections.disjoint(f.dependsOn(),changed)){changed.add(f.key());s.drafts.remove(f.key());continue;}
            if(old==null)continue;
            if(f.kind()==Kind.CHOICE)s.autoSelect.put(f.key(),old);else s.drafts.put(f.key(),old);
        }
        s.field=index;s.multi.clear();s.page=0;s.search="";s.version="";
    }
    /**
     * The player-facing reason for a refusal. Services refuse with IllegalArgumentException or
     * IllegalStateException and a message written for players (or a bundled key); anything else is a fault,
     * whose Java message would only confuse, so the player gets a plain sentence and the log gets the detail.
     */
    private static Component reason(Throwable failure,Context c){
        if(failure instanceof FormException form)return form.component;
        if(failure instanceof Keyed keyed)return keyed.component();
        String message=failure.getMessage();
        if((failure instanceof IllegalArgumentException||failure instanceof IllegalStateException)&&!(failure instanceof NumberFormatException)&&message!=null&&!message.isBlank())
            return PlayerWords.component(message,c);
        com.mojang.logging.LogUtils.getLogger().warn("[MCA: Kingdoms] Kingdom Tasks request failed unexpectedly",failure);
        return msg("error.unexpected","Something went wrong. Check the current status before trying again.");
    }
    /** Runs the reviewed handler once; the session keeps the outcome for CHECK. */
    private static Reply execute(ServerPlayer p,Request q,Session s,Context c){
        s.executed=true;s.state=UUID.randomUUID();
        try{var outcome=s.task.handler().run(c);
            if(outcome instanceof Pending pending){s.result=PlayerWords.component(pending.message(),c);s.status="waiting";Session retained=s;pending.completion().whenComplete((value,failure)->p.getServer().execute(()->{if(failure==null&&value instanceof Outcome typed)apply(retained,typed,c);else{retained.result=failure==null?PlayerWords.component(value,c):failed(failure,c);retained.status=failure==null?done(retained.task):status(failure);retained.next=next(retained,c,failure);}}));}
            else if(outcome instanceof Outcome typed)apply(s,typed,c);
            else{s.result=PlayerWords.component(outcome,c);s.status=done(s.task);s.next=next(s,c,null);}
        }catch(Exception failure){s.result=failed(failure,c);s.status=status(failure);s.next=next(s,c,failure);}
        return result(q,s);
    }
    /**
     * A typed outcome (F07): its state, not its wording, decides the status, and its operation is kept so Check can read
     * that record again. A refusal reads like any other refusal; the next step is the outcome's own when it names one.
     */
    private static void apply(Session s,Outcome outcome,Context c){
        Component message=PlayerWords.component(outcome.message(),c);
        s.result=switch(outcome.state()){
            case REFUSED->lines(List.of(msg("failed","Could not complete: %s",message),msg("failed.hint","Check the current status before repeating a consequential action.")));
            case UNKNOWN->lines(List.of(msg("failed.unknown","We could not confirm the result. Check its status before trying again."),message));
            default->message;};
        s.status=outcome.state()==Outcome.State.APPLIED&&!s.task.consequential()?"read":outcome.status();
        if(!outcome.operation().isBlank())s.operation=outcome.operation();
        s.next=outcome.next().isPresent()?outcome.next():next(s,c,outcome.state()==Outcome.State.REFUSED?new IllegalArgumentException("refused"):null);
    }
    /**
     * Check: reads the executed operation's current state from its owner through the task's probe, without running the
     * handler again, so no payment, ownership change, vote or signature repeats. Tasks without a probe (their result was
     * final when it was reported) keep the retained result.
     */
    private static void recheck(ServerPlayer p,Session s){
        if(s.operation.isBlank()||!ActionRegistry.probed(s.task))return;
        var c=s.context(p);
        try{
            var current=ActionRegistry.probe(s.task,c,s.operation);
            if(current.isEmpty()){s.result=lines(List.of(msg("check.gone","This record is no longer available to you; its last known outcome is below."),s.result));return;}
            apply(s,current.get(),c);
            s.result=lines(List.of(msg("check.current","Current status, read again just now; nothing was sent again."),s.result));
        }catch(RuntimeException unreadable){
            com.mojang.logging.LogUtils.getLogger().warn("[MCA: Kingdoms] Could not read the current status of {}",s.task.id(),unreadable);
            s.result=lines(List.of(msg("check.unreadable","The current status could not be read just now; the last known outcome is below."),s.result));
        }
    }
    /** A completed action is "done"; a read-only task changed nothing, so its result is simply what it read. */
    private static String done(Task task){return task.consequential()?"done":"read";}
    /** A service refusal is a known outcome; any other fault leaves the result unconfirmed. Never parsed from text. */
    private static String status(Throwable failure){
        Throwable cause=failure instanceof java.util.concurrent.CompletionException&&failure.getCause()!=null?failure.getCause():failure;
        return cause instanceof FormException||(cause instanceof IllegalArgumentException||cause instanceof IllegalStateException)&&!(cause instanceof NumberFormatException)&&cause.getMessage()!=null&&!cause.getMessage().isBlank()?"refused":"unknown";
    }
    private static Optional<NextStep> next(Session s,Context c,Throwable failure){try{return ActionRegistry.nextStep(s.task,c,failure);}catch(RuntimeException ignored){return Optional.empty();}}
    private static Component failed(Throwable failure,Context c){
        if(status(failure).equals("unknown"))return lines(List.of(msg("failed.unknown","We could not confirm the result. Check its status before trying again."),reason(failure,c)));
        return lines(List.of(msg("failed","Could not complete: %s",reason(failure,c)),msg("failed.hint","Check the current status before repeating a consequential action.")));
    }
    /** A task this server cannot run: the established cause and the Book topic about it. No session is opened. */
    private static Reply unavailable(Request q,Task task,TaskMeta.Availability availability){
        var lines=List.of(availability.reason(),msg("unavailable.hint","This is a setting or an installed mod on this server, not something you did. The Book explains the feature; an operator can change it."));
        return new Reply(q.request(),NONE,NONE,"unavailable",encode(title(task)),encode(lines(lines)),List.of(),task.id(),0,false,false,false,0,0,"","","",availability.learnMore());
    }
    private static Map<String,String> prefill(String json){
        var out=new LinkedHashMap<String,String>();if(json==null||json.isBlank())return out;
        try{for(var e:JsonParser.parseString(json).getAsJsonObject().entrySet()){String v=e.getValue().isJsonPrimitive()?e.getValue().getAsString():"";if(v.length()<=512&&v.chars().noneMatch(Character::isISOControl))out.put(e.getKey(),v);}}catch(RuntimeException ignored){}
        return out;
    }
    /** Opening-screen context: choice fields are selected while still offered, text fields start from the value as a draft. */
    private static void seed(Session s,Map<String,String> prefill){
        for(var f:s.task.fields()){String value=prefill.get(f.key());if(value==null)continue;if(f.kind()==Kind.CHOICE)s.autoSelect.put(f.key(),value);else s.drafts.put(f.key(),value);}
    }
    /** Selects prefilled or retained choices, in order, while the server still offers them; stops at the first field needing input. */
    private static void autoSelect(ServerPlayer p,Session s){
        while(s.field<s.task.fields().size()){
            var f=s.task.fields().get(s.field);if(f.kind()!=Kind.CHOICE)return;String wanted=s.autoSelect.remove(f.key());if(wanted==null)return;
            var found=f.options().apply(s.context(p)).stream().filter(o->o.value().equals(wanted)).findFirst();
            if(found.isEmpty()||found.get().metadata().containsKey(ActionRegistry.UNAVAILABLE)){s.notice=msg("edit.gone","Your earlier choice for %s is no longer available; choose again.",label(s.task,f));return;}
            s.values.put(f.key(),wanted);s.selected.put(f.key(),found.get());s.field++;s.page=0;s.search="";
        }
    }
    private static void validate(ServerPlayer p,Session s){
        var values=new LinkedHashMap<String,String>();var selected=new LinkedHashMap<String,Choice>();
        for(var f:s.task.fields()){
            var c=new Context(p,values,selected);String value=s.values.getOrDefault(f.key(),"");
            if(f.kind()==Kind.CHOICE){var found=f.options().apply(c).stream().filter(o->o.value().equals(value)).findFirst().orElseThrow(()->fail("validate.gone","A selected person or record is no longer available."));
                if(found.metadata().containsKey(ActionRegistry.UNAVAILABLE))throw fail("validate.unavailable","%s can no longer be chosen: %s",found.shown(),found.shownDetail());
                if(!found.equals(s.selected.get(f.key())))throw fail("validate.changed","A selected record changed. Open the task again to review it.");selected.put(f.key(),found);
            }else if(f.kind()==Kind.MULTI){var permitted=f.options().apply(c).stream().filter(o->!o.metadata().containsKey(ActionRegistry.UNAVAILABLE)).map(Choice::value).toList();for(String v:value.isEmpty()&&f.optional()?new String[0]:value.split(","))if(!permitted.contains(v))throw fail("validate.choice","An available choice changed.");}
            else if(f.kind()==Kind.NUMBER&&f.bounds()!=null&&!(f.optional()&&value.isBlank())){
                try{f.constraint(c).check(Long.parseLong(value));}catch(NumberFormatException invalid){throw fail("error.number","Enter a whole number, using digits only.");}
            }
            values.put(f.key(),value);
        }
    }
    static Component label(Task task,Field f){return tr("task.mcakingdoms."+task.id()+".field."+f.key(),f.label());}
    static Component title(Task task){return tr(task.titleKey(),task.title());}
    static Component help(Task task){return tr(task.helpKey(),task.help());}
    static Component category(Task task){return tr(task.categoryKey(),task.category());}
    private static Component help(Field f,Context c){return PlayerWords.component(f.help(),c);}
    /** Says plainly whether the task only reads or changes records, on every form page. */
    private static Component behaviour(Task task){return task.consequential()?msg("behaviour.changes","This task changes records. Nothing happens until you review it and choose Apply reviewed action.")
            :msg("behaviour.reads","This task only reads records. It runs as soon as its fields are complete and changes nothing.");}
    private static int steps(Task task){return task.fields().size()+(task.consequential()?1:0);}
    private static String optionId(Session s,String value){return UUID.nameUUIDFromBytes((s.id+":"+value).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().substring(0,13);}
    private static Reply form(ServerPlayer p,Request q,Session s){
        var c=s.context(p);
        if(s.field==s.task.fields().size()){
            validate(p,s);s.version=s.task.version().apply(c);var lines=new ArrayList<Component>();lines.add(help(s.task));
            // A task without questions (Register my residence, for one) has no choices to list or edit.
            if(!s.task.fields().isEmpty())lines.add(heading("review.section.choices","Your choices"));
            var editable=new ArrayList<Option>();
            for(int i=0;i<s.task.fields().size();i++){var f=s.task.fields().get(i);var chosen=s.selected.get(f.key());Component value;
                if(f.kind()==Kind.MULTI){var picked=Arrays.asList(s.values.getOrDefault(f.key(),"").split(","));var names=Component.empty();boolean first=true;
                    for(var o:f.options().apply(c))if(picked.contains(o.value())){if(!first)names.append(", ");first=false;names.append(o.shown());}
                    value=first?msg("review.none","None"):names;}
                else value=chosen==null?Component.literal(s.values.getOrDefault(f.key(),"")):chosen.shown();
                if(value.getString().isBlank())value=msg("review.blank","(left blank)");
                lines.add(label(s.task,f).copy().append(": ").append(value));
                if(chosen!=null){if(!chosen.shownDetail().getString().isBlank())lines.add(chosen.shownDetail());entityLocation(p,chosen).ifPresent(lines::add);}
                editable.add(new Option(Integer.toString(i),encode(label(s.task,f)),encode(value),false,"field:"+f.key()));
            }
            var effects=ActionRegistry.effects(s.task,c);
            if(!effects.isEmpty()){lines.add(heading("review.section.changes","What this changes"));for(Object effect:effects)lines.add(PlayerWords.component(effect,c));}
            var requirements=ActionRegistry.requirements(s.task,c);
            if(!requirements.isEmpty()){lines.add(heading("review.section.requirements","Requirements now"));requirements.forEach(r->lines.add(r.line()));}
            lines.add(heading("review.section.confirm","Before you confirm"));
            Component action=ActionRegistry.applyLabel(s.task,c);
            lines.add(s.task.consequential()?msg("review.consequential.named","Review the people, terms and consequences above. %s performs this action once; backing out changes nothing.",action)
                    :msg("review.ready","Ready. Continue to perform this task."));
            if(s.task.consequential()&&!s.task.fields().isEmpty())lines.add(msg("review.edit","Choose Edit an answer to change one answer; the review is rebuilt from current records before you can apply."));
            int steps=steps(s.task);
            return new Reply(q.request(),s.id,s.state,"review",encode(title(s.task)),encode(lines(lines)),editable.size()<=20?editable:editable.subList(0,20),s.task.id(),0,false,s.field>0,false,steps,steps,"",encode(action),"","");
        }
        var f=s.task.fields().get(s.field);boolean selection=f.kind()==Kind.CHOICE||f.kind()==Kind.MULTI;int step=s.field+1,steps=steps(s.task);
        if(!selection){
            String draft=s.values.containsKey(f.key())?s.values.get(f.key()):s.drafts.getOrDefault(f.key(),f.initial());
            var help=new ArrayList<Component>();help.add(title(s.task));help.add(help(f,c));
            var bounds=f.constraint(c);if(bounds!=null)help.add(bounds.describe());
            if(s.field==0)requirementLines(s,c,help);
            soFar(s,help);
            if(f.optional())help.add(msg("optional","Optional: leave it blank to skip."));
            if(s.notice!=null)help.add(s.notice);
            if(s.problem!=null)help.add(msg("problem","Problem: %s",s.problem).copy().withStyle(ChatFormatting.RED));
            help.add(behaviour(s.task));
            return new Reply(q.request(),s.id,s.state,"text",encode(label(s.task,f)),encode(lines(help)),List.of(),draft,0,false,s.field>0,false,step,steps,"");
        }
        if(f.kind()==Kind.MULTI&&s.multi.isEmpty()&&s.drafts.containsKey(f.key())){
            // Revisited or edited multi-selection: restore the earlier selection where those choices are still offered.
            var offered=f.options().apply(c).stream().map(Choice::value).toList();
            for(String v:s.drafts.remove(f.key()).split(","))if(offered.contains(v))s.multi.add(v);
        }
        s.candidates=List.copyOf(f.options().apply(c));var options=new ArrayList<Option>();var tokens=SearchText.tokens(s.search);
        // Rank title (label) matches before detail matches; ties keep the provider's order. Only listed records are searched.
        var ranked=new ArrayList<int[]>();
        for(int i=0;i<s.candidates.size();i++){var option=s.candidates.get(i);
            var rank=tokens.isEmpty()?SearchText.Rank.TITLE:SearchText.rank(tokens,LocalizedText.readings(option.shown()),List.of(),LocalizedText.readings(option.shownDetail()));
            if(rank!=SearchText.Rank.NONE)ranked.add(new int[]{rank.ordinal(),i});}
        ranked.sort(Comparator.<int[]>comparingInt(r->r[0]).thenComparingInt(r->r[1]));
        int start=s.page*20;
        for(int r=start;r<ranked.size()&&options.size()<20;r++){int i=ranked.get(r)[1];var option=s.candidates.get(i);
            var detail=bounded(option.shownDetail(),1024).copy();entityLocation(p,option).ifPresent(l->detail.append("\n").append(l));
            boolean marked=f.kind()==Kind.MULTI?s.multi.contains(option.value()):option.value().equals(s.current.get(f.key()));
            if(marked&&f.kind()==Kind.CHOICE)detail.append("\n").append(msg("current","Your current choice"));
            options.add(new Option(Integer.toString(i),encode(bounded(option.shown(),256)),encode(detail),marked,optionId(s,option.value())));}
        var help=new ArrayList<Component>();help.add(title(s.task));help.add(help(f,c));
        if(s.field==0)requirementLines(s,c,help);
        soFar(s,help);
        if(s.notice!=null)help.add(s.notice);
        if(s.candidates.isEmpty())help.add(f.targetKind().isEmpty()?msg("empty","No eligible known choices. Check your location, permissions, prerequisites and installed integrations."):targetHint(f.targetKind(),c).map(h->PlayerWords.component(h,c)).orElse(msg("empty","No eligible known choices. Check your location, permissions, prerequisites and installed integrations.")));
        else if(ranked.isEmpty())help.add(msg("no_match","No choices match “%s”. Clear the search to see all %s choices.",s.search,s.candidates.size()));
        if(f.kind()==Kind.MULTI)help.add(msg("multi.count","%s selected.",s.multi.size()));
        help.add(behaviour(s.task));
        return new Reply(q.request(),s.id,s.state,"choice",encode(label(s.task,f)),encode(lines(help)),options,s.search,s.page,ranked.size()>start+20,s.field>0,f.kind()==Kind.MULTI,step,steps,"");
    }
    /** The answers given so far, so the task's context stays visible on every page ("So far: Kingdom: Serenum; Capital: Bellmeadow"). */
    private static void soFar(Session s,List<Component> help){
        if(s.field==0)return;var answers=Component.empty();boolean first=true;
        for(int i=0;i<s.field&&i<s.task.fields().size();i++){var f=s.task.fields().get(i);var chosen=s.selected.get(f.key());
            Component value=chosen!=null?chosen.shown():Component.literal(s.values.getOrDefault(f.key(),""));if(value.getString().isBlank())continue;
            if(!first)answers.append("; ");first=false;answers.append(label(s.task,f)).append(": ").append(bounded(value,80));}
        if(!first)help.add(msg("so_far","So far: %s",answers));
    }
    /** Before a workflow: the task's known requirements, so a player sees what is missing before answering anything. */
    private static void requirementLines(Session s,Context c,List<Component> help){
        var requirements=ActionRegistry.requirements(s.task,c);if(requirements.isEmpty())return;
        help.add(heading("requirements.title","What this needs"));requirements.forEach(r->help.add(r.line()));
    }
    private static Component heading(String key,String fallback){return msg(key,fallback).copy().withStyle(ChatFormatting.BOLD);}
    /** Search text of one task in every bundled language, plus the English fallbacks. */
    private static List<String> readings(String key,String fallback){var out=new ArrayList<String>();out.add(fallback);for(String locale:LocalizedText.BUNDLED)LocalizedText.get(locale,key).ifPresent(out::add);return out;}
    /** A task's goal phrases in every bundled language ("paid work", "make peace"); none when the task has no entry. */
    private static List<String> aliases(Task t){var out=new ArrayList<String>();for(String locale:LocalizedText.BUNDLED)LocalizedText.get(locale,TaskMeta.aliasesKey(t)).ifPresent(out::add);return out;}
    private static Reply catalogue(ServerPlayer p,Request q){
        var tokens=SearchText.tokens(q.search());boolean operator=p.hasPermissions(2);
        var ordered=tasks(p).stream().sorted(Comparator.comparing(Task::category).thenComparing(Task::title)).toList();
        var ranked=new ArrayList<Map.Entry<Integer,Task>>();
        for(var t:ordered){var rank=SearchText.rank(tokens,readings(t.titleKey(),t.title()),aliases(t),readings(t.categoryKey(),t.category()),
                operator?List.of(String.join(" ",readings(t.helpKey(),t.help())),t.id()):readings(t.helpKey(),t.help()));
            if(rank!=SearchText.Rank.NONE)ranked.add(Map.entry(SearchText.phrase(q.search(),aliases(t))?-1:rank.ordinal(),t));}
        ranked.sort(Map.Entry.comparingByKey()); // stable: equal ranks keep category/title order
        var tasks=ranked.stream().map(Map.Entry::getValue).toList();
        int start=Math.min(tasks.size(),q.page()*20);
        var options=tasks.subList(start,Math.min(start+20,tasks.size())).stream().map(t->new Option(t.id(),encode(title(t)),encode(category(t).copy().append(" · ").append(help(t))),t.consequential(),t.id())).toList();
        Component detail=tasks.isEmpty()&&!tokens.isEmpty()?msg("catalogue.no_match","No tasks match “%s”. Try fewer or different words, or clear the search.",q.search())
                :msg("catalogue.help","Choose a task. Search by what you want to do. No commands are entered or sent.");
        return new Reply(q.request(),NONE,NONE,"tasks",encode(msg("catalogue.title","Kingdom Tasks")),encode(detail),options,q.search(),q.page(),start+20<tasks.size(),false,false);
    }
    /**
     * The authorized catalogue for client-side search in the player's own language: ids, the consequential flag and English
     * fallbacks (help only where no bundled key exists). Bounded by the task registry; opening a task is still validated here.
     */
    private static Reply index(ServerPlayer p,Request q){
        var tasks=new JsonArray();
        // Unavailable tasks often share one reason (every task of a provider the server lacks): each distinct reason is sent
        // once, in "reasons", and an entry names it by position ("q").
        var reasons=new JsonArray();var reasonIds=new HashMap<String,Integer>();
        for(var t:tasks(p).stream().sorted(Comparator.comparing(Task::category).thenComparing(Task::title)).toList()){
            var entry=new JsonObject();entry.addProperty("i",t.id());entry.addProperty("c",t.consequential());entry.addProperty("t",t.title());entry.addProperty("g",t.category());
            entry.addProperty("p",TaskMeta.group(t).name());
            if(!LocalizedText.has("en_us",t.helpKey()))entry.addProperty("h",t.help());
            // Unavailable features stay listed with their reason, so players learn what the server lacks instead of guessing.
            var availability=TaskMeta.availability(t,p.getServer());
            if(!availability.available()){entry.addProperty("a",availability.state().name());
                entry.addProperty("q",reasonIds.computeIfAbsent(encode(resolvable(availability.reason())),reason->{reasons.add(reason);return reasons.size()-1;}));
                entry.addProperty("l",availability.learnMore());}
            tasks.add(entry);
        }
        var index=new JsonObject();index.addProperty("operator",p.hasPermissions(2));index.add("tasks",tasks);index.add("reasons",reasons);
        return new Reply(q.request(),NONE,NONE,"index",encode(msg("catalogue.title","Kingdom Tasks")),encode(msg("catalogue.help","Choose a task. Search by what you want to do. No commands are entered or sent.")),List.of(),index.toString(),0,false,false,false);
    }
    private static Optional<Component> entityLocation(ServerPlayer player,Choice choice){
        try{var entity=player.serverLevel().getEntity(UUID.fromString(choice.value()));
            if(entity instanceof net.minecraft.world.entity.LivingEntity&&entity.distanceToSqr(player)<=32*32&&player.hasLineOfSight(entity))return Optional.of(msg("location","Current location: %s",entity.blockPosition().toShortString()));
        }catch(IllegalArgumentException ignored){}return Optional.empty();
    }
    /**
     * {@code component} without the English fallbacks a client of this version translates from its own language file, as
     * the index already omits help text the client has. Keeps the catalogue reply well inside its payload budget.
     */
    static Component resolvable(Component component){
        if(!(component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable)||!LocalizedText.has("en_us",translatable.getKey()))return component;
        Object[] args=Arrays.stream(translatable.getArgs()).map(arg->arg instanceof Component c?resolvable(c):arg).toArray();
        MutableComponent out=Component.translatable(translatable.getKey(),args).setStyle(component.getStyle());
        for(var sibling:component.getSiblings())out.append(resolvable(sibling));
        return out;
    }
    public static String encodeReply(Reply reply){
        String encoded=JSON.toJson(reply);if(dev.otectus.mcakingdoms.network.PayloadBudget.fits(encoded))return encoded;
        return JSON.toJson(new Reply(reply.request(),reply.session(),reply.state(),"error",encode(msg("long.title","Details too long")),encode(msg("long","These details exceed the display limit. Narrow your search or ask an operator to shorten the record text before continuing.")),List.of(),"",0,false,reply.back(),false));
    }
    private static Component bounded(Component text,int limit){return dev.otectus.mcakingdoms.network.PayloadBudget.clip(text,limit);}
    private static Reply copy(Request q,Reply r){return new Reply(q.request(),r.session(),r.state(),r.mode(),r.title(),r.detail(),r.options(),r.value(),r.page(),r.more(),r.back(),r.multi(),r.step(),r.steps(),r.nextOrEmpty(),r.action()==null?"":r.action(),r.statusOrEmpty(),r.learnOrEmpty());}
    private static Reply result(Request q,Session s){return new Reply(q.request(),s.id,s.state,"result",encode(title(s.task)),encode(s.result),List.of(),s.task.id(),0,false,false,false,0,0,encodeNext(s),"",s.status,"");}
    /** The next step as JSON for the client: the task, server-checked prefill and its translated title. */
    private static String encodeNext(Session s){
        if(s.next.isEmpty())return "";var next=s.next.get();var task=ActionRegistry.find(next.task());if(task.isEmpty())return "";
        var json=new JsonObject();json.addProperty("task",next.task());var prefill=new JsonObject();next.prefill().forEach(prefill::addProperty);json.addProperty("prefill",prefill.toString());json.addProperty("label",encode(title(task.get())));
        return json.toString();
    }
    public static final class Cleanup {
        @SubscribeEvent public static void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){SESSIONS.remove(e.getEntity().getUUID());RETAINED.remove(e.getEntity().getUUID());LAST_REQUEST.remove(e.getEntity().getUUID());}
        @SubscribeEvent public static void stopped(net.minecraftforge.event.server.ServerStoppedEvent e){SESSIONS.clear();RETAINED.clear();LAST_REQUEST.clear();}
    }
    private InteractionNetwork(){}
}
