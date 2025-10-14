package io.armadaproject.jenkins.plugin;

import hudson.slaves.SlaveComputer;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;

/**
 * Enriches a Pod specification with Jenkins agent configuration. Mimics the behavior of
 * PodTemplateBuilder from the Kubernetes plugin.
 */
public class PodEnricher {

  private static final Logger LOGGER = Logger.getLogger(PodEnricher.class.getName());

  private static final String JNLP_NAME = "jnlp";
  private static final String DEFAULT_AGENT_IMAGE = "jenkins/inbound-agent:latest";
  private static final String DEFAULT_WORKING_DIR = "/home/jenkins/agent";
  private static final String WORKSPACE_VOLUME_NAME = "workspace-volume";
  private static final String NO_RECONNECT_AFTER_TIMEOUT = "1d";

  // Default resource limits
  private static final String DEFAULT_JNLP_CONTAINER_CPU_REQUEST = "256m";
  private static final String DEFAULT_JNLP_CONTAINER_CPU_LIMIT = "256m";
  private static final String DEFAULT_JNLP_CONTAINER_MEMORY_REQUEST = "256Mi";
  private static final String DEFAULT_JNLP_CONTAINER_MEMORY_LIMIT = "256Mi";

  private final ArmadaCloud cloud;
  private final ArmadaSlave slave;
  private final Pod pod;

  public PodEnricher(ArmadaCloud cloud, ArmadaSlave slave, Pod pod) {
    this.cloud = cloud;
    this.slave = slave;
    this.pod = pod;
  }

  /**
   * Enriches the pod by ensuring JNLP agent container exists with proper configuration. Behavior
   * matches Kubernetes plugin's PodTemplateBuilder.
   *
   * @return the enriched pod
   */
  public Pod enrich() {
    ensureWorkspaceVolume();
    enrichAgentContainer();
    return pod;
  }

  /**
   * Ensures workspace volume exists in the pod.
   */
  private void ensureWorkspaceVolume() {
    if (pod.getSpec().getVolumes() == null) {
      pod.getSpec().setVolumes(new ArrayList<>());
    }

    boolean hasWorkspaceVolume = pod.getSpec().getVolumes().stream()
        .anyMatch(v -> WORKSPACE_VOLUME_NAME.equals(v.getName()));

    if (!hasWorkspaceVolume) {
      Volume workspaceVolume = new VolumeBuilder()
          .withName(WORKSPACE_VOLUME_NAME)
          .withNewEmptyDir()
          .endEmptyDir()
          .build();
      pod.getSpec().getVolumes().add(workspaceVolume);
      LOGGER.fine("Added workspace volume to pod");
    }
  }

  /**
   * Enriches the agent container following K8s plugin logic: 1. If jnlp container doesn't exist,
   * create minimal one 2. Always enrich with Jenkins env vars (user env vars take precedence) 3.
   * Set default image if not specified 4. Set default resources if not specified
   */
  private void enrichAgentContainer() {
    if (pod.getSpec().getContainers() == null) {
      pod.getSpec().setContainers(new ArrayList<>());
    }

    // Find or create jnlp container
    Optional<Container> agentOpt = pod.getSpec().getContainers().stream()
        .filter(c -> JNLP_NAME.equals(c.getName()))
        .findFirst();

    Container agentContainer;
    if (agentOpt.isEmpty()) {
      // Create minimal jnlp container with workspace volume mount
      LOGGER.fine("No jnlp container found, creating one");
      agentContainer = new ContainerBuilder()
          .withName(JNLP_NAME)
          .withVolumeMounts(new VolumeMountBuilder()
              .withName(WORKSPACE_VOLUME_NAME)
              .withMountPath(DEFAULT_WORKING_DIR)
              .build())
          .build();
      pod.getSpec().getContainers().add(agentContainer);
    } else {
      agentContainer = agentOpt.get();
      LOGGER.fine("Found existing jnlp container, enriching it");
    }

    // Get working directory from agent container (or use default)
    String workingDir = agentContainer.getWorkingDir();
    if (workingDir == null) {
      workingDir = DEFAULT_WORKING_DIR;
    }

    // Propagate working dir to other containers if they don't have one
    final String finalWorkingDir = workingDir;
    pod.getSpec().getContainers().stream()
        .filter(c -> c.getWorkingDir() == null)
        .forEach(c -> c.setWorkingDir(finalWorkingDir));

    // Ensure ALL containers have the workspace volume mounted
    // (not just the JNLP container) so they can access the shared workspace
    pod.getSpec().getContainers().forEach(c -> ensureWorkspaceVolumeMount(c, finalWorkingDir));

    // Set default image if blank
    if (StringUtils.isBlank(agentContainer.getImage())) {
      agentContainer.setImage(DEFAULT_AGENT_IMAGE);
      LOGGER.fine("Set default agent image: " + DEFAULT_AGENT_IMAGE);
    }

    // Build environment variables with proper override order
    Map<String, EnvVar> envVars = new HashMap<>();

    // 1. Start with Jenkins agent env vars
    envVars.putAll(buildAgentEnvVars(workingDir));

    // 2. Let existing container env vars override
    if (agentContainer.getEnv() != null) {
      agentContainer.getEnv().forEach(env -> envVars.put(env.getName(), env));
    }

    // Always set the merged env vars
    agentContainer.setEnv(new ArrayList<>(envVars.values()));

    // Set default resources if not specified
    if (agentContainer.getResources() == null) {
      Map<String, Quantity> requests = new HashMap<>();
      requests.put("cpu", new Quantity(DEFAULT_JNLP_CONTAINER_CPU_REQUEST));
      requests.put("memory", new Quantity(DEFAULT_JNLP_CONTAINER_MEMORY_REQUEST));

      Map<String, Quantity> limits = new HashMap<>();
      limits.put("cpu", new Quantity(DEFAULT_JNLP_CONTAINER_CPU_LIMIT));
      limits.put("memory", new Quantity(DEFAULT_JNLP_CONTAINER_MEMORY_LIMIT));

      agentContainer.setResources(new ResourceRequirementsBuilder()
          .withRequests(requests)
          .withLimits(limits)
          .build());
    }
  }

