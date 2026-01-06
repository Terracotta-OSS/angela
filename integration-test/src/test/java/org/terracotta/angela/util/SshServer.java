/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
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
package org.terracotta.angela.util;

import org.apache.sshd.common.file.nativefs.NativeFileSystemFactory;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.scp.server.ScpCommandFactory;
import org.apache.sshd.server.shell.InteractiveProcessShellFactory;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.util.Collections.singletonList;

/**
 * @author Mathieu Carbou
 */
public class SshServer implements Closeable {
  private static final Logger logger = LoggerFactory.getLogger(SshServer.class);

  private final org.apache.sshd.server.SshServer sshd = org.apache.sshd.server.SshServer.setUpDefaultServer();
  private final Path root;

  private int port = 0;

  public SshServer(Path root) {
    this.root = root;
  }

  public int getPort() {
    return port;
  }

  public SshServer withPort(int port) {
    this.port = port;
    return this;
  }

  public SshServer start() {
    try {
      // create isolated file system
      Files.createDirectories(root);
      sshd.setFileSystemFactory(new NativeFileSystemFactory());
      sshd.setPort(port);
      sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(root.resolve("hostkey.ser")));
      sshd.setSubsystemFactories(singletonList(new SftpSubsystemFactory.Builder().build()));
      sshd.setCommandFactory(new ScpCommandFactory.Builder()
          .withDelegate(new ProcessShellCommandFactory())
          .build());
      sshd.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
      sshd.setShellFactory(new InteractiveProcessShellFactory());
      sshd.start();
      return this;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    try {
      sshd.stop();
      sshd.close();
    } catch (IOException e) {
      logger.warn("Error closing SSH server: " + e.getMessage(), e);
    }
  }

  @Override
  public String toString() {
    final StringBuilder sb = new StringBuilder("SshServer{");
    sb.append("root=").append(root);
    sb.append(", port=").append(port);
    sb.append('}');
    return sb.toString();
  }

  public static void main(String[] args) throws Throwable {
    new SshServer(Paths.get("target", "foo")).withPort(2222).start();
    System.in.read();
  }
}
