<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Upstream credential for scans and calls. The secret is a tenant vault entry whose Tool Group and Device Group equal the registration binding. */
final readonly class McpUpstreamAuth implements \JsonSerializable {
    public function __construct(
        public string $secretName,
        public McpAuthScheme $scheme,
        public ?string $header = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['secretName', 'scheme', 'header']) || array_diff(['secretName', 'scheme'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['secretName'],
            McpAuthScheme::from($data['scheme']),
            array_key_exists('header', $data) ? $data['header'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
