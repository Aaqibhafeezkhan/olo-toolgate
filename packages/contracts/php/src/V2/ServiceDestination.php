<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Approved destination with its reviewed host. */
final readonly class ServiceDestination implements \JsonSerializable {
    public function __construct(
        public string $id,
        public DestinationScheme $scheme,
        public string $host,
        public int $port,
        public TlsSettings $tls,
        public EgressMode $mode
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'scheme', 'host', 'port', 'tls', 'mode']) || array_diff(['id', 'scheme', 'host', 'port', 'tls', 'mode'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            DestinationScheme::from($data['scheme']),
            $data['host'],
            $data['port'],
            TlsSettings::fromArray($data['tls']),
            EgressMode::from($data['mode'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
