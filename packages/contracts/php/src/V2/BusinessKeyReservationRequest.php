<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** reserveBusinessOperation input, carried on POST /access/invocations. */
final readonly class BusinessKeyReservationRequest implements \JsonSerializable {
    public function __construct(
        public string $toolId,
        public KeyNamespace $namespace,
        public string $businessKeyHash,
        public BusinessKeySource $source,
        public string $fingerprint,
        public CallerIdentity $caller,
        public ?string $namespaceIdentity = null,
        public ?int $dedupeWindowMs = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['toolId', 'namespace', 'namespaceIdentity', 'businessKeyHash', 'source', 'fingerprint', 'caller', 'dedupeWindowMs']) || array_diff(['toolId', 'namespace', 'businessKeyHash', 'source', 'fingerprint', 'caller'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['toolId'],
            KeyNamespace::from($data['namespace']),
            $data['businessKeyHash'],
            BusinessKeySource::from($data['source']),
            $data['fingerprint'],
            CallerIdentity::fromArray($data['caller']),
            array_key_exists('namespaceIdentity', $data) ? $data['namespaceIdentity'] : null,
            array_key_exists('dedupeWindowMs', $data) ? $data['dedupeWindowMs'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
