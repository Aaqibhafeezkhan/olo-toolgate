<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Pinned package build and fenced worker lease; artifacts confer no runtime authority. */
final readonly class SdkBuilderBuild implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $draftId,
        public int $draftRevision,
        public string $toolGroupId,
        public string $source,
        public string $sourceDigest,
        public string $creatorUserId,
        public SdkBuilderState $state,
        public int $revision,
        public int $createdAtUnixMs,
        public int $expiresAtUnixMs,
        public string $leaseId,
        public ?string $artifactDigest = null,
        public ?string $sourceArchiveDigest = null,
        public ?string $packageDigest = null,
        public ?SdkBuilderError $error = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'draftId', 'draftRevision', 'toolGroupId', 'source', 'sourceDigest', 'creatorUserId', 'state', 'revision', 'createdAtUnixMs', 'expiresAtUnixMs', 'leaseId', 'artifactDigest', 'sourceArchiveDigest', 'packageDigest', 'error']) || array_diff(['id', 'draftId', 'draftRevision', 'toolGroupId', 'source', 'sourceDigest', 'creatorUserId', 'state', 'revision', 'createdAtUnixMs', 'expiresAtUnixMs', 'leaseId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['draftId'],
            $data['draftRevision'],
            $data['toolGroupId'],
            $data['source'],
            $data['sourceDigest'],
            $data['creatorUserId'],
            SdkBuilderState::from($data['state']),
            $data['revision'],
            $data['createdAtUnixMs'],
            $data['expiresAtUnixMs'],
            $data['leaseId'],
            array_key_exists('artifactDigest', $data) ? $data['artifactDigest'] : null,
            array_key_exists('sourceArchiveDigest', $data) ? $data['sourceArchiveDigest'] : null,
            array_key_exists('packageDigest', $data) ? $data['packageDigest'] : null,
            array_key_exists('error', $data) ? SdkBuilderError::from($data['error']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
