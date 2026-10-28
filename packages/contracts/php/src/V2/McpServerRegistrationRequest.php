<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Registers an upstream MCP server in the routed tenant. The pool is the binding Device Group. */
final readonly class McpServerRegistrationRequest implements \JsonSerializable {
    public function __construct(
        public string $slug,
        public string $url,
        public McpUpstreamAuth $auth,
        public string $bindingId,
        public bool $strict
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['slug', 'url', 'auth', 'bindingId', 'strict']) || array_diff(['slug', 'url', 'auth', 'bindingId', 'strict'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['slug'],
            $data['url'],
            McpUpstreamAuth::fromArray($data['auth']),
            $data['bindingId'],
            $data['strict']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
