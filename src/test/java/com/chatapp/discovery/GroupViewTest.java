package com.chatapp.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class GroupViewTest {

  @Test
  void firstUpsertIsNewSubsequentIsNot() {
    GroupView view = new GroupView();
    assertTrue(view.upsert(new Peer(1, "10.0.0.1", 6001)), "first sighting is new");
    assertFalse(view.upsert(new Peer(1, "10.0.0.1", 6001)), "same peer again is not new");
    assertEquals(1, view.size(), "a repeated peer must not duplicate");
  }

  @Test
  void refreshingAPeerKeepsItSingleButUpdatesAddress() {
    GroupView view = new GroupView();
    view.upsert(new Peer(2, "10.0.0.2", 6002));
    assertFalse(view.upsert(new Peer(2, "10.0.0.99", 7002)), "same id is a refresh, not new");
    assertEquals(1, view.size());
    assertTrue(view.snapshot().contains(new Peer(2, "10.0.0.99", 7002)), "address is refreshed");
  }

  @Test
  void tracksMultiplePeersWithSortedIds() {
    GroupView view = new GroupView();
    view.upsert(new Peer(3, "h", 1));
    view.upsert(new Peer(1, "h", 2));
    view.upsert(new Peer(2, "h", 3));
    assertEquals(Set.of(1, 2, 3), view.ids());
    assertTrue(view.contains(2));
  }

  @Test
  void removeDropsThePeer() {
    GroupView view = new GroupView();
    view.upsert(new Peer(1, "h", 1));
    view.remove(1);
    assertFalse(view.contains(1));
    assertEquals(0, view.size());
  }
}
