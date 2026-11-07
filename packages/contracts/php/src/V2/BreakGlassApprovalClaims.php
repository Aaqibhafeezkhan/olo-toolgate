<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Exact JCS offline approval payload. Issued-to-expiry window is at most ten minutes; two or three distinct current operator keys must approve identical claims. */
final readonly class BreakGlassApprovalClaims implements \JsonSerializable {
    public function __construct(
        public string $tenantId,
        public string $requestId,
        public KillScope $scope,
        public KillMode $mode,
        public string $reason,
        public int $issuedAtUnixMs,
        public int $notAfterUnixMs
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['tenantId', 'requestId', 'scope', 'mode', 'reason', 'issuedAtUnixMs', 'notAfterUnixMs']) || array_diff(['tenantId', 'requestId', 'scope', 'mode', 'reason', 'issuedAtUnixMs', 'notAfterUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['tenantId'],
            $data['requestId'],
            KillScope::fromArray($data['scope']),
            KillMode::from($data['mode']),
            $data['reason'],
            $data['issuedAtUnixMs'],
            $data['notAfterUnixMs']
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
