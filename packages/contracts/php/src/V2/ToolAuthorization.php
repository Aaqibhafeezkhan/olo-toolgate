<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** The tool's single permission and the resources it acts on (OLO M3.7). Absent: action invoke on tool://{toolId}. */
final readonly class ToolAuthorization implements \JsonSerializable {
    public function __construct(
        public string $action,
        public array $resources
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['action', 'resources']) || array_diff(['action', 'resources'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['action'],
            array_map(static fn ($item) => $item, $data['resources'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
