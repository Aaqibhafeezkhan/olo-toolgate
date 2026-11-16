// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
package io.ololabs.toolgate.control.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;

/** Maps the verified delivery into the pinned workflow's message input. */
public final class WorkflowDispatchInput {
    private static final int MAX_MESSAGE_BYTES = 32768;

    private WorkflowDispatchInput() {}

    public static String message(Codec codec, WorkflowProfiles.DispatchTarget target, TriggerStream.DispatchEvent delivery) {
        if (!target.binding().sourceAssignmentUid().equals(delivery.assignmentUid())) throw ManagementAccess.denied();
        // The pointer is relative to the event data, so envelope metadata is outside its scope.
        var value = delivery.event().path("data").at(target.binding().messagePointer());
        if (!value.isTextual()) throw Failure.validation();
        String message = value.textValue();
        if (message.isBlank() || message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) throw Failure.validation();

        var schema = (JsonNode) codec.value(codec.json(target.workflow().parameterSchema()));
        var input = JsonNodeFactory.instance.objectNode().put("message", message);
        if (TriggerConfigs.validate(schema, input) != null) throw Failure.validation();
        return message;
    }
}
