package io.armadaproject.jenkins.plugin;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.util.FormValidation;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgent;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentDescriptor;
import org.jenkinsci.plugins.variant.OptionalExtension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

@OptionalExtension(requirePlugins = "pipeline-model-extensions")
@Symbol("armada")
public class ArmadaDeclarativeAgent extends DeclarativeAgent<ArmadaDeclarativeAgent> {

  private String yaml;
  private String cloud;

  @DataBoundConstructor
  public ArmadaDeclarativeAgent() {
  }

  public String getYaml() {
    return yaml;
  }

  @DataBoundSetter
  public void setYaml(String yaml) {
    this.yaml = Util.fixEmpty(yaml);
  }

  public String getCloud() {
    return cloud;
  }

  @DataBoundSetter
  public void setCloud(String cloud) {
    this.cloud = Util.fixEmpty(cloud);
  }

  @Extension
  @Symbol("armada")
  public static class DescriptorImpl extends DeclarativeAgentDescriptor<ArmadaDeclarativeAgent> {

    @NonNull
    @Override
    public String getName() {
      return "armada";
    }

    public FormValidation doCheckYaml(@QueryParameter String value) {
      if (value == null || value.trim().isEmpty()) {
        return FormValidation.error("YAML configuration is required");
      }
      return FormValidation.ok();
    }
  }
}
