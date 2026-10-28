<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A recorded quarantine and the evidence that caused it. */
final readonly class McpQuarantine implements \JsonSerializable {
    public function __construct(
        public McpQuarantineReason $reason,
        public string $evidenceId,
        public array $toolIds,
        public bool $serverWide,
        public int $atUnixMs,
        public int $approvalSequence
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['reason', 'evidenceId', 'toolIds', 'serverWide', 'atUnixMs', 'approvalSequence']) || array_diff(['reason', 'evidenceId', 'toolIds', 'serverWide', 'atUnixMs', 'approvalSequence'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            McpQuarantineReason::from($data['reason']),
            $data['evidenceId'],
            array_map(static fn ($item) => $item, $data['toolIds']),
            $data['serverWide'],
            $data['atUnixMs'],
            $data['approvalSequence']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
