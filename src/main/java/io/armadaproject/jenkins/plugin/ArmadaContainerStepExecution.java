package io.armadaproject.jenkins.plugin;

import hudson.LauncherDecorator;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

/**
 * Execution for ArmadaContainerStep. Decorates command execution to run in the specified Armada
 * container.
 */
public class ArmadaContainerStepExecution extends StepExecution {

  private static final Logger LOGGER =
      Logger.getLogger(ArmadaContainerStepExecution.class.getName());

  private final String containerName;

  public ArmadaContainerStepExecution(StepContext context, String containerName) {
    super(context);
    this.containerName = containerName;
  }

  @Override
  public boolean start() throws Exception {
    TaskListener listener = getContext().get(TaskListener.class);
    Node node = getContext().get(Node.class);

    if (listener == null) {
      throw new IOException("TaskListener is null");
    }

    if (node == null) {
      listener.error("Not running on a node").close();
      throw new IOException("Not running on a node");
    }

    Computer computer = node.toComputer();
    if (!(computer instanceof ArmadaComputer armadaComputer)) {
      listener.error("Not running on an Armada agent").close();
      throw new IOException("Not running on an Armada agent. Computer type: " +
          (computer != null ? computer.getClass().getName() : "null"));
    }

    ArmadaSlave slave = armadaComputer.getNode();

    if (slave == null) {
      listener.error("ArmadaSlave node is null").close();
      throw new IOException("ArmadaSlave node is null");
    }

    listener.getLogger().println("Started armadaContainer('" + containerName + "')");
    listener.getLogger().println("++++++++++++++++++++++++++++++++++++++++");

    ArmadaNodeContext nodeContext = new ArmadaNodeContext(getContext());

    LauncherDecorator existingDecorator = getContext().get(LauncherDecorator.class);

    // Create body invoker with ArmadaExecDecorator
    BodyInvoker invoker = getContext().newBodyInvoker();
    invoker = ArmadaExecDecorator.decorate(
        invoker,
        containerName,
        nodeContext,
        existingDecorator
    );

    invoker.withCallback(new BodyExecutionCallback() {
      @Override
      public void onSuccess(StepContext context, Object result) {
        try {
          TaskListener listener = context.get(TaskListener.class);
          if (listener != null) {
            listener.getLogger().println("++++++++++++++++++++++++++++++++++++++++");
            listener.getLogger().println("Finished armadaContainer('" + containerName + "')");
            listener.getLogger().println("========================================");
          }
          LOGGER.fine("Finished container execution: " + containerName);
          context.onSuccess(result);
        } catch (IOException | InterruptedException e) {
          context.onFailure(e);
        }
      }

      @Override
      public void onFailure(StepContext context, Throwable t) {
        try {
          TaskListener listener = context.get(TaskListener.class);
          if (listener != null) {
            listener.getLogger().println("++++++++++++++++++++++++++++++++++++++++");
            listener.getLogger().println(
                "Failed armadaContainer('" + containerName + "'): " + t.getMessage());
            listener.getLogger().println("========================================");
          }
          LOGGER.severe("Container execution failed: " + t.getMessage());
          context.onFailure(t);
        } catch (IOException | InterruptedException e) {
          context.onFailure(e);
        }
      }
    }).start();

    return false; // Async execution
  }
}
