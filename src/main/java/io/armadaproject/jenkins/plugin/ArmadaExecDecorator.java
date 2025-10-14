package io.armadaproject.jenkins.plugin;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Computer;
import hudson.model.Node;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serial;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import javax.annotation.Nonnull;
import org.apache.commons.io.output.TeeOutputStream;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;

/**
 * Decorator that intercepts launcher calls and executes commands in Armada containers
 * using Kubernetes exec API. Uses ArmadaNodeContext to subscribe to Armada events
 * and discover the actual pod details when the job is running.
 *
 * This implementation follows the pattern from the Kubernetes plugin's ContainerExecDecorator:
 * - Starts a shell (sh) via exec
 * - Writes commands to the shell's stdin
 * - Properly handles durable task plugin's complex shell scripts
 */
public class ArmadaExecDecorator extends LauncherDecorator implements Serializable, Closeable {

  @Serial
  private static final long serialVersionUID = 1L;
  private static final Logger LOGGER = Logger.getLogger(ArmadaExecDecorator.class.getName());

  private static final String WEBSOCKET_CONNECTION_MAX_RETRY_SYSTEM_PROPERTY =
      ArmadaExecDecorator.class.getName() + ".websocketConnectionMaxRetries";
  private static final String WEBSOCKET_CONNECTION_MAX_RETRY_BACKOFF_SYSTEM_PROPERTY =
      ArmadaExecDecorator.class.getName() + ".websocketConnectionMaxRetryBackoff";
  private static final String WEBSOCKET_CONNECTION_TIMEOUT_SYSTEM_PROPERTY =
      ArmadaExecDecorator.class.getName() + ".websocketConnectionTimeout";

  /** time to wait in seconds for websocket to connect */
  private static final int WEBSOCKET_CONNECTION_TIMEOUT =
      Integer.getInteger(WEBSOCKET_CONNECTION_TIMEOUT_SYSTEM_PROPERTY, 30);
  /** maximum number of times to retry failed websocket connection */
  private static final int WEBSOCKET_CONNECTION_MAX_RETRY =
      Integer.getInteger(WEBSOCKET_CONNECTION_MAX_RETRY_SYSTEM_PROPERTY, 5);
  /** maximum backoff time for retrying failed websocket connection  */
  private static final int WEBSOCKET_CONNECTION_MAX_RETRY_BACKOFF =
      Integer.getInteger(WEBSOCKET_CONNECTION_MAX_RETRY_BACKOFF_SYSTEM_PROPERTY, 30);

  /** stdin buffer size for commands sent to Kubernetes exec api */
  private static final int STDIN_BUFFER_SIZE =
      Integer.getInteger(ArmadaExecDecorator.class.getName() + ".stdinBufferSize", 16 * 1024);
  /** time in milliseconds to wait for checking whether the process immediately returned */
  public static final int COMMAND_FINISHED_TIMEOUT_MS = 200;

  private static final String COOKIE_VAR = "JENKINS_SERVER_COOKIE";
  private static final String EXIT = "exit";
  private static final String NEWLINE = "\n";
  private static final char CTRL_C = '\u0003';

