// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

/** Tenant-transactional MCP proxy state (docs/planning/mcp-proxy/plan.md §4.2): registrations, their immutable scan
 *  snapshots and approvals, and scan jobs. Every row belongs to the session's tenant. */
public interface McpServerStore {
    /** document is the McpServerRegistration. */
    record Server(String slug,long revision,String document) {}
    Server server(String slug);
    java.util.List<Server> servers(int limit);
    long count();
    /** Inserts revision 1, or replaces exactly the previous revision. */
    void saveServer(Server server);
    void deleteServer(String slug);

    /** document is the McpSnapshot. */
    record Snapshot(String slug,long revision,String document) {}
    Snapshot snapshot(String slug,long revision);
    /** Revisions of one registration, newest first. */
    java.util.List<Long> snapshotRevisions(String slug);
    void saveSnapshot(Snapshot snapshot);
    void deleteSnapshot(String slug,long revision);

    /** document is the McpApproval. */
    record Approval(String slug,long sequence,String document) {}
    Approval approval(String slug,long sequence);
    /** Newest first. */
    java.util.List<Approval> approvals(String slug,int limit);
    void saveApproval(Approval approval);

    /** document holds the McpScanJob and its lease as {job, lease}. */
    record Scan(String scanId,String slug,String hostId,String state,long deadline,long createdAt,String document) {}
    Scan scan(String scanId);
    /** The oldest queued, unexpired scan for this host. */
    Scan nextScan(String hostId,long now);
    /** Scans of one registration in these states. */
    java.util.List<Scan> scans(String slug,java.util.Set<String> states);
    /** Unfinished scans across the tenant (QUEUED, CLAIMED, ADMITTED). */
    java.util.List<Scan> open(int limit);
    /** Inserts a scan or replaces its state and document. */
    void saveScan(Scan scan);
    /** Removes finished scans whose deadline passed before `before`. */
    void prune(long before);
}
