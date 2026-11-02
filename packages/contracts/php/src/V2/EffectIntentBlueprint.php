<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Reviewed request blueprint. Values are constants or closed $arg JSON-pointer references; resolved argument data is never interpreted as a template. */
final readonly class EffectIntentBlueprint implements \JsonSerializable {
    public function __construct(
        public mixed $path,
        public \stdClass $query,
        public \stdClass $headers,
        public string $contentType,
        public EffectBodyMode $bodyMode,
        public mixed $body
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['path', 'query', 'headers', 'contentType', 'bodyMode', 'body']) || array_diff(['path', 'query', 'headers', 'contentType', 'bodyMode', 'body'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['path'],
            (object) $data['query'],
            (object) $data['headers'],
            $data['contentType'],
            EffectBodyMode::from($data['bodyMode']),
            $data['body']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v, $k) => $v !== null || in_array($k, ['path', 'body'], true), ARRAY_FILTER_USE_BOTH);
    }
}
