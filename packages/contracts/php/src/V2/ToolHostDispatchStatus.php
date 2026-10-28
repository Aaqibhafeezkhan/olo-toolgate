<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** A dispatch the Gateway handed to Control for a Tool Host that fetches its calls (pull mode), with the host's admission and result once it has sent them. */
final readonly class ToolHostDispatchStatus implements \JsonSerializable {
    public function __construct(
        public string $invocationId,
        public string $hostId,
        public ToolHostDispatchState $state,
        public ?AdmissionResponse $admission = null,
        public ?ResultMessage $result = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['invocationId', 'hostId', 'state', 'admission', 'result']) || array_diff(['invocationId', 'hostId', 'state'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['invocationId'],
            $data['hostId'],
            ToolHostDispatchState::from($data['state']),
            array_key_exists('admission', $data) ? AdmissionResponse::fromArray($data['admission']) : null,
            array_key_exists('result', $data) ? ResultMessage::fromArray($data['result']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
