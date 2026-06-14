package com.chatapp.election;

import java.util.Set;

/** Pure, stateless election logic: given a set of alive server IDs, return the winner. */
public final class Election {

  private Election() {}

  /**
   * Deterministically picks the leader from a set of alive server IDs using highest-ID-wins.
   *
   * @throws IllegalStateException if {@code aliveIds} is empty
   */
  public static int pickLeader(Set<Integer> aliveIds) {
    return aliveIds.stream()
        .mapToInt(Integer::intValue)
        .max()
        .orElseThrow(
            () -> new IllegalStateException("cannot elect a leader from an empty set of ids"));
  }
}
