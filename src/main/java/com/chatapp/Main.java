package com.chatapp;

import com.chatapp.client.ClientMain;
import com.chatapp.server.ServerMain;
import java.util.Arrays;

/**
 * Single entry point for the shaded fat-jar. The first argument selects the role:
 *
 * <pre>{@code
 * java -jar chatapp.jar server   # start a server (configured via env vars)
 * java -jar chatapp.jar client   # start a chat client
 * }</pre>
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) {
    if (args.length == 0) {
      usage();
      System.exit(2);
      return;
    }
    String[] rest = Arrays.copyOfRange(args, 1, args.length);
    switch (args[0]) {
      case "server" -> ServerMain.main(rest);
      case "client" -> ClientMain.main(rest);
      default -> {
        usage();
        System.exit(2);
      }
    }
  }

  private static void usage() {
    System.err.println("usage: java -jar chatapp.jar <server|client>");
  }
}
