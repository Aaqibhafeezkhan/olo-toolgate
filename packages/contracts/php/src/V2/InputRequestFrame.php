<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Tool asks for input (MRTR or tasks/update). */
final readonly class InputRequestFrame implements \JsonSerializable {
    public function __construct(
        public string $kind,
        public string $requestId,
        public \stdClass $request
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['kind', 'requestId', 'request']) || array_diff(['kind', 'requestId', 'request'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['kind'] !== 'INPUT_REQUEST') {
            throw new \InvalidArgumentException('kind must be INPUT_REQUEST');
        }
        return new self(
            $data['kind'],
            $data['requestId'],
            (object) $data['request']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
