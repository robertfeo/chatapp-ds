package com.chatapp.election;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ElectionTest {

  @Test
  void singleSurvivorElectsItself() {
    assertEquals(5, Election.pickLeader(Set.of(5)));
  }

  @Test
  void highestIdWinsAmongThree() {
    assertEquals(3, Election.pickLeader(Set.of(1, 2, 3)));
  }

  @Test
  void highestIdWinsAmongTwo() {
    assertEquals(2, Election.pickLeader(Set.of(1, 2)));
  }

  @Test
  void emptySetThrows() {
    assertThrows(IllegalStateException.class, () -> Election.pickLeader(Set.of()));
  }

  @Test
  void resultIsDeterministicForSameInput() {
    Set<Integer> ids = Set.of(7, 3, 11, 2, 9);
    int first = Election.pickLeader(ids);
    int second = Election.pickLeader(ids);
    assertEquals(first, second, "election must be deterministic");
    assertEquals(11, first);
  }

  @Test
  void largeIdSetAlwaysPicksMax() {
    Set<Integer> ids = Set.of(100, 50, 200, 1, 150);
    assertEquals(200, Election.pickLeader(ids));
  }

  /** Parameterized: after removing the current leader, the next highest ID wins. */
  @ParameterizedTest
  @MethodSource("failoverCases")
  void leaderFailoverPicksNextHighest(FailoverCase tc) {
    assertEquals(tc.expected(), Election.pickLeader(tc.survivors()));
  }

  static java.util.stream.Stream<FailoverCase> failoverCases() {
    return java.util.stream.Stream.of(
        new FailoverCase(Set.of(1, 2), 2), // leader 3 died, ID=2 takes over
        new FailoverCase(Set.of(1), 1), // leader 2 died, ID=1 takes over
        new FailoverCase(Set.of(2, 3), 3) // leader 4 died, ID=3 takes over
        );
  }

  record FailoverCase(Set<Integer> survivors, int expected) {}
}
