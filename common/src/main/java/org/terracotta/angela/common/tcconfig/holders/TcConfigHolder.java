/*
 * Copyright Terracotta, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terracotta.angela.common.tcconfig.holders;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.tcconfig.TsaStripeConfig;
import org.terracotta.angela.common.util.IpUtils;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This holds the contents of the Tc Config
 * It is subcassed into versions
 *
 * @author Aurelien Broszniowski
 */
public abstract class TcConfigHolder {

  private final static Logger logger = LoggerFactory.getLogger(TcConfigHolder.class);

  protected volatile String tcConfigContent;        // tc config content
  private volatile String installedTcConfigPath;
  private final List<String> logsPathList = new ArrayList<String>();
  private final List<TerracottaServer> servers = new ArrayList<>();

  public TcConfigHolder() {
  }

  public TcConfigHolder(TcConfigHolder tcConfigHolder) {
    this.tcConfigContent = tcConfigHolder.tcConfigContent;
    this.installedTcConfigPath = tcConfigHolder.installedTcConfigPath;
    this.logsPathList.addAll(tcConfigHolder.logsPathList);
  }

  public TcConfigHolder(InputStream tcConfigInputStream) {
    try {
      DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      Document tcConfigXml = builder.parse(tcConfigInputStream);

      this.tcConfigContent = domToString(tcConfigXml);
    } catch (Exception e) {
      throw new RuntimeException("Cannot parse tc-config xml", e);
    }
  }

  public void writeTcConfigFile(File kitDir, final String tcConfigFilename) {
    File tempConfigFile;
    try {
      tempConfigFile = new File(kitDir.getAbsolutePath() + File.separatorChar + tcConfigFilename);
      try (FileOutputStream fos = new FileOutputStream(tempConfigFile)) {
        fos.write(this.tcConfigContent.getBytes(Charset.defaultCharset()));
      }
      this.installedTcConfigPath = tempConfigFile.getAbsolutePath();
      logger.info("Installed Tc Config path: {}", installedTcConfigPath);
    } catch (IOException e) {
      throw new RuntimeException("Cannot write tc-config xml to kitDir location: " + kitDir.getAbsolutePath(), e);
    }
  }

  protected abstract NodeList getServersList(Document tcConfigXml, XPath xPath) throws XPathExpressionException;

  @SuppressFBWarnings("REC_CATCH_EXCEPTION")
  public void initialize(PortAllocator portAllocator, Function<String, Boolean> support) {
    if (!this.servers.isEmpty()) {
      // Already initialized
      return;
    }

    List<TerracottaServer> servers = new ArrayList<>();

    // read servers list from XML
    try {
      XPath xPath = XPathFactory.newInstance().newXPath();
      DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      Document tcConfigXml = builder.parse(new ByteArrayInputStream(this.tcConfigContent.getBytes(UTF_8)));

      NodeList serversList = getServersList(tcConfigXml, xPath);
      for (int i = 0; i < serversList.getLength(); i++) {
        Node server = serversList.item(i);

        Node hostNode = (Node) xPath.evaluate("@host", server, XPathConstants.NODE);
        String hostname =
            hostNode == null || hostNode.getTextContent().equals("%i") || hostNode.getTextContent()
                .equals("%h") ? IpUtils.getHostName() : hostNode.getTextContent();

        Node nameNode = (Node) xPath.evaluate("@name", server, XPathConstants.NODE);

        //TODO : create client and send command to get free port -> can't connect ? agent exception!
        // add into xml the port!!
        // log it!!!
        // remove below updatePorts method

        int tsaPort = getOrAllocate("tsa-port", server, i, xPath, portAllocator);
        int jmxPort = support.apply("jmx-port") ? getOrAllocate("jmx-port", server, i, xPath, portAllocator) : 0;
        int tsaGroupPort = getOrAllocate("tsa-group-port", server, i, xPath, portAllocator);
        int managementPort = support.apply("management-port") ? getOrAllocate("management-port", server, i, xPath, portAllocator) : 0;

        String symbolicName = nameNode == null ? hostname + ":" + tsaPort : nameNode.getTextContent();

        Node logNode = (Node) xPath.evaluate("*[name()='logs']", server, XPathConstants.NODE);

        TerracottaServer terracottaServer = TerracottaServer
            .server(symbolicName, hostname)
            .tsaPort(tsaPort)
            .tsaGroupPort(tsaGroupPort)
            .managementPort(managementPort)
            .logs(logNode == null ? null : logNode.getTextContent())
            .jmxPort(jmxPort);
        servers.add(terracottaServer);
      }
    } catch (Exception e) {
      throw new RuntimeException("Cannot parse tc-config xml", e);
    }
    this.servers.addAll(servers);
  }

