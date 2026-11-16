<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority. */
final readonly class WorkflowStackPlatform implements \JsonSerializable {
    public function __construct(
        public string $quickstartImage,
        public string $deviceImage,
        public int $httpPort,
        public int $tlsPort,
        public int $registryPort
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['quickstartImage', 'deviceImage', 'httpPort', 'tlsPort', 'registryPort']) || array_diff(['quickstartImage', 'deviceImage', 'httpPort', 'tlsPort', 'registryPort'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['quickstartImage'],
            $data['deviceImage'],
            $data['httpPort'],
            $data['tlsPort'],
            $data['registryPort']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
