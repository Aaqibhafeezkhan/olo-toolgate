<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Durable admission evidence bound to this subscription UID and delivered cursor. ADMITTED requires runId; DUPLICATE returns its original durable outcome reference and runId when one exists. Terminal rejection/filtering is recorded, never inferred locally. */
final readonly class WorkflowDispatchReceipt implements \JsonSerializable {
    public function __construct(
        public string $subscriptionUid,
        public string $cursor,
        public string $admissionId,
        public WorkflowDispatchDisposition $disposition,
        public ?string $runId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['subscriptionUid', 'cursor', 'admissionId', 'disposition', 'runId']) || array_diff(['subscriptionUid', 'cursor', 'admissionId', 'disposition'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['subscriptionUid'],
            $data['cursor'],
            $data['admissionId'],
            WorkflowDispatchDisposition::from($data['disposition']),
            array_key_exists('runId', $data) ? $data['runId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
