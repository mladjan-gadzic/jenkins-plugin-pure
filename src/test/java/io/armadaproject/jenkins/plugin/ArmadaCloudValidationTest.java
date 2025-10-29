package io.armadaproject.jenkins.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.util.FormValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class ArmadaCloudValidationTest {

  private ArmadaCloud.DescriptorImpl descriptor;

  @BeforeEach
  public void setUp(JenkinsRule jenkins) {
    descriptor = jenkins.jenkins.getDescriptorByType(ArmadaCloud.DescriptorImpl.class);
  }

  // Test doCheckName
  @Test
  public void testDoCheckName_ValidName() {
    FormValidation result = descriptor.doCheckName("test-cloud");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckName_NullValue() {
    FormValidation result = descriptor.doCheckName(null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Cloud name is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckName_EmptyValue() {
    FormValidation result = descriptor.doCheckName("");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Cloud name is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckName_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckName("   ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Cloud name is not configured", result.getMessage());
  }

  // Test doCheckArmadaUrl
  @Test
  public void testDoCheckArmadaUrl_ValidUrl() {
    FormValidation result = descriptor.doCheckArmadaUrl("localhost");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaUrl_NullValue() {
    FormValidation result = descriptor.doCheckArmadaUrl(null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server URL is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaUrl_EmptyValue() {
    FormValidation result = descriptor.doCheckArmadaUrl("");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server URL is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaUrl_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckArmadaUrl("   ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server URL is not configured", result.getMessage());
  }

  // Test doCheckArmadaPort
  @Test
  public void testDoCheckArmadaPort_ValidPort() {
    FormValidation result = descriptor.doCheckArmadaPort("8080");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaPort_MinPort() {
    FormValidation result = descriptor.doCheckArmadaPort("1");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaPort_MaxPort() {
    FormValidation result = descriptor.doCheckArmadaPort("65535");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaPort_NullValue() {
    FormValidation result = descriptor.doCheckArmadaPort(null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaPort_EmptyValue() {
    FormValidation result = descriptor.doCheckArmadaPort("");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaPort_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckArmadaPort("   ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaPort_NotANumber() {
    FormValidation result = descriptor.doCheckArmadaPort("abc");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port is not a valid number: abc", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaPort_BelowMinimum() {
    FormValidation result = descriptor.doCheckArmadaPort("0");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port must be between 1 and 65535, got: 0", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaPort_AboveMaximum() {
    FormValidation result = descriptor.doCheckArmadaPort("65536");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port must be between 1 and 65535, got: 65536", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaPort_NegativeNumber() {
    FormValidation result = descriptor.doCheckArmadaPort("-1");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port must be between 1 and 65535, got: -1", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaPort_DecimalNumber() {
    FormValidation result = descriptor.doCheckArmadaPort("80.5");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port is not a valid number: 80.5", result.getMessage());
  }

  // Test doCheckArmadaQueue
  @Test
  public void testDoCheckArmadaQueue_ValidQueue() {
    FormValidation result = descriptor.doCheckArmadaQueue("example");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaQueue_NullValue() {
    FormValidation result = descriptor.doCheckArmadaQueue(null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Queue is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaQueue_EmptyValue() {
    FormValidation result = descriptor.doCheckArmadaQueue("");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Queue is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaQueue_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckArmadaQueue("   ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Queue is not configured", result.getMessage());
  }

  // Test doCheckArmadaNamespace
  @Test
  public void testDoCheckArmadaNamespace_ValidNamespace() {
    FormValidation result = descriptor.doCheckArmadaNamespace("default");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaNamespace_NullValue() {
    FormValidation result = descriptor.doCheckArmadaNamespace(null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Namespace is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaNamespace_EmptyValue() {
    FormValidation result = descriptor.doCheckArmadaNamespace("");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Namespace is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaNamespace_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckArmadaNamespace("   ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Namespace is not configured", result.getMessage());
  }

  // Test doCheckArmadaLookoutPort (optional field)
  @Test
  public void testDoCheckArmadaLookoutPort_ValidPort() {
    FormValidation result = descriptor.doCheckArmadaLookoutPort("3000");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaLookoutPort_NullValue() {
    FormValidation result = descriptor.doCheckArmadaLookoutPort(null);
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaLookoutPort_EmptyValue() {
    FormValidation result = descriptor.doCheckArmadaLookoutPort("");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaLookoutPort_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckArmadaLookoutPort("   ");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaLookoutPort_NotANumber() {
    FormValidation result = descriptor.doCheckArmadaLookoutPort("abc");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Lookout Port is not a valid number: abc", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaLookoutPort_BelowMinimum() {
    FormValidation result = descriptor.doCheckArmadaLookoutPort("0");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Lookout Port must be between 1 and 65535, got: 0", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaLookoutPort_AboveMaximum() {
    FormValidation result = descriptor.doCheckArmadaLookoutPort("65536");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Lookout Port must be between 1 and 65535, got: 65536",
        result.getMessage());
  }

  // Test doCheckArmadaClusterConfigPath
  @Test
  public void testDoCheckArmadaClusterConfigPath_ValidPath() {
    FormValidation result = descriptor.doCheckArmadaClusterConfigPath("/tmp/clusters.xml");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckArmadaClusterConfigPath_NullValue() {
    FormValidation result = descriptor.doCheckArmadaClusterConfigPath(null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Cluster Config Path is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaClusterConfigPath_EmptyValue() {
    FormValidation result = descriptor.doCheckArmadaClusterConfigPath("");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Cluster Config Path is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckArmadaClusterConfigPath_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckArmadaClusterConfigPath("   ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Cluster Config Path is not configured", result.getMessage());
  }

  // Test doCheckJenkinsUrl
  @Test
  public void testDoCheckJenkinsUrl_ValidHttpUrl() {
    FormValidation result = descriptor.doCheckJenkinsUrl("http://localhost:8080");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckJenkinsUrl_ValidHttpsUrl() {
    FormValidation result = descriptor.doCheckJenkinsUrl("https://jenkins.example.com");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckJenkinsUrl_NullValue() {
    FormValidation result = descriptor.doCheckJenkinsUrl(null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Jenkins URL is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckJenkinsUrl_EmptyValue() {
    FormValidation result = descriptor.doCheckJenkinsUrl("");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Jenkins URL is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckJenkinsUrl_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckJenkinsUrl("   ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Jenkins URL is not configured", result.getMessage());
  }

  @Test
  public void testDoCheckJenkinsUrl_MissingProtocol() {
    FormValidation result = descriptor.doCheckJenkinsUrl("localhost:8080");
    // This no longer validates protocol - just checks if it's not empty
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckJenkinsUrl_InvalidProtocol() {
    FormValidation result = descriptor.doCheckJenkinsUrl("ftp://localhost:8080");
    // This no longer validates protocol - just checks if it's not empty
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  // Test doTestArmadaConnection validation
  @Test
  public void testDoTestArmadaConnection_NullUrl() {
    FormValidation result = descriptor.doTestArmadaConnection(null, "8080", null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server URL is not configured", result.getMessage());
  }

  @Test
  public void testDoTestArmadaConnection_EmptyUrl() {
    FormValidation result = descriptor.doTestArmadaConnection("", "8080", null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server URL is not configured", result.getMessage());
  }

  @Test
  public void testDoTestArmadaConnection_NullPort() {
    FormValidation result = descriptor.doTestArmadaConnection("localhost", null, null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port is not configured", result.getMessage());
  }

  @Test
  public void testDoTestArmadaConnection_EmptyPort() {
    FormValidation result = descriptor.doTestArmadaConnection("localhost", "", null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port is not configured", result.getMessage());
  }

  @Test
  public void testDoTestArmadaConnection_InvalidPort() {
    FormValidation result = descriptor.doTestArmadaConnection("localhost", "abc", null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port is not a valid number: abc", result.getMessage());
  }

  @Test
  public void testDoTestArmadaConnection_PortBelowMinimum() {
    FormValidation result = descriptor.doTestArmadaConnection("localhost", "0", null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port must be between 1 and 65535, got: 0", result.getMessage());
  }

  @Test
  public void testDoTestArmadaConnection_PortAboveMaximum() {
    FormValidation result = descriptor.doTestArmadaConnection("localhost", "65536", null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("Armada Server Port must be between 1 and 65535, got: 65536", result.getMessage());
  }

  // Test Descriptor
  @Test
  public void testDescriptorDisplayName() {
    assertNotNull(descriptor);
    assertEquals("Armada", descriptor.getDisplayName());
  }

}
