<img src="angela.png" height="100" alt="Angela Logo" />

A distributed control framework to handle a Terracotta cluster and test clients

## What is the purpose of Angela?

One major obstacle to testing a client/server system is setting up the environment. Not only the server must be installed, but also the clients and both of them might have to be installed on different remote locations. 

Angela is meant to tackle this problem and ease the setup of a distributed environment.

It also helps with the control of that distributed environment (e.g. starting/stopping some of the components, fetching some remote files, monitoring, injecting network failures). 

The current implementation is targeted for Terracotta, which is a distributed data management platform.

Angela also supports Ehcache 2 and 3, which are implementations of a distributed cache.

Angela can be extensible to handle other distributed softwares.

## Initial setup

For running tests on a node, Angela expects a directory at /data/angela to store all its metadata. So make sure that this directory exists or can be created before running any tests. For more details on what what that directory is used for, refer to Angela Directory Structure

## Tsa Cluster example

Given the following cluster configuration:

```
  <servers>
    <server host="localhost" name="Server1">
      <logs>logs1</logs>
      <tsa-port>9510</tsa-port>
      <tsa-group-port>9530</tsa-group-port>
    </server>
  </servers>
```

We expect the TSA to contain one Terracotta server running on localhost, and this will be automatically resolved by Angela. We can ask now Angela to setup such a cluster:

```
    ConfigurationContext configContext = customConfigurationContext() (1)
        .tsa(tsa -> tsa (2)
            .topology(new Topology( (3)
                distribution(version(EHCACHE_VERSION), PackageType.KIT, LicenseType.EHCACHE_OS), (4)
                tcConfig(version(EHCACHE_VERSION), getClass().getResource("/tc-config-a.xml")))) (5)
        );

    ClusterFactory factory = new ClusterFactory("GettingStarted::configureCluster", configContext); (6)
    Tsa tsa = factory.tsa() (7)
        .startAll() (8)

    factory.close(); (9)
```

  1) Create a custom configuration context that is going to hold all the configurable bits

  2) Define the TSA config

  3) Specify the Terracotta cluster topology

  4) Specify the Terracotta distribution : version, package type (KIT) and License

  5) Specify the Terracotta cluster config

  6) Create a Tsa logical instance that serves as an endpoint to call functionalities regarding the Tsa lifecycle

  7) Install the Tsa from the distribution on the appropriate server(s) (localhost in this case)

  8) Start all servers from the Tsa

  9) Stop all Terracotta servers and cleans up the installation

## Tsa API

```
      Tsa tsa = factory.tsa() (1)
          .startAll() (2)

      TerracottaServer active = tsa.getActive(); (3)
      Collection<TerracottaServer> actives = tsa.getActives(); (4)
      TerracottaServer passive = tsa.getPassive(); (5)
      Collection<TerracottaServer> passives = tsa.getPassives(); (6)

      tsa.stopAll(); (7)

      tsa.start(active); (8)
      tsa.start(passive);

      tsa.stop(active); (9)
      Callable<TerracottaServerState> serverState = () -> tsa.getState(passive); (10)
      Awaitility.await()
          .pollInterval(1, SECONDS)
          .atMost(15, SECONDS)
          .until(serverState, is(TerracottaServerState.STARTED_AS_ACTIVE));
```

  1) Install all Terracotta servers for the given topology

  2) Start all Terracotta servers

  3) Get the reference of the active server. Null is returned if there is none. An exception is throw if there are more than one

  4) Get the references of all active servers. Get an empty collection if there are none.

  5) Get the reference of the passive server. Null is returned if there is none. An exception is throw if there are more than one

  6) Get the references of all passive servers. Get an empty collection if there are none.

  7) Stop all Terracotta servers

  8) Start one Terracotta server

  9) Stop one Terracotta server

  10) Get the current state of the Terracotta server

## Client array example

```
    ConfigurationContext configContext = customConfigurationContext()
        .clientArray(clientArray -> clientArray (1)
            .clientArrayTopology(new ClientArrayTopology( (2)
                distribution(version(EHCACHE_VERSION), PackageType.KIT, LicenseType.EHCACHE_OS), (3)
                newClientArrayConfig().host("localhost-1", "localhost").host("localhost-2", "localhost")) (4)
            )
        );
    ClusterFactory factory = new ClusterFactory("GettingStarted::runClient", configContext);
    ClientArray clientArray = factory.clientArray(0); (5)
    ClientArrayFuture f = clientArray.executeOnAll((context) -> System.out.println("Hello")); (6)
    f.get(); (7)

    factory.close();
```

  1) Define the client array config

  2) Define the client array topology

  3) Specify the distribution from which to install the client jars

  4) Specify the list of hosts that are going to be used by this client array (two clients, both on localhost in this case)

  5) Create a client array on the remote servers

  6) Execute the lambda on all the remote clients

  7) Wait until all the clients finish their execution

