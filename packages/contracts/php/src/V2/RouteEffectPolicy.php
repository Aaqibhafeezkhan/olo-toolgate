<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Operator-reviewed generic route intent. Consent-required routes remain refused until exact consent authority is implemented. */
final readonly class RouteEffectPolicy implements \JsonSerializable {
    public function __construct(
        public string $routeId,
        public EffectClassification $classification,
        public bool $consentRequired,
        public bool $acknowledgedUnfencedRisk,
        public EffectIntentBlueprint $intent
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['routeId', 'classification', 'consentRequired', 'acknowledgedUnfencedRisk', 'intent']) || array_diff(['routeId', 'classification', 'consentRequired', 'acknowledgedUnfencedRisk', 'intent'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['routeId'],
            EffectClassification::from($data['classification']),
            $data['consentRequired'],
            $data['acknowledgedUnfencedRisk'],
            EffectIntentBlueprint::fromArray($data['intent'])
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