  protected int getOrAllocate(String portName, Node serverNode, int idx, XPath xPath, PortAllocator portAllocator) throws XPathExpressionException {
    Node node = (Node) xPath.evaluate("*[name()='" + portName + "']", serverNode, XPathConstants.NODE);
    if (node != null) {
      final int port = Integer.parseInt(node.getTextContent());
      logger.debug("Port: node[" + idx + "] " + portName + "=" + port + " (user-set)");
      return port;
    } else {
      int port = portAllocator.reserve(1).next();
      logger.debug("Port: node[" + idx + "] " + portName + "=" + port + " (random)");
      updateServerPort(idx, portName, port);
      return port;
    }
  }

  public List<TerracottaServer> getServers() {
    if (this.servers.isEmpty()) {
      throw new IllegalStateException("Not initialized");
    }
    return servers;
  }

  public String getTcConfigContent() {
    return tcConfigContent;
  }

  public String getTcConfigPath() {
    return this.installedTcConfigPath;
  }

  public synchronized void updateLogsLocation(final File kitDir, final int stripeId) {
    modifyXml((tcConfigXml, xPath) -> {
      NodeList serversList = getServersList(tcConfigXml, xPath);
      int cnt = 1;
      for (int i = 0; i < serversList.getLength(); i++) {
        Node server = serversList.item(i);
        Node logsNode = (Node) xPath.evaluate("*[name()='logs']", server, XPathConstants.NODE);
        String logsPath;
        if (logsNode != null) {
          logsPath = kitDir.getAbsolutePath() + File.separatorChar + logsNode.getTextContent();
        } else {
          logsPath = kitDir.getAbsolutePath() + File.separatorChar + "logs-" + stripeId + "-" + cnt;
          Element newElement = tcConfigXml.createElement("logs");
          newElement.setTextContent(logsPath);
          server.appendChild(newElement);
        }
        logsPathList.add(logsPath);
        cnt++;
      }
    });
  }

  public void updateServerHost(int serverIndex, String newServerHost) {
    modifyXml((tcConfigXml, xPath) -> {
      NodeList serversList = getServersList(tcConfigXml, xPath);
      if (serverIndex >= serversList.getLength()) {
        throw new ArrayIndexOutOfBoundsException("Server index " + serverIndex + " out of bounds: " + serversList.getLength());
      }
      Node server = serversList.item(serverIndex);

      Attr attribute = tcConfigXml.createAttribute("host");
      attribute.setValue(newServerHost);
      server.getAttributes().setNamedItem(attribute);
    });
  }

  public void updateServerName(int serverIndex, String newServerName) {
    modifyXml((tcConfigXml, xPath) -> {
      NodeList serversList = getServersList(tcConfigXml, xPath);
      if (serverIndex >= serversList.getLength()) {
        throw new ArrayIndexOutOfBoundsException("Server index " + serverIndex + " out of bounds: " + serversList.getLength());
      }
      Node server = serversList.item(serverIndex);

      Attr attribute = tcConfigXml.createAttribute("name");
      attribute.setValue(newServerName);
      server.getAttributes().setNamedItem(attribute);
    });
  }

  public void updateServerPort(int serverIndex, String portName, int port) {
    modifyXml((tcConfigXml, xPath) -> {
      NodeList serversList = getServersList(tcConfigXml, xPath);
      if (serverIndex >= serversList.getLength()) {
        throw new ArrayIndexOutOfBoundsException("Server index " + serverIndex + " out of bounds: " + serversList.getLength());
      }
      Node server = serversList.item(serverIndex);

      Node existingPortNode = (Node) xPath.evaluate("*[name()='" + portName + "']", server, XPathConstants.NODE);
      if (existingPortNode != null) {
        server.removeChild(existingPortNode);
      }

      Element portNode = tcConfigXml.createElement(portName);
      portNode.appendChild(tcConfigXml.createTextNode(Integer.toString(port)));
      server.appendChild(portNode);
    });
  }

  public void addServer(final int stripeIndex, final String hostname) {
    modifyXml((tcConfigXml, xPath) -> {
      int serverIndex = getServersList(tcConfigXml, xPath).getLength() + 1;

      if (serverIndex > 9 || stripeIndex > 54) {
        throw new IllegalArgumentException("Too many stripes (" + stripeIndex + ") or passives (" + serverIndex + ")");
      }

      Node serverElt = (Node) xPath.evaluate("//*[name()='servers']", tcConfigXml.getDocumentElement(), XPathConstants.NODE);

      Element node = tcConfigXml.createElement("server");
      node.setAttribute("host", hostname);
      node.setAttribute("name", "Server" + stripeIndex + "-" + serverIndex);

      serverElt.appendChild(node);
    });
  }


