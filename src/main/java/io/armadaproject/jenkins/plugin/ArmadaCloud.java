package io.armadaproject.jenkins.plugin;

import api.EventOuterClass.EventMessage;
import api.EventOuterClass.EventStreamMessage;
import api.EventOuterClass.JobRunningEvent;
import api.EventOuterClass.JobSetRequest;
import api.Health.HealthCheckResponse.ServingStatus;
import hudson.AbortException;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import io.armadaproject.ArmadaClient;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class ArmadaCloud extends Cloud {

  private static final Logger LOGGER = Logger.getLogger(ArmadaCloud.class.getName());
  public static final int DEFAULT_RETENTION_TIMEOUT_MINUTES = 5;

  private transient Map<String, ArmadaJobTemplate> dynamicTemplates = new ConcurrentHashMap<>();
  private transient ArmadaEventManager<JobRunningEvent> armadaEventManager;
  private transient ConcurrentHashMap<String, Thread> jobSetIdThreads;

  private String armadaUrl;
  private String armadaPort;
  private String armadaQueue;
  private String armadaNamespace;
  private String armadaLookoutUrl;
  private String armadaLookoutPort;
  private String armadaJobSetPrefix;
  private String armadaJobSetId;
  private String armadaClusterConfigPath;
  private String jenkinsUrl;

  @DataBoundConstructor
  public ArmadaCloud(String name) {
    super(name);
  }

  /**
   * Called after deserialization to initialize transient fields.
   */
  protected Object readResolve() {
    if (dynamicTemplates == null) {
      dynamicTemplates = new ConcurrentHashMap<>();
    }
    if (armadaEventManager == null) {
      armadaEventManager = new ArmadaEventManager<>();
    }
    if (jobSetIdThreads == null) {
      jobSetIdThreads = new ConcurrentHashMap<>();
    }
    return this;
  }

  public String getArmadaUrl() {
    return armadaUrl;
  }

  @DataBoundSetter
  public void setArmadaUrl(String armadaUrl) {
    this.armadaUrl = armadaUrl;
  }

  public String getArmadaPort() {
    return armadaPort;
  }

  @DataBoundSetter
  public void setArmadaPort(String armadaPort) {
    this.armadaPort = armadaPort;
  }

  public String getArmadaQueue() {
    return armadaQueue;
  }

  @DataBoundSetter
  public void setArmadaQueue(String armadaQueue) {
    this.armadaQueue = armadaQueue;
  }

  public String getArmadaNamespace() {
    return armadaNamespace;
  }

  @DataBoundSetter
  public void setArmadaNamespace(String armadaNamespace) {
    this.armadaNamespace = armadaNamespace;
  }

  public String getArmadaLookoutUrl() {
    return armadaLookoutUrl;
  }

  @DataBoundSetter
  public void setArmadaLookoutUrl(String armadaLookoutUrl) {
    this.armadaLookoutUrl = armadaLookoutUrl;
  }

  public String getArmadaLookoutPort() {
    return armadaLookoutPort;
  }

  @DataBoundSetter
  public void setArmadaLookoutPort(String armadaLookoutPort) {
    this.armadaLookoutPort = armadaLookoutPort;
  }

  public String getArmadaJobSetPrefix() {
    return armadaJobSetPrefix;
  }

  @DataBoundSetter
  public void setArmadaJobSetPrefix(String armadaJobSetPrefix) {
    this.armadaJobSetPrefix = armadaJobSetPrefix;
  }

  public String getArmadaJobSetId() {
    return armadaJobSetId;
  }

  @DataBoundSetter
  public void setArmadaJobSetId(String armadaJobSetId) {
    this.armadaJobSetId = armadaJobSetId;
  }

  public String getArmadaClusterConfigPath() {
    return armadaClusterConfigPath;
  }

  @DataBoundSetter
  public void setArmadaClusterConfigPath(String armadaClusterConfigPath) {
    this.armadaClusterConfigPath = armadaClusterConfigPath;
  }

  public String getJenkinsUrl() {
    return jenkinsUrl;
  }

  @DataBoundSetter
  public void setJenkinsUrl(String jenkinsUrl) {
    this.jenkinsUrl = jenkinsUrl;
  }

  public int getRetentionTimeout() {
    return DEFAULT_RETENTION_TIMEOUT_MINUTES;
  }

  // TODO check whether there is existing pod template for label
  @Override
  public boolean canProvision(CloudState state) {
    return true;
  }

  @Override
  public Collection<PlannedNode> provision(CloudState state, int excessWorkload) {
    String label = state.getLabel() != null ? state.getLabel().toString() : null;
    if (label == null) {
      LOGGER.warning("No label provided for provisioning");
      return new ArrayList<>();
    }

    // Look up the dynamic template for this label
    ArmadaJobTemplate template = dynamicTemplates.get(label);
    if (template == null) {
      LOGGER.warning("No pod template found for label: " + label);
      return new ArrayList<>();
    }

    Collection<PlannedNode> result = new ArrayList<>();

    // Create a planned node for each requested workload
    for (int i = 0; i < excessWorkload; i++) {
      Callable<hudson.model.Node> callable = createNodeCallable(template);
      FutureTask<Node> future = new FutureTask<>(callable);

      result.add(new PlannedNode(
          template.getLabel(),
          future,
          1
      ));

      // Execute the task immediately
      future.run();
    }

    return result;
  }

  /**
   * Creates a callable that provisions the actual node.
   */
  private Callable<hudson.model.Node> createNodeCallable(ArmadaJobTemplate template) {
    return () -> {
      try {
        ArmadaSlave slave = new ArmadaSlave(this, template);

        // Add the slave to Jenkins
        Jenkins.get().addNode(slave);

        return slave;
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE, "Failed to create Armada slave", e);
        throw new IOException("Failed to provision Armada agent", e);
      }
    };
  }

  public void addDynamicTemplate(ArmadaJobTemplate template) {
    LOGGER.fine("Adding dynamic template for label: " + template.getLabel());
    dynamicTemplates.put(template.getLabel(), template);
  }

  public void removeDynamicTemplate(String label) {
    LOGGER.fine("Removing dynamic template for label: " + label);
    dynamicTemplates.remove(label);
  }

  public static ArmadaCloud resolveCloud(String cloudName) throws AbortException {
    if (cloudName == null) {
      ArmadaCloud cloud = Jenkins.get().clouds.get(ArmadaCloud.class);
      if (cloud == null) {
        throw new AbortException("No Armada cloud was found.");
      }
      return cloud;
    }

    Cloud cloud = Jenkins.get().getCloud(cloudName);
    if (cloud == null) {
      throw new AbortException(String.format("Cloud does not exist: %s", cloudName));
    }

    if (!(cloud instanceof ArmadaCloud)) {
      throw new AbortException(String.format(
          "Cloud is not an Armada cloud: %s (%s)",
          cloudName, cloud.getClass().getName()));
    }

    return (ArmadaCloud) cloud;
  }

  /**
   * Gets the event manager, creating it lazily if needed.
   */
  public ArmadaEventManager<JobRunningEvent> getArmadaEventManager() {
    if (armadaEventManager == null) {
      armadaEventManager = new ArmadaEventManager<>();
    }
    return armadaEventManager;
  }

  /**
   * Gets the job set threads map, creating it lazily if needed.
   */
  public ConcurrentHashMap<String, Thread> getJobSetIdThreads() {
    if (jobSetIdThreads == null) {
      jobSetIdThreads = new ConcurrentHashMap<>();
    }
    return jobSetIdThreads;
  }

  /**
   * Starts watching Armada events for the specified job set ID. Creates a background thread that
   * streams events from Armada and publishes them to subscribers.
   *
   * @param jobSetId the job set ID to watch
   * @return the thread watching the events
   */
  public Thread startWatchingArmadaEvents(String jobSetId) {
    Runnable job = () -> {
      try (ArmadaClient armadaClient = new ArmadaClient(armadaUrl, Integer.parseInt(armadaPort))) {
        JobSetRequest jobSetRequest = JobSetRequest.newBuilder()
            .setId(jobSetId)
            .setQueue(armadaQueue)
            .setWatch(true)
            .build();

        StreamObserver<EventStreamMessage> streamObserver = new StreamObserver<>() {
          @Override
          public void onNext(EventStreamMessage value) {
            LOGGER.fine("Event received for jobSetId: " + jobSetId + " message: " + value);
            if (!value.hasMessage()) {
              return;
            }

            EventMessage message = value.getMessage();
            if (!message.hasRunning()) {
              return;
            }

            JobRunningEvent jobRunningEvent = message.getRunning();
            getArmadaEventManager().publish(jobSetId, jobRunningEvent);
          }

          @Override
          public void onError(Throwable t) {
            LOGGER.severe("Error received for jobSetId: " + jobSetId + " error: " + t);
          }

          @Override
          public void onCompleted() {
            LOGGER.fine("Streaming completed for jobSetId: " + jobSetId);
          }
        };

        armadaClient.streamEvents(jobSetRequest, streamObserver);
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE,
            "Failed to connect to Armada. Could not start watching events.", e);
      }
    };
    Thread watcher = new Thread(job);
    watcher.setName("armada-event-watcher-" + jobSetId);
    watcher.setDaemon(true);
    getJobSetIdThreads().put(jobSetId, watcher);
    watcher.start();
    return watcher;
  }

  /**
   * Connects to a Kubernetes cluster using the provided server URL.
   *
   * @param serverUrl the Kubernetes API server URL
   * @param namespace the namespace to use
   * @return a Kubernetes client connected to the cluster
   */
  public KubernetesClient connect(String serverUrl, String namespace) {
    Config config = new ConfigBuilder()
        .withMasterUrl(serverUrl)
        .withNamespace(namespace)
        .withTrustCerts(true)
        .build();

    return new KubernetesClientBuilder()
        .withConfig(config)
        .build();
  }


  @Extension
  public static class DescriptorImpl extends Descriptor<Cloud> {

    @Override
    @Nonnull
    public String getDisplayName() {
      return "Armada";
    }

    @POST
    public FormValidation doTestArmadaConnection(@QueryParameter String armadaUrl,
        @QueryParameter String armadaPort) {
      try (ArmadaClient armadaClient = new ArmadaClient(armadaUrl, Integer.parseInt(armadaPort))) {
        if (ServingStatus.SERVING == armadaClient.checkHealth()) {
          return FormValidation.ok("Connected to Armada");
        }

        return FormValidation.error("Connection to Armada failed %s:%s", armadaUrl,
            armadaPort);
      } catch (Exception e) {
        LOGGER.log(Level.FINE,
            String.format("Error testing Armada connection %s:%s", armadaUrl, armadaPort), e);
        return FormValidation.error(
            "Error testing Armada connection url:%s, port:%s, cause:%s", armadaUrl, armadaPort,
            e.getCause().toString());
      }
    }
  }
}
