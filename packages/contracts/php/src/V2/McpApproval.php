<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** An accepted approval: one per approval sequence, with its generated package. */
final readonly class McpApproval implements \JsonSerializable {
    public function __construct(
        public int $sequence,
        public int $snapshotRevision,
        public array $tools,
        public array $spkiPins,
        public string $packageId,
        public string $packageVersion,
        public string $packageDigest,
        public int $approvedAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['sequence', 'snapshotRevision', 'tools', 'spkiPins', 'packageId', 'packageVersion', 'packageDigest', 'approvedAtUnixMs']) || array_diff(['sequence', 'snapshotRevision', 'tools', 'spkiPins', 'packageId', 'packageVersion', 'packageDigest', 'approvedAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['sequence'],
            $data['snapshotRevision'],
            array_map(static fn ($item) => McpApprovedTool::fromArray($item), $data['tools']),
            array_map(static fn ($item) => $item, $data['spkiPins']),
            $data['packageId'],
            $data['packageVersion'],
            $data['packageDigest'],
            $data['approvedAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
