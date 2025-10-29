package io.armadaproject.jenkins.plugin;

import api.Job.JobStatusRequest;
import api.Job.JobStatusResponse;
import api.SubmitOuterClass.JobState;
import api.SubmitOuterClass.JobSubmitRequest;
import api.SubmitOuterClass.JobSubmitResponse;
import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import io.armadaproject.ArmadaClient;
import io.fabric8.kubernetes.api.model.Pod;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.awaitility.Awaitility;

public class ArmadaLauncher extends JNLPLauncher {

  private static final Logger LOGGER = Logger.getLogger(ArmadaLauncher.class.getName());

  private final AtomicBoolean launched = new AtomicBoolean(false);

  public ArmadaLauncher() {
    super();
  }

  @Override
  public boolean isLaunchSupported() {
    return !launched.get();
  }

  @Override
  public synchronized void launch(SlaveComputer computer, TaskListener listener) {
    if (!(computer instanceof ArmadaComputer armadaComputer)) {
      throw new IllegalArgumentException("ArmadaLauncher can only launch ArmadaComputer instances");
    }

    computer.setAcceptingTasks(false);
    ArmadaSlave node = armadaComputer.getNode();

    if (node == null) {
      throw new IllegalStateException("Node has been removed, cannot launch " + computer.getName());
    }

    if (launched.get()) {
      LOGGER.log(Level.FINE, "Agent has already been launched, activating: {0}",
          node.getNodeName());
      computer.setAcceptingTasks(true);
      return;
    }

    try {
      // Use the listener from the template to ensure logs go to pipeline console
      TaskListener effectiveListener = listener;
      ArmadaJobTemplate template = node.getTemplate();
      if (template != null) {
        effectiveListener = template.getListener();
        LOGGER.fine("Using listener from ArmadaJobTemplate for pipeline console output");
      }

      submitToArmada(node, armadaComputer, effectiveListener);

      waitForAgentConnection(node, armadaComputer, effectiveListener);

      // Mark as accepting tasks and launched
      computer.setAcceptingTasks(true);
      launched.set(true);

      try {
        node.save();
      } catch (IOException e) {
        LOGGER.log(Level.WARNING, "Could not save() agent: " + e.getMessage(), e);
      }
    } catch (Exception e) {
      LOGGER.severe("Failed to launch Armada agent: " + e.getMessage());
      listener.error("Failed to launch Armada agent: " + e.getMessage()).close();
      e.printStackTrace(listener.getLogger());
      throw new RuntimeException(e);
    }
  }

  private void submitToArmada(ArmadaSlave node, ArmadaComputer computer, TaskListener listener)
      throws IOException {
    Pod podSpec = validateAndGetPodSpec(node);

    try {
      ArmadaCloud cloud = ArmadaCloud.resolveCloud(node.getCloudName());
      podSpec = enrichPodSpec(cloud, node, podSpec, listener);
      logArmadaConfiguration(cloud, listener);

      try (ArmadaClient armadaClient = cloud.createArmadaClient()) {
        String jobSetId = generateAndSetJobSetId(cloud, listener);

        if (handleExistingJob(armadaClient, computer, listener)) {
          return;
        }

        submitNewJob(armadaClient, cloud, computer, podSpec, jobSetId, listener);
      }
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      handleSubmissionError(e, listener);
    }
  }

  /**
   * Validates and retrieves the pod specification from the node template.
   */
  private Pod validateAndGetPodSpec(ArmadaSlave node) throws IOException {
    Pod podSpec = node.getTemplate().getPodSpec();
    if (podSpec == null) {
      throw new IOException("No pod specification available");
    }
    return podSpec;
  }

  /**
   * Enriches the pod specification with JNLP agent configuration.
   */
  private Pod enrichPodSpec(ArmadaCloud cloud, ArmadaSlave node, Pod podSpec,
      TaskListener listener) {
    listener.getLogger().println("Enriching pod with JNLP agent configuration...");
    PodEnricher enricher = new PodEnricher(cloud, node, podSpec);
    Pod enrichedPod = enricher.enrich();
    listener.getLogger().println("Pod enriched successfully");
    return enrichedPod;
  }

  /**
   * Logs the Armada cloud configuration to the listener.
   */
  private void logArmadaConfiguration(ArmadaCloud cloud, TaskListener listener) {
    listener.getLogger().println("Using Armada configuration:");
    listener.getLogger().println("  URL: " + cloud.getArmadaUrl() + ":" + cloud.getArmadaPort());
    listener.getLogger().println("  Namespace: " + cloud.getArmadaNamespace());
    listener.getLogger().println("  Queue: " + cloud.getArmadaQueue());
  }

  /**
   * Generates a new job set ID with timestamp and sets it on the cloud.
   */
  private String generateAndSetJobSetId(ArmadaCloud cloud, TaskListener listener) {
    String jobSetId = cloud.getDisplayName()
        + new SimpleDateFormat(ArmadaPluginConfig.JOB_SET_DATE_FORMAT).format(new Date());
    cloud.setArmadaJobSetId(jobSetId);
    listener.getLogger().println("  Job Set ID: " + jobSetId);
    return jobSetId;
  }

