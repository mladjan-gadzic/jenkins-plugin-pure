package io.armadaproject.jenkins.plugin;

import hudson.model.TaskListener;
import io.fabric8.kubernetes.api.model.Pod;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.logging.Logger;

/**
 * Represents a job template for Armada with a label, pod specification, and task listener. The
 * TaskListener is necessary to stream logs to the pipeline console during execution.
 * <p>
 * Note: TaskListener is marked transient because many implementations are not serializable. After
 * deserialization, the listener will be null. Callers should check for null and handle gracefully
 * (e.g., fall back to a provided listener or log to stderr).
 */
public class ArmadaJobTemplate implements Serializable {

  private static final long serialVersionUID = 2L;
  private static final Logger LOGGER = Logger.getLogger(ArmadaJobTemplate.class.getName());

  private final String label;
  private final Pod podSpec;

  /**
   * The listener for streaming logs to the pipeline console. Marked transient because TaskListener
   * implementations may not be serializable. Will be null after deserialization.
   */
  private final transient TaskListener listener;

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

  /**
   * Returns the task listener for console output. May be null if this object was deserialized.
   * Callers should handle null gracefully.
   *
   * @return the task listener, or null if not available
   */
  public TaskListener getListener() {
    return listener != null ? listener : TaskListener.NULL;
  }

  /**
   * Custom serialization to handle the transient listener field.
   */
  private void writeObject(ObjectOutputStream out) throws IOException {
    out.defaultWriteObject();
    // listener is transient, so it won't be serialized
    if (listener != null) {
      LOGGER.fine("TaskListener will not be serialized for template: " + label);
    }
  }

  /**
   * Custom deserialization to handle the transient listener field.
   */
  private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
    in.defaultReadObject();
    // listener will be null after deserialization
    LOGGER.fine("TaskListener is null after deserialization for template: " + label);
  }
}
