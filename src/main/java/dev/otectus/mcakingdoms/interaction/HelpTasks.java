package dev.otectus.mcakingdoms.interaction;

import java.util.List;
import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/** First-session and capability tasks: what this server enables for you, and the operator's health view. */
public final class HelpTasks {
    public static void init(){
        add(new Task("help.available","Getting started","What is available here?","Shows which features this server enables, which companion mods are installed, and your own access. Read-only.",
                false,false,List.of(),c->"",c->ServerFacts.forPlayer(c.player)));
        add(new Task("administration.health","Administration","Server health","Feature switches, installed providers, saved-data writability, the last definition reload, this start's Ultima migration and village reconciliation. Read-only.",
                true,false,List.of(),c->"",c->ServerFacts.forOperator(c.server())));
    }
    private HelpTasks(){}
}
