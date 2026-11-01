<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Executor request for downstream authorization; scope comes exclusively from the admitted invocation. */
final readonly class ServicePermitIssue implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $fencingToken,
        public ServiceAudience $audience,
        public ?string $httpRequestDigest = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'fencingToken', 'audience', 'httpRequestDigest']) || array_diff(['invocationId', 'fencingToken', 'audience'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['fencingToken'],
            ServiceAudience::from($data['audience']),
            array_key_exists('httpRequestDigest', $data) ? $data['httpRequestDigest'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