  public void createOrUpdateTcProperty(String name, String value) {
    modifyXml((tcConfigXml, xPath) -> {
      String indentation = guessIndentation(tcConfigXml);
      Node tcProperties = (Node) xPath.evaluate("//*[name()='tc-properties']", tcConfigXml.getDocumentElement(), XPathConstants.NODE);
      boolean createdTcProperties = false;

      if (tcProperties == null) {
        tcProperties = tcConfigXml.createElement("tc-properties");
        tcProperties.appendChild(tcConfigXml.createTextNode("\n"));
        tcConfigXml.getDocumentElement().appendChild(tcConfigXml.createTextNode(indentation));
        tcConfigXml.getDocumentElement().appendChild(tcProperties);
        tcConfigXml.getDocumentElement().appendChild(tcConfigXml.createTextNode("\n"));
        createdTcProperties = true;
      }

      Element existingProperty = null;

      NodeList tcPropertiesChildren = tcProperties.getChildNodes();
      for (int i = 0; i < tcPropertiesChildren.getLength(); i++) {
        Node tcProperty = tcPropertiesChildren.item(i);
        NamedNodeMap attributes = tcProperty.getAttributes();
        Node nameAttribute = attributes != null ? attributes.getNamedItem("name") : null;
        if (nameAttribute != null && nameAttribute.getNodeValue().equals(name)) {
          existingProperty = (Element) tcProperty;
          break;
        }
      }

      if (existingProperty == null) {
        tcProperties.appendChild(tcConfigXml.createTextNode(indentation + (createdTcProperties ? indentation : "")));
        Element newProperty = tcConfigXml.createElement("property");
        newProperty.setAttribute("name", name);
        newProperty.setAttribute("value", value);
        tcProperties.appendChild(newProperty);
        tcProperties.appendChild(tcConfigXml.createTextNode("\n" + indentation));
      } else {
        existingProperty.setAttribute("value", value);
      }

    });
  }

  private String guessIndentation(Document document) {
    Element documentElement = document.getDocumentElement();
    Node item = documentElement.getChildNodes().item(0);
    if (item.getNodeType() == Node.TEXT_NODE) {
      Text text = (Text) item;
      return text.getTextContent().replace("\n", "").replace("\r", "");
    }
    return "  ";
  }


  public List<String> getLogsLocation() {
    return this.logsPathList;
  }

  public abstract void updateSecurityRootDirectoryLocation(final String securityRootDirectory);

  public abstract void updateDataDirectory(final String rootId, final String newlocation);

  public abstract void updateHostname(final String serverName, final String hostname);

  public abstract void updateAuditDirectoryLocation(final File kitDir, final int stripeId);

  public abstract List<TerracottaServer> retrieveGroupMembers(final String serverName, final boolean updateProxy, PortAllocator portAllocator);

  public abstract void updateServerGroupPort(Map<ServerSymbolicName, Integer> proxiedPorts);

  public abstract void updateServerTsaPort(Map<ServerSymbolicName, Integer> proxiedPorts);

  public abstract Map<ServerSymbolicName, Integer> retrieveTsaPorts(final boolean updateForProxy, PortAllocator portAllocator);

  public void substituteToken(String token, String value) {
    this.tcConfigContent = this.tcConfigContent.replaceAll(token, value);
  }

  void modifyXml(XmlModifier xmlModifier) {
    try {
      XPath xPath = XPathFactory.newInstance().newXPath();
      DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      Document tcConfigXml = builder.parse(new ByteArrayInputStream(this.tcConfigContent.getBytes(UTF_8)));

      xmlModifier.modify(tcConfigXml, xPath);

      this.tcConfigContent = domToString(tcConfigXml);
    } catch (Exception e) {
      throw new RuntimeException("Cannot modify tc-config xml", e);
    }
  }

  public abstract void addOffheap(String resourceName, String size, String unit);

  public abstract void addDataDirectory(List<TsaStripeConfig.TsaDataDirectory> tsaDataDirectoryList);

  public abstract Map<String, String> getDataDirectories();

  public abstract void addPersistencePlugin(String persistenceDataName);

  public abstract List<String> getPluginServices();

  @FunctionalInterface
  protected interface XmlModifier {
    void modify(Document tcConfigXml, XPath xPath) throws Exception;
  }

  private static String domToString(Document document) throws TransformerException, IOException {
    DOMSource domSource = new DOMSource(document);
    try (StringWriter writer = new StringWriter()) {
      StreamResult result = new StreamResult(writer);
      TransformerFactory tf = TransformerFactory.newInstance();
      Transformer transformer = tf.newTransformer();
      transformer.transform(domSource, result);
      return writer.toString();
    }
  }

}
