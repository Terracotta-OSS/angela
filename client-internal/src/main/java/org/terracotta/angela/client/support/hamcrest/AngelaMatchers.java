/*
 * Copyright Terracotta, Inc.
 * Copyright Super iPaaS Integration LLC, an IBM Company 2024
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
package org.terracotta.angela.client.support.hamcrest;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.terracotta.angela.common.ToolExecutionResult;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Mathieu Carbou
 */
public class AngelaMatchers {

  public static Matcher<ToolExecutionResult> successful() {
    return new CustomTypeSafeMatcher<ToolExecutionResult>("successful") {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        return result.getExitStatus() == 0;
      }
    };
  }

  public static Matcher<ToolExecutionResult> containsOutput(String text) {
    return new CustomTypeSafeMatcher<ToolExecutionResult>("contains " + text) {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        return find(result.getOutput(), text);
      }
    };
  }

  public static Matcher<ToolExecutionResult> containsLinesInOrderStartingWith(Collection<String> expectedLines) {
    return new CustomTypeSafeMatcher<ToolExecutionResult>("contains lines in order starting with:\n" + String.join("\n", expectedLines)) {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        LinkedList<String> lines = new LinkedList<>(expectedLines);
        for (String out : result.getOutput()) {
          if (out.startsWith(lines.getFirst())) {
            lines.removeFirst();
          }
        }
        return lines.isEmpty();
      }

      @Override
      protected void describeMismatchSafely(ToolExecutionResult result, Description mismatchDescription) {
        LinkedList<String> lines = new LinkedList<>(expectedLines);
        for (String out : result.getOutput()) {
          if (out.startsWith(lines.getFirst())) {
            lines.removeFirst();
          }
        }
        mismatchDescription.appendText("these lines were not found:\n" + String.join("\n", lines) + "\n in output:\n" + String.join("\n", result.getOutput()));
      }
    };
  }

  public static Matcher<ToolExecutionResult> containsLinesStartingWith(Collection<String> expectedLines) {
    return new CustomTypeSafeMatcher<ToolExecutionResult>("contains lines starting with:\n" + String.join("\n", expectedLines)) {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        LinkedList<String> lines = new LinkedList<>(expectedLines);
        for (String out : result.getOutput()) {
          if (lines.isEmpty()) {
            break;
          } else {
            lines.removeIf(out::startsWith);
          }
        }
        return lines.isEmpty();
      }

      @Override
      protected void describeMismatchSafely(ToolExecutionResult result, Description mismatchDescription) {
        LinkedList<String> lines = new LinkedList<>(expectedLines);
        for (String out : result.getOutput()) {
          if (lines.isEmpty()) {
            break;
          } else {
            lines.removeIf(out::startsWith);
          }
        }
        mismatchDescription.appendText("these lines were not found:\n" + String.join("\n", lines) + "\n in output:\n" + String.join("\n", result.getOutput()));
      }
    };
  }

  public static Matcher<ToolExecutionResult> hasExitStatus(int exitStatus) {
    return new CustomTypeSafeMatcher<ToolExecutionResult>(" exist status " + exitStatus) {
      @Override
      protected boolean matchesSafely(ToolExecutionResult result) {
        return result.getExitStatus() == exitStatus;
      }
    };
  }

  private static boolean find(List<String> lines, String text) {
    // reverse search because chances are that the string we are searching for is more at the end than at the beginning
    for (int i = lines.size() - 1; i >= 0; i--) {
      if (lines.get(i).contains(text)) {
        return true;
      }
    }
    return false;
  }
}
