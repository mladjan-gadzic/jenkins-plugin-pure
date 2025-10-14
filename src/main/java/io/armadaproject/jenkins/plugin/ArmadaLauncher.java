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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.awaitility.Awaitility;

public class ArmadaLauncher extends JNLPLauncher {

  private static final Logger LOGGER = Logger.getLogger(ArmadaLauncher.class.getName());
  private static final int DEFAULT_SLAVE_CONNECT_TIMEOUT = 600; // 10 minutes
  private static final int POLL_INTERVAL_SECONDS = 1;
  private static final long REPORT_INTERVAL = TimeUnit.SECONDS.toMillis(30L);

  private volatile boolean launched = false;

  public ArmadaLauncher() {
    super();
  }

  @Override
  public boolean isLaunchSupported() {
    return !launched;
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

    if (launched) {
      LOGGER.log(Level.FINE, "Agent has already been launched, activating: {0}",
          node.getNodeName());
      computer.setAcceptingTasks(true);
      return;
    }

    try {
      // Use the listener from the template if available, otherwise use the provided listener
      TaskListener effectiveListener = listener;
      ArmadaJobTemplate template = node.getTemplate();
      if (template != null && template.getListener() != null) {
        effectiveListener = template.getListener();
        LOGGER.fine("Using listener from ArmadaJobTemplate");
      }

      submitToArmada(node, armadaComputer, effectiveListener);

      waitForAgentConnection(node, armadaComputer, effectiveListener);

      // Mark as accepting tasks and launched
      computer.setAcceptingTasks(true);
      launched = true;

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
    Pod podSpec = node.getTemplate().getPodSpec();
    if (podSpec == null) {
      throw new IOException("No pod specification available");
    }

    try {
      ArmadaCloud cloud = ArmadaCloud.resolveCloud(node.getCloudName());

      // Enrich pod with JNLP agent container (matches K8s plugin behavior)
      listener.getLogger().println("Enriching pod with JNLP agent configuration...");
      PodEnricher enricher = new PodEnricher(cloud, node, podSpec);
      podSpec = enricher.enrich();
      listener.getLogger().println("Pod enriched successfully");

      listener.getLogger().println("Using Armada configuration:");
      listener.getLogger().println("  URL: " + cloud.getArmadaUrl() + ":" + cloud.getArmadaPort());
      listener.getLogger().println("  Namespace: " + cloud.getArmadaNamespace());
      listener.getLogger().println("  Queue: " + cloud.getArmadaQueue());

      try (ArmadaClient armadaClient = new ArmadaClient(
          cloud.getArmadaUrl(),
          Integer.parseInt(cloud.getArmadaPort()))) {

        // Generate job set ID with timestamp
        String newArmadaJobSetId = cloud.getDisplayName()
            + new SimpleDateFormat("-ddMMyyyy").format(new Date());
        cloud.setArmadaJobSetId(newArmadaJobSetId);

        listener.getLogger().println("  Job Set ID: " + newArmadaJobSetId);

        // Check if job already exists (handles interrupted provisioning)
        if (computer.getArmadaJobId() != null && !computer.getArmadaJobId().isEmpty()) {
          JobStatusResponse jobStatusResponse = armadaClient.getJobStatus(
              JobStatusRequest.newBuilder()
                  .addJobIds(computer.getArmadaJobId())
                  .build());
          JobState existingJobState = jobStatusResponse.getJobStatesMap()
              .get(computer.getArmadaJobId());

          LOGGER.fine(
              "Job with id: " + computer.getArmadaJobId() + " in state: " + existingJobState);

          if (existingJobState != JobState.UNKNOWN) {
            listener.getLogger().println("Job already exists: " + computer.getArmadaJobId());
            computer.setLaunching(true);

            // Wait for job to be running
            waitForJobRunning(armadaClient, computer, listener);
            return;
          }
        }

        // Create Armada mapper and generate job submit request
        ArmadaMapper mapper = new ArmadaMapper(
            cloud.getArmadaQueue(),
            cloud.getArmadaNamespace(),
            newArmadaJobSetId,
            podSpec
        );

        JobSubmitRequest request = mapper.createJobSubmitRequest();

        if (podSpec.getSpec() != null && podSpec.getSpec().getContainers() != null) {
          listener.getLogger().println("  Containers: " + podSpec.getSpec().getContainers().size());
          podSpec.getSpec().getContainers().forEach(container -> {
            listener.getLogger()
                .println("    - " + container.getName() + " (image: " + container.getImage() + ")");
          });
        }

        listener.getLogger().println("Submitting job request to Armada...");
        JobSubmitResponse response = armadaClient.submitJob(request);

        if (response != null && response.getJobResponseItemsList() != null &&
            !response.getJobResponseItemsList().isEmpty()) {

          String jobId = response.getJobResponseItems(0).getJobId();
          computer.setArmadaJobId(jobId);
          computer.setArmadaJobSetId(newArmadaJobSetId);
          computer.setLaunching(true);

          listener.getLogger().println("Job submitted successfully with id: " + jobId);

          // Build Lookout URL if configured
          if (cloud.getArmadaLookoutUrl() != null && !cloud.getArmadaLookoutUrl().isEmpty()) {
            String armadaLookoutJobUrl = cloud.getArmadaLookoutUrl() + ":"
                + cloud.getArmadaLookoutPort() + "/?sb=" + jobId;
            listener.getLogger().println("Lookout URL: " + armadaLookoutJobUrl);
          }

          // Wait for job to be running using Armada API
          waitForJobRunning(armadaClient, computer, listener);

        } else {
          throw new IOException("Failed to submit job to Armada: No job ID returned");
        }
      }
    } catch (Exception e) {
      LOGGER.severe("Failed to submit job to Armada: " + e.getMessage());
      listener.error("Failed to submit job to Armada: " + e.getMessage()).close();
      throw new IOException("Failed to submit job to Armada", e);
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
          .atMost(DEFAULT_SLAVE_CONNECT_TIMEOUT, TimeUnit.SECONDS)
          .pollInterval(POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
          .until(() -> {
            JobStatusResponse jobStatus = armadaClient.getJobStatus(
                JobStatusRequest.newBuilder()
                    .addJobIds(jobId)
                    .build());
            JobState currentState = jobStatus.getJobStatesMap().get(jobId);

            LOGGER.fine("Job " + jobId + " state: " + currentState);

            // Check for terminal failure states
            if (currentState == JobState.FAILED || currentState == JobState.REJECTED) {
              throw new IOException("Job entered failed state: " + currentState);
            }

            return currentState == JobState.RUNNING;
          });

      listener.getLogger().println("Job is running: " + jobId);

    } catch (Exception e) {
      LOGGER.severe("Job failed to reach RUNNING state: " + e.getMessage());
      listener.error("Job failed to reach RUNNING state: " + e.getMessage()).close();
      throw new IOException("Job did not start successfully", e);
    }
  }

  /**
   * Waits for the JNLP agent to connect back to Jenkins.
   */
  private void waitForAgentConnection(ArmadaSlave node, ArmadaComputer computer,
      TaskListener listener) throws IOException {
    listener.getLogger().println("Waiting for agent to connect...");

    int waitForSlaveToConnect = DEFAULT_SLAVE_CONNECT_TIMEOUT;
    int waitedForSlave;
    long lastReportTimestamp = System.currentTimeMillis();

    try (ArmadaClient armadaClient = new ArmadaClient(
        ArmadaCloud.resolveCloud(node.getCloudName()).getArmadaUrl(),
        Integer.parseInt(ArmadaCloud.resolveCloud(node.getCloudName()).getArmadaPort()))) {

      for (waitedForSlave = 0; waitedForSlave < waitForSlaveToConnect; waitedForSlave++) {
        SlaveComputer slaveComputer = node.getComputer();

        if (slaveComputer == null) {
          throw new IllegalStateException("Node was deleted, computer is null");
        }

        if (slaveComputer.isOnline()) {
          listener.getLogger()
              .println("Agent connected successfully after " + waitedForSlave + " seconds");
          return;
        }

        // Check that the job hasn't failed during wait
        String jobId = computer.getArmadaJobId();
        if (jobId != null && !jobId.isEmpty()) {
          JobState jobState = armadaClient.getJobStatus(
              JobStatusRequest.newBuilder()
                  .addJobIds(jobId)
                  .build()).getJobStatesMap().get(jobId);

          if (jobState == JobState.FAILED || jobState == JobState.REJECTED
              || jobState == JobState.SUCCEEDED) {
            throw new IllegalStateException(
                "Job terminated while waiting for agent connection. State: " + jobState);
          }
        }

        // Report progress periodically
        if (lastReportTimestamp + REPORT_INTERVAL < System.currentTimeMillis()) {
          LOGGER.log(Level.INFO, "Waiting for agent to connect ({1}/{2}): {0}",
              new Object[]{node.getNodeName(), waitedForSlave, waitForSlaveToConnect});
          listener.getLogger().printf(
              "Waiting for agent to connect (%2$s/%3$s): %1$s%n",
              node.getNodeName(), waitedForSlave, waitForSlaveToConnect);
          lastReportTimestamp = System.currentTimeMillis();
        }

        Thread.sleep(1000);
      }

      // Timeout reached
      throw new IllegalStateException(
          "Agent did not connect after " + waitedForSlave + " seconds");

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for agent connection", e);
    }
  }
}