Full example : See class [EhcacheTest](integration-test/src/test/java/org/terracotta/angela/EhcacheTest.java)

## IMPORTANT: settings.xml

You can run all the Maven commands with `-s settings.xml` to use the project's settings.xml
and isolate downloaded libraries inside. Change the repo location to point to your default m2 home if needed.

Example: `./mvnw -s settings.xml clean install`

## How to build

    mvn clean install

## Run specific tests

    mvn test -f integration-test/pom.xml -Dtest=<test-name>

Be careful not to cd directly into the module, you would not use the right kit version !

## Things to know

 * Angela is looking for JDK's in `$HOME/.m2/toolchains.xml`, the standard Maven toolchains file.
 See https://maven.apache.org/guides/mini/guide-using-toolchains.html to get its format and learn more about it.
 * Angela uses SSH to connect to remote hosts, so every non-localhost machine name is expected to be accessible via ssh,
 with everything already configured for passwordless authentication.
 * Angela spawns a small controlling app on every remote hosts that is very network-latency sensitive and uses lots of
 random ports. In a nutshell, this means that testing across WANs or firewalls just doesn't work. 
 * Angela expects a writeable `/data` folder (or at least a pre-created, writeable `/data/angela` folder) on every
 machine she runs on, i.e.: the one running the test as well as all the remote hosts.

## Updates Feb. 2022

### Angela system properties

Corresponding class: `AngelaProperties`

| **System Property**                  |           **Default value**            | **Description**                                                                                                                                                                |
|--------------------------------------|:--------------------------------------:|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **angela.rootDir**                   |              /data/angela              | root dir where Angela puts installation, work directories and any file that is needed                                                                                          |
| **angela.kitInstallationDir**        |                                        | use this property to use a local build instead of downloading a kit build                                                                                                      |
| **angela.kitCopy**                   |                 false                  | forces a kit copy instead of using a common kit install for multiple tests. useful for parallel execution of tests that changes files in the kit install (e.g. tmc.properties) |
| **angela.skipUninstall**             |                 false                  | do not clean work directory (used to have access to logs after end of test for debugging test issues)                                                                          |
| **angela.distribution**              |                                        |                                                                                                                                                                                |
| **angela.additionalLocalHostnames**  |                   ""                   | Define additional hostnames or ip addresses to be considered as local, separated by comma. Used in case the test is faking some local hostnames                                |
| **angela.igniteLogging**             |                 false                  | display Ignite logging (used to help debugging the behaviour of Angela)                                                                                                        |
| **angela.agent.debug**               |                 false                  | put a remote agent in debug mode                                                                                                                                               |
| **angela.tms.fullLogging**           |                 false                  |                                                                                                                                                                                |
| **angela.tsa.fullLogging**           |                 false                  |                                                                                                                                                                                |
| **angela.voter.fullLogging**         |                 false                  |                                                                                                                                                                                |
| **angela.ssh.userName**              |    System.getProperty("user.name")     |                                                                                                                                                                                |
| **angela.ssh.userName.keyPath**      |                                        |                                                                                                                                                                                |
| **angela.ssh.strictHostKeyChecking** |                  true                  |                                                                                                                                                                                |
| **angela.ssh.port**                  |                   22                   |                                                                                                                                                                                |
| **angela.java.resolver**             |               toolchain                | can be set to "user"                                                                                                                                                           |
| **angela.java.home**                 |    System.getProperty("java.home")     |                                                                                                                                                                                |
| **angela.java.version**              |                  1.8                   |                                                                                                                                                                                |
| **angela.java.vendor**               |                  zulu                  |                                                                                                                                                                                |
| **angela.java.opts**                 | -Djdk.security.allowNonCaAnchor=false  |                                                                                                                                                                                |


### Concepts

1. `GroupId`: a UUID determined at the `AngelaOrchestrator` level. All Ignite agents will be part of the same group.
2. `AgentGroup`: a class representing the cluster of all Ignite nodes
3. `AgentID`: identifies an Ignite agent on the cluster in the form: `name#pid@hostname#port`
4. Agent `types`: there can be 3 types of agents:
    - `orchestrator-agent`: the agent started in the test JVM locally to control the other ones
    - `remote-agent`: the agent started via SSH on a remote host
    - others: agents spawned from another agent (orchestrator or remote) to execute jobs for a client Id either locally or on a remote host
