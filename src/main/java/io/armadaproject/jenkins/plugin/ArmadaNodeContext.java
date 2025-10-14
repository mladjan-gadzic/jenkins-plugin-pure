package io.armadaproject.jenkins.plugin;

import static org.awaitility.Awaitility.await;

import api.EventOuterClass.JobRunningEvent;
import hudson.AbortException;
import hudson.model.Node;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jenkinsci.plugins.workflow.steps.StepContext;

/**
 * Helper class for steps running in an Armada node context. This class subscribes to Armada events
 * and waits for the job to reach running state, then extracts pod information from the
 * JobRunningEvent to connect to the Kubernetes cluster.
 */
public class ArmadaNodeContext implements Serializable {

  @Serial
  private static final long serialVersionUID = 1L;
  private transient StepContext context;
  private transient KubernetesClient client;

  private String podName;
  private String namespace;
  private final String cloudName;
  private final String nodeName;

  public ArmadaNodeContext(StepContext context) throws Exception {
    this.context = context;
    ArmadaSlave agent = getArmadaSlave();
    // Pod name and namespace will be set from Armada event when connectToCloud() is called
    this.podName = null;
    this.namespace = null;
    this.cloudName = agent.getCloudName();
    this.nodeName = agent.getNodeName();
  }

  public String getPodName() throws Exception {
    return podName;
  }

  public String getNamespace() throws Exception {
    return namespace;
  }

  public KubernetesClient connectToCloud() throws Exception {
    if (client != null) {
      return client;
    }

    ArmadaSlave armadaSlave;
    if (context != null) {
      armadaSlave = getArmadaSlave();
    } else {
      // After deserialization, context is null, so get the slave from Jenkins
      hudson.model.Node node = jenkins.model.Jenkins.get().getNode(nodeName);
      if (!(node instanceof ArmadaSlave)) {
        throw new RuntimeException("Node is not an Armada slave: " + nodeName);
      }
      armadaSlave = (ArmadaSlave) node;
    }

    ArmadaCloud armadaCloud = ArmadaCloud.resolveCloud(cloudName);
    ArmadaComputer computer = (ArmadaComputer) armadaSlave.getComputer();

    if (computer == null) {
      throw new RuntimeException("Computer is null for slave: " + nodeName);
    }

    String jobId = computer.getArmadaJobId();
    String jobSetId = computer.getArmadaJobSetId();

    if (jobId == null || jobId.isEmpty()) {
      throw new RuntimeException("No job ID available for slave: " + nodeName);
    }

    if (jobSetId == null || jobSetId.isEmpty()) {
      throw new RuntimeException("No job set ID available for slave: " + nodeName);
    }

    ArmadaEventManager<JobRunningEvent> armadaEventManager = armadaCloud.getArmadaEventManager();
    AtomicReference<JobRunningEvent> matchedEvent = new AtomicReference<>();
    Consumer<JobRunningEvent> consumer = event -> {
      if (event.getJobId().equals(jobId)) {
        matchedEvent.set(event);
      }
    };
    armadaEventManager.subscribe(jobSetId, consumer);

    armadaCloud.getJobSetIdThreads().putIfAbsent(jobSetId,
        armadaCloud.startWatchingArmadaEvents(jobSetId));

    try {
      await().atMost(60, TimeUnit.SECONDS).until(() -> matchedEvent.get() != null);
    } catch (Exception e) {
      throw new RuntimeException("Timeout waiting for JobRunningEvent for job: " + jobId, e);
    } finally {
      armadaEventManager.unsubscribe(jobSetId, consumer);
    }

    JobRunningEvent event = matchedEvent.get();
    if (event == null) {
      throw new RuntimeException("Failed to find job: " + jobId +
          " running event for jobSetId: " + jobSetId);
    }

    String clusterId = event.getClusterId();
    String serverUrl;
    try {
      serverUrl = ClusterConfigParser.parse(armadaCloud.getArmadaClusterConfigPath())
          .get(clusterId);

      if (serverUrl == null || serverUrl.isEmpty()) {
        throw new RuntimeException("No server URL found for cluster: " + clusterId);
      }

    } catch (Exception ex) {
      throw new RuntimeException("Failed to parse cluster config file", ex);
    }

    podName = event.getPodName();
    namespace = event.getPodNamespace();

    return armadaCloud.connect(serverUrl, namespace);
  }

  private ArmadaSlave getArmadaSlave() throws IOException, InterruptedException {
    Node node = context.get(Node.class);
    if (!(node instanceof ArmadaSlave)) {
      throw new AbortException(
          String.format("Node is not an Armada node: %s",
              node != null ? node.getNodeName() : null));
    }
    return (ArmadaSlave) node;
  }

}
