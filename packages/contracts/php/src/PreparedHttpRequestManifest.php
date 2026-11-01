<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Credential-free toolgate-http-request-v1 manifest of the exact prepared wire request. Never conveys authority on its own. */
final readonly class PreparedHttpRequestManifest implements \JsonSerializable {
    public function __construct(
        public string $version,
        public string $profileId,
        public string $profileDigest,
        public string $routeId,
        public string $scheme,
        public string $host,
        public int $port,
        public string $method,
        public string $path,
        public string $query,
        public string $contentType,
        public string $bodyDigest,
        public \stdClass $headers,
        public mixed $authDeclarationId,
        public array $credentialHeaders
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['version', 'profileId', 'profileDigest', 'routeId', 'scheme', 'host', 'port', 'method', 'path', 'query', 'contentType', 'bodyDigest', 'headers', 'authDeclarationId', 'credentialHeaders']) || array_diff(['version', 'profileId', 'profileDigest', 'routeId', 'scheme', 'host', 'port', 'method', 'path', 'query', 'contentType', 'bodyDigest', 'headers', 'authDeclarationId', 'credentialHeaders'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['version'],
            $data['profileId'],
            $data['profileDigest'],
            $data['routeId'],
            $data['scheme'],
            $data['host'],
            $data['port'],
            $data['method'],
            $data['path'],
            $data['query'],
            $data['contentType'],
            $data['bodyDigest'],
            (object) $data['headers'],
            $data['authDeclarationId'],
            array_map(static fn ($item) => $item, $data['credentialHeaders'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
