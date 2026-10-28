<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** The upstream an MCP_PROXY package proxies: its URL and credential reference, bound by the package digest, and the identity and TLS pins it was approved with. */
final readonly class McpProxyServer implements \JsonSerializable {
    public function __construct(
        public string $slug,
        public string $url,
        public McpUpstreamAuth $auth,
        public McpServerInfo $serverInfo,
        public array $spkiPins
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['slug', 'url', 'auth', 'serverInfo', 'spkiPins']) || array_diff(['slug', 'url', 'auth', 'serverInfo', 'spkiPins'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['slug'],
            $data['url'],
            McpUpstreamAuth::fromArray($data['auth']),
            McpServerInfo::fromArray($data['serverInfo']),
            array_map(static fn ($item) => $item, $data['spkiPins'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
