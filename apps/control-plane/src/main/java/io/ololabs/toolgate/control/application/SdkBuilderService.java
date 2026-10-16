// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import io.ololabs.toolgate.contracts.*;
import io.ololabs.toolgate.control.domain.Ids;
import io.ololabs.toolgate.jsonsdk.JsonProject;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static io.ololabs.toolgate.control.application.BuilderStore.Kind.*;

/** Durable SDK build queue. Authoring, building, importing and invoking are distinct authority boundaries. */
public final class SdkBuilderService {
    private final Store store;
    private final Codec codec;
    private final Ids.TenantId tenant;
    private final Clock clock;
    private final SdkBuildWorker worker;
    private final BuilderArtifacts artifacts;

    public SdkBuilderService(Store store, Codec codec, String tenant, Clock clock, SdkBuildWorker worker, BuilderArtifacts artifacts) {
        this.store = store; this.codec = codec; this.tenant = new Ids.TenantId(tenant); this.clock = clock;
        this.worker = worker; this.artifacts = artifacts;
    }
    private void actor(DirectoryService.Actor actor) {
        actor.requireAdmin(); if (!actor.tenant().equals(tenant)) throw ManagementAccess.denied();
    }
    private long now(Store.Session tx) {
        long now = clock.millis();
        if (now < 0 || now > 9007199254740991L - 600000 || tx.approvalClock(now) > now) throw Failure.unavailable();
        return now;
    }
    private void permission(Store.Session tx, String user, String action, String group) {
        var entry = tx.load().entries().get(Ids.Kind.TOOL_GROUP.id(group));
        if (entry == null || !entry.enabled()) throw ManagementAccess.denied();
        new ManagementAccess(codec).require(tx.load(), user, "tool_builder." + action, Ids.Kind.TOOL_GROUP, group, clock.millis());
    }
    private boolean visible(Store.Session tx, String user, String group) {
        try { permission(tx, user, "read", group); return true; }
        catch (Failure denied) { if (denied.status() != 403) throw denied; return false; }
    }
    private void author(Store.Session tx, String user, String group) {
        permission(tx, user, "read", group);
        permission(tx, user, "build", group);
    }
    private Store.Reply reply(Object document, long revision) { return new Store.Reply(200, codec.json(document), revision); }
    private SdkBuilderDraft draft(Store.Session tx, String id) {
        var row = tx.builder().get(DRAFT, Ids.valid(id));
        if (row == null) throw new Failure(ErrorCode.NOT_FOUND, 404, "SDK project not found");
        return codec.model(row.document(), SdkBuilderDraft.class);
    }
    private SdkBuilderBuild build(Store.Session tx, String id) {
        var row = tx.builder().get(TEST, Ids.valid(id));
        if (row == null) throw new Failure(ErrorCode.NOT_FOUND, 404, "SDK build not found");
        return codec.model(row.document(), SdkBuilderBuild.class);
    }
    private String source(String source) {
        try {
            String canonical = new String(JsonProject.parse(source.getBytes(StandardCharsets.UTF_8)).source(), StandardCharsets.UTF_8);
            if (canonical.length() > 32768) throw Failure.validation();
            return canonical;
        } catch (java.io.IOException | IllegalArgumentException invalid) { throw Failure.validation(); }
    }
    private String document(Object value) {
        String document = codec.json(value);
        if (document.getBytes(StandardCharsets.UTF_8).length > 65536) throw Failure.validation();
        return document;
    }
    public Store.Reply status(DirectoryService.Actor actor) {
        actor(actor);
        return store.transaction(tenant, false, tx -> {
            var access = new ManagementAccess(codec); long now = clock.millis();
            if (!access.capabilities(tx.load(), actor.userId(), now).contains("tool_builder.read")) throw ManagementAccess.denied();
            var groups = tx.load().entries().values().stream().filter(entry -> entry.id().kind() == Ids.Kind.TOOL_GROUP && entry.enabled())
                    .filter(entry -> visible(tx, actor.userId(), entry.id().value())).map(entry -> {
                        var group = codec.model(entry.document(), ControlToolGroup.class);
                        return new SdkBuilderGroup(group.id(), group.name(), access.allowed(tx.load(), actor.userId(), "tool_builder.build", Ids.Kind.TOOL_GROUP, group.id(), now));
                    }).sorted(java.util.Comparator.comparing(SdkBuilderGroup::name).thenComparing(SdkBuilderGroup::id)).toList();
            return reply(new SdkBuilderStatus(JsonProject.sdkVersion(), worker != null && artifacts != null, 32768L, groups), 0);
        });
    }
    public Store.Reply drafts(DirectoryService.Actor actor, String cursor) {
        actor(actor); if (cursor != null) Ids.valid(cursor);
        return store.transaction(tenant, false, tx -> {
            var rows = tx.builder().page(DRAFT, cursor == null ? "" : cursor, 17);
            var items = rows.stream().limit(16).map(r -> codec.model(r.document(), SdkBuilderDraft.class))
                    .filter(d -> visible(tx, actor.userId(), d.toolGroupId())).toList();
            return reply(new SdkBuilderDraftPage(items, rows.size() > 16 ? rows.get(15).id() : null), 0);
        });
    }
    public Store.Reply save(DirectoryService.Actor actor, String body, String key, String requestId) {
        actor(actor); Ids.valid(key); Ids.valid(requestId);
        var request = codec.model(body, SdkBuilderSave.class); String source = source(request.source());
        String digest = DirectoryService.digest("sdk-builder.save\n" + body);
        return store.transaction(tenant, true, tx -> {
            now(tx); author(tx, actor.userId(), request.toolGroupId());
            var existing = tx.builder().get(DRAFT, request.id());
            if (existing != null) author(tx, actor.userId(), codec.model(existing.document(), SdkBuilderDraft.class).toolGroupId());
            var replay = tx.replay(actor.id(), key, digest); if (replay != null) return replay;
            if (existing == null && tx.builder().count(DRAFT) >= 128) throw Failure.conflict();
            var result = new SdkBuilderDraft(request.id(), request.expectedRevision() + 1, request.toolGroupId(), source, "sha256:" + DirectoryService.digest(source));
            tx.builder().save(DRAFT, new BuilderStore.Row(result.id(), result.revision(), document(result)), request.expectedRevision());
            tx.audit(actor.id(), "BUILDER_SAVE", result.id(), result.revision(), requestId, digest);
            var response = reply(result, result.revision()); tx.remember(actor.id(), key, digest, response); return response;
        });
    }
    public Store.Reply queue(DirectoryService.Actor actor, String body, String key, String requestId) {
        actor(actor); Ids.valid(key); Ids.valid(requestId);
        var request = codec.model(body, SdkBuilderBuildRequest.class);
        String digest = DirectoryService.digest("sdk-builder.build\n" + body);
        return store.transaction(tenant, true, tx -> {
            long now = now(tx); var draft = draft(tx, request.draftId()); author(tx, actor.userId(), draft.toolGroupId());
            var replay = tx.replay(actor.id(), key, digest); if (replay != null) return replay;
            if (worker == null || artifacts == null) throw Failure.unavailable();
            if (!draft.revision().equals(request.expectedRevision())) throw Failure.conflict();
            tx.builder().prune(Math.max(0, now - 604800000));
            if (tx.builder().count(TEST) >= 256) throw Failure.conflict();
            var result = new SdkBuilderBuild(request.id(), draft.id(), draft.revision(), draft.toolGroupId(), draft.source(), draft.sourceDigest(),
                    actor.userId(), SdkBuilderState.QUEUED, 1L, now, now + 300000, UUID.randomUUID().toString(), null, null, null, null);
            tx.builder().save(TEST, new BuilderStore.Row(result.id(), 1, document(result)), 0);
            tx.audit(actor.id(), "BUILDER_TEST", result.id(), 1, requestId, digest);
            var response = reply(result, 1); tx.remember(actor.id(), key, digest, response); return response;
        });
    }
    public Store.Reply builds(DirectoryService.Actor actor, String cursor) {
        actor(actor); if (cursor != null) Ids.valid(cursor);
        return store.transaction(tenant, false, tx -> {
            var rows = tx.builder().page(TEST, cursor == null ? "" : cursor, 17);
            var items = rows.stream().limit(16).map(r -> codec.model(r.document(), SdkBuilderBuild.class))
                    .filter(b -> visible(tx, actor.userId(), b.toolGroupId())).toList();
            return reply(new SdkBuilderBuildPage(items, rows.size() > 16 ? rows.get(15).id() : null), 0);
        });
    }
    private void save(Store.Session tx, SdkBuilderBuild previous, SdkBuilderBuild next) {
        tx.builder().save(TEST, new BuilderStore.Row(next.id(), next.revision(), document(next)), previous.revision());
    }
    private SdkBuilderBuild transition(SdkBuilderBuild b, SdkBuilderState state, String artifact, String sourceArchive, String packageDigest, SdkBuilderError error) {
        return new SdkBuilderBuild(b.id(), b.draftId(), b.draftRevision(), b.toolGroupId(), b.source(), b.sourceDigest(), b.creatorUserId(), state,
                b.revision() + 1, b.createdAtUnixMs(), b.expiresAtUnixMs(), b.leaseId(), artifact, sourceArchive, packageDigest, error);
    }
    /** Called by a lifecycle-owned worker loop. Claim/finalize are transactional; compilation never holds a DB transaction. */
    public boolean workOne() {
        if (worker == null || artifacts == null) return false;
        var claimed = store.transaction(tenant, true, tx -> {
            long now = now(tx); String cursor = "";
            for (int page = 0; page < 8; page++) {
                var rows = tx.builder().page(TEST, cursor, 32);
                for (var row : rows) {
                    var job = codec.model(row.document(), SdkBuilderBuild.class);
                    if (job.state() != SdkBuilderState.QUEUED && job.state() != SdkBuilderState.RUNNING) continue;
                    if (job.expiresAtUnixMs() <= now) { save(tx, job, transition(job, SdkBuilderState.FAILED, null, null, null, SdkBuilderError.EXPIRED)); continue; }
                    if (job.state() == SdkBuilderState.RUNNING) continue;
                    try { author(tx, job.creatorUserId(), job.toolGroupId()); }
                    catch (Failure denied) {
                        if (denied.status() != 403) throw denied;
                        save(tx, job, transition(job, SdkBuilderState.FAILED, null, null, null, SdkBuilderError.AUTHORITY_REVOKED)); continue;
                    }
                    if (!job.sourceDigest().equals("sha256:" + DirectoryService.digest(job.source()))) {
                        save(tx, job, transition(job, SdkBuilderState.FAILED, null, null, null, SdkBuilderError.BUILD_FAILED)); continue;
                    }
                    var running = transition(job, SdkBuilderState.RUNNING, null, null, null, null); save(tx, job, running);
                    tx.audit(DirectoryService.digest(job.creatorUserId()), "BUILDER_LEASE", job.id(), running.revision(), job.leaseId(), DirectoryService.digest(job.source()));
                    return running;
                }
                if (rows.size() < 32) break; cursor = rows.getLast().id();
            }
            return null;
        });
        if (claimed == null) return false;
        SdkBuildWorker.Outputs outputs = null;
        try { outputs = worker.build(claimed.source().getBytes(StandardCharsets.UTF_8)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (java.io.IOException | RuntimeException rejected) { /* Only a stable failure code crosses the worker boundary. */ }
        var completed = outputs;
        store.transaction(tenant, true, tx -> {
            var current = build(tx, claimed.id()); long now = now(tx);
            if (current.state() != SdkBuilderState.RUNNING || !current.revision().equals(claimed.revision()) || !current.leaseId().equals(claimed.leaseId())) return null;
            SdkBuilderBuild result;
            try {
                author(tx, current.creatorUserId(), current.toolGroupId());
                if (now >= current.expiresAtUnixMs()) result = transition(current, SdkBuilderState.FAILED, null, null, null, SdkBuilderError.EXPIRED);
                else if (completed == null) result = transition(current, SdkBuilderState.FAILED, null, null, null, SdkBuilderError.BUILD_FAILED);
                else {
                    var verified = PackageArchive.validate(completed.packageBytes(), value -> (com.fasterxml.jackson.databind.JsonNode) codec.value(value));
                    var project = JsonProject.parse(current.source().getBytes(StandardCharsets.UTF_8));
                    var expected = new java.util.TreeMap<String, String>();
                    for (var tool : project.tools()) expected.put(project.id() + "/" + tool.name(), tool.kind());
                    var actual = new java.util.TreeMap<String, String>();
                    for (var tool : verified.manifest().path("tools")) actual.put(tool.path("descriptor").path("toolId").asText(), tool.path("descriptor").path("kind").asText());
                    if (!project.id().equals(verified.manifest().path("id").asText()) || !project.version().equals(verified.manifest().path("version").asText())
                            || !JsonProject.sdkVersion().equals(verified.manifest().path("sdk").path("version").asText()) || !expected.equals(actual)
                            || !java.util.Arrays.equals(completed.sourceArchive(), io.ololabs.toolgate.jsonsdk.JsonSdk.sourceArchive(current.source().getBytes(StandardCharsets.UTF_8))))
                        throw new java.io.IOException("SDK outputs do not match their project");
                    String artifact = artifacts.put(completed.packageBytes()), sourceArchive = artifacts.put(completed.sourceArchive());
                    var manifest = verified.manifest(); String versionId = DirectoryService.digest(manifest.path("id").asText() + "\n" + manifest.path("version").asText());
                    var sealed = tx.builder().get(VERSION, versionId);
                    String immutable = codec.json(java.util.Map.of("artifactDigest", artifact, "sourceArchiveDigest", sourceArchive, "sourceDigest", current.sourceDigest(), "packageDigest", verified.packageDigest()));
                    if (sealed != null && !codec.value(sealed.document()).equals(codec.value(immutable))) result = transition(current, SdkBuilderState.FAILED, null, null, null, SdkBuilderError.VERSION_CONFLICT);
                    else {
                        if (sealed == null) tx.builder().save(VERSION, new BuilderStore.Row(versionId, 1, immutable), 0);
                        result = transition(current, SdkBuilderState.READY, artifact, sourceArchive, verified.packageDigest(), null);
                    }
                }
            } catch (Failure denied) {
                if (denied.status() != 403 && denied.status() != 400 && denied.status() != 422) throw denied;
                result = transition(current, SdkBuilderState.FAILED, null, null, null, denied.status() == 403 ? SdkBuilderError.AUTHORITY_REVOKED : SdkBuilderError.BUILD_FAILED);
            } catch (java.io.IOException invalid) { result = transition(current, SdkBuilderState.FAILED, null, null, null, SdkBuilderError.BUILD_FAILED); }
            save(tx, current, result);
            tx.audit(DirectoryService.digest(current.creatorUserId()), "BUILDER_RESULT", current.id(), result.revision(), current.leaseId(), DirectoryService.digest(codec.json(result)));
            return null;
        });
        return true;
    }
    public byte[] download(DirectoryService.Actor actor, String id, boolean source) {
        actor(actor);
        String digest = store.transaction(tenant, false, tx -> {
            var build = build(tx, id); permission(tx, actor.userId(), "read", build.toolGroupId());
            if (build.state() != SdkBuilderState.READY) throw Failure.conflict();
            return source ? build.sourceArchiveDigest() : build.artifactDigest();
        });
        if (artifacts == null) throw Failure.unavailable();
        try { return artifacts.read(digest); }
        catch (java.io.IOException invalid) { throw Failure.unavailable(); }
    }
}
