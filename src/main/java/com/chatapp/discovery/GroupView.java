package com.chatapp.discovery;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The local view of the server group: which servers this process currently knows about, keyed by
 * id. Thread-safe, because discovery updates it from a receiver thread while other threads read it.
 *
 * <p>Updates are idempotent: learning about the same peer twice does not create a duplicate entry.
 */
public final class GroupView {

  private final Map<Integer, Peer> peers = new ConcurrentHashMap<>();

  /**
   * Add or refresh a peer.
   *
   * @return {@code true} if this peer id was not known before (a genuinely new discovery)
   */
  public boolean upsert(Peer peer) {
    return peers.put(peer.id(), peer) == null;
  }

  /** Remove a peer by id, e.g. when it is declared dead. */
  public void remove(int id) {
    peers.remove(id);
  }

  public boolean contains(int id) {
    return peers.containsKey(id);
  }

  public int size() {
    return peers.size();
  }

  /** The known ids, sorted, for stable logging and comparison. */
  public Set<Integer> ids() {
    return new TreeSet<>(peers.keySet());
  }

  /** An immutable snapshot of the current peers. */
  public Set<Peer> snapshot() {
    return Set.copyOf(peers.values());
  }
}
