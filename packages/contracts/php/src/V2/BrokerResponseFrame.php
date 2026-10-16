<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Executor to tool on stdin: the result of the outstanding brokered operation, or a canonical error. Nothing else is written to the tool after its ToolInput. */
final readonly class BrokerResponseFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $requestId,
        public ?\stdClass $result = null,
        public ?ToolErrorBody $error = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'requestId', 'result', 'error']) || array_diff(['kind', 'requestId'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'BROKER_RESPONSE') {
            throw new \InvalidArgumentException('kind must be BROKER_RESPONSE');
        }
        return new self(
            $data['kind'],
            $data['requestId'],
            array_key_exists('result', $data) ? (object) $data['result'] : null,
            array_key_exists('error', $data) ? ToolErrorBody::fromArray($data['error']) : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
