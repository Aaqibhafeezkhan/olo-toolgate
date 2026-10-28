<?php
// Copyright 2026 OLO Labs
// SPDX-License-Identifier: Apache-2.0
// GENERATED FILE — DO NOT EDIT DIRECTLY; tools/contracts/generate.py
declare(strict_types=1);
namespace OloLabs\ToolGate\Contracts\V2;

/** Claims of a scan permit (typ toolgate-mcp-scan-permit+jws), signed in the effect-signing domain. exp - iat is at most 10 s (admission); deadlineUnixMs bounds the whole scan. spkiPins is required for REFRESH. */
final readonly class McpScanClaims implements \JsonSerializable {
    public function __construct(
        public string $jti,
        public string $iss,
        public string $aud,
        public int $iat,
        public int $exp,
        public string $scanId,
        public string $slug,
        public int $registrationRevision,
        public string $bindingId,
        public McpScanPurpose $purpose,
        public string $url,
        public McpUpstreamAuth $auth,
        public array $spkiPins,
        public string $fencingToken,
        public int $deadlineUnixMs,
        public ?int $approvalSequence = null
    ) {}

    /** Decode a structural model; canonical schema validation is also required. */
    public static function fromArray(array $data): self {
        if (array_diff(array_keys($data), ['jti', 'iss', 'aud', 'iat', 'exp', 'scanId', 'slug', 'registrationRevision', 'bindingId', 'purpose', 'url', 'auth', 'spkiPins', 'approvalSequence', 'fencingToken', 'deadlineUnixMs']) || array_diff(['jti', 'iss', 'aud', 'iat', 'exp', 'scanId', 'slug', 'registrationRevision', 'bindingId', 'purpose', 'url', 'auth', 'spkiPins', 'fencingToken', 'deadlineUnixMs'], array_keys($data))) {
            throw new \InvalidArgumentException('Unknown or missing contract fields');
        }
        if ($data['iss'] !== 'control') {
            throw new \InvalidArgumentException('iss must be control');
        }
        return new self(
            $data['jti'],
            $data['iss'],
            $data['aud'],
            $data['iat'],
            $data['exp'],
            $data['scanId'],
            $data['slug'],
            $data['registrationRevision'],
            $data['bindingId'],
            McpScanPurpose::from($data['purpose']),
            $data['url'],
            McpUpstreamAuth::fromArray($data['auth']),
            array_map(static fn ($item) => $item, $data['spkiPins']),
            $data['fencingToken'],
            $data['deadlineUnixMs'],
            array_key_exists('approvalSequence', $data) ? $data['approvalSequence'] : null
        );
    }

    public function jsonSerialize(): object {
        return (object) array_filter(get_object_vars($this), static fn ($v) => $v !== null);
    }
}
