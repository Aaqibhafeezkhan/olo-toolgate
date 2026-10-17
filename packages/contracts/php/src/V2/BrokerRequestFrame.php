<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool to executor on stdout: one brokered operation. request is the operation payload: the recorded-exchange route call shape for ROUTE_CALL, {handle} for SECRET_REDEEM, {path, destination, text} for FILES_*. One request is outstanding at a time. */
final readonly class BrokerRequestFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $requestId,
        public BrokerOperation $operation,
        public \stdClass $request
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'requestId', 'operation', 'request']) || array_diff(['kind', 'requestId', 'operation', 'request'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'BROKER_REQUEST') {
            throw new \InvalidArgumentException('kind must be BROKER_REQUEST');
        }
        return new self(
            $data['kind'],
            $data['requestId'],
            BrokerOperation::from($data['operation']),
            (object) $data['request']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
