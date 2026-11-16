<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Workflow-scoped operator deployment data; structural configuration confers no runtime authority. */
final readonly class WorkflowStackMonitoring implements \JsonSerializable {
    public function __construct(
        public bool $enabled,
        public string $mode,
        public string $grafanaUser,
        public string $grafanaSecret,
        public int $grafanaPort,
        public int $prometheusPort,
        public \stdClass $collectorConfig,
        public float $spanSampleRatio,
        public WorkflowStackMonitoringImages $images
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['enabled', 'mode', 'grafanaUser', 'grafanaSecret', 'grafanaPort', 'prometheusPort', 'collectorConfig', 'spanSampleRatio', 'images']) || array_diff(['enabled', 'mode', 'grafanaUser', 'grafanaSecret', 'grafanaPort', 'prometheusPort', 'collectorConfig', 'spanSampleRatio', 'images'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['enabled'],
            $data['mode'],
            $data['grafanaUser'],
            $data['grafanaSecret'],
            $data['grafanaPort'],
            $data['prometheusPort'],
            (object) $data['collectorConfig'],
            $data['spanSampleRatio'],
            WorkflowStackMonitoringImages::fromArray($data['images'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
