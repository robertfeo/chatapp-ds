package com.chatapp.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Computes the set of UDP broadcast destinations a datagram should be sent to so it actually leaves
 * the real LAN interface. A single target (e.g. {@code 255.255.255.255}) is unreliable on a host
 * crowded with VPN and virtual adapters, where the OS may pick the wrong interface; sending to
 * every live interface's own broadcast address as well guarantees the LAN one is covered.
 *
 * <p>Used by both discovery (hello announcements) and election (vote/answer/coordinator), so every
 * broadcast in the system fans out the same way.
 */
public final class BroadcastTargets {

  private BroadcastTargets() {}

  /**
   * The configured fallback broadcast address plus each up, non-loopback interface's own broadcast,
   * all on {@code port}.
   */
  public static List<InetSocketAddress> compute(String fallbackBroadcastAddr, int port) {
    Set<InetSocketAddress> targets = new LinkedHashSet<>();
    try {
      targets.add(new InetSocketAddress(InetAddress.getByName(fallbackBroadcastAddr), port));
    } catch (IOException ignored) {
      // a bad configured value must not stop interface-derived targets
    }
    try {
      for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!nif.isUp() || nif.isLoopback()) {
          continue;
        }
        for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
          InetAddress b = ia.getBroadcast();
          if (b != null) {
            targets.add(new InetSocketAddress(b, port));
          }
        }
      }
    } catch (SocketException ignored) {
      // fall back to just the configured target
    }
    return new ArrayList<>(targets);
  }
}