5. `AgentControler`: the agent controller has been cleared from any Ignite related code. It now ONLY contains the methods called statically from Ignite closures. This class is installed statically.
6. `Executors`: these` are the main refactoring. All the com layer has been refactored in these implementations:
    - `IgniteFreeExecutor`: a local implementation bypassing any Ignite launching
    - `IgniteLocalExecutor`: an implementation using Ignite but only locally. it won't spawn remote agents through SSH. All angela configs specifying a remote host will be executed on the local machine. New Ignite agents can still be spawned to execute client jobs.
    - `IgniteSshRemoteExecutor`: this is the default implementation which will spawn agents remotely if a non-local hostname is specified in a configuration
7. `Agent`: an agent now decides its own port to start with (thanks to the port mapper) and exposes its agentId. It also registers 3 attributes: `angela.version`, `angela.nodeName` and `angela.group` and needs to be started with `angela.instanceName` (agent name or type) and `angela.group` (the group he will be part of).
8. **Closing**: closing an executor will communicate to all spawned Ignite agents to also close themselves. Angela was  not relying on Ignite to communicate closure, but was relying on killing spawned clients through SSH with their PID. `Executor.shutdown(agentId)` can now close any spawned agent.

### Angela API Usage

|  | **Spawned servers** | **Inline servers** |
|---|:---:|:---:|
| **Ignite-free mode** | X | X |
| **Ignite-local mode** | X | X |
| **Ingite-remote mode (default)** | X | X |

First create an `AngelaOrchestrator` through Junit rule or the AngelaOrchestrator builder API.

**If you are not using Junit, use the `AngelaOrchestrator.buidler()` API instead**. 
There are several examples in this project in the test module. 

```java
@Rule public transient AngelaOrchestratorRule angelaOrchestratorRule = new AngelaOrchestratorRule();
```

Then derive the cluster factories:

```java
try (ClusterFactory factory = angelaOrchestratorRule.newClusterFactory("ConfigToolTest::testFailingClusterToolCommand", configContext)) {
    // [...]
}
```

**Ignite-free mode:**

```java
@Rule public transient AngelaOrchestratorRule angelaOrchestratorRule = new AngelaOrchestratorRule().igniteFree();
```

Can be used in conjunction with `RuntimeOption.INLINE_SERVERS` to use inline mode for servers

**Ignite local only mode:**

```java
@Rule public transient AngelaOrchestratorRule angelaOrchestratorRule = new AngelaOrchestratorRule().igniteLocal();
```

Only one local Ignite controler, and other local Ignite spawned to execute client jobs.

Can be used in conjunction with `RuntimeOption.INLINE_SERVERS` to use inline mode for servers

**Ignite with remote support (default)**

```java
@Rule public transient AngelaOrchestratorRule angelaOrchestratorRule = new AngelaOrchestratorRule().igniteRemote();

// or

@Rule public transient AngelaOrchestratorRule angelaOrchestratorRule = new AngelaOrchestratorRule().igniteRemote(executor -> {
  executor.setStrictHostKeyChecking(false);
  executor.setPort(2222);
  executor.setRemoteUserName("testusername");
  executor.setTcEnv(...)
});
```

Can be used in conjunction with `RuntimeOption.INLINE_SERVERS` to use inline mode for servers

### What about Inline mode ?

Inline mode will spawn tc nodes within the test JVM. It can be activated with:

```java
distribution(version(Versions.EHCACHE_VERSION), KIT, TERRACOTTA_OS, RuntimeOption.INLINE_SERVERS)
```

### Programmatic SPI

An executor can be obtained from an orchestrator:

```java
Executor executor = angelaOrchestratorRule.getExecutor();
```

Getting an executor Ignite-free

```java
  UUID group = UUID.randomUUID();
  Agent agent = Agent.local(group);
  Executor executor = new IgniteFreeExecutor(agent);
```

Getting a local Ignite executor:

```java
  UUID group = UUID.randomUUID();
  PortAllocator portAllocator = new DefaultPortAllocator();
  Agent agent = Agent.igniteOrchestrator(group, portAllocator);
  AgentID agentID = agent.getAgentID();
  Executor executor = new IgniteLocalExecutor(agent);
```

Getting a standard Ignite executor supporting SSH agent install:

```java
  PortAllocator portAllocator = new DefaultPortAllocator();
  UUID group = UUID.randomUUID();
  Agent agent = Agent.igniteOrchestrator(group, portAllocator);
  AgentID agentID = agent.getAgentID();
  Executor executor = new IgniteSshRemoteExecutor(agent)
      .setStrictHostKeyChecking(false)
      .setPort(...);
```

### Adding your own application

If you look at WebMIs, this shows how to use the framework to control the lifecycle of your own application.

You will need to write classes such as:

- WebMIsServerInstance
- WebMIsInstall
- WebMIsTopology
- WebMIsServerState
- WebMIsServer
- WebMIsConfigurationContext
- WebMIs