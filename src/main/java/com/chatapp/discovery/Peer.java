package com.chatapp.discovery;

/**
 * A discovered server: its unique id and the address other servers and clients use to reach its TCP
 * chat port.
 */
public record Peer(int id, String host, int port) {}
