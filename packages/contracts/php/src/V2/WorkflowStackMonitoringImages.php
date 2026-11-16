<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Explicit workflow monitoring service images; credentials remain external custody references. */
final readonly class WorkflowStackMonitoringImages implements \JsonSerializable {
    public function __construct(
        public string $collector,
        public string $prometheus,
        public string $grafana,
        public string $tempo
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['collector', 'prometheus', 'grafana', 'tempo']) || array_diff(['collector', 'prometheus', 'grafana', 'tempo'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['collector'],
            $data['prometheus'],
            $data['grafana'],
            $data['tempo']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
