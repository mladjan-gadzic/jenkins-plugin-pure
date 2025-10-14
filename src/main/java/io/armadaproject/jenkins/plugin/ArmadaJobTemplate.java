package io.armadaproject.jenkins.plugin;

import hudson.model.TaskListener;
import io.fabric8.kubernetes.api.model.Pod;
import java.io.Serializable;

public class ArmadaJobTemplate implements Serializable {

  private static final long serialVersionUID = 1L;

  private final String label;
  private final Pod podSpec;
  private final TaskListener listener;

  public ArmadaJobTemplate(String label, Pod pod, TaskListener listener) {
    this.label = label;
    this.podSpec = pod;
    this.listener = listener;
  }

  public String getLabel() {
    return label;
  }

  public Pod getPodSpec() {
    return podSpec;
  }

  public TaskListener getListener() {
    return listener;
  }

}
