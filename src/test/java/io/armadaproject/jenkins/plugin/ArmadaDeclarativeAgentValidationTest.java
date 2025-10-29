package io.armadaproject.jenkins.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.util.FormValidation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
public class ArmadaDeclarativeAgentValidationTest {

  private ArmadaDeclarativeAgent.DescriptorImpl descriptor;

  @BeforeEach
  public void setUp(JenkinsRule jenkins) {
    descriptor = jenkins.jenkins.getDescriptorByType(ArmadaDeclarativeAgent.DescriptorImpl.class);
  }

  // Test doCheckYaml
  @Test
  public void testDoCheckYaml_ValidYaml() {
    String validYaml = "apiVersion: v1\n" +
        "kind: Pod\n" +
        "metadata:\n" +
        "  name: test-pod\n";
    FormValidation result = descriptor.doCheckYaml(validYaml);
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckYaml_SimpleString() {
    FormValidation result = descriptor.doCheckYaml("some yaml content");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckYaml_NullValue() {
    FormValidation result = descriptor.doCheckYaml(null);
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("YAML configuration is required", result.getMessage());
  }

  @Test
  public void testDoCheckYaml_EmptyValue() {
    FormValidation result = descriptor.doCheckYaml("");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("YAML configuration is required", result.getMessage());
  }

  @Test
  public void testDoCheckYaml_WhitespaceOnly() {
    FormValidation result = descriptor.doCheckYaml("   ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("YAML configuration is required", result.getMessage());
  }

  @Test
  public void testDoCheckYaml_NewlineOnly() {
    FormValidation result = descriptor.doCheckYaml("\n\n");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("YAML configuration is required", result.getMessage());
  }

  @Test
  public void testDoCheckYaml_TabsAndSpaces() {
    FormValidation result = descriptor.doCheckYaml("\t  \t  ");
    assertEquals(FormValidation.Kind.ERROR, result.kind);
    assertEquals("YAML configuration is required", result.getMessage());
  }

  @Test
  public void testDoCheckYaml_MultilineValid() {
    String multilineYaml = "apiVersion: v1\n" +
        "kind: Pod\n" +
        "spec:\n" +
        "  containers:\n" +
        "  - name: test\n" +
        "    image: busybox\n";
    FormValidation result = descriptor.doCheckYaml(multilineYaml);
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckYaml_WithLeadingWhitespace() {
    FormValidation result = descriptor.doCheckYaml("  apiVersion: v1");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  @Test
  public void testDoCheckYaml_WithTrailingWhitespace() {
    FormValidation result = descriptor.doCheckYaml("apiVersion: v1  ");
    assertEquals(FormValidation.Kind.OK, result.kind);
  }

  // Test Descriptor
  @Test
  public void testDescriptorName() {
    assertNotNull(descriptor);
    assertEquals("armada", descriptor.getName());
  }

  // Test ArmadaDeclarativeAgent data binding
  @Test
  public void testArmadaDeclarativeAgent_SetYaml() {
    ArmadaDeclarativeAgent agent = new ArmadaDeclarativeAgent();
    String yaml = "apiVersion: v1\nkind: Pod";
    agent.setYaml(yaml);
    assertEquals(yaml, agent.getYaml());
  }

  @Test
  public void testArmadaDeclarativeAgent_SetYamlNull() {
    ArmadaDeclarativeAgent agent = new ArmadaDeclarativeAgent();
    agent.setYaml(null);
    assertEquals(null, agent.getYaml());
  }

  @Test
  public void testArmadaDeclarativeAgent_SetYamlEmpty() {
    ArmadaDeclarativeAgent agent = new ArmadaDeclarativeAgent();
    agent.setYaml("");
    // Util.fixEmpty() returns null for empty strings
    assertEquals(null, agent.getYaml());
  }

  @Test
  public void testArmadaDeclarativeAgent_SetYamlWhitespace() {
    ArmadaDeclarativeAgent agent = new ArmadaDeclarativeAgent();
    agent.setYaml("   ");
    // Util.fixEmpty() only checks for null or empty, not whitespace
    assertEquals("   ", agent.getYaml());
  }

  @Test
  public void testArmadaDeclarativeAgent_SetCloud() {
    ArmadaDeclarativeAgent agent = new ArmadaDeclarativeAgent();
    agent.setCloud("test-cloud");
    assertEquals("test-cloud", agent.getCloud());
  }

  @Test
  public void testArmadaDeclarativeAgent_SetCloudNull() {
    ArmadaDeclarativeAgent agent = new ArmadaDeclarativeAgent();
    agent.setCloud(null);
    assertEquals(null, agent.getCloud());
  }

  @Test
  public void testArmadaDeclarativeAgent_SetCloudEmpty() {
    ArmadaDeclarativeAgent agent = new ArmadaDeclarativeAgent();
    agent.setCloud("");
    // Util.fixEmpty() returns null for empty strings
    assertEquals(null, agent.getCloud());
  }

  @Test
  public void testArmadaDeclarativeAgent_InitialState() {
    ArmadaDeclarativeAgent agent = new ArmadaDeclarativeAgent();
    assertEquals(null, agent.getYaml());
    assertEquals(null, agent.getCloud());
  }
}
