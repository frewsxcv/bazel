// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.apple;

import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;

import java.util.ArrayList;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a value with multiple components, separated by periods, for example {@code 4.5.6} or
 * {@code 5.0.1beta2}. Components must start with a non-negative integer and at least one component
 * must be present.
 *
 * <p>Specifically, the format of a component is {@code \d+([a-z]+\d*)?}.
 *
 * <p>Dotted versions are ordered using natural integer sorting on components in order from first to
 * last where any missing element is considered to have the value 0 if they don't contain any
 * non-numeric characters. For example:
 * <pre>
 *   3.1.25 > 3.1.1
 *   3.1.20 > 3.1.2
 *   3.1.1 > 3.1
 *   3.1 == 3.1.0.0
 *   3.2 > 3.1.8
 * </pre>
 *
 * <p>If the component contains any alphabetic characters after the leading integer, it is
 * considered <strong>smaller</strong> than any components with the same integer but larger than any
 * component with a smaller integer. If the integers are the same, the alphabetic sequences are
 * compared lexicographically, and if <i>they</i> turn out to be the same, the final (optional)
 * integer is compared. As with the leading integer, this final integer is considered to be 0 if not
 * present. For example:
 * <pre>
 *   3.1.1 > 3.1.1beta3
 *   3.1.1beta1 > 3.1.0
 *   3.1 > 3.1.0alpha1
 *
 *   3.1.0beta0 > 3.1.0alpha5.6
 *   3.4.2alpha2 > 3.4.2alpha1
 *   3.4.2alpha2 > 3.4.2alpha1.5
 *   3.1alpha1 > 3.1alpha
 * </pre>
 *
 * <p>This class is immutable and can safely be shared among threads.
 */
@SkylarkModule(
  name = "DottedVersion",
  doc = "A value representing a version with multiple components, seperated by periods, such as "
      + "1.2.3.4."
)
public final class DottedVersion implements Comparable<DottedVersion> {
  private static final Splitter DOT_SPLITTER = Splitter.on('.');
  private static final Pattern COMPONENT_PATTERN = Pattern.compile("(\\d+)(?:([a-z]+)(\\d*))?");
  private static final String ILLEGAL_VERSION =
      "Dotted version components must all be of the form \\d+([a-z]+\\d*)? but got %s";
  private static final String NO_ALPHA_SEQUENCE = null;
  private static final Component ZERO_COMPONENT = new Component(0, NO_ALPHA_SEQUENCE, 0);

  /**
   * Generates a new dotted version from the given version string.
   *
   * @throws IllegalArgumentException if the passed string is not a valid dotted version
   */
  public static DottedVersion fromString(String version) {
    ArrayList<Component> components = new ArrayList<>();
    for (String component : DOT_SPLITTER.split(version)) {
      components.add(toComponent(component, version));
    }

    // Remove trailing (but not the first) zero components for easier comparison and hashcoding.
    for (int i = components.size() - 1; i > 0; i--) {
      if (components.get(i).equals(ZERO_COMPONENT)) {
        components.remove(i);
      }
    }

    return new DottedVersion(ImmutableList.copyOf(components), version);
  }

  private static Component toComponent(String component, String version) {
    Matcher parsedComponent = COMPONENT_PATTERN.matcher(component);
    if (!parsedComponent.matches()) {
      throw new IllegalArgumentException(String.format(ILLEGAL_VERSION, version));
    }

    int firstNumber;
    String alphaSequence = NO_ALPHA_SEQUENCE;
    int secondNumber = 0;
    firstNumber = parseNumber(parsedComponent, 1, version);

    if (parsedComponent.group(2) != null) {
      alphaSequence = parsedComponent.group(2);
    }

    if (!Strings.isNullOrEmpty(parsedComponent.group(3))) {
      secondNumber = parseNumber(parsedComponent, 3, version);
    }

    return new Component(firstNumber, alphaSequence, secondNumber);
  }

  private static int parseNumber(Matcher parsedComponent, int group, String version) {
    int firstNumber;
    try {
      firstNumber = Integer.parseInt(parsedComponent.group(group));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(String.format(ILLEGAL_VERSION, version));
    }
    return firstNumber;
  }

  private final ImmutableList<Component> components;
  private final String stringRepresentation;

  private DottedVersion(ImmutableList<Component> components, String version) {
    this.components = components;
    this.stringRepresentation = version;
  }

  @Override
  @SkylarkCallable(name = "compare_to", 
    doc = "Compares based on most signifigant (first) not-matching version component. "
        + "So, for example, 1.2.3 > 1.2.4")
  public int compareTo(DottedVersion other) {
    int maxComponents = Math.max(components.size(), other.components.size());
    for (int componentIndex = 0; componentIndex < maxComponents; componentIndex++) {
      Component myComponent = getComponent(componentIndex);
      Component otherComponent = other.getComponent(componentIndex);
      int comparison = myComponent.compareTo(otherComponent);
      if (comparison != 0) {
        return comparison;
      }
    }
    return 0;
  }

  @Override
  public String toString() {
    return stringRepresentation;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }

    return compareTo((DottedVersion) other) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(components);
  }

  private Component getComponent(int groupIndex) {
    if (components.size() > groupIndex) {
      return components.get(groupIndex);
    }
    return ZERO_COMPONENT;
  }

  private static final class Component implements Comparable<Component> {
    private final int firstNumber;
    private final String alphaSequence;
    private final int secondNumber;

    public Component(int firstNumber, String alphaSequence, int secondNumber) {
      this.firstNumber = firstNumber;
      this.alphaSequence = alphaSequence;
      this.secondNumber = secondNumber;
    }

    @Override
    public int compareTo(Component other) {
      return ComparisonChain.start()
          .compare(firstNumber, other.firstNumber)
          .compare(alphaSequence, other.alphaSequence, Ordering.natural().nullsLast())
          .compare(secondNumber, other.secondNumber)
          .result();
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }

      return compareTo((Component) other) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(firstNumber, alphaSequence, secondNumber);
    }
  }
}
