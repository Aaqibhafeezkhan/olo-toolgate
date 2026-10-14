<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts;

/** Endpoint identity foundation wire model. keyFingerprint is returned only to callers who may approve through a group rule. */
final readonly class EndpointDeviceRecord implements \JsonSerializable {
    public function __construct(
        public string $deviceId,
        public string $tenantId,
        public string $userId,
        public EndpointState $state,
        public int $revision,
        public int $lastSeenUnixMs,
        public int $reportSequence,
        public ?string $keyFingerprint = null,
        public ?ClientReport $report = null,
        public ?int $connectionExpiresAtUnixMs = null,
        public ?bool $connectionApproved = null,
        public ?int $approvalRevision = null,
        public ?string $systemName = null,
        public ?string $ipAddress = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['deviceId', 'tenantId', 'userId', 'keyFingerprint', 'state', 'revision', 'lastSeenUnixMs', 'reportSequence', 'report', 'connectionExpiresAtUnixMs', 'connectionApproved', 'approvalRevision', 'systemName', 'ipAddress']) || array_diff(['deviceId', 'tenantId', 'userId', 'state', 'revision', 'lastSeenUnixMs', 'reportSequence'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        return new self(
            $data['deviceId'],
            $data['tenantId'],
            $data['userId'],
            EndpointState::from($data['state']),
            $data['revision'],
            $data['lastSeenUnixMs'],
            $data['reportSequence'],
            array_key_exists('keyFingerprint', $data) ? $data['keyFingerprint'] : null,
            array_key_exists('report', $data) ? ClientReport::fromArray($data['report']) : null,
            array_key_exists('connectionExpiresAtUnixMs', $data) ? $data['connectionExpiresAtUnixMs'] : null,
            array_key_exists('connectionApproved', $data) ? $data['connectionApproved'] : null,
            array_key_exists('approvalRevision', $data) ? $data['approvalRevision'] : null,
            array_key_exists('systemName', $data) ? $data['systemName'] : null,
            array_key_exists('ipAddress', $data) ? $data['ipAddress'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
