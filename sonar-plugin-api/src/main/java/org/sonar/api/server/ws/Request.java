/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.api.server.ws;

import org.apache.commons.lang.StringUtils;

import javax.annotation.CheckForNull;

/**
 * @since 4.2
 */
public abstract class Request {

  public abstract WebService.Action action();

  /**
   * Returns the name of the HTTP method with which this request was made. Possible
   * values are GET and POST. Others are not supported.
   */
  public abstract String method();

  /**
   * Returns value of a mandatory parameter
   *
   * @throws java.lang.IllegalArgumentException is value is null or blank
   */
  public String mandatoryParam(String key) {
    String value = param(key);
    if (StringUtils.isBlank(value)) {
      throw new IllegalArgumentException(String.format("Parameter '%s' is missing", key));
    }
    return value;
  }

  /**
   * Returns value of a mandatory parameter
   *
   * @throws java.lang.IllegalArgumentException is value is null or blank
   */
  public boolean mandatoryParamAsBoolean(String key) {
    String s = mandatoryParam(key);
    return Boolean.parseBoolean(s);
  }

  /**
   * Returns value of a mandatory parameter
   *
   * @throws java.lang.IllegalArgumentException is value is null or blank
   */
  public int mandatoryParamAsInt(String key) {
    String s = mandatoryParam(key);
    return Integer.parseInt(s);
  }

  /**
   * Returns value of a mandatory parameter
   *
   * @throws java.lang.IllegalArgumentException is value is null or blank
   */
  public long mandatoryParamAsLong(String key) {
    String s = mandatoryParam(key);
    return Long.parseLong(s);
  }

  @CheckForNull
  public abstract String param(String key);

  @CheckForNull
  public String param(String key, @CheckForNull String defaultValue) {
    return StringUtils.defaultString(param(key), defaultValue);
  }

  @CheckForNull
  public Integer paramAsInt(String key) {
    String s = param(key);
    return s == null ? null : Integer.parseInt(s);
  }

  public int paramAsInt(String key, int defaultValue) {
    String s = param(key);
    return s == null ? defaultValue : Integer.parseInt(s);
  }

  @CheckForNull
  public Long paramAsLong(String key) {
    String s = param(key);
    return s == null ? null : Long.parseLong(s);
  }

  public long paramAsLong(String key, long defaultValue) {
    String s = param(key);
    return s == null ? defaultValue : Long.parseLong(s);
  }

  @CheckForNull
  public Boolean paramAsBoolean(String key) {
    String s = param(key);
    return s == null ? null : Boolean.parseBoolean(s);
  }

  public boolean paramAsBoolean(String key, boolean defaultValue) {
    String s = param(key);
    return s == null ? defaultValue : Boolean.parseBoolean(s);
  }
}
