<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Declared HTTP route, without host values. */
final readonly class RouteDeclaration implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $destination,
        public HttpMethod $method,
        public string $path,
        public array $query,
        public array $headers,
        public array $requestContentTypes,
        public int $maxRequestBytes,
        public int $maxResponseBytes,
        public Streaming $streaming,
        public int $timeoutMs,
        public ?string $auth = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'destination', 'method', 'path', 'query', 'headers', 'requestContentTypes', 'maxRequestBytes', 'maxResponseBytes', 'streaming', 'timeoutMs', 'auth']) || array_diff(['id', 'destination', 'method', 'path', 'query', 'headers', 'requestContentTypes', 'maxRequestBytes', 'maxResponseBytes', 'streaming', 'timeoutMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['destination'],
            HttpMethod::from($data['method']),
            $data['path'],
            array_map(static fn ($item) => $item, $data['query']),
            array_map(static fn ($item) => $item, $data['headers']),
            array_map(static fn ($item) => $item, $data['requestContentTypes']),
            $data['maxRequestBytes'],
            $data['maxResponseBytes'],
            Streaming::from($data['streaming']),
            $data['timeoutMs'],
            array_key_exists('auth', $data) ? $data['auth'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