  @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "not needed on deserialization")
  private transient List<Closeable> closables;

  private String containerName;
  private EnvironmentExpander environmentExpander;
  private EnvVars globalVars;
  private EnvVars rcEnvVars;
  private String shell;
  private ArmadaNodeContext nodeContext;

  public ArmadaExecDecorator() {}

  public ArmadaExecDecorator(String containerName) {
    this.containerName = containerName;
  }

  public String getContainerName() {
    return containerName;
  }

  public void setContainerName(String containerName) {
    this.containerName = containerName;
  }

  public EnvironmentExpander getEnvironmentExpander() {
    return environmentExpander;
  }

  public void setEnvironmentExpander(EnvironmentExpander environmentExpander) {
    this.environmentExpander = environmentExpander;
  }

  public EnvVars getGlobalVars() {
    return globalVars;
  }

  public void setGlobalVars(EnvVars globalVars) {
    this.globalVars = globalVars;
  }

  public void setRunContextEnvVars(EnvVars rcVars) {
    this.rcEnvVars = rcVars;
  }

  public EnvVars getRunContextEnvVars() {
    return this.rcEnvVars;
  }

  public void setShell(String shell) {
    this.shell = shell;
  }

  public ArmadaNodeContext getNodeContext() {
    return nodeContext;
  }

  public void setNodeContext(ArmadaNodeContext nodeContext) {
    this.nodeContext = nodeContext;
  }

  @Override
  @Nonnull
  public Launcher decorate(@Nonnull Launcher launcher, @Nonnull Node node) {
    // Only decorate if this is an Armada slave
    if (node != null && !(node instanceof ArmadaSlave)) {
      return launcher;
    }

    return new Launcher.DecoratedLauncher(launcher) {
      @Override
      public Proc launch(ProcStarter starter) throws IOException {
        LOGGER.log(Level.FINEST, "Launch proc with command: {0}", starter.cmds());

        FilePath pwd = starter.pwd();
        String[] envVars = starter.envs();

        if (node != null) {
          final Computer computer = node.toComputer();
          if (computer != null) {
            try {
              EnvVars environment = computer.getEnvironment();
              if (environment != null) {
                List<String> resultEnvVar = new ArrayList<>();
                for (String keyValue : envVars) {
                  String[] split = keyValue.split("=", 2);
                  if (!split[1].equals(environment.get(split[0]))) {
                    resultEnvVar.add(keyValue);
                  }
                }
                envVars = resultEnvVar.toArray(new String[0]);
              }
            } catch (InterruptedException e) {
              throw new IOException("Unable to retrieve environment variables", e);
            }
          }
        }

        return doLaunch(
            starter.quiet(),
            fixDoubleDollar(envVars),
            starter.stdout(),
            pwd,
            starter.masks(),
            getCommands(starter, launcher.isUnix()));
      }

      @Override
      public void kill(Map<String, String> modelEnvVars) throws IOException, InterruptedException {
        getListener().getLogger().println("Killing processes");

        String cookie = modelEnvVars.get(COOKIE_VAR);

        int exitCode = doLaunch(
                true,
                null,
                null,
                null,
                null,
                "sh",
                "-c",
                "kill \\`grep -l '" + COOKIE_VAR + "=" + cookie
                    + "' /proc/*/environ | cut -d / -f 3 \\`")
            .join();

        getListener().getLogger().println("kill finished with exit code " + exitCode);
      }
    };
  }

  private Proc doLaunch(
      boolean quiet,
      String[] cmdEnvs,
      OutputStream outputForCaller,
      FilePath pwd,
      boolean[] masks,
      String... commands) throws IOException {
    long startMethod = System.nanoTime();

    PrintStream printStream;
    OutputStream stream;

    // Get Kubernetes connection info via nodeContext
    KubernetesClient client;
    String podName;
    String namespace;

    try {
      client = nodeContext.connectToCloud();
      podName = nodeContext.getPodName();
      namespace = nodeContext.getNamespace();
    } catch (Exception e) {
      throw new IOException("Failed to connect to Kubernetes cluster", e);
    }

    // Verify pod exists
    Pod pod = client.pods().inNamespace(namespace).withName(podName).get();
    if (pod == null) {
      throw new IOException("Pod not found: " + podName + " in namespace: " + namespace);
    }

    // Only output to stdout at the beginning for diagnostics.
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    // Wrap stdout so that we can toggle it off.
    ToggleOutputStream toggleStdout = new ToggleOutputStream(stdout);

    // Do not send this command to the output when in quiet mode
    if (quiet) {
      stream = toggleStdout;
      printStream = new PrintStream(stream, true, StandardCharsets.UTF_8.toString());
    } else {
      // Get a logger from somewhere - we'll use the nodeContext's listener if available
      printStream = new PrintStream(toggleStdout, true, StandardCharsets.UTF_8.toString());
      stream = toggleStdout;
    }

    ByteArrayOutputStream dryRunCaller = null;
    ToggleOutputStream toggleDryRunCaller = null;
    ToggleOutputStream toggleOutputForCaller = null;

    // Send to proc caller as well if they sent one
    if (outputForCaller != null && !outputForCaller.equals(printStream)) {
      boolean isUnix = true; // We'll determine this from shell
      if (isUnix) {
        stream = new TeeOutputStream(outputForCaller, stream);
      } else {
        // Prepare to capture output for later.
        dryRunCaller = new ByteArrayOutputStream();
        toggleDryRunCaller = new ToggleOutputStream(dryRunCaller);
        toggleOutputForCaller = new ToggleOutputStream(outputForCaller, true);
        stream = new TeeOutputStream(toggleOutputForCaller, stream);
        stream = new TeeOutputStream(toggleDryRunCaller, stream);
      }
    }

    String[] sh = shell != null
        ? new String[]{shell}
        : new String[]{"sh"};  // Default to Unix shell for Armada
    String msg = "Executing " + String.join(" ", sh) + " script inside container "
        + containerName + " of pod " + podName;
    LOGGER.log(Level.FINEST, msg);
    printStream.println(msg);

    if (closables == null) {
      closables = new ArrayList<>();
    }

    int attempts = 0;
    ExecWatchWrapper watchWrapper = null;
    while (watchWrapper == null && attempts < WEBSOCKET_CONNECTION_MAX_RETRY) {

      if (attempts > 0) {
        // Exponential backoff
        long backoffInSeconds = Math.min(
            Integer.toUnsignedLong((int) Math.pow(2, attempts)),
            WEBSOCKET_CONNECTION_MAX_RETRY_BACKOFF);
        printStream.println("Retrying in " + backoffInSeconds + "s ...");
        try {
          Thread.sleep(backoffInSeconds * 1000);
        } catch (InterruptedException ex) {
          printStream.println("Retry wait interrupted");
        } finally {
          printStream.println("Retrying...");
        }
      }

      try {
        final AtomicBoolean alive = new AtomicBoolean(false);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicLong startAlive = new AtomicLong();

        ExecWatch watch = client.pods()
            .inNamespace(namespace)
            .resource(pod)
            .inContainer(containerName)
            .redirectingInput(STDIN_BUFFER_SIZE)
            .writingOutput(stream)
            .writingError(stream)
            .usingListener(new ExecListener() {
              @Override
              public void onOpen() {
                alive.set(true);
                started.countDown();
                startAlive.set(System.nanoTime());
                LOGGER.log(Level.FINEST, "onOpen : {0}", finished);
              }

              @Override
              public void onFailure(Throwable t, Response response) {
                alive.set(false);
                t.printStackTrace(printStream);
                started.countDown();
                LOGGER.log(Level.FINEST, "onFailure : {0}", finished);
                if (finished.getCount() == 0) {
                  LOGGER.log(
                      Level.WARNING,
                      "onFailure called but latch already finished. This may be a bug");
                }
                finished.countDown();
              }

              @Override
              public void onClose(int i, String s) {
                alive.set(false);
                started.countDown();
                LOGGER.log(Level.FINEST, "onClose : {0} [{1} ms]", new Object[]{
                    finished,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startAlive.get())
                });
                if (finished.getCount() == 0) {
                  LOGGER.log(
                      Level.WARNING,
                      "onClose called but latch already finished. This indicates a bug");
                }
                finished.countDown();
              }
            })
            .exec(sh);

        try {
          if (started.await(WEBSOCKET_CONNECTION_TIMEOUT, TimeUnit.SECONDS)) {
            watchWrapper = new ExecWatchWrapper(watch, alive, finished);
          } else {
            closeWatch(watch);
            printStream.println("Timed out waiting for websocket connection. "
                + "You should increase the value of system property "
                + WEBSOCKET_CONNECTION_TIMEOUT_SYSTEM_PROPERTY + " currently set at "
                + WEBSOCKET_CONNECTION_TIMEOUT + " seconds");
          }
        } catch (InterruptedException e) {
          closeWatch(watch);
          throw e;
        }

      } catch (KubernetesClientException e) {
        printStream.print("Failed to start websocket connection: ");
        String message = e.getMessage();
        if (message != null && message.startsWith("container " + containerName + " not found in pod")) {
          throw e;
        }
        e.printStackTrace(printStream);
      } catch (InterruptedException e) {
        printStream.println(
            "Failed to start websocket connection: "
                + "Interrupted while waiting for websocket connection");
        e.printStackTrace(printStream);
      } finally {
        attempts++;
      }
    }

    if (watchWrapper == null || watchWrapper.getExecWatch() == null) {
      throw new AbortException("Failed to start websocket connection after " + attempts
          + " attempts. Check logs above for more details.");
    }

    ExecWatch watch = watchWrapper.getExecWatch();
    final AtomicBoolean alive = watchWrapper.getAlive();
    final CountDownLatch finished = watchWrapper.getFinished();

    try {
      if (finished.await(COMMAND_FINISHED_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        printStream.println("Process exited immediately after creation. See output above");
        throw new AbortException(
            "Process exited immediately after creation. Check logs above for more details.");
      }
      toggleStdout.disable();
      OutputStream stdin = watch.getInput();
      PrintStream in = new PrintStream(stdin, true, StandardCharsets.UTF_8.name());

      boolean isWindows = false; // Armada runs on Unix
      if (isWindows) {
        in.print("@echo off");
        in.print(newLine(true));
      }

      if (pwd != null) {
        in.print(String.format("cd \"%s\"", pwd.getRemote()));
        in.print(newLine(isWindows));
      }

      EnvVars envVars = new EnvVars();

      if (globalVars != null) {
        envVars.overrideAll(globalVars);
      }

      if (rcEnvVars != null) {
        envVars.overrideAll(rcEnvVars);
      }

      if (environmentExpander != null) {
        environmentExpander.expand(envVars);
      }

      if (cmdEnvs != null) {
        for (String cmdEnv : cmdEnvs) {
          envVars.addLine(cmdEnv);
        }
      }

      LOGGER.log(Level.FINEST, "Launching with env vars: {0}", envVars.toString());

      setupEnvironmentVariable(envVars, in, isWindows);

      if (toggleOutputForCaller != null) {
        toggleOutputForCaller.enable();
      }

      if (toggleDryRunCaller != null) {
        toggleDryRunCaller.disable();
      }

      doExec(in, isWindows, printStream, masks, commands);

      LOGGER.log(
          Level.INFO,
          "Created process inside pod: [" + podName + "], container: [" + containerName + "]"
              + "[" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMethod) + " ms]");

      ArmadaProc proc = new ArmadaProc(watch, alive, finished, stdin, printStream);
      closables.add(proc);
      return proc;
    } catch (InterruptedException ie) {
      closeWatch(watch);
      throw new InterruptedIOException(ie.getMessage());
    } catch (RuntimeException e) {
      closeWatch(watch);
      throw e;
    }
  }

  private void setupEnvironmentVariable(EnvVars vars, PrintStream out, boolean windows) throws IOException {
    for (Map.Entry<String, String> entry : vars.entrySet()) {
      if (entry.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
        out.print(String.format(
            windows ? "set %s=%s" : "export %s='%s'",
            entry.getKey(),
            windows ? entry.getValue() : entry.getValue().replace("'", "'\\''")));
        out.print(newLine(windows));
      }
    }
  }

  private static String newLine(boolean windows) {
    return windows ? "\r\n" : "\n";
  }

  private static void doExec(
      PrintStream in, boolean windows, PrintStream out, boolean[] masks, String... statements) {
    long start = System.nanoTime();
    ByteArrayOutputStream loggingOutput = new ByteArrayOutputStream();
    TeeOutputStream teeOutput = new TeeOutputStream(out, loggingOutput);
    MaskOutputStream maskedOutput = new MaskOutputStream(teeOutput, masks);
    PrintStream tee;
    try {
      String encoding = StandardCharsets.UTF_8.name();
      tee = new PrintStream(new TeeOutputStream(in, maskedOutput), false, encoding);
      PrintStream unmasked = new PrintStream(teeOutput, false, encoding);
      unmasked.print("Executing command: ");
      for (String statement : statements) {
        if (windows) {
          tee.append(statement).append(" ");
        } else {
          tee.append("\"").append(statement).append("\" ");
        }
      }
      tee.print(newLine(windows));
      LOGGER.log(
          Level.FINEST,
          loggingOutput.toString(encoding) + "[" + TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start)
              + " μs." + "]");
      tee.print(EXIT);
      tee.print(newLine(windows));
      tee.flush();
    } catch (UnsupportedEncodingException e) {
      LOGGER.log(Level.SEVERE, "Failed to execute command because of unsupported encoding", e);
    }
  }

  static String[] getCommands(Launcher.ProcStarter starter, boolean unix) {
    List<String> allCommands = new ArrayList<>();

    for (String cmd : starter.cmds()) {
      String fixedCommand = cmd.replaceAll("\\$\\$", Matcher.quoteReplacement("\\$"));
      if (unix) {
        fixedCommand = fixedCommand.replaceAll("\\\"", Matcher.quoteReplacement("\\\""));
      }
      allCommands.add(fixedCommand);
    }
    return allCommands.toArray(new String[0]);
  }

  private static void closeWatch(ExecWatch watch) {
    try {
      watch.close();
    } catch (Exception e) {
      LOGGER.log(Level.INFO, "failed to close watch", e);
    }
  }

  private static String[] fixDoubleDollar(String[] envVars) {
    if (envVars == null) return null;
    String[] result = new String[envVars.length];
    for (int i = 0; i < envVars.length; i++) {
      result[i] = envVars[i].replaceAll("\\$\\$", Matcher.quoteReplacement("$"));
    }
    return result;
  }

  @Override
  public void close() throws IOException {
    if (closables == null) return;

    for (Closeable closable : closables) {
      try {
        closable.close();
      } catch (Exception e) {
        LOGGER.log(Level.FINE, "failed to close", e);
      }
    }
  }

  /**
   * Process implementation that executes commands via Kubernetes exec API.
   */
  private static class ArmadaProc extends Proc implements Closeable, Runnable {

    private final AtomicBoolean alive;
    private final CountDownLatch finished;
    private final ExecWatch watch;
    private final OutputStream stdin;
    private final PrintStream printStream;

    public ArmadaProc(
        ExecWatch watch,
        AtomicBoolean alive,
        CountDownLatch finished,
        OutputStream stdin,
        PrintStream printStream) {
      this.watch = watch;
      this.stdin = stdin == null ? watch.getInput() : stdin;
      this.alive = alive;
      this.finished = finished;
      this.printStream = printStream;
      jenkins.util.Timer.get().schedule(this, 1, TimeUnit.MINUTES);
    }

    @Override
    public boolean isAlive() {
      return alive.get();
    }

    @Override
    public void kill() throws IOException, InterruptedException {
      try {
        stdin.write(CTRL_C);
        stdin.write(EXIT.getBytes(StandardCharsets.UTF_8));
        stdin.write(NEWLINE.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
      } catch (IOException e) {
        LOGGER.log(Level.FINE, "Proc kill failed, ignoring", e);
      } finally {
        close();
      }
    }

    @Override
    public int join() throws IOException, InterruptedException {
      try {
        LOGGER.log(Level.FINEST, "Waiting for websocket to close on command finish ({0})", finished);
        finished.await();
        LOGGER.log(Level.FINEST, "Command is finished ({0})", finished);

        CompletableFuture<Integer> exitCodeFuture = watch.exitCode();

        if (exitCodeFuture == null) {
          LOGGER.log(Level.FINEST, "exitCodeFuture is null.");
          if (printStream != null) {
            printStream.print("exitCodeFuture is null.");
          }
          return -1;
        }

        Integer exitCode = exitCodeFuture.get();

        if (exitCode == null) {
          LOGGER.log(
              Level.FINEST,
              "The container exec watch was closed before it could obtain an exit code from the process.");
          if (printStream != null) {
            printStream.print(
                "The container exec watch was closed before it could obtain an exit code from the process.");
          }
          return -1;
        }
        return exitCode;
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause != null) {
          LOGGER.log(Level.FINEST, "ExecutionException occurred while waiting for exit code", cause);
          if (printStream != null) {
            printStream.printf("ExecutionException occurred while waiting for exit code: %s%n", cause);
          }
        } else {
          LOGGER.log(Level.FINEST, "ExecutionException occurred while waiting for exit code", e);
          if (printStream != null) {
            printStream.printf("ExecutionException occurred while waiting for exit code: %s%n", e);
          }
        }
        return -1;
      } finally {
        close();
      }
    }

    @Override
    public InputStream getStdout() {
      return watch.getOutput();
    }

    @Override
    public InputStream getStderr() {
      return watch.getError();
    }

    @Override
    public OutputStream getStdin() {
      return stdin;
    }

    @Override
    public void close() throws IOException {
      try {
        watch.close();
      } catch (Exception e) {
        LOGGER.log(Level.INFO, "failed to close watch", e);
      }
    }

    @Override
    public void run() {
      if (!isAlive()) {
        LOGGER.fine("process is no longer alive");
        return;
      }
      try {
        stdin.write(NEWLINE.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        LOGGER.fine("sent a newline to keep socket alive");
        jenkins.util.Timer.get().schedule(this, 1, TimeUnit.MINUTES);
      } catch (IOException x) {
        LOGGER.log(Level.FINE, "socket keepalive failed", x);
      }
    }
  }

  /**
   * Wrapper of ExecWatch that also holds watch attributes for liveness and closure.
   */
  private static class ExecWatchWrapper {

    private final ExecWatch execWatch;
    private final AtomicBoolean alive;
    private final CountDownLatch finished;

    public ExecWatchWrapper(ExecWatch execWatch, AtomicBoolean alive, CountDownLatch finished) {
      this.execWatch = execWatch;
      this.alive = alive;
      this.finished = finished;
    }

    public ExecWatch getExecWatch() {
      return execWatch;
    }

    public AtomicBoolean getAlive() {
      return alive;
    }

    public CountDownLatch getFinished() {
      return finished;
    }
  }

  /**
   * OutputStream that can be toggled on/off.
   */
  private static class ToggleOutputStream extends FilterOutputStream {
    private boolean disabled;

    public ToggleOutputStream(OutputStream out) {
      this(out, false);
    }

    public ToggleOutputStream(OutputStream out, boolean disabled) {
      super(out);
      this.disabled = disabled;
    }

    public void disable() {
      disabled = true;
    }

    public void enable() {
      disabled = false;
    }

    @Override
    public void write(int b) throws IOException {
      if (!disabled) {
        out.write(b);
      }
    }

    @Override
    public void write(byte[] b) throws IOException {
      if (!disabled) {
        out.write(b);
      }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (!disabled) {
        out.write(b, off, len);
      }
    }
  }

  /**
   * Process given stream and mask as specified by the bitfield.
   * Uses space as a separator to determine which fragments to hide.
   */
  private static class MaskOutputStream extends FilterOutputStream {
    private static final String MASK_STRING = "********";
    private final boolean[] masks;
    private static final char SEPARATOR = ' ';
    private int index;
    private boolean wrote;

    public MaskOutputStream(OutputStream out, boolean[] masks) {
      super(out);
      this.masks = masks;
    }

    @Override
    public void write(int b) throws IOException {
      if (masks == null || index >= masks.length) {
        out.write(b);
      } else if (isSeparator(b)) {
        out.write(b);
        index++;
        wrote = false;
      } else if (masks[index]) {
        if (!wrote) {
          wrote = true;
          for (char c : MASK_STRING.toCharArray()) {
            out.write(c);
          }
        }
      } else {
        out.write(b);
      }
    }

    private boolean isSeparator(int b) {
      return b == SEPARATOR;
    }
  }

  /**
   * Creates a decorator and merges it with existing decorators.
   *
   * @param invoker the body invoker to decorate
   * @param containerName the name of the container to execute commands in
   * @param nodeContext the node context for event subscription and pod discovery
   * @param existingDecorator any existing launcher decorator
   * @return the decorated body invoker
   */
  public static BodyInvoker decorate(BodyInvoker invoker, String containerName,
      ArmadaNodeContext nodeContext, LauncherDecorator existingDecorator) {
    ArmadaExecDecorator decorator = new ArmadaExecDecorator(containerName);
    decorator.setNodeContext(nodeContext);
    return invoker.withContext(
        BodyInvoker.mergeLauncherDecorators(existingDecorator, decorator));
  }
}
