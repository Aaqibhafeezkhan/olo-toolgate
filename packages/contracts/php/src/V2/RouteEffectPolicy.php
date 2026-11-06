<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Operator-reviewed generic route intent. Financial/destructive and declared consent-required routes require immutable exact-effect approval and atomic slot authority. */
final readonly class RouteEffectPolicy implements \JsonSerializable {
    public function __construct(
        public string $routeId,
        public EffectClassification $classification,
        public bool $consentRequired,
        public bool $acknowledgedUnfencedRisk,
        public EffectIntentBlueprint $intent,
        public ?TargetDedupeGuarantee $targetDedupeGuarantee = null,
        public ?FencedTarget $fencedTarget = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['routeId', 'classification', 'consentRequired', 'acknowledgedUnfencedRisk', 'intent', 'targetDedupeGuarantee', 'fencedTarget']) || array_diff(['routeId', 'classification', 'consentRequired', 'acknowledgedUnfencedRisk', 'intent'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['routeId'],
            EffectClassification::from($data['classification']),
            $data['consentRequired'],
            $data['acknowledgedUnfencedRisk'],
            EffectIntentBlueprint::fromArray($data['intent']),
            array_key_exists('targetDedupeGuarantee', $data) ? TargetDedupeGuarantee::fromArray($data['targetDedupeGuarantee']) : null,
            array_key_exists('fencedTarget', $data) ? FencedTarget::fromArray($data['fencedTarget']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
