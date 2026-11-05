<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Control-reconstructed outbound business request. Contains resolved mapped values, never credentials. Native MCP excludes handler ID and opaque session/protocol headers; these remain bound by the execution grant. */
final readonly class EffectConsentMappedRequest implements \JsonSerializable {
    public function __construct(
        public EffectConsentMappingKind $kind,
        public string $routeId,
        public EffectClassification $classification,
        public string $scheme,
        public string $host,
        public int $port,
        public string $method,
        public string $path,
        public string $query,
        public \stdClass $headers,
        public string $contentType,
        public EffectBodyMode $bodyMode,
        public mixed $body
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'routeId', 'classification', 'scheme', 'host', 'port', 'method', 'path', 'query', 'headers', 'contentType', 'bodyMode', 'body']) || array_diff(['kind', 'routeId', 'classification', 'scheme', 'host', 'port', 'method', 'path', 'query', 'headers', 'contentType', 'bodyMode', 'body'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['scheme'] !== 'https') {
            throw new \InvalidArgumentException('scheme must be https');
        }
        return new self(
            EffectConsentMappingKind::from($data['kind']),
            $data['routeId'],
            EffectClassification::from($data['classification']),
            $data['scheme'],
            $data['host'],
            $data['port'],
            $data['method'],
            $data['path'],
            $data['query'],
            (object) $data['headers'],
            $data['contentType'],
            EffectBodyMode::from($data['bodyMode']),
            $data['body']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v, $k) => $v !== null || in_array($k, ['body'], true), ARRAY_FILTER_USE_BOTH);
    }
}
