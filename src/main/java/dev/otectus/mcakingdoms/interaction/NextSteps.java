package dev.otectus.mcakingdoms.interaction;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static dev.otectus.mcakingdoms.interaction.ActionRegistry.*;

/**
 * After a task finishes, the read or requirement task that explains what happened, with the context already known
 * (kingdom, organization, settlement) offered as a prefill. The server validates that prefill like any other choice and
 * checks the player's access when the task opens; a next step never applies anything by itself.
 */
final class NextSteps {
    private NextSteps(){}

    private static Map<String,String> keep(Context c,String... keys){var out=new HashMap<String,String>();for(String key:keys)if(!c.text(key).isBlank())out.put(key,c.text(key));return out;}

    static void init(){
        for(Task task:all()){
            String id=task.id();
            if(id.equals("definitions.reload"))nextStep(id,(c,failure)->Optional.of(new NextStep("administration.health")));
            else if(id.equals("politics.bootstrap"))nextStep(id,(c,failure)->Optional.of(failure!=null?new NextStep("politics.founding_status"):new NextStep("politics.read_overview",keep(c,"kingdom"))));
            else if(id.startsWith("politics.")&&task.consequential())nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("politics.read_overview",keep(c,"kingdom"))));
            else if(id.equals("civic.join")||id.equals("civic.introduction")||id.equals("civic.commissions")||id.equals("civic.hospitality")||id.equals("civic.workshop"))
                nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("civic.explain",keep(c,"organization"))));
            else if(id.startsWith("warfare.")&&task.consequential())nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("help.available")));
            else if(id.startsWith("world.")&&task.consequential()&&!task.operator())nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("help.available")));
            else if(id.startsWith("evolution.")&&task.consequential()&&!task.operator())nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("evolution.overview")));
            else if(id.startsWith("protection.")&&task.consequential())nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("protection.overview")));
            else if(id.startsWith("transfer.")&&task.consequential()&&!task.operator())nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("transfer.overview")));
            else if(id.startsWith("drama.")&&task.consequential())nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("drama.overview")));
            else if(id.startsWith("organization.")&&task.consequential())nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("organization.lifecycle_overview")));
            else if(id.equals("settlement.merge")||id.equals("settlement.kingdom"))nextStep(id,(c,failure)->failure==null?Optional.empty():Optional.of(new NextStep("settlement.debug",Map.of("settlement",c.text(id.equals("settlement.merge")?"source":"settlement")))));
        }
    }
}
