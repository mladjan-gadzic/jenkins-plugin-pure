package io.armadaproject.jenkins.plugin;

import api.SubmitOuterClass.JobState;
import java.io.IOException;

/**
 * Validates Armada job states and provides state machine logic.
 */
public class JobStateValidator {

  private JobStateValidator() {
    // Utility class - prevent instantiation
  }

  /**
   * Validates that a job state is acceptable for continuing execution. Throws IOException if
   * the job is in a failed or unexpected state.
   *
   * @param state the job state to validate
   * @param jobId the job ID for error messages
   * @throws IOException if the state indicates failure or unexpected termination
   */
  public static void validate(JobState state, String jobId) throws IOException {
    if (state == JobState.UNKNOWN) {
      throw new IOException(String.format("Job %s in state UNKNOWN: Job state is UNKNOWN", jobId));
    }
    if (state == JobState.FAILED || state == JobState.REJECTED) {
      throw new IOException(String.format("Job %s in state %s: Job entered failed state", jobId, state.name()));
    }
    if (state == JobState.CANCELLED) {
      throw new IOException(String.format("Job %s in state CANCELLED: Job was cancelled", jobId));
    }
    if (state == JobState.SUCCEEDED) {
      throw new IOException(String.format("Job %s in state SUCCEEDED: Job terminated unexpectedly", jobId));
    }
  }

  /**
   * Checks if a job state is terminal (no further state changes expected).
   *
   * @param state the job state to check
   * @return true if the state is terminal
   */
  public static boolean isTerminal(JobState state) {
    return state == JobState.SUCCEEDED
        || state == JobState.FAILED
        || state == JobState.REJECTED
        || state == JobState.CANCELLED;
  }

  /**
   * Checks if a job state indicates the job is running.
   *
   * @param state the job state to check
   * @return true if the job is running
   */
  public static boolean isRunning(JobState state) {
    return state == JobState.RUNNING;
  }

  /**
   * Checks if a job state indicates failure.
   *
   * @param state the job state to check
   * @return true if the job failed
   */
  public static boolean isFailed(JobState state) {
    return state == JobState.FAILED || state == JobState.REJECTED;
  }
}
