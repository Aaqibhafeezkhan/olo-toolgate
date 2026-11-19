<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Durable acceptance receipt; it is not evidence of execution success. */
final readonly class WorkflowRunStartReceipt implements \JsonSerializable {
    public function __construct(
        public string $runId,
        public string $profileId,
        public string $profileDigest,
        public string $workflowId,
        public string $state
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['runId', 'profileId', 'profileDigest', 'workflowId', 'state']) || array_diff(['runId', 'profileId', 'profileDigest', 'workflowId', 'state'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['state'] !== 'QUEUED') {
            throw new \InvalidArgumentException('state must be QUEUED');
        }
        return new self(
            $data['runId'],
            $data['profileId'],
            $data['profileDigest'],
            $data['workflowId'],
            $data['state']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
