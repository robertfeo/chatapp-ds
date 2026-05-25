package com.chatapp.election;

import java.util.NoSuchElementException;
import java.util.Set;

/** Pure, stateless election logic: given a set of alive server IDs, return the winner. */
public final class Election {

  private Election() {}

  /**
   * Deterministically picks the leader from a set of alive server IDs using highest-ID-wins.
   *
   * @throws NoSuchElementException if {@code aliveIds} is empty
   */
  public static int pickLeader(Set<Integer> aliveIds) {
    return aliveIds.stream()
        .mapToInt(Integer::intValue)
        .max()
        .orElseThrow(() -> new NoSuchElementException("cannot elect from an empty set"));
  }
}
