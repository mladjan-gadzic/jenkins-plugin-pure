package io.armadaproject.jenkins.plugin;

import hudson.Extension;
import hudson.model.TaskListener;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

public class ArmadaJobTemplateStep extends Step implements Serializable {

  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = Logger.getLogger(ArmadaJobTemplateStep.class.getName());

  private final String yaml;
  private final String cloud;
  private transient String label;

  @DataBoundConstructor
  public ArmadaJobTemplateStep(String yaml, String cloud) {
    this.yaml = yaml;
    this.cloud = cloud;
  }

  public String getLabel() {
    return label;
  }

  @Override
  public StepExecution start(StepContext context) throws Exception {
    return new ArmadaJobTemplateStepExecution(this, context);
  }

  public String registerTemplate(TaskListener listener) throws IOException {
    ArmadaCloud cloud = ArmadaCloud.resolveCloud(this.cloud);
    label = ArmadaSlave.generateLabel(cloud.name);

    Pod podSpec = createPodSpec(yaml);
    validatePodSpec(podSpec, listener);

    ArmadaJobTemplate template = new ArmadaJobTemplate(label, podSpec, listener);
    cloud.addDynamicTemplate(template);
    listener.getLogger().println("Registered armada job template: " + label);
    return label;
  }

  private void validatePodSpec(Pod podSpec, TaskListener listener) throws IOException {
    if (podSpec.getSpec() == null) {
      throw new IOException("Pod spec is missing");
    }
    if (podSpec.getSpec().getContainers() == null ||
        podSpec.getSpec().getContainers().isEmpty()) {
      listener.getLogger().println(
          "Warning: No containers defined in pod spec. JNLP agent will be added automatically.");
    }
  }

  public void unregisterTemplate() throws IOException {
    ArmadaCloud cloud = ArmadaCloud.resolveCloud(this.cloud);
    cloud.removeDynamicTemplate(label);
  }

  public Pod createPodSpec(String yaml) throws IOException {
    if (yaml == null || yaml.isEmpty()) {
      throw new IOException("No YAML configuration provided");
    }

    try {
      String cleanedYaml = cleanYaml(yaml);

      Pod podSpec = Serialization.unmarshal(cleanedYaml, Pod.class);

      if (podSpec == null) {
        throw new IOException("Failed to parse YAML into Pod specification");
      }

      // Ensure metadata is initialized
      if (podSpec.getMetadata() == null) {
        podSpec.setMetadata(new io.fabric8.kubernetes.api.model.ObjectMetaBuilder().build());
      }

      return podSpec;
    } catch (Exception e) {
      LOGGER.severe("Failed to create pod spec from YAML: " + e.getMessage());
      throw new IOException("Failed to parse YAML or create pod spec", e);
    }
  }

  public String cleanYaml(String yaml) {
    if (yaml == null || yaml.isEmpty()) {
      return yaml;
    }

    String[] lines = yaml.split("\n");

    // Find the minimum indentation (excluding empty lines)
    int minIndent = Integer.MAX_VALUE;
    for (String line : lines) {
      if (!line.trim().isEmpty()) {
        int indent = 0;
        while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
          indent++;
        }
        minIndent = Math.min(minIndent, indent);
      }
    }

    // Remove the common indentation from each line
    if (minIndent > 0 && minIndent != Integer.MAX_VALUE) {
      StringBuilder result = new StringBuilder();
      for (String line : lines) {
        if (line.trim().isEmpty()) {
          result.append("\n");
        } else {
          result.append(line.substring(Math.min(minIndent, line.length()))).append("\n");
        }
      }
      return result.toString().trim();
    }

    return yaml.trim();
  }

  @Extension
  public static class DescriptorImpl extends StepDescriptor {

    @Override
    public String getFunctionName() {
      return "armadaJobTemplate";
    }

    @Override
    public String getDisplayName() {
      return "Define an Armada Job Template";
    }

    @Override
    public boolean takesImplicitBlockArgument() {
      return true;
    }

    @Override
    public Set<? extends Class<?>> getRequiredContext() {
      return Collections.emptySet();
    }
  }
}
