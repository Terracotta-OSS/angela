/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.angela.common.tcconfig;

public class NamedSecurityRootDirectory {
  private final ServerSymbolicName serverSymbolicName;
  private final SecurityRootDirectory securityRootDirectory;

  public static NamedSecurityRootDirectory withSecurityFor(ServerSymbolicName serverSymbolicName,
                                                           SecurityRootDirectory securityRootDirectory) {
    return new NamedSecurityRootDirectory(serverSymbolicName, securityRootDirectory);
  }


  private NamedSecurityRootDirectory(ServerSymbolicName serverSymbolicName, SecurityRootDirectory securityRootDirectory) {
    this.serverSymbolicName = serverSymbolicName;
    this.securityRootDirectory = securityRootDirectory;
  }

  public ServerSymbolicName getServerSymbolicName() {
    return serverSymbolicName;
  }

  public SecurityRootDirectory getSecurityRootDirectory() {
    return securityRootDirectory;
  }
}
