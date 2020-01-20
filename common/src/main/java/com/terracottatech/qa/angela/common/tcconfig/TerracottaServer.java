package com.terracottatech.qa.angela.common.tcconfig;

import com.terracottatech.qa.angela.common.util.HostPort;

import java.util.Objects;

/**
 * Logical definition of a Terracotta server instance
 *
 * @author Aurelien Broszniowski
 */
public class TerracottaServer {

  private final ServerSymbolicName serverSymbolicName;
  private final String hostName;
  private volatile int tsaPort;
  private volatile int tsaGroupPort;
  private volatile int managementPort;
  private volatile int jmxPort;
  private volatile String bindAddress;
  private volatile String groupBindAddress;
  private volatile String configRepo;
  private volatile String configFile;
  private volatile String logs;
  private volatile String metaData;
  private volatile String dataDir;
  private volatile String offheap;

  private TerracottaServer(String serverSymbolicName, String hostName) {
    this.serverSymbolicName = new ServerSymbolicName(serverSymbolicName);
    this.hostName = hostName;
  }

  public static TerracottaServer server(String symbolicName, String hostName) {
    return new TerracottaServer(symbolicName, hostName);
  }

  public TerracottaServer tsaPort(int tsaPort) {
    this.tsaPort = tsaPort;
    return this;
  }

  public TerracottaServer tsaGroupPort(int tsaGroupPort) {
    this.tsaGroupPort = tsaGroupPort;
    return this;
  }

  public TerracottaServer managementPort(int managementPort) {
    this.managementPort = managementPort;
    return this;
  }

  public TerracottaServer jmxPort(int jmxPort) {
    this.jmxPort = jmxPort;
    return this;
  }

  public TerracottaServer bindAddress(String bindAddress) {
    this.bindAddress = bindAddress;
    return this;
  }

  public TerracottaServer groupBindAddress(String groupBindAddress) {
    this.groupBindAddress = groupBindAddress;
    return this;
  }

  public TerracottaServer configRepo(String configRepo) {
    this.configRepo = configRepo;
    return this;
  }

  public TerracottaServer configFile(String configFile) {
    this.configFile = configFile;
    return this;
  }

  public TerracottaServer logs(String logs) {
    this.logs = logs;
    return this;
  }

  public TerracottaServer metaData(String metaData) {
    this.metaData = metaData;
    return this;
  }

  public TerracottaServer dataDir(String dataDir) {
    this.dataDir = dataDir;
    return this;
  }

  public TerracottaServer offheap(String offheap) {
    this.offheap = offheap;
    return this;
  }

  public ServerSymbolicName getServerSymbolicName() {
    return serverSymbolicName;
  }

  public String getHostname() {
    return hostName;
  }

  public int getTsaPort() {
    return tsaPort;
  }

  public String getHostPort() {
    return new HostPort(hostName, tsaPort).getHostPort();
  }

  public int getTsaGroupPort() {
    return tsaGroupPort;
  }

  public int getManagementPort() {
    return managementPort;
  }

  public int getJmxPort() {
    return jmxPort;
  }

  public String getBindAddress() {
    return bindAddress;
  }

  public String getGroupBindAddress() {
    return groupBindAddress;
  }

  public String getConfigRepo() {
    return configRepo;
  }

  public String getConfigFile() {
    return configFile;
  }

  public String getMetaData() {
    return metaData;
  }

  public String getDataDir() {
    return dataDir;
  }

  public String getOffheap() {
    return offheap;
  }

  public String getLogs() {
    return logs;
  }

  @Override
  public String toString() {
    return "TerracottaServer{" +
        "serverSymbolicName=" + serverSymbolicName +
        ", hostname='" + hostName + '\'' +
        '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TerracottaServer that = (TerracottaServer) o;
    return tsaPort == that.tsaPort &&
        tsaGroupPort == that.tsaGroupPort &&
        managementPort == that.managementPort &&
        jmxPort == that.jmxPort &&
        Objects.equals(serverSymbolicName, that.serverSymbolicName) &&
        Objects.equals(hostName, that.hostName) &&
        Objects.equals(bindAddress, that.bindAddress) &&
        Objects.equals(groupBindAddress, that.groupBindAddress) &&
        Objects.equals(configRepo, that.configRepo) &&
        Objects.equals(configFile, that.configFile) &&
        Objects.equals(logs, that.logs) &&
        Objects.equals(metaData, that.metaData) &&
        Objects.equals(offheap, that.offheap) &&
        Objects.equals(dataDir, that.dataDir);
  }

  @Override
  public int hashCode() {
    return Objects.hash(serverSymbolicName, hostName, tsaPort, tsaGroupPort, managementPort, jmxPort, configRepo,
        bindAddress, groupBindAddress, configFile, logs, metaData, offheap, dataDir);
  }
}
