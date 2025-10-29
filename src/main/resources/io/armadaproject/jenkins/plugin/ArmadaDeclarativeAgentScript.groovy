package io.armadaproject.jenkins.plugin


import org.jenkinsci.plugins.pipeline.modeldefinition.agent.CheckoutScript
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentScript2
import org.jenkinsci.plugins.workflow.cps.CpsScript

class ArmadaDeclarativeAgentScript extends DeclarativeAgentScript2<ArmadaDeclarativeAgent> {

    ArmadaDeclarativeAgentScript(CpsScript s, ArmadaDeclarativeAgent a) {
        super(s, a)
    }

    @Override
    public void run(Closure body) {
        if (!describable.getYaml()) {
            script.error("No YAML configuration provided for Armada agent")
        }

        script.armadaJobTemplate(yaml: describable.getYaml(), cloud: describable.getCloud()) {
            // Access the label from the environment variable set by the step
            def label = script.env.ARMADA_TEMPLATE_LABEL

            script.node(label) {
                CheckoutScript.doCheckout2(script, describable) {
                    body.call()
                }
            }
        }
    }
}