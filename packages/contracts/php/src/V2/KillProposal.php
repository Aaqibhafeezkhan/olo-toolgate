<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Immutable request and append-only reviews; no execution authority. */
final readonly class KillProposal implements \JsonSerializable {
    public function __construct(
        public string $id,
        public string $requesterUserId,
        public KillProposalKind $kind,
        public KillProposalState $state,
        public int $revision,
        public int $authorizationEpoch,
        public int $createdAtUnixMs,
        public int $expiresAtUnixMs,
        public string $reason,
        public array $supersedes,
        public array $reviews,
        public ?KillScope $scope = null,
        public ?KillMode $mode = null,
        public ?string $eventId = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['id', 'requesterUserId', 'kind', 'state', 'revision', 'authorizationEpoch', 'createdAtUnixMs', 'expiresAtUnixMs', 'scope', 'mode', 'reason', 'supersedes', 'reviews', 'eventId']) || array_diff(['id', 'requesterUserId', 'kind', 'state', 'revision', 'authorizationEpoch', 'createdAtUnixMs', 'expiresAtUnixMs', 'reason', 'supersedes', 'reviews'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['id'],
            $data['requesterUserId'],
            KillProposalKind::from($data['kind']),
            KillProposalState::from($data['state']),
            $data['revision'],
            $data['authorizationEpoch'],
            $data['createdAtUnixMs'],
            $data['expiresAtUnixMs'],
            $data['reason'],
            array_map(static fn ($item) => $item, $data['supersedes']),
            array_map(static fn ($item) => KillReview::fromArray($item), $data['reviews']),
            array_key_exists('scope', $data) ? KillScope::fromArray($data['scope']) : null,
            array_key_exists('mode', $data) ? KillMode::from($data['mode']) : null,
            array_key_exists('eventId', $data) ? $data['eventId'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
