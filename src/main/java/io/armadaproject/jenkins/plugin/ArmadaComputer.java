package io.armadaproject.jenkins.plugin;

import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

public class ArmadaComputer extends AbstractCloudComputer<ArmadaSlave> {

  private static final Logger LOGGER = Logger.getLogger(ArmadaComputer.class.getName());

  private boolean launching;
  private String armadaJobId = "";
  private String armadaJobSetId = "";

  public ArmadaComputer(@Nonnull ArmadaSlave slave) {
    super(slave);
    slave.getTemplate().getListener().getLogger()
        .println("Created computer for label: " + slave.getTemplate().getLabel());
  }

  @Override
  public void taskAccepted(Executor executor, Queue.Task task) {
    super.taskAccepted(executor, task);
  }

  @Override
  public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
    super.taskCompleted(executor, task, durationMS);
  }

  @Override
  public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS,
      Throwable problems) {
    super.taskCompletedWithProblems(executor, task, durationMS, problems);
    LOGGER.warning("Task completed with problems on Armada agent: " + getName() + " - "
        + problems.getMessage());
  }

  /**
   * @return true if the Pod has been created in Armada and the current instance is waiting for the
   * job to be usable.
   */
  public boolean isLaunching() {
    return launching;
  }

  public void setLaunching(boolean launching) {
    this.launching = launching;
  }

  public String getArmadaJobId() {
    return armadaJobId;
  }

  public void setArmadaJobId(String armadaJobId) {
    this.armadaJobId = armadaJobId;
  }

  public String getArmadaJobSetId() {
    return armadaJobSetId;
  }

  public void setArmadaJobSetId(String armadaJobSetId) {
    this.armadaJobSetId = armadaJobSetId;
  }

  @Override
  public void setAcceptingTasks(boolean acceptingTasks) {
    super.setAcceptingTasks(acceptingTasks);
    if (acceptingTasks) {
      launching = false;
    }
  }
}
