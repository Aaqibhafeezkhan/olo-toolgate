<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Backend-recorded structural progress. Runtime reports do not attest tool effects or finality. */
final readonly class WorkflowRunProgressEvent implements \JsonSerializable {
    public function __construct(
        public int $sequence,
        public string $occurredAt,
        public WorkflowProgressSource $source,
        public string $kind,
        public ?string $nodeId = null,
        public ?string $subjectId = null,
        public ?string $toolRef = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['sequence', 'occurredAt', 'source', 'kind', 'nodeId', 'subjectId', 'toolRef']) || array_diff(['sequence', 'occurredAt', 'source', 'kind'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['sequence'],
            $data['occurredAt'],
            WorkflowProgressSource::from($data['source']),
            $data['kind'],
            array_key_exists('nodeId', $data) ? $data['nodeId'] : null,
            array_key_exists('subjectId', $data) ? $data['subjectId'] : null,
            array_key_exists('toolRef', $data) ? $data['toolRef'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
