<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Started upload. */
final readonly class TriggerCheckpointUpload implements \JsonSerializable {
    public function __construct(
        public string $uploadId,
        public int $chunkBytes,
        public int $expiresAtUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['uploadId', 'chunkBytes', 'expiresAtUnixMs']) || array_diff(['uploadId', 'chunkBytes', 'expiresAtUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['chunkBytes'] !== 65536) {
            throw new \InvalidArgumentException('chunkBytes must be 65536');
        }
        return new self(
            $data['uploadId'],
            $data['chunkBytes'],
            $data['expiresAtUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
