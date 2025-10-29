package io.armadaproject.jenkins.plugin;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;

public class ArmadaJobTemplateStepExecution extends AbstractStepExecutionImpl {

  public static final Logger LOGGER = Logger.getLogger(
      ArmadaJobTemplateStepExecution.class.getName());
  private static final long serialVersionUID = 1L;
  @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "not needed on deserialization")
  private final transient ArmadaJobTemplateStep step;

  ArmadaJobTemplateStepExecution(ArmadaJobTemplateStep step, StepContext context) {
    super(context);
    this.step = step;
  }

  @Override
  public boolean start() throws Exception {
    String label = step.registerTemplate(getContext().get(hudson.model.TaskListener.class));

    BodyInvoker invoker = getContext().newBodyInvoker()
        .withContext(EnvironmentExpander.merge(
            getContext().get(EnvironmentExpander.class),
            EnvironmentExpander.constant(
                Collections.singletonMap("ARMADA_TEMPLATE_LABEL", label))))
        .withCallback(
            new BodyExecutionCallback() {
              @Override
              public void onSuccess(StepContext context, Object result) {
                context.onSuccess(result);
              }

              @Override
              public void onFailure(StepContext context, Throwable t) {
                try {
                  step.unregisterTemplate();
                } catch (IOException e) {
                  LOGGER.log(java.util.logging.Level.WARNING, "Failed to unregister template", e);
                }
                context.onFailure(t);
              }
            });
    invoker.start();

    return false;
  }


}
