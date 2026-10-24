package dev.otectus.mcakingdoms.clienttest;

import dev.otectus.mcakingdoms.client.*;
import dev.otectus.mcakingdoms.client.politics.KingdomScreen;
import dev.otectus.mcakingdoms.client.townstead.TownsteadBlueprintHeader;
import dev.otectus.mcakingdoms.compat.townstead.network.TownsteadCivicHeaderPacket.Header;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;

/** Opt-in visual tour of the real screens at three GUI scales; never submitted in runtime jars. */
final class GuiStyleAcceptance {
    private final Path output;
    private int step, ticks;
    private Screen screen;
    private boolean captured, behaviorChecked;
    private VillageLedgerScreen ledger;
    GuiStyleAcceptance(Path output) { this.output=output; }
    boolean tick(Minecraft mc) throws Exception {
        if(step==27)return true;
        int page=step%9;
        if(screen==null) {
            mc.options.guiScale().set(2+step/9);mc.options.pauseOnLostFocus=false;mc.resizeDisplay();
            if(step/9==2) mc.getWindow().setGuiScale(mc.getWindow().getHeight()/240.0D);
            screen=switch(page) {
                case 0 -> ledger=new VillageLedgerScreen(null);
                case 1 -> ledger;
                case 2 -> new GuildScreen(null);
                case 3 -> new KingdomScreen(null,"mcakingdoms:serenum","");
                case 4 -> new KingdomActionsScreen(null,"settlement.create","");
                case 5 -> new HeaderPreview();
                case 6 -> new KingdomGuideScreen(null);
                case 7 -> {
                    var selected=(dev.otectus.mcakingdoms.presentation.SettlementSummary)field(ledger,"selected");
                    yield new WarRoomScreen(null,selected.id(),selected.displayName());
                }
                default -> new EntryPreview((dev.otectus.mcakingdoms.presentation.SettlementSummary)field(ledger,"selected"));
            };
            mc.setScreen(screen);ticks=0;captured=false;behaviorChecked=false;
            if(page==1){var select=VillageLedgerScreen.class.getDeclaredMethod("selectRow",int.class);select.setAccessible(true);select.invoke(ledger,0);}
        }
        if(mc.screen!=screen)throw new AssertionError("GUI tour screen changed unexpectedly");
        if(++ticks>240)throw new AssertionError("GUI tour timed out: "+step);
        boolean ready=switch(page) {
            case 0 -> !((boolean)field(screen,"loading"))&&field(screen,"page")!=null;
            case 1 -> field(screen,"selected")!=null;
            case 2 -> field(screen,"view")!=null&&field(screen,"pending")==null;
            case 3 -> field(screen,"page")!=null&&field(screen,"pending")==null;
            case 4 -> field(screen,"reply")!=null&&field(screen,"pending")==null;
            case 7 -> field(screen,"snapshot")!=null;
            default -> true;
        };
        if(!ready||ticks<30)return false;
        if (!behaviorChecked) {
            if (page == 2) {
                AbstractWidget shortRecord = new MenuTextPanel(0,0,100,50,Component.literal("Heading"));
                ((MenuTextPanel)shortRecord).setContent(java.util.List.of(Component.literal("First"),Component.literal("Last")));
                shortRecord.setFocused(true);
                shortRecord.mouseClicked(shortRecord.getWidth()+4,10,0);
                shortRecord.mouseDragged(shortRecord.getWidth()+4,15,0,0,5);
                if(scroll(shortRecord)<=0)throw new AssertionError("A slightly overflowing record must support scrollbar dragging");
                AbstractWidget record = (AbstractWidget)field(screen,"record");
                MenuTextPanel panel = (MenuTextPanel)record;
                @SuppressWarnings("unchecked") var original=(java.util.List<Component>)field(record,"content");
                panel.setContent(java.util.stream.IntStream.range(0,40).mapToObj(i->(Component)Component.literal("Scrollable record line "+i)).toList());
                screen.setFocused(record);
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_END,0,0);
                if (scroll(record) <= 0) throw new AssertionError("Guild record keyboard scroll did not advance");
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                if (scroll(record) != 0) throw new AssertionError("Guild record Home did not reset scroll");
                record.mouseScrolled(record.getX()+12,record.getY()+30,-1);
                if (scroll(record) <= 0) throw new AssertionError("Guild record wheel scroll did not advance");
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                record.mouseClicked(record.getX()+record.getWidth()+4,record.getY()+4,0);
                record.mouseDragged(record.getX()+record.getWidth()+4,record.getY()+record.getHeight()+1,0,0,record.getHeight());
                if(scroll(record)<=0)throw new AssertionError("Native scrollbar drag did not advance");
                record.mouseReleased(record.getX()+record.getWidth()+4,record.getY()+record.getHeight()+1,0);
                screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME,0,0);
                panel.setContent(original);
                screen.setFocused(null);
            }
            if (page == 3) {
                var original=(dev.otectus.mcakingdoms.api.politics.Politics.Page)field(screen,"page");
                var rows=java.util.stream.IntStream.range(0,20).mapToObj(i->new dev.otectus.mcakingdoms.api.politics.Politics.Row("row"+i,"Record "+i,"Details "+i)).toList();
                var fixture=new dev.otectus.mcakingdoms.api.politics.Politics.Page(original.requestId(),original.revision(),original.kingdom(),original.tab(),original.offset(),false,rows,original.definitions(),original.actionDenials(),original.diagnostic());
                var pageField=KingdomScreen.class.getDeclaredField("page");pageField.setAccessible(true);pageField.set(screen,fixture);
                screen.resize(mc,screen.width,screen.height);
                press(screen,Component.translatable("politics.mcakingdoms.next").getString());
                if((int)field(screen,"rowScroll")<=0||field(screen,"pending")!=null)throw new AssertionError("Keyboard Next did not reach the next local records");
                press(screen,Component.translatable("politics.mcakingdoms.previous").getString());
                if((int)field(screen,"rowScroll")!=0)throw new AssertionError("Keyboard Previous did not return to the first records");
                pageField.set(screen,original);screen.resize(mc,screen.width,screen.height);
            }
            if (page == 4) {
                var target=(net.minecraft.client.gui.components.EditBox)field(screen,"input");
                target.setValue("draft-retained");
                screen.resize(mc,screen.width,screen.height);
                target=(net.minecraft.client.gui.components.EditBox)field(screen,"input");
                if (!target.getValue().equals("draft-retained")) throw new AssertionError("Kingdom form lost its draft on resize");
                target.setValue("");
            }
            behaviorChecked=true;
        }
        for(var child:screen.children())if(child instanceof AbstractWidget widget&&widget.visible) {
            if(widget.getX()<0||widget.getY()<0||widget.getX()+widget.getWidth()>screen.width||widget.getY()+widget.getHeight()>screen.height)
                throw new AssertionError("GUI widget outside screen: "+widget.getMessage().getString());
        }
        var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w->w.visible).toList();
        for(int a=0;a<widgets.size();a++)for(int b=a+1;b<widgets.size();b++){
            var one=widgets.get(a);var two=widgets.get(b);
            if(one.getX()<two.getX()+two.getWidth()&&one.getX()+one.getWidth()>two.getX()
                    &&one.getY()<two.getY()+two.getHeight()&&one.getY()+one.getHeight()>two.getY())
                throw new AssertionError("Overlapping widgets: "+one.getMessage().getString()+" / "+two.getMessage().getString());
        }
        String name="gui-"+(step/9==2?"compact":2+step/9)+"-"+new String[]{"ledger","settlement","guild","kingdom","form","blueprint-header","guide","war-room","entry"}[page]+".png";
        Path image=output.resolve("screenshots").resolve(name);
        if(!captured){Screenshot.grab(output.toFile(),name,mc.getMainRenderTarget(),message->{});captured=true;return false;}
        if(!Files.isRegularFile(image)||Files.size(image)==0)return false;
        System.out.println("GUI_STYLE_PASS "+name);step++;screen=null;return step==27;
    }
    private static void press(Screen screen,String label){
        var button=screen.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance)
                .map(net.minecraft.client.gui.components.Button.class::cast).filter(b->b.getMessage().getString().equals(label)).findFirst().orElseThrow();
        if(!button.active)throw new AssertionError("Disabled paging button: "+label);
        screen.setFocused(button);
        if(!screen.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER,0,0))throw new AssertionError("Keyboard button activation failed: "+label);
    }
    private static int scroll(AbstractWidget widget)throws Exception {
        var f=java.util.Arrays.stream(net.minecraft.client.gui.components.AbstractScrollWidget.class.getDeclaredFields()).filter(v->v.getType()==double.class).findFirst().orElseThrow();f.setAccessible(true);
        return ((Double)f.get(widget)).intValue();
    }
    private static final class EntryPreview extends Screen {
        private final dev.otectus.mcakingdoms.presentation.SettlementSummary settlement;
        EntryPreview(dev.otectus.mcakingdoms.presentation.SettlementSummary settlement){super(Component.literal("Entry banner"));this.settlement=settlement;}
        @Override public void render(GuiGraphics graphics,int x,int y,float tick){
            renderBackground(graphics);
            try {
                var type=Class.forName("dev.otectus.mcakingdoms.client.SettlementOverlayRenderer");
                var draw=type.getDeclaredMethod("render",GuiGraphics.class,net.minecraft.client.gui.Font.class,
                        dev.otectus.mcakingdoms.presentation.SettlementSummary.class,dev.otectus.mcakingdoms.presentation.KingdomSummary.class,
                        int.class,int.class,int.class,int.class,int.class,float.class);draw.setAccessible(true);
                draw.invoke(null,graphics,font,settlement,new dev.otectus.mcakingdoms.presentation.KingdomSummary(
                        new ResourceLocation("mcakingdoms:serenum"),"kingdom.mcakingdoms.serenum",new ResourceLocation("mcakingdoms:serenum"),0x426946),width,height,54,40,80,0.0F);
            }catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}
            super.render(graphics,x,y,tick);
        }
        @Override public boolean isPauseScreen(){return false;}
    }
    private static Object field(Object instance,String name)throws Exception {var f=instance.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(instance);}
    private static final class HeaderPreview extends Screen {
        HeaderPreview(){super(Component.literal("Blueprint header"));}
        @Override public void render(GuiGraphics graphics,int x,int y,float tick) {
            renderBackground(graphics);
            try {
                var draw=TownsteadBlueprintHeader.class.getDeclaredMethod("draw",GuiGraphics.class,Screen.class,Header.class);draw.setAccessible(true);
                draw.invoke(null,graphics,this,new Header("Willowbrook",new ResourceLocation("mcakingdoms:serenum"),"kingdom.mcakingdoms.serenum",new ResourceLocation("mcakingdoms:serenum"),0x426946,"respected"));
            }catch(ReflectiveOperationException failure){throw new RuntimeException(failure);}
            super.render(graphics,x,y,tick);
        }
        @Override public boolean isPauseScreen(){return false;}
    }
}