  /**
   * Handles the case where a job already exists (interrupted provisioning recovery).
   *
   * @return true if an existing job was found and handled, false otherwise
   */
  private boolean handleExistingJob(ArmadaClient armadaClient, ArmadaComputer computer,
      TaskListener listener) throws IOException {
    if (computer.getArmadaJobId() == null || computer.getArmadaJobId().isEmpty()) {
      return false;
    }

    JobStatusResponse jobStatusResponse = armadaClient.getJobStatus(
        JobStatusRequest.newBuilder()
            .addJobIds(computer.getArmadaJobId())
            .build());
    JobState existingJobState = jobStatusResponse.getJobStatesMap()
        .get(computer.getArmadaJobId());

    LOGGER.fine("Job with id: " + computer.getArmadaJobId() + " in state: " + existingJobState);

    if (existingJobState != JobState.UNKNOWN) {
      listener.getLogger().println("Job already exists: " + computer.getArmadaJobId());
      computer.setLaunching(true);
      waitForJobRunning(armadaClient, computer, listener);
      return true;
    }

    return false;
  }

  /**
   * Submits a new job to Armada and waits for it to be running.
   */
  private void submitNewJob(ArmadaClient armadaClient, ArmadaCloud cloud, ArmadaComputer computer,
      Pod podSpec, String jobSetId, TaskListener listener) throws IOException {
    JobSubmitRequest request = createJobSubmitRequest(cloud, podSpec, jobSetId);
    logContainerInfo(podSpec, listener);

    listener.getLogger().println("Submitting job request to Armada...");
    JobSubmitResponse response = armadaClient.submitJob(request);

    String jobId = extractJobId(response);
    configureComputerWithJobInfo(computer, jobId, jobSetId);

    listener.getLogger().println("Job submitted successfully with id: " + jobId);
    logLookoutUrl(cloud, jobId, listener);

    waitForJobRunning(armadaClient, computer, listener);
  }

  /**
   * Creates a job submit request using the ArmadaMapper.
   */
  private JobSubmitRequest createJobSubmitRequest(ArmadaCloud cloud, Pod podSpec, String jobSetId) {
    ArmadaMapper mapper = new ArmadaMapper(
        cloud.getArmadaQueue(),
        cloud.getArmadaNamespace(),
        jobSetId,
        podSpec
    );
    return mapper.createJobSubmitRequest();
  }

  /**
   * Logs container information to the listener.
   */
  private void logContainerInfo(Pod podSpec, TaskListener listener) {
    if (podSpec.getSpec() != null && podSpec.getSpec().getContainers() != null) {
      listener.getLogger().println("  Containers: " + podSpec.getSpec().getContainers().size());
      podSpec.getSpec().getContainers().forEach(container -> {
        listener.getLogger()
            .println("    - " + container.getName() + " (image: " + container.getImage() + ")");
      });
    }
  }

  /**
   * Extracts the job ID from the submit response.
   */
  private String extractJobId(JobSubmitResponse response) throws IOException {
    if (response == null || response.getJobResponseItemsList() == null ||
        response.getJobResponseItemsList().isEmpty()) {
      throw new IOException("No job ID returned from Armada");
    }
    return response.getJobResponseItems(0).getJobId();
  }

  /**
   * Configures the computer with job ID and job set ID.
   */
  private void configureComputerWithJobInfo(ArmadaComputer computer, String jobId,
      String jobSetId) {
    computer.setArmadaJobId(jobId);
    computer.setArmadaJobSetId(jobSetId);
    computer.setLaunching(true);
  }

  /**
   * Logs the Lookout URL if configured.
   */
  private void logLookoutUrl(ArmadaCloud cloud, String jobId, TaskListener listener) {
    if (cloud.getArmadaLookoutUrl() != null && !cloud.getArmadaLookoutUrl().isEmpty()) {
      String armadaLookoutJobUrl = cloud.getArmadaLookoutUrl() + ":"
          + cloud.getArmadaLookoutPort() + "/?sb=" + jobId;
      listener.getLogger().println("Lookout URL: " + armadaLookoutJobUrl);
    }
  }

  /**
   * Handles job submission errors by logging and wrapping them.
   */
  private void handleSubmissionError(Exception e, TaskListener listener) throws IOException {
    LOGGER.severe("Failed to submit job to Armada: " + e.getMessage());
    listener.error("Failed to submit job to Armada: " + e.getMessage()).close();
    throw new IOException("Failed to submit job to Armada", e);
  }

  /**
   * Gets the current state of a job from Armada.
   */
  private JobState getJobState(ArmadaClient client, String jobId) throws IOException {
    try {
      JobStatusResponse status = client.getJobStatus(
          JobStatusRequest.newBuilder().addJobIds(jobId).build());
      return status.getJobStatesMap().get(jobId);
    } catch (Exception e) {
      throw new IOException("Failed to get job status for: " + jobId, e);
    }
  }

