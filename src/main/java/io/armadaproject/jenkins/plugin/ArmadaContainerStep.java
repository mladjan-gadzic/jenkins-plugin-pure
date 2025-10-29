package io.armadaproject.jenkins.plugin;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import java.util.Collections;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Pipeline step to run commands inside a specific container of an Armada pod. Usage:
 * armadaContainer('containerName') { sh 'command' }
 */
public class ArmadaContainerStep extends Step {

  private final String name;

  @DataBoundConstructor
  public ArmadaContainerStep(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  @Override
  public StepExecution start(StepContext context) throws Exception {
    return new ArmadaContainerStepExecution(context, name);
  }

  @Extension
  public static class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "armadaContainer";
    }

    @Override
    @NonNull
    public String getDisplayName() {
      return "Run build steps in an Armada container";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return true;
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Collections.singleton(TaskListener.class);
    }
  }
}
