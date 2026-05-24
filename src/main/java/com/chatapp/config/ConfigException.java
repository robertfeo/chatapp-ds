package com.chatapp.config;

/** Thrown when required configuration is missing or invalid, so the process can fail fast. */
public class ConfigException extends Exception {

  public ConfigException(String message) {
    super(message);
  }
}