  /**
   * Ensures the given container has the workspace volume mounted. This is required for all
   * containers to access the shared workspace directory.
   */
  private void ensureWorkspaceVolumeMount(Container container, String workingDir) {
    if (container.getVolumeMounts() == null) {
      container.setVolumeMounts(new ArrayList<>());
    }

    boolean hasWorkspaceMount = container.getVolumeMounts().stream()
        .anyMatch(vm -> WORKSPACE_VOLUME_NAME.equals(vm.getName()));

    if (!hasWorkspaceMount) {
      VolumeMount workspaceMount = new VolumeMountBuilder()
          .withName(WORKSPACE_VOLUME_NAME)
          .withMountPath(workingDir)
          .withReadOnly(false)
          .build();
      container.getVolumeMounts().add(workspaceMount);
      LOGGER.fine("Added workspace volume mount to container: " + container.getName());
    }
  }

  /**
   * Builds the environment variables required for JNLP agent to connect to Jenkins. Matches the
   * agentEnvVars() method from PodTemplateBuilder.
   */
  private Map<String, EnvVar> buildAgentEnvVars(String workingDir) {
    Map<String, EnvVar> envVars = new HashMap<>();

    SlaveComputer computer = slave.getComputer();
    if (computer != null) {
      // Critical: JNLP MAC secret for authentication
      envVars.put("JENKINS_SECRET", new EnvVar("JENKINS_SECRET", computer.getJnlpMac(), null));

      // Agent name (backwards compat)
      envVars.put("JENKINS_NAME", new EnvVar("JENKINS_NAME", computer.getName(), null));
      envVars.put("JENKINS_AGENT_NAME", new EnvVar("JENKINS_AGENT_NAME", computer.getName(), null));
    } else {
      LOGGER.log(Level.WARNING, "Computer is null for agent: {0}", slave.getNodeName());
    }

    // Working directory
    envVars.put("JENKINS_AGENT_WORKDIR", new EnvVar("JENKINS_AGENT_WORKDIR", workingDir, null));

    // Jenkins URL and connection settings
    String jenkinsUrl = cloud.getJenkinsUrl();
    if (StringUtils.isNotBlank(jenkinsUrl)) {
      if (!jenkinsUrl.endsWith("/")) {
        jenkinsUrl += "/";
      }
      envVars.put("JENKINS_URL", new EnvVar("JENKINS_URL", jenkinsUrl, null));
    } else {
      LOGGER.log(Level.WARNING, "Jenkins URL is not configured in Armada cloud: {0}", cloud.name);
    }

    // Optional: Jenkins tunnel
    // Note: ArmadaCloud doesn't have jenkinsTunnel field, but you could add it if needed
    // if (StringUtils.isNotBlank(cloud.getJenkinsTunnel())) {
    //   envVars.put("JENKINS_TUNNEL", new EnvVar("JENKINS_TUNNEL", cloud.getJenkinsTunnel(), null));
    // }

    // Remoting options
    envVars.put("REMOTING_OPTS",
        new EnvVar("REMOTING_OPTS", "-noReconnectAfter " + NO_RECONNECT_AFTER_TIMEOUT, null));

    return envVars;
  }
}