  /**
   * Waits for the Armada job to reach RUNNING state using Armada's status API.
   */
  private void waitForJobRunning(ArmadaClient armadaClient, ArmadaComputer computer,
      TaskListener listener) throws IOException {
    String jobId = computer.getArmadaJobId();
    listener.getLogger().println("Waiting for job to be running...");

    try {
      Awaitility.await()
          .atMost(ArmadaPluginConfig.DEFAULT_SLAVE_CONNECT_TIMEOUT, TimeUnit.SECONDS)
          .pollInterval(ArmadaPluginConfig.POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
          .until(() -> {
            JobState currentState = getJobState(armadaClient, jobId);
            LOGGER.fine("Job " + jobId + " state: " + currentState);

            // Check for terminal failure states using validator
            JobStateValidator.validate(currentState, jobId);

            return JobStateValidator.isRunning(currentState);
          });

      listener.getLogger().println("Job is running: " + jobId);

    } catch (Exception e) {
      LOGGER.severe("Job failed to reach RUNNING state: " + e.getMessage());
      listener.error("Job failed to reach RUNNING state: " + e.getMessage()).close();
      throw new IOException("Job did not start successfully: " + jobId, e);
    }
  }

  /**
   * Waits for the JNLP agent to connect back to Jenkins.
   */
  private void waitForAgentConnection(ArmadaSlave node, ArmadaComputer computer,
      TaskListener listener) throws IOException {
    listener.getLogger().println("Waiting for agent to connect...");

    try {
      ArmadaCloud cloud = ArmadaCloud.resolveCloud(node.getCloudName());
      try (ArmadaClient armadaClient = cloud.createArmadaClient()) {
        pollForAgentConnection(node, computer, armadaClient, listener);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for agent connection", e);
    }
  }

  /**
   * Polls for agent connection with periodic status checks and reporting.
   */
  private void pollForAgentConnection(ArmadaSlave node, ArmadaComputer computer,
      ArmadaClient armadaClient, TaskListener listener)
      throws InterruptedException, IOException {
    long lastReportTimestamp = System.currentTimeMillis();

    for (int waitedSeconds = 0; waitedSeconds < ArmadaPluginConfig.DEFAULT_SLAVE_CONNECT_TIMEOUT;
        waitedSeconds++) {
      if (checkAgentConnected(node, waitedSeconds, listener)) {
        return;
      }

      validateJobStillRunning(computer, armadaClient);

      lastReportTimestamp = reportProgressIfNeeded(node, waitedSeconds, lastReportTimestamp,
          listener);

      Thread.sleep(ArmadaPluginConfig.AGENT_CONNECTION_POLL_INTERVAL_MS);
    }

    throw new IllegalStateException(
        "Agent did not connect after " + ArmadaPluginConfig.DEFAULT_SLAVE_CONNECT_TIMEOUT
            + " seconds");
  }

  /**
   * Checks if the agent has connected successfully.
   *
   * @return true if agent is online, false otherwise
   */
  private boolean checkAgentConnected(ArmadaSlave node, int waitedSeconds, TaskListener listener) {
    SlaveComputer slaveComputer = node.getComputer();

    if (slaveComputer == null) {
      throw new IllegalStateException("Node was deleted, computer is null");
    }

    if (slaveComputer.isOnline()) {
      listener.getLogger()
          .println("Agent connected successfully after " + waitedSeconds + " seconds");
      return true;
    }

    return false;
  }

  /**
   * Validates that the job hasn't failed during the wait period.
   */
  private void validateJobStillRunning(ArmadaComputer computer, ArmadaClient armadaClient)
      throws IOException {
    String jobId = computer.getArmadaJobId();
    if (jobId != null && !jobId.isEmpty()) {
      JobState jobState = getJobState(armadaClient, jobId);
      JobStateValidator.validate(jobState, jobId);
    }
  }

  /**
   * Reports connection progress periodically.
   *
   * @return the updated timestamp if report was made, original timestamp otherwise
   */
  private long reportProgressIfNeeded(ArmadaSlave node, int waitedSeconds,
      long lastReportTimestamp, TaskListener listener) {
    if (lastReportTimestamp + ArmadaPluginConfig.REPORT_INTERVAL_MS < System.currentTimeMillis()) {
      LOGGER.log(Level.INFO, "Waiting for agent to connect ({1}/{2}): {0}",
          new Object[]{node.getNodeName(), waitedSeconds,
              ArmadaPluginConfig.DEFAULT_SLAVE_CONNECT_TIMEOUT});
      listener.getLogger().printf(
          "Waiting for agent to connect (%2$s/%3$s): %1$s%n",
          node.getNodeName(), waitedSeconds, ArmadaPluginConfig.DEFAULT_SLAVE_CONNECT_TIMEOUT);
      return System.currentTimeMillis();
    }
    return lastReportTimestamp;
  }
}
