package io.armadaproject.jenkins.plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses cluster configuration XML file to map cluster names to Kubernetes API server URLs.
 * <p>
 * Expected XML format:
 * <pre>
 * &lt;clusters&gt;
 *   &lt;cluster&gt;
 *     &lt;name&gt;cluster-name&lt;/name&gt;
 *     &lt;url&gt;https://k8s.example.com:6443&lt;/url&gt;
 *   &lt;/cluster&gt;
 * &lt;/clusters&gt;
 * </pre>
 */
public class ClusterConfigParser {

  private static final Logger LOGGER = Logger.getLogger(ClusterConfigParser.class.getName());

  /**
   * Parses cluster configuration from XML file.
   *
   * @param configPath path to the cluster configuration XML file
   * @return map of cluster names to Kubernetes API server URLs
   * @throws IOException if file doesn't exist, is malformed, or cannot be parsed
   */
  public static Map<String, String> parse(String configPath) throws IOException {
    if (configPath == null || configPath.trim().isEmpty()) {
      throw new IOException("Cluster configuration error at '': Cluster config path cannot be null or empty");
    }

    File configFile = new File(configPath);
    if (!configFile.exists()) {
      throw new IOException(String.format("Cluster configuration error at '%s': Cluster config file not found", configPath));
    }

    if (!configFile.canRead()) {
      throw new IOException(String.format("Cluster configuration error at '%s': Cluster config file is not readable", configPath));
    }

    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Enable secure processing to prevent XXE attacks
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

      DocumentBuilder builder = factory.newDocumentBuilder();
      Document doc = builder.parse(configFile);
      doc.getDocumentElement().normalize();

      Map<String, String> clusterMap = new HashMap<>();
      NodeList clusterList = doc.getElementsByTagName("cluster");

      if (clusterList.getLength() == 0) {
        LOGGER.warning("No clusters found in config file: " + configPath);
        return clusterMap;
      }

      for (int i = 0; i < clusterList.getLength(); i++) {
        Node clusterNode = clusterList.item(i);

        if (clusterNode.getNodeType() == Node.ELEMENT_NODE) {
          Element clusterElement = (Element) clusterNode;

          NodeList nameNodes = clusterElement.getElementsByTagName("name");
          NodeList urlNodes = clusterElement.getElementsByTagName("url");

          if (nameNodes.getLength() == 0) {
            LOGGER.warning("Cluster element missing 'name' tag at index " + i);
            continue;
          }

          if (urlNodes.getLength() == 0) {
            LOGGER.warning("Cluster element missing 'url' tag at index " + i);
            continue;
          }

          String name = nameNodes.item(0).getTextContent();
          String url = urlNodes.item(0).getTextContent();

          if (name == null || name.trim().isEmpty()) {
            LOGGER.warning("Cluster name is empty at index " + i);
            continue;
          }

          if (url == null || url.trim().isEmpty()) {
            LOGGER.warning("Cluster URL is empty for cluster: " + name);
            continue;
          }

          clusterMap.put(name.trim(), url.trim());
          LOGGER.fine("Loaded cluster: " + name + " -> " + url);
        }
      }

      return clusterMap;

    } catch (ParserConfigurationException e) {
      throw new IOException(String.format("Cluster configuration error at '%s': Failed to configure XML parser", configPath), e);
    } catch (SAXException e) {
      throw new IOException(String.format("Cluster configuration error at '%s': Failed to parse cluster config XML", configPath), e);
    } catch (IOException e) {
      throw new IOException(String.format("Cluster configuration error at '%s': Failed to read cluster config file", configPath), e);
    }
  }
}
